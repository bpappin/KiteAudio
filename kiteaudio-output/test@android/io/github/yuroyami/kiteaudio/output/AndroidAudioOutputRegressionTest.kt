package io.github.yuroyami.kiteaudio.output

import io.github.yuroyami.kiteaudio.core.AudioFormat
import io.github.yuroyami.kiteaudio.core.ChannelLayout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Drives the Android output's control flow on the host JVM with a fake track. No device is opened. */
class AndroidAudioOutputRegressionTest {
    private class FakeTrack : AndroidPcmTrack {
        val writes = AtomicInteger()
        @Volatile var playFailure = false
        @Volatile var released = false
        @Volatile var writer: (ShortArray, Int, Int) -> Int = { _, _, count -> Thread.sleep(1); count }
        @Volatile var onPause: () -> Unit = {}
        /** The system underrun counter for the number of writes so far; -1 is a device below API level 24. */
        @Volatile var underruns: (Int) -> Int = { 0 }
        val underrunReads = AtomicInteger()

        override val bufferSizeInFrames: Int = 1024
        override fun play() { check(!released && !playFailure) { "injected play failure" } }
        override fun pause() { onPause() }
        override fun flush() {}
        override fun release() { released = true }
        override fun write(data: ShortArray, offset: Int, count: Int): Int {
            writes.incrementAndGet()
            return writer(data, offset, count)
        }
        override val underrunCount: Int get() { underrunReads.incrementAndGet(); return underruns(writes.get()) }
        override fun raiseWorkerPriority() {}

        fun open(observer: (AudioOutputEvent) -> Unit = {}): AudioOutput =
            AndroidAudioOutput(AudioFormat(48_000, ChannelLayout.STEREO), this, 4, observer)
    }

    private fun await(latch: CountDownLatch) { assertTrue(latch.await(3, TimeUnit.SECONDS), "worker did not reach expected state") }

    /**
     * Plays [blocks] blocks, short where [short] says so, then stops from the renderer. Puts every
     * event into [events], each with the number of writes made when it arrived.
     */
    private fun play(track: FakeTrack, output: AudioOutput, events: MutableList<Pair<AudioOutputEvent, Int>>, blocks: Int, short: (Int) -> Boolean = { false }) {
        val renders = AtomicInteger()
        val ended = CountDownLatch(1)
        synchronized(events) { events.clear() }
        listener = { event ->
            synchronized(events) { events += event to track.writes.get() }
            if (event is AudioOutputEvent.Stopped) ended.countDown()
        }
        val callback = AudioRenderCallback { _, frames ->
            val block = renders.incrementAndGet()
            if (block > blocks) { output.stop(); frames } else if (short(block)) frames - 1 else frames
        }
        // A previous stream may still be unwinding after its Stopped event.
        var started = false
        repeat(3000) {
            if (!started) started = runCatching { output.start(callback) }.isSuccess.also { if (!it) Thread.sleep(1) }
        }
        assertTrue(started, "the previous stream did not unwind")
        await(ended)
    }

    @Volatile private var listener: (AudioOutputEvent) -> Unit = {}

    private fun deviceEvents(events: List<Pair<AudioOutputEvent, Int>>) =
        synchronized(events) { events.filter { it.first is AudioOutputEvent.DeviceUnderrun } }

    @Test
    fun aRisingSystemCounterSendsDeviceUnderrunsAfterTheWriteAndNoUnderrun() {
        val track = FakeTrack()
        // The counter already shows 5 at the first block, which only sets the start.
        track.underruns = { writes -> when { writes <= 1 -> 5; writes <= 3 -> 7; else -> 8 } }
        val events = mutableListOf<Pair<AudioOutputEvent, Int>>()
        val output = track.open { listener(it) }
        try {
            play(track, output, events, blocks = 6)
            assertEquals(
                listOf(AudioOutputEvent.DeviceUnderrun(2, 2) to 2, AudioOutputEvent.DeviceUnderrun(1, 3) to 4),
                deviceEvents(events),
            )
            assertTrue(synchronized(events) { events.none { it.first is AudioOutputEvent.Underrun } }, "$events")
            // One read of the counter for each written block.
            assertEquals(6, track.underrunReads.get())
        } finally { output.close() }
    }

    @Test
    fun aShortRenderSendsOnlyUnderrunEvenWhenTheCounterRises() {
        val track = FakeTrack()
        track.underruns = { writes -> if (writes <= 2) 0 else 4 }
        val events = mutableListOf<Pair<AudioOutputEvent, Int>>()
        val output = track.open { listener(it) }
        try {
            play(track, output, events, blocks = 5) { it == 3 }
            assertTrue(deviceEvents(events).isEmpty(), "$events")
            assertEquals(1, synchronized(events) { events.count { it.first == AudioOutputEvent.Underrun(1) } })
        } finally { output.close() }
    }

    @Test
    fun totalsContinueAcrossStopAndStart() {
        val track = FakeTrack()
        val events = mutableListOf<Pair<AudioOutputEvent, Int>>()
        val output = track.open { listener(it) }
        try {
            // The counter is 0 after writes 1 and 2, then 1 after write 3.
            track.underruns = { writes -> if (writes < 3) 0 else 1 }
            play(track, output, events, blocks = 4)
            assertEquals(listOf<AudioOutputEvent>(AudioOutputEvent.DeviceUnderrun(1, 1)), deviceEvents(events).map { it.first })
            // The track keeps its count over the pause. Two more underruns happen in the new stream.
            track.underruns = { writes -> if (writes < 6) 1 else 3 }
            play(track, output, events, blocks = 3)
            assertEquals(listOf<AudioOutputEvent>(AudioOutputEvent.DeviceUnderrun(2, 3)), deviceEvents(events).map { it.first })
        } finally { output.close() }
    }

    @Test
    fun aDeviceBelowApiLevel24SendsNoDeviceUnderrun() {
        val track = FakeTrack()
        track.underruns = { -1 }
        val events = mutableListOf<Pair<AudioOutputEvent, Int>>()
        val output = track.open { listener(it) }
        try {
            play(track, output, events, blocks = 5)
            assertTrue(deviceEvents(events).isEmpty(), "$events")
            assertTrue(synchronized(events) { events.any { it.first is AudioOutputEvent.Started } })
        } finally { output.close() }
    }

    @Test
    fun invalidBufferSizesFailBeforeTheFactoryTouchesAudioTrack() {
        for (frames in listOf(-1, 0, Int.MAX_VALUE)) {
            assertFailsWith<IllegalArgumentException> { openAudioOutput(AudioFormat(48_000, ChannelLayout.STEREO), frames) }
        }
    }

    @Test
    fun shortAndZeroWritesKeepEverySampleInOrder() {
        val track = FakeTrack()
        val accepted = mutableListOf<Short>()
        var transfers = 0
        // One frame per write, and the second write accepts nothing.
        track.writer = { data, offset, _ ->
            if (transfers++ == 1) 0
            else { synchronized(accepted) { accepted += data[offset]; accepted += data[offset + 1] }; 2 }
        }
        val ended = CountDownLatch(1)
        val renders = AtomicInteger()
        val output = track.open { if (it is AudioOutputEvent.Stopped) ended.countDown() }
        try {
            output.start { out, frames ->
                if (renders.incrementAndGet() == 1) { for (i in out.indices) out[i] = (i + 1) / 16f; frames }
                else { output.stop(); frames }
            }
            await(ended)
            val expected = (1..8).map { (it / 16f * 32767f).toInt().toShort() }
            assertEquals(expected, synchronized(accepted) { accepted.toList() }, "short writes lost or duplicated samples")
            assertEquals(5, track.writes.get())
        } finally { output.close() }
    }

    @Test
    fun writesThatMakeNoProgressStopTheStreamAfterTenTries() {
        val track = FakeTrack()
        track.writer = { _, _, _ -> 0 }
        val lost = CountDownLatch(1)
        val output = track.open { if (it is AudioOutputEvent.DeviceLost) lost.countDown() }
        try {
            output.start { _, frames -> frames }
            await(lost)
            assertFalse(output.isRunning)
            assertEquals(10, track.writes.get())
        } finally { output.close() }
    }

    @Test
    fun aCanceledRendererKeepsOwnershipUntilItUnwindsAndCloseIsTerminal() {
        val track = FakeTrack()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val exited = CountDownLatch(1)
        val calls = AtomicInteger()
        val output = track.open { if (it is AudioOutputEvent.Stopped) exited.countDown() }
        try {
            output.start { _, n -> calls.incrementAndGet(); entered.countDown(); await(release); n }
            await(entered)
            output.stop()
            assertFailsWith<IllegalStateException> { output.start { _, n -> n } }
            release.countDown()
            await(exited)
            assertEquals(1, calls.get())
            assertEquals(0, track.writes.get(), "a canceled renderer submitted stale samples")
            output.close()
            assertFailsWith<IllegalStateException> { output.start { _, n -> n } }
        } finally { release.countDown(); output.close() }
    }

    @Test
    fun aFailedPlayIsContainedAndTheNextStartRuns() {
        val track = FakeTrack()
        val events = mutableListOf<AudioOutputEvent>()
        // Every event reaches an observer that throws.
        val output = track.open { event -> synchronized(events) { events += event }; error("observer failure") }
        try {
            track.playFailure = true
            output.start { _, n -> n }
            assertFalse(output.isRunning)
            val failure = synchronized(events) { events.filterIsInstance<AudioOutputEvent.Failed>().single() }
            assertEquals("the audio output would not start", failure.reason)
            track.playFailure = false
            val retried = CountDownLatch(1)
            output.start { _, _ -> retried.countDown(); 0 }
            await(retried)
            assertTrue(output.isRunning)
        } finally { output.close() }
    }

    @Test
    fun anInvalidRendererResultStopsTheStreamBeforeAnyWrite() {
        val track = FakeTrack()
        val failed = CountDownLatch(1)
        val ended = CountDownLatch(1)
        val output = track.open {
            if (it is AudioOutputEvent.Failed) failed.countDown()
            if (it is AudioOutputEvent.Stopped) ended.countDown()
        }
        try {
            output.start { _, _ -> Int.MIN_VALUE }
            await(ended)
            assertEquals(0, failed.count, "an invalid renderer result was not reported as a failure")
            assertFalse(output.isRunning)
            assertEquals(0, track.writes.get())
        } finally { output.close() }
    }

    @Test
    fun stopUnblocksAPlatformWriteBeforeItWaits() {
        val track = FakeTrack()
        val inWrite = CountDownLatch(1)
        val paused = CountDownLatch(1)
        track.onPause = { paused.countDown() }
        track.writer = { _, _, _ -> inWrite.countDown(); await(paused); 0 }
        val ended = CountDownLatch(1)
        val output = track.open { if (it is AudioOutputEvent.Stopped) ended.countDown() }
        try {
            output.start { _, n -> n }
            await(inWrite)
            output.stop()
            assertEquals(0, paused.count, "stop returned without pausing the track")
            assertFalse(output.isRunning)
            await(ended)
        } finally { output.close() }
    }
}
