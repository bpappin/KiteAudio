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

        override val bufferSizeInFrames: Int = 1024
        override fun play() { check(!released && !playFailure) { "injected play failure" } }
        override fun pause() { onPause() }
        override fun flush() {}
        override fun release() { released = true }
        override fun write(data: ShortArray, offset: Int, count: Int): Int {
            writes.incrementAndGet()
            return writer(data, offset, count)
        }
        override fun raiseWorkerPriority() {}

        fun open(observer: (AudioOutputEvent) -> Unit = {}): AudioOutput =
            AndroidAudioOutput(AudioFormat(48_000, ChannelLayout.STEREO), this, 4, observer)
    }

    private fun await(latch: CountDownLatch) { assertTrue(latch.await(3, TimeUnit.SECONDS), "worker did not reach expected state") }

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
