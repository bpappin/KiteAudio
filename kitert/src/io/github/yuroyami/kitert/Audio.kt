package io.github.yuroyami.kitert

import kotlin.time.TimeSource

/** How many channels, and in what order. */
public enum class ChannelLayout(public val channels: Int) {
    Mono(1),
    Stereo(2),
    ;

    public companion object {
        /** The layout with this many channels, or stereo when it is not one we handle. */
        public fun of(channels: Int): ChannelLayout = if (channels == 1) Mono else Stereo
    }
}

/** The PCM stream format supplied to the platform output. */
public data class AudioFormat @Throws(IllegalArgumentException::class) public constructor(
    val sampleRate: Int,
    val layout: ChannelLayout = ChannelLayout.Stereo,
) {
    init {
        require(sampleRate > 0) { "sample rate must be above zero, got $sampleRate" }
    }

    public val channels: Int get() = layout.channels

    /** How long a number of frames lasts, in microseconds. */
    public fun framesToMicros(frames: Int): Long = frames.toLong() * 1_000_000L / sampleRate

    /** How many frames a duration holds. */
    public fun microsToFrames(micros: Long): Int = (micros * sampleRate / 1_000_000L).toInt()
}

/**
 * How much to trust the latency a device reports.
 *
 * Some platforms measure it, some estimate it, and some say nothing at all. A number the app
 * uses to line up sound and picture has to carry how much it is worth, or a guess gets treated
 * as a measurement.
 */
public enum class LatencyQuality {
    /** The device measured it. */
    Measured,

    /** Worked out from the buffer size, which is close but not the whole path. */
    Estimated,

    /** Nothing is known, and the number is a default. */
    Unknown,
}

/** What a device reports about its own delay. */
public data class Latency(
    val frames: Int,
    val quality: LatencyQuality,
) {
    public companion object {
        public val Unknown: Latency = Latency(0, LatencyQuality.Unknown)
    }
}

/**
 * Fills a buffer with sound.
 *
 * Called serially on a platform worker or callback queue, on a deadline. Apple also primes
 * buffers on that callback queue before start returns. Thread identity is not guaranteed.
 * Everything the usual advice says applies: no
 * allocation, no locks, no logging, no file access. Missing the deadline is a click, and a click
 * is the one thing a listener always notices.
 *
 * @param out interleaved frames, [frames] times the channel count in length
 * @param frames how many frames are wanted
 * @return a count in 0..[frames]. Fewer means the rest is silence. An invalid count or
 * exception stops the stream and reports [AudioSinkEvent.Failed].
 */
public fun interface AudioRenderCallback {
    public fun render(out: FloatArray, frames: Int): Int
}

/** Something has changed about the device. */
public sealed interface AudioSinkEvent {
    /** The stream started. */
    public data class Started(val format: AudioFormat, val latency: Latency) : AudioSinkEvent

    /** The stream has stopped and its render callback has unwound. May follow [Failed]. */
    public data object Stopped : AudioSinkEvent

    /** The render callback did not keep up, and silence was played. */
    public data class Underrun(val frames: Int) : AudioSinkEvent

    /** Rendering took longer than the buffer lasts, even if every frame was filled. */
    public data class Overrun(val deadlineMicros: Long, val tookMicros: Long) : AudioSinkEvent

    /** The device went away: headphones unplugged, an interface removed. */
    public data class DeviceLost(val reason: String) : AudioSinkEvent

    /** Something failed and the stream cannot continue. */
    public data class Failed(val reason: String, val cause: Throwable? = null) : AudioSinkEvent
}

/**
 * Somewhere to send sound.
 *
 * Open it, start it, and it pulls from the callback until it is stopped. Nothing is pushed in:
 * the device decides when it wants more, which is what keeps the timing the device's own rather
 * than a guess made on another thread.
 */
public interface AudioSink {

    /** The configured PCM stream format; hardware may resample it. */
    public val format: AudioFormat

    /** An estimate based on platform buffer capacity, excluding hardware and route delay. */
    public val latency: Latency

    public val isRunning: Boolean

    /**
     * Begins pulling from [callback]. Already-running calls are ignored. A closed sink or a
     * previous render/event callback that is still unwinding causes [IllegalStateException].
     * Restart from a Stopped observer must be deferred until that observer returns.
     */
    @Throws(IllegalStateException::class)
    public fun start(callback: AudioRenderCallback)

    /**
     * Cancels the current render generation. In-progress rendering, writes, or event delivery
     * may finish after this returns. Restart is rejected until that generation has unwound.
     * JVM/Android wait up to 500 ms after unblocking output; Apple schedules teardown.
     * A call from the renderer never waits for itself. Safe when already stopped.
     */
    public fun stop()

    /**
     * Permanently closes this sink and cancels rendering immediately. Resource cleanup and
     * application callbacks already in progress may finish after this returns.
     * Safe to call from a callback.
     */
    public fun close()
}

internal fun validateBufferFrames(format: AudioFormat, frames: Int) {
    require(frames in 1..(Int.MAX_VALUE / (format.channels * 2 * 4))) {
        "bufferFrames must be positive and fit the platform PCM buffer size, got $frames"
    }
}

/** Observer failures never escape a worker or native callback and are not recursively reported. */
internal fun deliverAudioEvent(observer: (AudioSinkEvent) -> Unit, event: AudioSinkEvent) {
    try { observer(event) } catch (_: Throwable) { }
}

internal fun renderAudioBlock(
    callback: AudioRenderCallback,
    out: FloatArray,
    frames: Int,
    deadlineMicros: Long,
    observer: (AudioSinkEvent) -> Unit,
): Boolean {
    val startedAt = TimeSource.Monotonic.markNow()
    val filled = try {
        callback.render(out, frames)
    } catch (problem: Throwable) {
        deliverAudioEvent(observer, AudioSinkEvent.Failed("the render callback threw", problem))
        return false
    }
    val tookMicros = startedAt.elapsedNow().inWholeMicroseconds
    if (filled !in 0..frames) {
        deliverAudioEvent(observer, AudioSinkEvent.Failed("the render callback returned $filled for $frames frames"))
        return false
    }
    if (tookMicros > deadlineMicros) {
        deliverAudioEvent(observer, AudioSinkEvent.Overrun(deadlineMicros, tookMicros))
    }
    if (filled < frames) {
        out.fill(0f, filled * (out.size / frames))
        deliverAudioEvent(observer, AudioSinkEvent.Underrun(frames - filled))
    }
    return true
}

/** Opens a sink for this platform. */
/**
 * Opens a sink for this platform.
 *
 * [bufferFrames] must be positive and fit four interleaved 16-bit PCM blocks in an Int.
 * [onEvent] runs on platform workers/control queues, and may run on the caller for an open
 * failure. Keep it brief. Observer exceptions are contained and ignored.
 *
 * @throws IllegalArgumentException for an invalid buffer size or unsupported format.
 */
@Throws(Exception::class)
public expect fun openAudioSink(
    preferredFormat: AudioFormat = AudioFormat(48_000),
    /** How much sound to keep buffered. Smaller is tighter timing and more risk of a click. */
    bufferFrames: Int = 1024,
    onEvent: (AudioSinkEvent) -> Unit = {},
): AudioSink

/** True when this platform has a sink at all. */
public expect fun audioOutputAvailable(): Boolean
