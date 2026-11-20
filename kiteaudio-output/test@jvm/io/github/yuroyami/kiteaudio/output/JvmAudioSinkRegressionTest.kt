package io.github.yuroyami.kiteaudio.output

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
class JvmAudioSinkRegressionTest {
    private class Output {
        val writes = AtomicInteger()
        val starts = AtomicInteger()
        val stopped = CountDownLatch(1)
        var startFailure = false
        var writer: (ByteArray, Int, Int) -> Int = { _, _, count -> Thread.sleep(1); count }
        val line = Proxy.newProxyInstance(SourceDataLine::class.java.classLoader, arrayOf(SourceDataLine::class.java)) { _, method, args ->
            when (method.name) {
                "start" -> { starts.incrementAndGet(); check(!startFailure) { "start failure" }; null }
                "write" -> { writes.incrementAndGet(); writer(args!![0] as ByteArray, args[1] as Int, args[2] as Int) }
                "stop", "close" -> { stopped.countDown(); null }
                "getBufferSize" -> 4096
                "isOpen", "isRunning", "isActive" -> true
                "getLongFramePosition", "getMicrosecondPosition" -> 0L
                "getFramePosition", "available" -> 0
                "getLevel" -> 0f
                else -> null
            }
        } as SourceDataLine
        fun sink(observer: (AudioSinkEvent) -> Unit = {}): AudioSink = JvmAudioSink(AudioFormat(48_000), line, 4, observer)
    }

    private fun await(latch: CountDownLatch) { assertTrue(latch.await(3, TimeUnit.SECONDS), "worker did not reach expected state") }

    private fun startAfterUnwind(sink: AudioSink, callback: AudioRenderCallback) {
        repeat(3000) {
            try { sink.start(callback); return } catch (_: IllegalStateException) { Thread.sleep(1) }
        }
        error("generation did not finish unwinding")
    }

    @Test
    fun terminalObserverCompletesBeforeTheNextStartedEvent() {
        val output = Output()
        val terminalEntered = CountDownLatch(1)
        val terminalRelease = CountDownLatch(1)
        val starts = AtomicInteger()
        val sink = output.sink {
            if (it is AudioSinkEvent.Started) starts.incrementAndGet()
            if (it is AudioSinkEvent.Stopped) { terminalEntered.countDown(); await(terminalRelease) }
        }
        try {
            sink.start { _, n -> sink.stop(); n }
            await(terminalEntered)
            assertFailsWith<IllegalStateException> { sink.start { _, n -> n } }
            assertEquals(1, starts.get())
            terminalRelease.countDown()
            val fresh = CountDownLatch(1)
            startAfterUnwind(sink) { _, n -> fresh.countDown(); n }
            await(fresh)
            assertEquals(2, starts.get())
        } finally { terminalRelease.countDown(); sink.close() }
    }

    @Test
    fun boundedStopRetainsOwnershipUntilTheOldRendererUnwinds() {
        val output = Output()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val ended = CountDownLatch(1)
        val calls = AtomicInteger()
        val sink = output.sink { if (it is AudioSinkEvent.Stopped) ended.countDown() }
        try {
            sink.start { _, frames -> calls.incrementAndGet(); entered.countDown(); await(release); frames }
            await(entered)
            sink.stop()
            assertFalse(sink.isRunning)
            assertFailsWith<IllegalStateException> { sink.start { _, n -> n } }
            release.countDown()
            await(ended)
            assertEquals(1, calls.get())
            assertEquals(0, output.writes.get(), "a canceled renderer submitted stale samples")
            val restarted = CountDownLatch(1)
            startAfterUnwind(sink) { _, frames -> restarted.countDown(); frames }
            await(restarted)
        } finally { release.countDown(); sink.close() }
    }

    @Test
    fun stopUnblocksAPlatformWriteBeforeWaiting() {
        val output = Output()
        val entered = CountDownLatch(1)
        output.writer = { _, _, _ -> entered.countDown(); await(output.stopped); 0 }
        val ended = CountDownLatch(1)
        val sink = output.sink { if (it is AudioSinkEvent.Stopped) ended.countDown() }
        try {
            sink.start { _, n -> n }
            await(entered)
            sink.stop()
            await(ended)
            assertFalse(sink.isRunning)
        } finally { sink.close() }
    }

    @Test
    fun startedObserverCanCancelWithoutCreatingAnOverlappingWorker() {
        val output = Output()
        val ended = CountDownLatch(1)
        val callbacks = AtomicInteger()
        val rejection = AtomicReference<Throwable?>()
        lateinit var sink: AudioSink
        sink = output.sink { event ->
            if (event is AudioSinkEvent.Started) {
                sink.stop()
                rejection.set(runCatching { sink.start { _, n -> n } }.exceptionOrNull())
            }
            if (event is AudioSinkEvent.Stopped) ended.countDown()
        }
        try {
            sink.start { _, n -> callbacks.incrementAndGet(); n }
            await(ended)
            assertTrue(rejection.get() is IllegalStateException)
            assertEquals(0, callbacks.get())
            assertEquals(1, output.starts.get())
        } finally { sink.close() }
    }

    @Test
    fun callbackCloseIsTerminalAndDoesNotWaitForItself() {
        val output = Output()
        val closedFromCallback = CountDownLatch(1)
        val ended = CountDownLatch(1)
        val sink = output.sink { if (it is AudioSinkEvent.Stopped) ended.countDown() }
        sink.start { _, n -> sink.close(); closedFromCallback.countDown(); n }
        await(closedFromCallback)
        await(ended)
        assertFalse(sink.isRunning)
        assertEquals(0, output.writes.get())
        assertFailsWith<IllegalStateException> { sink.start { _, n -> n } }
        sink.close()
    }

    @Test
    fun rendererViolationsStopWithContainedFailureEvents() {
        for (returned in listOf(-1, Int.MIN_VALUE, 5)) {
            val output = Output()
            val failed = AtomicInteger()
            val ended = CountDownLatch(1)
            val sink = output.sink {
                if (it is AudioSinkEvent.Failed) { failed.incrementAndGet(); error("broken observer") }
                if (it is AudioSinkEvent.Stopped) ended.countDown()
            }
            try {
                sink.start { _, _ -> returned }
                await(ended)
                assertFalse(sink.isRunning)
                assertEquals(1, failed.get())
                assertEquals(0, output.writes.get())
            } finally { sink.close() }
        }
    }

    @Test
    fun observerFailuresDoNotKillTheWorker() {
        val output = Output()
        val rendered = CountDownLatch(2)
        val sink = output.sink { error("broken observer") }
        try {
            sink.start { _, _ -> rendered.countDown(); 0 }
            await(rendered)
            assertTrue(sink.isRunning)
        } finally { sink.close() }
    }

    @Test
    fun backendStartFailureCanBeRetried() {
        val output = Output()
        val failures = AtomicInteger()
        val rendered = CountDownLatch(1)
        val sink = output.sink { if (it is AudioSinkEvent.Failed) failures.incrementAndGet() }
        try {
            output.startFailure = true
            sink.start { _, n -> n }
            assertFalse(sink.isRunning)
            assertEquals(1, failures.get())
            output.startFailure = false
            sink.start { _, n -> rendered.countDown(); n }
            await(rendered)
            assertEquals(2, output.starts.get())
        } finally { sink.close() }
    }

    @Test
    fun shortWritesPreserveEverySampleAndZeroProgressIsBounded() {
        val output = Output()
        val accepted = mutableListOf<Byte>()
        val ended = CountDownLatch(1)
        var transfers = 0
        output.writer = { data, offset, _ ->
            synchronized(accepted) { repeat(4) { accepted += data[offset + it] } }
            if (++transfers == 4) ended.countDown()
            4
        }
        val sink = output.sink()
        val renders = AtomicInteger()
        try {
            sink.start { out, n ->
                if (renders.incrementAndGet() == 1) { for (i in out.indices) out[i] = (i + 1) / 16f; n }
                else { sink.stop(); n }
            }
            await(ended)
            sink.stop()
            assertEquals(16, synchronized(accepted) { accepted.size })
            val expected = (1..8).flatMap { value ->
                val sample = (value / 16f * 32767f).toInt()
                listOf((sample and 255).toByte(), (sample shr 8).toByte())
            }
            assertEquals(expected, accepted)
        } finally { sink.close() }
        val zero = Output()
        zero.writer = { _, _, _ -> 0 }
        val failed = CountDownLatch(1)
        val zeroSink = zero.sink { if (it is AudioSinkEvent.DeviceLost) failed.countDown() }
        try {
            zeroSink.start { _, n -> n }
            await(failed)
            assertFalse(zeroSink.isRunning)
            assertEquals(10, zero.writes.get())
        } finally { zeroSink.close() }
    }

    @Test
    fun invalidBufferSizesFailBeforeOpeningAnyBackend() {
        for (frames in listOf(-1, 0, Int.MAX_VALUE)) {
            assertFailsWith<IllegalArgumentException> { openAudioSink(AudioFormat(48_000), frames) }
        }
    }

    @Test
    fun estimatedLatencyUsesTheBackendCapacity() {
        val output = Output()
        val sink = output.sink()
        try { assertEquals(Latency(1024, LatencyQuality.Estimated), sink.latency) }
        finally { sink.close() }
    }
}
