package io.github.yuroyami.kiteaudio.output

import io.github.yuroyami.kiteaudio.core.AudioFormat
import io.github.yuroyami.kiteaudio.core.ChannelLayout
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.sound.sampled.SourceDataLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Deterministic output doubles: these tests never discover or open a physical audio device. */
class JvmAudioOutputRegressionTest {
    private class FakeDevice {
        val writes = AtomicInteger()
        val starts = AtomicInteger()
        val stopped = CountDownLatch(1)
        var startFailure = false
        var writer: (ByteArray, Int, Int) -> Int = { _, _, count -> Thread.sleep(1); count }
        @Volatile var bufferSize = 4096
        /** The free bytes of the line for the number of writes so far. */
        @Volatile var free: (Int) -> Int = { 0 }
        val availableCalls = AtomicInteger()
        val line = Proxy.newProxyInstance(SourceDataLine::class.java.classLoader, arrayOf(SourceDataLine::class.java)) { _, method, args ->
            when (method.name) {
                "start" -> { starts.incrementAndGet(); check(!startFailure) { "start failure" }; null }
                "write" -> { writes.incrementAndGet(); writer(args!![0] as ByteArray, args[1] as Int, args[2] as Int) }
                "stop", "close" -> { stopped.countDown(); null }
                "getBufferSize" -> bufferSize
                "available" -> { availableCalls.incrementAndGet(); free(writes.get()) }
                "isOpen", "isRunning", "isActive" -> true
                "getLongFramePosition", "getMicrosecondPosition" -> 0L
                "getFramePosition" -> 0
                "getLevel" -> 0f
                else -> null
            }
        } as SourceDataLine
        fun open(observer: (AudioOutputEvent) -> Unit = {}): AudioOutput = JvmAudioOutput(AudioFormat(48_000, ChannelLayout.STEREO), line, 4, observer)
    }

    private fun await(latch: CountDownLatch) { assertTrue(latch.await(3, TimeUnit.SECONDS), "worker did not reach expected state") }

    private fun startAfterUnwind(output: AudioOutput, callback: AudioRenderCallback) {
        repeat(3000) {
            try { output.start(callback); return } catch (_: IllegalStateException) { Thread.sleep(1) }
        }
        error("generation did not finish unwinding")
    }

    @Test
    fun terminalObserverCompletesBeforeTheNextStartedEvent() {
        val device = FakeDevice()
        val terminalEntered = CountDownLatch(1)
        val terminalRelease = CountDownLatch(1)
        val starts = AtomicInteger()
        val output = device.open {
            if (it is AudioOutputEvent.Started) starts.incrementAndGet()
            if (it is AudioOutputEvent.Stopped) { terminalEntered.countDown(); await(terminalRelease) }
        }
        try {
            output.start { _, n -> output.stop(); n }
            await(terminalEntered)
            assertFailsWith<IllegalStateException> { output.start { _, n -> n } }
            assertEquals(1, starts.get())
            terminalRelease.countDown()
            val fresh = CountDownLatch(1)
            startAfterUnwind(output) { _, n -> fresh.countDown(); n }
            await(fresh)
            assertEquals(2, starts.get())
        } finally { terminalRelease.countDown(); output.close() }
    }

    @Test
    fun boundedStopRetainsOwnershipUntilTheOldRendererUnwinds() {
        val device = FakeDevice()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val ended = CountDownLatch(1)
        val calls = AtomicInteger()
        val output = device.open { if (it is AudioOutputEvent.Stopped) ended.countDown() }
        try {
            output.start { _, frames -> calls.incrementAndGet(); entered.countDown(); await(release); frames }
            await(entered)
            output.stop()
            assertFalse(output.isRunning)
            assertFailsWith<IllegalStateException> { output.start { _, n -> n } }
            release.countDown()
            await(ended)
            assertEquals(1, calls.get())
            assertEquals(0, device.writes.get(), "a canceled renderer submitted stale samples")
            val restarted = CountDownLatch(1)
            startAfterUnwind(output) { _, frames -> restarted.countDown(); frames }
            await(restarted)
        } finally { release.countDown(); output.close() }
    }

    @Test
    fun stopUnblocksAPlatformWriteBeforeWaiting() {
        val device = FakeDevice()
        val entered = CountDownLatch(1)
        device.writer = { _, _, _ -> entered.countDown(); await(device.stopped); 0 }
        val ended = CountDownLatch(1)
        val output = device.open { if (it is AudioOutputEvent.Stopped) ended.countDown() }
        try {
            output.start { _, n -> n }
            await(entered)
            output.stop()
            await(ended)
            assertFalse(output.isRunning)
        } finally { output.close() }
    }

    @Test
    fun startedObserverCanCancelWithoutCreatingAnOverlappingWorker() {
        val device = FakeDevice()
        val ended = CountDownLatch(1)
        val callbacks = AtomicInteger()
        val rejection = AtomicReference<Throwable?>()
        lateinit var output: AudioOutput
        output = device.open { event ->
            if (event is AudioOutputEvent.Started) {
                output.stop()
                rejection.set(runCatching { output.start { _, n -> n } }.exceptionOrNull())
            }
            if (event is AudioOutputEvent.Stopped) ended.countDown()
        }
        try {
            output.start { _, n -> callbacks.incrementAndGet(); n }
            await(ended)
            assertTrue(rejection.get() is IllegalStateException)
            assertEquals(0, callbacks.get())
            assertEquals(1, device.starts.get())
        } finally { output.close() }
    }

    @Test
    fun callbackCloseIsTerminalAndDoesNotWaitForItself() {
        val device = FakeDevice()
        val closedFromCallback = CountDownLatch(1)
        val ended = CountDownLatch(1)
        val output = device.open { if (it is AudioOutputEvent.Stopped) ended.countDown() }
        output.start { _, n -> output.close(); closedFromCallback.countDown(); n }
        await(closedFromCallback)
        await(ended)
        assertFalse(output.isRunning)
        assertEquals(0, device.writes.get())
        assertFailsWith<IllegalStateException> { output.start { _, n -> n } }
        output.close()
    }

    @Test
    fun rendererViolationsStopWithContainedFailureEvents() {
        for (returned in listOf(-1, Int.MIN_VALUE, 5)) {
            val device = FakeDevice()
            val failed = AtomicInteger()
            val ended = CountDownLatch(1)
            val output = device.open {
                if (it is AudioOutputEvent.Failed) { failed.incrementAndGet(); error("broken observer") }
                if (it is AudioOutputEvent.Stopped) ended.countDown()
            }
            try {
                output.start { _, _ -> returned }
                await(ended)
                assertFalse(output.isRunning)
                assertEquals(1, failed.get())
                assertEquals(0, device.writes.get())
            } finally { output.close() }
        }
    }

    @Test
    fun observerFailuresDoNotKillTheWorker() {
        val device = FakeDevice()
        val rendered = CountDownLatch(2)
        val output = device.open { error("broken observer") }
        try {
            output.start { _, _ -> rendered.countDown(); 0 }
            await(rendered)
            assertTrue(output.isRunning)
        } finally { output.close() }
    }

    @Test
    fun backendStartFailureCanBeRetried() {
        val device = FakeDevice()
        val failures = AtomicInteger()
        val rendered = CountDownLatch(1)
        val output = device.open { if (it is AudioOutputEvent.Failed) failures.incrementAndGet() }
        try {
            device.startFailure = true
            output.start { _, n -> n }
            assertFalse(output.isRunning)
            assertEquals(1, failures.get())
            device.startFailure = false
            output.start { _, n -> rendered.countDown(); n }
            await(rendered)
            assertEquals(2, device.starts.get())
        } finally { output.close() }
    }

    @Test
    fun shortWritesPreserveEverySampleAndZeroProgressIsBounded() {
        val device = FakeDevice()
        val accepted = mutableListOf<Byte>()
        val ended = CountDownLatch(1)
        var transfers = 0
        device.writer = { data, offset, _ ->
            synchronized(accepted) { repeat(4) { accepted += data[offset + it] } }
            if (++transfers == 4) ended.countDown()
            4
        }
        val output = device.open()
        val renders = AtomicInteger()
        try {
            output.start { out, n ->
                if (renders.incrementAndGet() == 1) { for (i in out.indices) out[i] = (i + 1) / 16f; n }
                else { output.stop(); n }
            }
            await(ended)
            output.stop()
            assertEquals(16, synchronized(accepted) { accepted.size })
            val expected = (1..8).flatMap { value ->
                val sample = (value / 16f * 32767f).toInt()
                listOf((sample and 255).toByte(), (sample shr 8).toByte())
            }
            assertEquals(expected, accepted)
        } finally { output.close() }
        val zero = FakeDevice()
        zero.writer = { _, _, _ -> 0 }
        val failed = CountDownLatch(1)
        val zeroOutput = zero.open { if (it is AudioOutputEvent.DeviceLost) failed.countDown() }
        try {
            zeroOutput.start { _, n -> n }
            await(failed)
            assertFalse(zeroOutput.isRunning)
            assertEquals(10, zero.writes.get())
        } finally { zeroOutput.close() }
    }

    @Test
    fun invalidBufferSizesFailBeforeOpeningAnyBackend() {
        for (frames in listOf(-1, 0, Int.MAX_VALUE)) {
            assertFailsWith<IllegalArgumentException> { openAudioOutput(AudioFormat(48_000, ChannelLayout.STEREO), frames) }
        }
    }

    /**
     * Plays [blocks] blocks of 16 bytes on a line of 32 bytes, short where [short] says so, then
     * stops from the renderer. Returns every event with the number of writes made when it arrived.
     */
    private fun play(device: FakeDevice, output: AudioOutput, relay: Relay, blocks: Int, short: (Int) -> Boolean = { false }): List<Pair<AudioOutputEvent, Int>> {
        val renders = AtomicInteger()
        val ended = CountDownLatch(1)
        val events = mutableListOf<Pair<AudioOutputEvent, Int>>()
        relay.target = { event ->
            synchronized(events) { events += event to device.writes.get() }
            if (event is AudioOutputEvent.Stopped) ended.countDown()
        }
        startAfterUnwind(output) { _, frames ->
            val block = renders.incrementAndGet()
            if (block > blocks) { output.stop(); frames } else if (short(block)) frames - 1 else frames
        }
        await(ended)
        return synchronized(events) { events.toList() }
    }

    private class Relay { @Volatile var target: (AudioOutputEvent) -> Unit = {} }

    private fun lineOfTwoBlocks(): Pair<FakeDevice, Relay> = FakeDevice().apply { bufferSize = 32 } to Relay()

    @Test
    fun anEmptyLineBeforeAFullBlockIsOneDeviceUnderrunAfterTheWrite() {
        val (device, relay) = lineOfTwoBlocks()
        // The line is empty before writes 1, 2, 4 and 6. The first two fill an empty line at the start.
        device.free = { writes -> if (writes in setOf(0, 1, 3, 5)) 32 else 0 }
        val output = device.open { relay.target(it) }
        try {
            val events = play(device, output, relay, blocks = 7)
            assertEquals(
                listOf(AudioOutputEvent.DeviceUnderrun(1, 1) to 4, AudioOutputEvent.DeviceUnderrun(1, 2) to 6),
                events.filter { it.first is AudioOutputEvent.DeviceUnderrun },
            )
            assertTrue(events.none { it.first is AudioOutputEvent.Underrun }, "$events")
            // At most one look at the line for each block, and none before the line was full once.
            assertEquals(5, device.availableCalls.get())
        } finally { output.close() }
    }

    @Test
    fun aShortRenderOnAnEmptyLineSendsOnlyUnderrun() {
        val (device, relay) = lineOfTwoBlocks()
        device.free = { 32 }
        val output = device.open { relay.target(it) }
        try {
            val events = play(device, output, relay, blocks = 4) { it >= 3 }
            assertTrue(events.none { it.first is AudioOutputEvent.DeviceUnderrun }, "$events")
            assertEquals(2, events.count { it.first == AudioOutputEvent.Underrun(1) })
        } finally { output.close() }
    }

    @Test
    fun totalsContinueAcrossStopAndStartAndEachStreamSkipsItsOwnStart() {
        val (device, relay) = lineOfTwoBlocks()
        device.free = { 32 }
        val output = device.open { relay.target(it) }
        try {
            // Blocks 3 and 4 of each stream find the line empty. Blocks 1 and 2 fill it at the start.
            val first = play(device, output, relay, blocks = 4).filter { it.first is AudioOutputEvent.DeviceUnderrun }
            val second = play(device, output, relay, blocks = 4).filter { it.first is AudioOutputEvent.DeviceUnderrun }
            assertEquals(listOf<AudioOutputEvent>(AudioOutputEvent.DeviceUnderrun(1, 1), AudioOutputEvent.DeviceUnderrun(1, 2)), first.map { it.first })
            assertEquals(listOf<AudioOutputEvent>(AudioOutputEvent.DeviceUnderrun(1, 3), AudioOutputEvent.DeviceUnderrun(1, 4)), second.map { it.first })
            assertEquals(listOf(7, 8), second.map { it.second })
        } finally { output.close() }
    }

    @Test
    fun estimatedLatencyUsesTheBackendCapacity() {
        val device = FakeDevice()
        val output = device.open()
        try { assertEquals(Latency(1024, LatencyQuality.Estimated), output.latency) }
        finally { output.close() }
    }
}
