package io.github.yuroyami.kiteaudio.output

import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import platform.posix.usleep

/**
 * Opens a real AudioQueue on this Mac.
 *
 * This makes sound. It is short and quiet on purpose. The point is that the queue really pulls:
 * a sink that opens without error and then never asks for a sample looks fine from the outside
 * and is silent in practice.
 */
class AppleAudioSinkTest {

    @Test
    fun theQueuePullsFromTheCallback() {
        var blocks = 0
        var phase = 0.0
        val events = mutableListOf<AudioSinkEvent>()
        val format = AudioFormat(48_000)

        val sink = openAudioSink(format, bufferFrames = 512) { events += it }
        try {
            sink.start { out, frames ->
                for (i in 0 until frames) {
                    val value = (sin(phase) * 0.03).toFloat()
                    out[i * 2] = value
                    out[i * 2 + 1] = value
                    phase += 2.0 * PI * 440.0 / format.sampleRate
                }
                blocks++
                frames
            }

            // Half a second is plenty: 512 frames at 48 kHz is about 11 ms a block.
            var waited = 0
            while (blocks < 8 && waited < 500) {
                usleep(20_000u)
                waited += 20
            }
            assertTrue(blocks >= 8, "the queue asked for only $blocks blocks in $waited ms")
            assertTrue(sink.isRunning)
        } finally {
            sink.stop()
            sink.close()
        }

        assertTrue(events.any { it is AudioSinkEvent.Started }, "no Started event: $events")
        assertTrue(events.none { it is AudioSinkEvent.Failed }, "the sink failed: $events")
    }

    @Test
    fun theBuffersArePrimedBeforeTheQueueStarts() {
        // Starting with empty buffers is an audible click. Priming means the callback has
        // already run before start() returns.
        var blocks = 0
        val sink = openAudioSink(AudioFormat(48_000), bufferFrames = 256)
        try {
            sink.start { out, frames ->
                blocks++
                for (i in 0 until frames * 2) out[i] = 0f
                frames
            }
            assertTrue(blocks >= 3, "only $blocks buffers were primed before the queue started")
        } finally {
            sink.close()
        }
    }

    @Test
    fun stoppingAndClosingTwiceIsNotAnError() {
        val sink = openAudioSink(AudioFormat(48_000), bufferFrames = 256)
        sink.stop()
        sink.stop()
        sink.close()
        sink.close()
    }

    @Test
    fun appleAlwaysHasAnOutput() {
        assertTrue(audioOutputAvailable())
    }

    @Test
    fun theLatencyCountsEveryBufferInFlight() {
        val sink = openAudioSink(AudioFormat(48_000), bufferFrames = 512)
        try {
            assertEquals(LatencyQuality.Estimated, sink.latency.quality)
            assertTrue(sink.latency.frames >= 512, "the latency ignores the buffers in flight")
        } finally {
            sink.close()
        }
    }
}
