package io.github.yuroyami.kiteaudio.output

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Opens the real audio device on this machine.
 *
 * These tests make sound. They are short and quiet on purpose. If the machine has no output at
 * all they skip themselves rather than failing, because a build server with no sound card is not
 * a broken build.
 */
class JvmAudioSinkTest {

    private val available = audioOutputAvailable()

    @Test
    fun theDeviceIsThereOrItIsNotAndEitherIsAnAnswer() {
        // Not an assertion about the machine, only that asking does not throw.
        println("JVM audio output available: $available")
    }

    @Test
    fun aSinkPullsFromTheCallbackAndKeepsPulling() {
        if (!available) return

        val format = AudioFormat(48_000)
        val blocks = AtomicInteger(0)
        val enough = CountDownLatch(1)
        val events = mutableListOf<AudioSinkEvent>()

        val sink = openAudioSink(format, bufferFrames = 512) { synchronized(events) { events += it } }
        var phase = 0.0

        try {
            sink.start { out, frames ->
                // A quiet sine, so running the tests is not unpleasant.
                for (i in 0 until frames) {
                    val value = (sin(phase) * 0.03).toFloat()
                    out[i * 2] = value
                    out[i * 2 + 1] = value
                    phase += 2.0 * PI * 440.0 / format.sampleRate
                }
                if (blocks.incrementAndGet() >= 8) enough.countDown()
                frames
            }

            assertTrue(
                enough.await(5, TimeUnit.SECONDS),
                "the device asked for only ${blocks.get()} blocks in five seconds",
            )
            assertTrue(sink.isRunning)
        } finally {
            sink.stop()
            sink.close()
        }

        assertTrue(
            events.any { it is AudioSinkEvent.Started },
            "starting the sink reported no Started event",
        )
        assertTrue(events.none { it is AudioSinkEvent.Failed }, "the sink failed: $events")
    }

    @Test
    fun stoppingASinkStopsThePulling() {
        if (!available) return

        val blocks = AtomicInteger(0)
        val sink = openAudioSink(AudioFormat(48_000), bufferFrames = 512)
        try {
            sink.start { out, frames ->
                blocks.incrementAndGet()
                java.util.Arrays.fill(out, 0, frames * 2, 0f)
                frames
            }
            Thread.sleep(200)
            sink.stop()
            val after = blocks.get()
            Thread.sleep(200)
            assertEquals(after, blocks.get(), "the callback was still being pulled after stop")
            assertTrue(!sink.isRunning)
        } finally {
            sink.close()
        }
    }

    @Test
    fun stoppingTwiceIsNotAnError() {
        if (!available) return
        val sink = openAudioSink(AudioFormat(48_000), bufferFrames = 512)
        sink.stop()
        sink.stop()
        sink.close()
        sink.close()
    }

    @Test
    fun aCallbackThatThrowsIsReportedAndDoesNotTakeTheDeviceDown() {
        if (!available) return

        val events = mutableListOf<AudioSinkEvent>()
        val sink = openAudioSink(AudioFormat(48_000), bufferFrames = 512) {
            synchronized(events) { events += it }
        }
        try {
            sink.start { _, _ -> throw IllegalStateException("on purpose") }
            Thread.sleep(300)
            synchronized(events) {
                assertTrue(
                    events.any { it is AudioSinkEvent.Failed },
                    "a throwing callback was not reported: $events",
                )
            }
        } finally {
            sink.stop()
            sink.close()
        }
    }

    @Test
    fun theSinkReportsALatencyAndSaysHowMuchItIsWorth() {
        if (!available) return
        val sink = openAudioSink(AudioFormat(48_000), bufferFrames = 512)
        try {
            // An estimate, and it says so. A number the app lines picture up with has to carry
            // how much it can be trusted.
            assertEquals(LatencyQuality.Estimated, sink.latency.quality)
            assertTrue(sink.latency.frames >= 512, "the capacity estimate must include at least one render block")
        } finally {
            sink.close()
        }
    }

    @Test
    fun aFormatKnowsHowLongItsFramesLast() {
        val format = AudioFormat(48_000)
        assertEquals(10_000L, format.framesToMicros(480))
        assertEquals(480L, format.microsToFrames(10_000L))
        assertEquals(2, format.channels)
    }

    @Test
    fun aSampleRateOfZeroIsRefused() {
        val problem = runCatching { AudioFormat(0) }.exceptionOrNull()
        assertTrue(problem is IllegalArgumentException)
    }
}
