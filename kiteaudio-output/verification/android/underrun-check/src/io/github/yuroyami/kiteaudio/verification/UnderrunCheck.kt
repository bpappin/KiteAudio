package io.github.yuroyami.kiteaudio.verification

import android.media.AudioTrack
import android.os.Build
import android.os.SystemClock
import android.util.Log
import io.github.yuroyami.kiteaudio.core.AudioFormat
import io.github.yuroyami.kiteaudio.output.AudioOutput
import io.github.yuroyami.kiteaudio.output.AudioOutputEvent
import io.github.yuroyami.kiteaudio.output.AudioRenderCallback
import io.github.yuroyami.kiteaudio.output.openAudioOutput
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.sin

/** The logcat tag of every line that the check writes. run.sh filters on it. */
const val TAG = "KiteAudioCheck"

/** The first word of the summary line. run.sh looks for it. */
const val SUMMARY = "SUMMARY"

private const val SAMPLE_RATE = 48_000
private const val CHANNELS = 2
private const val BUFFER_FRAMES = 1024
private const val TONE_SECONDS = 20
private const val GAP_MILLIS = 300L

// The forced gaps start at these seconds of rendered sound. Each one is longer than the track buffer.
private val GAP_SECONDS = intArrayOf(3, 6, 9, 12, 15)

/**
 * Plays the tone, makes the forced gaps and judges the events. Blocks for about 22 seconds.
 * [show] receives each line for the screen.
 */
class UnderrunCheck(private val show: (String) -> Unit) {
    private val startNanos = SystemClock.elapsedRealtimeNanos()
    private val lock = Any()
    private var started = 0
    private var stopped = 0
    private var underruns = 0
    private var overruns = 0
    private val failures = mutableListOf<String>()
    private val losses = mutableListOf<String>()
    private val deviceUnderruns = mutableListOf<DeviceUnderrunRecord>()
    private var platformAtStart: Int? = null
    private val stoppedLatch = CountDownLatch(1)
    private val endLatch = CountDownLatch(1)
    @Volatile private var track: AudioTrack? = null

    fun run(): Verdict {
        line("start: Android ${Build.VERSION.RELEASE}, API level ${Build.VERSION.SDK_INT}, ${Build.MANUFACTURER} ${Build.MODEL}")
        val renderer = ToneRenderer()
        val output = try {
            openAudioOutput(AudioFormat(SAMPLE_RATE, CHANNELS), BUFFER_FRAMES, onEvent = ::onEvent)
        } catch (problem: Exception) {
            line("open failed: $problem")
            return finish(renderer, reachedEnd = false, platformAtEnd = null)
        }
        track = platformTrack(output)
        line(if (track == null) "platform counter: not readable, the check uses the events alone" else "platform counter: read from the AudioTrack of the output")
        line("format ${output.format}, latency ${output.latency}")
        var reachedEnd = false
        var platformAtEnd: Int? = null
        try {
            output.start(renderer)
            reachedEnd = endLatch.await(TONE_SECONDS + 20L, TimeUnit.SECONDS)
            output.stop()
            if (!stoppedLatch.await(3, TimeUnit.SECONDS)) line("no Stopped event within 3 s of stop()")
            platformAtEnd = readPlatform()
        } catch (problem: Exception) {
            line("run failed: $problem")
        } finally {
            output.close()
        }
        return finish(renderer, reachedEnd, platformAtEnd)
    }

    private fun finish(renderer: ToneRenderer, reachedEnd: Boolean, platformAtEnd: Int?): Verdict {
        val record = synchronized(lock) {
            RunRecord(
                apiLevel = Build.VERSION.SDK_INT,
                startedEvents = started,
                stoppedEvents = stopped,
                failures = failures.toList(),
                losses = losses.toList(),
                underrunEvents = underruns,
                overrunEvents = overruns,
                deviceUnderruns = deviceUnderruns.toList(),
                gaps = renderer.gaps(),
                plannedGaps = GAP_SECONDS.size,
                reachedEnd = reachedEnd,
                platformAtStart = platformAtStart,
                platformAtEnd = platformAtEnd,
            )
        }
        val verdict = judge(record)
        verdict.problems.forEach { line("problem: $it") }
        line(summary(record, verdict))
        return verdict
    }

    private fun summary(run: RunRecord, verdict: Verdict): String = buildString {
        append(SUMMARY)
        append(" result=").append(if (verdict.pass) "PASS" else "FAIL")
        append(" android=").append(Build.VERSION.RELEASE)
        append(" api=").append(run.apiLevel)
        append(" model=\"").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('"')
        append(" deviceUnderrunEvents=").append(run.deviceUnderruns.size)
        append(" countSum=").append(verdict.countSum)
        append(" lastTotal=").append(verdict.lastTotal ?: "none")
        append(" platformCounter=").append(run.platformAtEnd ?: "unreadable")
        append(" platformOffset=").append(verdict.platformOffset ?: "unknown")
        append(" gapsSeen=").append(verdict.gapsSeen).append('/').append(run.plannedGaps)
        append(" unforced=").append(verdict.unforcedEvents)
        append(" underrun=").append(run.underrunEvents)
        append(" overrun=").append(run.overrunEvents)
    }

    // Runs on the worker thread of the output, right after the output read the counter itself.
    private fun onEvent(event: AudioOutputEvent) {
        val now = SystemClock.elapsedRealtimeNanos()
        var platform: Int? = null
        synchronized(lock) {
            when (event) {
                is AudioOutputEvent.Started -> { started++; platformAtStart = readPlatform() }
                is AudioOutputEvent.Stopped -> stopped++
                is AudioOutputEvent.Underrun -> underruns++
                is AudioOutputEvent.Overrun -> overruns++
                is AudioOutputEvent.Failed -> failures += event.reason
                is AudioOutputEvent.DeviceLost -> losses += event.reason
                is AudioOutputEvent.DeviceUnderrun -> {
                    platform = readPlatform()
                    deviceUnderruns += DeviceUnderrunRecord(now, event.count, event.total, platform)
                }
            }
        }
        val extra = if (event is AudioOutputEvent.DeviceUnderrun) " platform=${platform ?: "unreadable"}" else ""
        line(String.format(Locale.US, "event t=%.3f s %s%s", seconds(now), event, extra))
        if (event is AudioOutputEvent.Stopped) stoppedLatch.countDown()
    }

    private fun readPlatform(): Int? = track?.let { runCatching { it.underrunCount }.getOrNull() }

    private fun seconds(nanos: Long): Double = (nanos - startNanos) / 1e9

    private fun line(text: String) {
        Log.i(TAG, text)
        show(text)
    }

    /** A quiet 440 Hz tone. It sleeps at the times in [GAP_SECONDS], so the track runs dry. */
    private inner class ToneRenderer : AudioRenderCallback {
        private val step = 2.0 * PI * 440.0 / SAMPLE_RATE
        private var phase = 0.0
        private var rendered = 0L
        private var nextGap = 0
        private val gapStart = LongArray(GAP_SECONDS.size)
        private val gapEnd = LongArray(GAP_SECONDS.size)
        @Volatile private var gapsMade = 0

        override fun render(out: FloatArray, frames: Int): Int {
            if (nextGap < GAP_SECONDS.size && rendered >= GAP_SECONDS[nextGap].toLong() * SAMPLE_RATE) {
                gapStart[nextGap] = SystemClock.elapsedRealtimeNanos()
                Thread.sleep(GAP_MILLIS)
                gapEnd[nextGap] = SystemClock.elapsedRealtimeNanos()
                nextGap++
                gapsMade = nextGap
            }
            for (frame in 0 until frames) {
                val value = (sin(phase) * 0.03).toFloat()
                for (channel in 0 until CHANNELS) out[frame * CHANNELS + channel] = value
                phase += step
                if (phase >= 2.0 * PI) phase -= 2.0 * PI
            }
            rendered += frames
            if (rendered >= TONE_SECONDS.toLong() * SAMPLE_RATE) endLatch.countDown()
            return frames
        }

        // The volatile write of gapsMade publishes the arrays to the thread that reads them.
        fun gaps(): List<ForcedGap> = List(gapsMade) { ForcedGap(gapStart[it], gapEnd[it]) }
    }
}

/**
 * The AudioTrack inside the output, read through private fields of kiteaudio-output 0.3.0. The
 * public API gives no access to it. Returns null when the fields have other names.
 */
private fun platformTrack(output: AudioOutput): AudioTrack? = runCatching {
    val wrapper = output.javaClass.getDeclaredField("track").apply { isAccessible = true }.get(output)
    wrapper.javaClass.getDeclaredField("track").apply { isAccessible = true }.get(wrapper) as AudioTrack
}.getOrNull()
