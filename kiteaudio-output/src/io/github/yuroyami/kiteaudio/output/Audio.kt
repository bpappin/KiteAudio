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
 * buffers on that callback queue before start returns. In a browser it runs on the page's main
 * thread, between other events: whenever the audio worklet hands back a block it has played, or
 * on a timer when the output plays by scheduled buffers. A long task elsewhere on the page delays
 * it. Thread identity is not guaranteed. Everything the usual advice says applies: no
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

    /**
     * The render callback returned fewer frames than the output asked for, and the output played
     * silence for the rest of the block.
     *
     * The cause is the callback. When the callback fills every block and the device still runs out
     * of sound, the output sends [DeviceUnderrun] instead.
     */
    public data class Underrun(
        /** The frames of silence that the output played. */
        val frames: Int,
    ) : AudioOutputEvent

    /**
     * The device ran out of sound although the render callback filled every block in time.
     *
     * The cause is outside the callback, for example a scheduling delay of the thread that writes
     * the blocks. A block with a short render sends only [Underrun], and the output counts no
     * device underrun for that block. The output sends this event after it writes a block, at most
     * once for each block, and never from inside the render callback. On some platforms the system
     * counts the underruns, and on others the output estimates them. The README of
     * `kiteaudio-output` says which platform does what.
     */
    public data class DeviceUnderrun(
        /** New underruns since the last event of this kind. */
        val count: Int,
        /** All underruns since the output was opened. */
        val total: Long,
    ) : AudioOutputEvent

    /** Rendering took longer than the buffer lasts, even if every frame was filled. */
    public data class Overrun(val deadlineMicros: Long, val tookMicros: Long) : AudioOutputEvent

    /**
     * The system ended the stream: the device went away, or a call or another app took the audio.
     *
     * The rule is the same on every platform. The output sends this event once, then [Stopped]
     * once, and [AudioOutput.isRunning] is false. The output does not resume by itself. To play
     * again, call [AudioOutput.start] after [Stopped] arrives. A [AudioOutput.stop] or
     * [AudioOutput.close] by the app never sends this event.
     *
     * What ends a stream on each platform:
     * - JVM and Android: a write that returns an invalid count or makes no progress.
     * - iOS: an audio session interruption that begins, a route change that removes the output
     *   device, or a loss or reset of the system media services.
     * - macOS: an AudioQueue that stops running. A move to a new default device that keeps the
     *   queue running is not a loss.
     * - Browser: an audio context that closed, or that the browser suspended or interrupted after
     *   the stream started.
     *
     * On iOS the host app owns the shared `AVAudioSession`: its category, its activation and its
     * background policy. The output never changes them.
     */
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

    /** True while the stream plays. False after a stop by the app or an end by the system. */
    public val isRunning: Boolean

    /**
     * Begins pulling from [callback]. Already-running calls are ignored. A closed output or a
     * previous render/event callback that is still unwinding causes [IllegalStateException].
     * Restart from a Stopped observer must be deferred until that observer returns.
     *
     * After [AudioOutputEvent.DeviceLost] the stream is over, and nothing restarts it. Call this
     * function again after [AudioOutputEvent.Stopped] to play. On Apple platforms each call
     * opens a new AudioQueue, so a start also works after a reset of the system media services.
     */
    @Throws(IllegalStateException::class)
    public fun start(callback: AudioRenderCallback)

    /**
     * Cancels the current render generation. In-progress rendering, writes, or event delivery
     * may finish after this returns. Restart is rejected until that generation has unwound.
     * JVM/Android wait up to 500 ms after unblocking output; Apple schedules teardown. The web
     * stops at once, or when a render in progress returns if called from the renderer.
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

/** What [renderAudioBlock] returns when the stream must stop. */
internal const val RENDER_FAILED: Int = -1

/**
 * Calls [callback] for one block and reports a slow or short render.
 *
 * @return the frames that the callback filled, or [RENDER_FAILED] after a failure was reported
 */
internal fun renderAudioBlock(
    callback: AudioRenderCallback,
    out: FloatArray,
    frames: Int,
    deadlineMicros: Long,
    observer: (AudioOutputEvent) -> Unit,
): Int {
    val startedAt = TimeSource.Monotonic.markNow()
    val filled = try {
        callback.render(out, frames)
    } catch (problem: Throwable) {
        deliverAudioEvent(observer, AudioOutputEvent.Failed("the render callback threw", problem))
        return RENDER_FAILED
    }
    val tookMicros = startedAt.elapsedNow().inWholeMicroseconds
    if (filled !in 0..frames) {
        deliverAudioEvent(observer, AudioOutputEvent.Failed("the render callback returned $filled for $frames frames"))
        return RENDER_FAILED
    }
    if (tookMicros > deadlineMicros) {
        deliverAudioEvent(observer, AudioOutputEvent.Overrun(deadlineMicros, tookMicros))
    }
    if (filled < frames) {
        out.fill(0f, filled * (out.size / frames))
        deliverAudioEvent(observer, AudioOutputEvent.Underrun(frames - filled))
    }
    return filled
}

/**
 * Keeps the [AudioOutputEvent.DeviceUnderrun] count of one output over its whole life.
 *
 * Only the thread or queue that writes the blocks of the output calls it, one stream after the
 * other, so it needs no lock.
 */
internal class DeviceUnderrunCounter {
    private var total = 0L

    /** Counts [dropouts] new underruns and sends them as one event. Does nothing for zero or less. */
    fun report(dropouts: Int, observer: (AudioOutputEvent) -> Unit) {
        if (dropouts <= 0) return
        total += dropouts
        deliverAudioEvent(observer, AudioOutputEvent.DeviceUnderrun(dropouts, total))
    }
}

/**
 * Opens an output for this platform.
 *
 * The output plays a layout of one or two channels. It reads only the channel count and
 * ignores the speaker positions of the layout. A layout of more channels throws
 * [IllegalArgumentException] before any device access.
 *
 * [bufferFrames] must be positive and fit four interleaved 16-bit PCM blocks in an Int.
 * [onEvent] runs on platform workers/control queues, or on the page's main thread in a browser,
 * and may run on the caller for an open failure. Keep it brief. Observer exceptions are contained
 * and ignored.
 *
 * In a browser the output is Web Audio: an audio worklet where the page can run one, otherwise
 * scheduled buffers. [AudioOutputEvent.Started] arrives only once the browser lets the page play
 * sound, which usually takes a click or a key press first. A start made before then waits, and
 * calling [AudioOutput.start] again from the click or key handler asks the browser once more.
 *
 * @throws IllegalArgumentException for an invalid buffer size or a layout of more than two channels.
 * @throws UnsupportedOperationException on the web, in a host with no Web Audio such as Node.js.
 */
@Throws(Exception::class)
public expect fun openAudioOutput(
    preferredFormat: AudioFormat = AudioFormat(48_000, ChannelLayout.STEREO),
    /** How much sound to keep buffered. Smaller is tighter timing and more risk of a click. */
    bufferFrames: Int = 1024,
    onEvent: (AudioOutputEvent) -> Unit = {},
): AudioOutput

/** True when this platform has an output at all. On the web, true in a page with Web Audio and false in Node.js. */
public expect fun audioOutputAvailable(): Boolean
