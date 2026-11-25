package io.github.yuroyami.kiteaudio.output

import io.github.yuroyami.kiteaudio.core.AudioFormat
import io.github.yuroyami.kiteaudio.core.ChannelLayout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * Opens the real audio device on this machine.
 *
 * These tests make sound. They are short and quiet on purpose. If the machine has no output at
 * all they report themselves as skipped rather than failing, because a build server with no sound
 * card is not a broken build.
 */
class JvmAudioOutputTest {

    private val available = audioOutputAvailable()

    @Test
    fun theDeviceIsThereOrItIsNotAndEitherIsAnAnswer() {
        // Not an assertion about the machine, only that asking does not throw.
        println("JVM audio output available: $available")
    }

    @Test
    fun anOutputPullsFromTheCallbackAndKeepsPulling() {
        assumeTrue(available, "no audio output device on this machine")

        val format = AudioFormat(48_000, ChannelLayout.STEREO)
        val blocks = AtomicInteger(0)
        val enough = CountDownLatch(1)
        val events = mutableListOf<AudioOutputEvent>()

        val output = openAudioOutput(format, bufferFrames = 512) { synchronized(events) { events += it } }
        var phase = 0.0

        try {
            output.start { out, frames ->
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
            assertTrue(output.isRunning)
        } finally {
            output.stop()
            output.close()
        }

        assertTrue(
            events.any { it is AudioOutputEvent.Started },
            "starting the output reported no Started event",
        )
        assertTrue(events.none { it is AudioOutputEvent.Failed }, "the output failed: $events")
    }

    @Test
    fun stoppingAnOutputStopsThePulling() {
        assumeTrue(available, "no audio output device on this machine")

        val blocks = AtomicInteger(0)
        val output = openAudioOutput(AudioFormat(48_000, ChannelLayout.STEREO), bufferFrames = 512)
        try {
            output.start { out, frames ->
                blocks.incrementAndGet()
                java.util.Arrays.fill(out, 0, frames * 2, 0f)
                frames
            }
            Thread.sleep(200)
            output.stop()
            val after = blocks.get()
            Thread.sleep(200)
            assertEquals(after, blocks.get(), "the callback was still being pulled after stop")
            assertTrue(!output.isRunning)
        } finally {
            output.close()
        }
    }

    @Test
    fun stoppingTwiceIsNotAnError() {
        assumeTrue(available, "no audio output device on this machine")
        val output = openAudioOutput(AudioFormat(48_000, ChannelLayout.STEREO), bufferFrames = 512)
        output.stop()
        output.stop()
        output.close()
        output.close()
    }

    @Test
    fun aCallbackThatThrowsIsReportedAndDoesNotTakeTheDeviceDown() {
        assumeTrue(available, "no audio output device on this machine")

        val events = mutableListOf<AudioOutputEvent>()
        val output = openAudioOutput(AudioFormat(48_000, ChannelLayout.STEREO), bufferFrames = 512) {
            synchronized(events) { events += it }
        }
        try {
            output.start { _, _ -> throw IllegalStateException("on purpose") }
            Thread.sleep(300)
            synchronized(events) {
                assertTrue(
                    events.any { it is AudioOutputEvent.Failed },
                    "a throwing callback was not reported: $events",
                )
            }
        } finally {
            output.stop()
            output.close()
        }
    }

    @Test
    fun theOutputReportsALatencyAndSaysHowMuchItIsWorth() {
        assumeTrue(available, "no audio output device on this machine")
        val output = openAudioOutput(AudioFormat(48_000, ChannelLayout.STEREO), bufferFrames = 512)
        try {
            // An estimate, and it says so. A number the app lines picture up with has to carry
            // how much it can be trusted.
            assertEquals(LatencyQuality.Estimated, output.latency.quality)
            assertTrue(output.latency.frames >= 512, "the capacity estimate must include at least one render block")
        } finally {
            output.close()
        }
    }

    /** The events of a live run, and its render gaps that are longer than the line holds. */
    private class LiveRun(val events: List<AudioOutputEvent>, val longGaps: Int)

    /** Plays silence for [millis], and blocks the render callback for [stallMillis] in the renders listed in [stalls]. */
    private fun playWithStalls(millis: Long, stalls: Set<Int>, stallMillis: Long): LiveRun {
        val events = mutableListOf<AudioOutputEvent>()
        val renders = AtomicInteger(0)
        val origin = System.nanoTime()
        val renderMicros = LongArray(4096)
        val stopped = CountDownLatch(1)
        val output = openAudioOutput(AudioFormat(48_000, ChannelLayout.STEREO), bufferFrames = 512) {
            synchronized(events) { events += it }
            if (it == AudioOutputEvent.Stopped) stopped.countDown()
        }
        try {
            output.start { out, frames ->
                java.util.Arrays.fill(out, 0f)
                val render = renders.getAndIncrement()
                if (render < renderMicros.size) renderMicros[render] = (System.nanoTime() - origin) / 1000
                if (render in stalls) Thread.sleep(stallMillis)
                frames
            }
            Thread.sleep(millis)
            assertTrue(renders.get() > 100, "the line asked for only ${renders.get()} blocks")
        } finally {
            output.close()
        }
        assertTrue(stopped.await(5, TimeUnit.SECONDS), "no Stopped after close")
        // A block of 512 frames lasts 10.7 ms, and the line holds four of them. Several gaps of more
        // than a block and a half can empty the line together, so each of them allows one dropout.
        // The first four blocks fill the line and are not checked.
        val gaps = longRenderGaps(renderMicros, renders.get(), 16_000L, stalls, firstChecked = 4)
        return LiveRun(synchronized(events) { events.toList() }, gaps)
    }

    @Test
    fun aLineThatKeepsUpReportsADropoutOnlyForALongGap() {
        assumeTrue(available, "no audio output device on this machine")
        val run = playWithStalls(2_500, emptySet(), 0)
        assertDeviceUnderruns(run.events, deliberate = 0, longGaps = run.longGaps)
    }

    @Test
    fun aLineThatRunsDryWhileTheCallbackBlocksReportsEachDropoutOnce() {
        assumeTrue(available, "no audio output device on this machine")
        // Each stall of 150 ms empties the line once.
        val run = playWithStalls(2_500, setOf(40, 100), 150)
        assertDeviceUnderruns(run.events, deliberate = 2, longGaps = run.longGaps)
    }

    @Test
    fun theDeviceListIsNotEmptyAndHasExactlyOneDefault() {
        assumeTrue(available, "no audio output device on this machine")
        val devices = runSuspending { audioOutputDevices() }
        println("JVM audio output devices: $devices")
        assertTrue(devices.isNotEmpty())
        assertEquals(1, devices.count { it.isDefault }, "$devices")
        assertEquals(devices.size, devices.map { it.id }.distinct().size, "the ids repeat: $devices")
    }

    @Test
    fun theDefaultDeviceOpensByItsListEntryAndPlays() {
        assumeTrue(available, "no audio output device on this machine")
        val device = runSuspending { audioOutputDevices() }.single { it.isDefault }
        val blocks = AtomicInteger(0)
        val enough = CountDownLatch(1)
        val events = mutableListOf<AudioOutputEvent>()
        val output = openAudioOutput(AudioFormat(48_000, ChannelLayout.STEREO), 512, device) { synchronized(events) { events += it } }
        var phase = 0.0
        try {
            output.start { out, frames ->
                for (i in 0 until frames) {
                    val value = (sin(phase) * 0.03).toFloat()
                    out[i * 2] = value
                    out[i * 2 + 1] = value
                    phase += 2.0 * PI * 440.0 / 48_000
                }
                if (blocks.incrementAndGet() >= 8) enough.countDown()
                frames
            }
            // A busy machine may be slow to pull, so allow five seconds.
            assertTrue(enough.await(5, TimeUnit.SECONDS), "the device asked for only ${blocks.get()} blocks in five seconds")
        } finally {
            output.close()
        }
        synchronized(events) {
            assertTrue(events.any { it is AudioOutputEvent.Started }, "$events")
            assertTrue(events.none { it is AudioOutputEvent.Failed }, "$events")
        }
    }

    @Test
    fun aFormatKnowsHowLongItsFramesLast() {
        val format = AudioFormat(48_000, ChannelLayout.STEREO)
        assertEquals(10_000L, format.framesToMicros(480))
        assertEquals(480L, format.microsToFrames(10_000L))
        assertEquals(2, format.channels)
    }

    @Test
    fun aLayoutOfMoreThanTwoChannelsIsRefusedBeforeTheDeviceOpens() {
        // No assumeTrue: the check runs before any device access.
        val problem = assertFailsWith<IllegalArgumentException> { openAudioOutput(AudioFormat(48_000, 6)) }
        assertTrue("6" in problem.message.orEmpty(), "the message does not name the count: ${problem.message}")
    }

    @Test
    fun aSampleRateOfZeroIsRefused() {
        val problem = runCatching { AudioFormat(0, ChannelLayout.STEREO) }.exceptionOrNull()
        assertTrue(problem is IllegalArgumentException)
    }
}
