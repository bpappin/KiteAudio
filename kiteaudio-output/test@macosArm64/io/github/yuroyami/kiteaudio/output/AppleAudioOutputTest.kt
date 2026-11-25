@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package io.github.yuroyami.kiteaudio.output

import io.github.yuroyami.kiteaudio.core.AudioFormat
import io.github.yuroyami.kiteaudio.core.ChannelLayout
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.pointed
import kotlinx.cinterop.value
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.TimeSource
import platform.AudioToolbox.AudioQueueBufferRef
import platform.AudioToolbox.AudioQueueRef
import platform.AudioToolbox.AudioQueueRefVar
import platform.AudioToolbox.AudioQueueStop
import platform.CoreAudioTypes.AudioStreamBasicDescription
import platform.darwin.dispatch_queue_t
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
    fun theDeviceListIsNotEmptyAndHasExactlyOneDefault() {
        val devices = runSuspending { audioOutputDevices() }
        println("macOS audio output devices: $devices")
        assertTrue(devices.isNotEmpty())
        assertEquals(1, devices.count { it.isDefault }, "$devices")
        assertEquals(devices.size, devices.map { it.id }.distinct().size, "the ids repeat: $devices")
        assertTrue(devices.all { it.channelCounts.single() > 0 }, "$devices")
    }

    @Test
    fun theDefaultDeviceOpensByItsListEntryAndPlays() {
        val device = runSuspending { audioOutputDevices() }.single { it.isDefault }
        val blocks = AtomicInt(0)
        val events = AtomicReference<List<AudioOutputEvent>>(emptyList())
        var phase = 0.0
        val output = openAudioOutput(AudioFormat(48_000, ChannelLayout.STEREO), 512, device) { events.append(it) }
        try {
            println("deviceFormat of ${device.name}: ${output.deviceFormat}")
            assertTrue(output.deviceFormat != null, "the hardware API did not answer for ${device.id}")
            output.start { out, frames ->
                for (i in 0 until frames) {
                    val value = (sin(phase) * 0.03).toFloat()
                    out[i * 2] = value
                    out[i * 2 + 1] = value
                    phase += 2.0 * PI * 440.0 / 48_000
                }
                blocks.fetchAndAdd(1)
                frames
            }
            // A busy machine may be slow to pull, so allow three seconds.
            awaitTrue("the queue asked for only ${blocks.load()} blocks") { blocks.load() >= 8 }
        } finally {
            output.close()
        }
        val seen = events.load()
        assertTrue(seen.any { it is AudioOutputEvent.Started }, "no Started event: $seen")
        assertTrue(seen.none { it is AudioOutputEvent.Failed }, "the output failed: $seen")
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
    /** The native driver, which also keeps the last queue it opened so a test can stop it from outside. */
    private class RecordingDriver : AppleAudioQueueDriver by NativeAudioQueueDriver {
        var queue: AudioQueueRef? = null
        override fun create(format: CPointer<AudioStreamBasicDescription>, output: CPointer<AudioQueueRefVar>, queue: dispatch_queue_t,
                            callback: (AudioQueueRef?, AudioQueueBufferRef?) -> Unit): Int =
            NativeAudioQueueDriver.create(format, output, queue, callback).also { this.queue = output.pointed.value }
    }

    private fun awaitTrue(what: String, condition: () -> Boolean) {
        repeat(150) { if (condition()) return; usleep(20_000u) }
        assertTrue(condition(), what)
    }

    @Test
    fun aRealQueueThatStopsWithoutTheAppEndsTheStreamWithDeviceLost() {
        val events = AtomicReference<List<AudioOutputEvent>>(emptyList())
        val blocks = AtomicInt(0)
        val driver = RecordingDriver()
        val output = AppleAudioOutput(AudioFormat(48_000, ChannelLayout.STEREO), 512, { events.append(it) }, driver)
        try {
            output.start { out, frames -> out.fill(0f); blocks.fetchAndAdd(1); frames }
            awaitTrue("the queue never pulled") { blocks.load() >= 6 }
            // The real IsRunning listener of AudioQueue must see this stop, as it sees one by the system.
            assertEquals(0, AudioQueueStop(driver.queue, true))
            awaitTrue("no Stopped after the queue stopped: ${events.load()}") { events.load().contains(AudioOutputEvent.Stopped) }
            assertFalse(output.isRunning)
            val seen = events.load().filter { it !is AudioOutputEvent.Underrun && it !is AudioOutputEvent.Overrun }
            assertEquals(1, seen.count { it is AudioOutputEvent.DeviceLost }, "$seen")
            assertEquals(AudioOutputEvent.Stopped, seen.last(), "$seen")
            assertTrue(seen[seen.size - 2] is AudioOutputEvent.DeviceLost, "$seen")
            assertEquals(0, output.openListenerRefs)

            // The app starts it again, and the new queue pulls.
            usleep(50_000u)
            val before = blocks.load()
            output.start { out, frames -> out.fill(0f); blocks.fetchAndAdd(1); frames }
            assertTrue(output.isRunning)
            awaitTrue("the restarted queue never pulled") { blocks.load() >= before + 6 }
        } finally {
            output.close()
        }
    }

    /** The events of a live run, and its render gaps that are longer than the queue holds. */
    private class LiveRun(val events: List<AudioOutputEvent>, val longGaps: Int)

    /** Plays silence for [millis], and blocks the render callback for [stallMicros] in the renders listed in [stalls]. */
    private fun playWithStalls(millis: Int, stalls: Set<Int>, stallMicros: UInt): LiveRun {
        val events = AtomicReference<List<AudioOutputEvent>>(emptyList())
        val renders = AtomicInt(0)
        val origin = TimeSource.Monotonic.markNow()
        val renderMicros = LongArray(4096)
        val output = openAudioOutput(AudioFormat(48_000, ChannelLayout.STEREO), bufferFrames = 512) { events.append(it) }
        try {
            output.start { out, frames ->
                out.fill(0f)
                val render = renders.fetchAndAdd(1)
                if (render < renderMicros.size) renderMicros[render] = origin.elapsedNow().inWholeMicroseconds
                if (render in stalls) usleep(stallMicros)
                frames
            }
            usleep((millis * 1000).toUInt())
            assertTrue(renders.load() > 100, "the queue asked for only ${renders.load()} blocks")
        } finally {
            output.close()
        }
        awaitTrue("no Stopped after close") { events.load().contains(AudioOutputEvent.Stopped) }
        // A buffer of 512 frames lasts 10.7 ms, and the queue holds three of them. Several gaps of more
        // than a buffer and a half can empty the queue together, so each of them allows one dropout.
        // The three priming renders and the first callback after the start are not checked.
        return LiveRun(events.load(), longRenderGaps(renderMicros, renders.load(), 16_000L, stalls, firstChecked = 4))
    }

    @Test
    fun aRealQueueThatKeepsUpReportsADropoutOnlyForALongGap() {
        val run = playWithStalls(2_500, emptySet(), 0u)
        assertDeviceUnderruns(run.events, deliberate = 0, longGaps = run.longGaps)
    }

    @Test
    fun aRealQueueThatRunsDryWhileTheCallbackBlocksReportsEachDropoutOnce() {
        // Each stall of 150 ms empties the queue once.
        val run = playWithStalls(2_500, setOf(40, 100), 150_000u)
        assertDeviceUnderruns(run.events, deliberate = 2, longGaps = run.longGaps)
    }

    @Test
    fun aRealQueueStoppedByTheAppSendsNoDeviceLost() {
        val events = AtomicReference<List<AudioOutputEvent>>(emptyList())
        val blocks = AtomicInt(0)
        val output = AppleAudioOutput(AudioFormat(48_000, ChannelLayout.STEREO), 512, { events.append(it) })
        try {
            output.start { out, frames -> out.fill(0f); blocks.fetchAndAdd(1); frames }
            awaitTrue("the queue never pulled") { blocks.load() >= 6 }
            // The queue reports running after a start. That must not read as a loss.
            assertTrue(output.isRunning)
            output.stop()
            awaitTrue("no Stopped after stop: ${events.load()}") { events.load().contains(AudioOutputEvent.Stopped) }
            usleep(100_000u)
            assertTrue(events.load().none { it is AudioOutputEvent.DeviceLost || it is AudioOutputEvent.Failed }, "${events.load()}")
            assertEquals(0, output.openListenerRefs)
        } finally {
            output.close()
        }
    }
}
