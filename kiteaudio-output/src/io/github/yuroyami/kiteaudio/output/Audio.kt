package io.github.yuroyami.kiteaudio.output

import io.github.yuroyami.kiteaudio.core.AudioFormat
import io.github.yuroyami.kiteaudio.core.ChannelLayout
import kotlin.time.TimeSource

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
 */
public fun interface AudioRenderCallback {
    /**
     * Writes up to [frames] frames into [out].
     *
     * @param out interleaved frames, [frames] times the channel count in length
     * @param frames how many frames are wanted
     * @return a count in 0..[frames]. Fewer means the rest is silence. An invalid count or
     * exception stops the stream and reports [AudioOutputEvent.Failed].
     */
    public fun render(out: FloatArray, frames: Int): Int
}

/** Something has changed about the device. */
public sealed interface AudioOutputEvent {
    /** The stream started. */
    public data class Started(val format: AudioFormat, val latency: Latency) : AudioOutputEvent

    /** The stream has stopped and its render callback has unwound. May follow [Failed]. */
    public data object Stopped : AudioOutputEvent

    /** The render callback did not keep up, and silence was played. */
    public data class Underrun(val frames: Int) : AudioOutputEvent

    /** Rendering took longer than the buffer lasts, even if every frame was filled. */
    public data class Overrun(val deadlineMicros: Long, val tookMicros: Long) : AudioOutputEvent

    /** The device went away: headphones unplugged, an interface removed. */
    public data class DeviceLost(val reason: String) : AudioOutputEvent

    /** Something failed and the stream cannot continue. */
    public data class Failed(val reason: String, val cause: Throwable? = null) : AudioOutputEvent
}

/**
 * Somewhere to send sound.
 *
 * Open it, start it, and it pulls from the callback until it is stopped. Nothing is pushed in:
 * the device decides when it wants more, which is what keeps the timing the device's own rather
 * than a guess made on another thread.
 */
public interface AudioOutput {

    /** The configured PCM stream format; hardware may resample it. */
    public val format: AudioFormat

    /** An estimate based on platform buffer capacity, excluding hardware and route delay. */
    public val latency: Latency

    public val isRunning: Boolean

    /**
     * Begins pulling from [callback]. Already-running calls are ignored. A closed output or a
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
     * Permanently closes this output and cancels rendering immediately. Resource cleanup and
     * application callbacks already in progress may finish after this returns.
     * Safe to call from a callback.
     */
    public fun close()
}

/** Throws [IllegalArgumentException] unless [format] has one or two channels. Positions are ignored. */
internal fun requireSupportedLayout(format: AudioFormat) {
    require(format.channels in 1..2) {
        "an output plays one or two channels, but the layout has ${format.channels}"
    }
}

internal fun validateBufferFrames(format: AudioFormat, frames: Int) {
    require(frames in 1..(Int.MAX_VALUE / (format.channels * 2 * 4))) {
        "bufferFrames must be positive and fit the platform PCM buffer size, got $frames"
    }
}

/** Observer failures never escape a worker or native callback and are not recursively reported. */
internal fun deliverAudioEvent(observer: (AudioOutputEvent) -> Unit, event: AudioOutputEvent) {
    try { observer(event) } catch (_: Throwable) { }
}

internal fun renderAudioBlock(
    callback: AudioRenderCallback,
    out: FloatArray,
    frames: Int,
    deadlineMicros: Long,
    observer: (AudioOutputEvent) -> Unit,
): Boolean {
    val startedAt = TimeSource.Monotonic.markNow()
    val filled = try {
        callback.render(out, frames)
    } catch (problem: Throwable) {
        deliverAudioEvent(observer, AudioOutputEvent.Failed("the render callback threw", problem))
        return false
    }
    val tookMicros = startedAt.elapsedNow().inWholeMicroseconds
    if (filled !in 0..frames) {
        deliverAudioEvent(observer, AudioOutputEvent.Failed("the render callback returned $filled for $frames frames"))
        return false
    }
    if (tookMicros > deadlineMicros) {
        deliverAudioEvent(observer, AudioOutputEvent.Overrun(deadlineMicros, tookMicros))
    }
    if (filled < frames) {
        out.fill(0f, filled * (out.size / frames))
        deliverAudioEvent(observer, AudioOutputEvent.Underrun(frames - filled))
    }
    return true
}

/**
 * Opens an output for this platform.
 *
 * The output plays a layout of one or two channels. It reads only the channel count and
 * ignores the speaker positions of the layout. A layout of more channels throws
 * [IllegalArgumentException] before any device access.
 *
 * [bufferFrames] must be positive and fit four interleaved 16-bit PCM blocks in an Int.
 * [onEvent] runs on platform workers/control queues, and may run on the caller for an open
 * failure. Keep it brief. Observer exceptions are contained and ignored.
 *
 * @throws IllegalArgumentException for an invalid buffer size or a layout of more than two channels.
 */
@Throws(Exception::class)
public expect fun openAudioOutput(
    preferredFormat: AudioFormat = AudioFormat(48_000, ChannelLayout.STEREO),
    /** How much sound to keep buffered. Smaller is tighter timing and more risk of a click. */
    bufferFrames: Int = 1024,
    onEvent: (AudioOutputEvent) -> Unit = {},
): AudioOutput

/** True when this platform has an output at all. */
public expect fun audioOutputAvailable(): Boolean
