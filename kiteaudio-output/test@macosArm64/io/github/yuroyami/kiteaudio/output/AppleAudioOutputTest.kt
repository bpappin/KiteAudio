@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package io.github.yuroyami.kiteaudio.output

import io.github.yuroyami.kiteaudio.core.AudioFormat
import io.github.yuroyami.kiteaudio.core.ChannelLayout
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import platform.posix.usleep

/**
 * Opens a real AudioQueue on this Mac.
 *
 * This makes sound. It is short and quiet on purpose. The point is that the queue really pulls:
 * an output that opens without error and then never asks for a sample looks fine from the outside
 * and is silent in practice.
 */
class AppleAudioOutputTest {

    /** Appends without a lock: the event queue of the output and the test thread both touch the list. */
    private fun AtomicReference<List<AudioOutputEvent>>.append(event: AudioOutputEvent) {
        while (true) {
            val current = load()
            if (compareAndSet(current, current + event)) return
        }
    }

    @Test
    fun theQueuePullsFromTheCallback() {
        val blocks = AtomicInt(0)
        // Only the render callback touches the phase, and the output runs it on one serial queue.
        var phase = 0.0
        val events = AtomicReference<List<AudioOutputEvent>>(emptyList())
        val format = AudioFormat(48_000, ChannelLayout.STEREO)

        val output = openAudioOutput(format, bufferFrames = 512) { events.append(it) }
        try {
            output.start { out, frames ->
                for (i in 0 until frames) {
                    val value = (sin(phase) * 0.03).toFloat()
                    out[i * 2] = value
                    out[i * 2 + 1] = value
                    phase += 2.0 * PI * 440.0 / format.sampleRate
                }
                blocks.fetchAndAdd(1)
                frames
            }

            // Half a second is plenty: 512 frames at 48 kHz is about 11 ms a block.
            var waited = 0
            while (blocks.load() < 8 && waited < 500) {
                usleep(20_000u)
                waited += 20
            }
            assertTrue(blocks.load() >= 8, "the queue asked for only ${blocks.load()} blocks in $waited ms")
            assertTrue(output.isRunning)
        } finally {
            output.stop()
            output.close()
        }

        val seen = events.load()
        assertTrue(seen.any { it is AudioOutputEvent.Started }, "no Started event: $seen")
        assertTrue(seen.none { it is AudioOutputEvent.Failed }, "the output failed: $seen")
    }

    @Test
    fun theBuffersArePrimedBeforeTheQueueStarts() {
        // Starting with empty buffers is an audible click. Priming means the callback has
        // already run before start() returns.
        val blocks = AtomicInt(0)
        val output = openAudioOutput(AudioFormat(48_000, ChannelLayout.STEREO), bufferFrames = 256)
        try {
            output.start { out, frames ->
                blocks.fetchAndAdd(1)
                for (i in 0 until frames * 2) out[i] = 0f
                frames
            }
            val primed = blocks.load()
            assertTrue(primed >= 3, "only $primed buffers were primed before the queue started")
        } finally {
            output.close()
        }
    }

    @Test
    fun stoppingAndClosingTwiceIsNotAnError() {
        val output = openAudioOutput(AudioFormat(48_000, ChannelLayout.STEREO), bufferFrames = 256)
        output.stop()
        output.stop()
        output.close()
        output.close()
    }

    @Test
    fun aLayoutOfMoreThanTwoChannelsIsRefusedBeforeTheDeviceOpens() {
        val problem = assertFailsWith<IllegalArgumentException> { openAudioOutput(AudioFormat(48_000, 6)) }
        assertTrue("6" in problem.message.orEmpty(), "the message does not name the count: ${problem.message}")
    }

    @Test
    fun appleAlwaysHasAnOutput() {
        assertTrue(audioOutputAvailable())
    }

    @Test
    fun theLatencyCountsEveryBufferInFlight() {
        val output = openAudioOutput(AudioFormat(48_000, ChannelLayout.STEREO), bufferFrames = 512)
        try {
            assertEquals(LatencyQuality.Estimated, output.latency.quality)
            assertTrue(output.latency.frames >= 512, "the latency ignores the buffers in flight")
        } finally {
            output.close()
        }
    }
}
