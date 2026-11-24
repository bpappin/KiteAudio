package io.github.yuroyami.kiteaudio.output

import io.github.yuroyami.kiteaudio.core.AudioFormat
import io.github.yuroyami.kiteaudio.core.ChannelLayout
import kotlin.math.PI
import kotlin.math.sin

/**
 * Plays a quiet 440 Hz tone through an output from [open] for [seconds], then reports what the
 * output said: the latency it started with, how many blocks it rendered, and every underrun, device
 * underrun, overrun and failure. The tone stops itself from inside the renderer, which is the path a stop from the
 * renderer takes. Not a test: the web test modules export it for kiteaudio-output/verification/web,
 * which runs it in a browser. Run from a click it plays at once. Run before one, it waits, and the
 * function it returns starts the output again, which is what a click handler would do. That
 * function does nothing once the check has finished, and says so through [report].
 */
internal fun browserPlaybackCheck(
    seconds: Int,
    open: (AudioFormat, Int, (AudioOutputEvent) -> Unit) -> AudioOutput,
    report: (String) -> Unit,
): () -> Unit {
    val format = AudioFormat(48_000, ChannelLayout.STEREO)
    val target = seconds.toLong() * format.sampleRate
    var played = 0L
    var blocks = 0
    var underrunFrames = 0L
    var underruns = 0
    var deviceUnderruns = 0L
    var overruns = 0
    var worstOverrunMicros = 0L
    val notes = mutableListOf<String>()
    var finished = false
    lateinit var output: AudioOutput
    output = open(format, 512) { event ->
        when (event) {
            is AudioOutputEvent.Started -> notes += "started: ${event.latency.frames} frames of latency, ${event.latency.quality}"
            is AudioOutputEvent.Underrun -> { underruns++; underrunFrames += event.frames }
            is AudioOutputEvent.DeviceUnderrun -> deviceUnderruns = event.total
            is AudioOutputEvent.Overrun -> { overruns++; if (event.tookMicros > worstOverrunMicros) worstOverrunMicros = event.tookMicros }
            is AudioOutputEvent.Failed -> notes += "failed: ${event.reason} ${event.cause ?: ""}"
            is AudioOutputEvent.DeviceLost -> notes += "device lost: ${event.reason}"
            AudioOutputEvent.Stopped -> {
                finished = true
                output.close()
                report(
                    (notes + listOf(
                        "rendered $blocks blocks, ${played * 1000 / format.sampleRate} ms of audio",
                        "underruns: $underruns, $underrunFrames frames of silence",
                        "device underruns: $deviceUnderruns",
                        "overruns: $overruns, worst $worstOverrunMicros us",
                        "stopped",
                    )).joinToString("\n"),
                )
            }
        }
    }
    val tone = AudioRenderCallback { out, frames ->
        for (i in 0 until frames) {
            val value = (0.1 * sin(2 * PI * 440.0 * (played + i) / format.sampleRate)).toFloat()
            out[i * 2] = value
            out[i * 2 + 1] = value
        }
        played += frames
        blocks++
        if (played >= target) output.stop()
        frames
    }
    output.start(tone)
    return { if (finished) report("already finished: the first start played without waiting for a click") else output.start(tone) }
}
