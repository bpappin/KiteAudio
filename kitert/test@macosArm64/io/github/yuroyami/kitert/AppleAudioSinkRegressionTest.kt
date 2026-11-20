@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package io.github.yuroyami.kitert

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.free
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import platform.AudioToolbox.AudioQueueBuffer
import platform.AudioToolbox.AudioQueueBufferRef
import platform.AudioToolbox.AudioQueueRef
import platform.AudioToolbox.AudioQueueRefVar
import platform.CoreAudioTypes.AudioStreamBasicDescription
import platform.darwin.dispatch_async
import platform.darwin.dispatch_queue_t
import platform.darwin.dispatch_sync
import platform.posix.usleep

/** Native allocation/dispatch is real; every AudioQueue operation is a fake with no hardware. */
class AppleAudioSinkRegressionTest {
    private class Driver : AppleAudioQueueDriver {
        private var queueStorage: ByteVar? = null
        private var queue: AudioQueueRef? = null
        private val buffers = mutableListOf<AudioQueueBufferRef>()
        private var callback: ((AudioQueueRef?, AudioQueueBufferRef?) -> Unit)? = null
        private var deliveryQueue: dispatch_queue_t = null
        val allocations = AtomicInt(0)
        val enqueues = AtomicInt(0)
        val starts = AtomicInt(0)
        val disposals = AtomicInt(0)
        val disposed = AtomicBoolean(false)
        val rejectDispose = AtomicBoolean(false)
        val rejectEnqueue = AtomicBoolean(false)
        var allocationFailure = -1
        val lifetimeViolation = AtomicBoolean(false)
        var disposalCheck: () -> Boolean = { true }
        override fun create(format: CPointer<AudioStreamBasicDescription>, output: CPointer<AudioQueueRefVar>, queue: dispatch_queue_t,
                            callback: (AudioQueueRef?, AudioQueueBufferRef?) -> Unit): Int {
            val storage = nativeHeap.alloc<ByteVar>()
            queueStorage = storage
            this.queue = storage.ptr.reinterpret()
            disposed.store(false)
            output.pointed.value = this.queue
            this.callback = callback
            deliveryQueue = queue
            return 0
        }
        override fun allocate(queue: AudioQueueRef, bytes: UInt, output: CPointer<CPointerVar<AudioQueueBuffer>>): Int {
            val index = allocations.fetchAndAdd(1)
            if (index == allocationFailure) return -1
            val buffer = nativeHeap.alloc<AudioQueueBuffer>()
            buffer.mAudioData = nativeHeap.allocArray<ByteVar>(bytes.toInt())
            buffer.mAudioDataBytesCapacity = bytes
            buffer.mAudioDataByteSize = 0u
            output.pointed.value = buffer.ptr
            buffers += buffer.ptr
            return 0
        }
        override fun enqueue(queue: AudioQueueRef, buffer: AudioQueueBufferRef): Int {
            if (disposed.load()) { lifetimeViolation.store(true); return -1 }
            enqueues.fetchAndAdd(1)
            return if (rejectEnqueue.load()) -1 else 0
        }
        override fun start(queue: AudioQueueRef): Int { starts.fetchAndAdd(1); return 0 }
        override fun stop(queue: AudioQueueRef): Int = 0
        override fun dispose(queue: AudioQueueRef): Int {
            disposals.fetchAndAdd(1)
            if (!disposalCheck()) lifetimeViolation.store(true)
            if (rejectDispose.load()) return -1
            buffers.forEach { buffer ->
                nativeHeap.free(buffer.pointed.mAudioData!!)
                nativeHeap.free(buffer)
            }
            buffers.clear()
            nativeHeap.free(queueStorage!!.ptr)
            queueStorage = null
            callback = null
            disposed.store(true)
            return 0
        }
        fun fireAsync() {
            val completion = callback!!
            val buffer = buffers.first()
            val currentQueue = queue
            dispatch_async(deliveryQueue) { completion(currentQueue, buffer) }
        }
        fun flushCallbacks() { dispatch_sync(deliveryQueue) { } }
    }

    private fun await(condition: () -> Boolean) {
        repeat(3000) { if (condition()) return; usleep(1_000u) }
        assertTrue(condition(), "expected asynchronous state was not reached")
    }

    @Test
    fun closeDuringPrimingDefersDisposalAndNeverUsesTheFreedBuffer() {
        val driver = Driver()
        val inside = AtomicBoolean(false)
        driver.disposalCheck = { !inside.load() }
        val sink = AppleAudioSink(AudioFormat(48_000), 4, {}, driver)
        sink.start { _, frames ->
            inside.store(true)
            sink.close()
            assertFalse(driver.disposed.load())
            inside.store(false)
            frames
        }
        await { driver.disposed.load() }
        assertFalse(sink.isRunning)
        assertFalse(driver.lifetimeViolation.load())
        assertEquals(1, driver.allocations.load())
        assertEquals(0, driver.enqueues.load())
        assertEquals(0, driver.starts.load())
        assertFailsWith<IllegalStateException> { sink.start { _, frames -> frames } }
    }

    @Test
    fun allocationFailureAtEveryPositionNeverStartsAndReclaimsPartialResources() {
        for (position in 0..2) {
            val driver = Driver().apply { allocationFailure = position }
            val failures = AtomicInt(0)
            val sink = AppleAudioSink(AudioFormat(48_000), 4, { if (it is AudioSinkEvent.Failed) failures.fetchAndAdd(1) }, driver)
            try {
                sink.start { _, frames -> frames }
                await { driver.disposed.load() }
                driver.flushCallbacks()
                assertFalse(sink.isRunning)
                assertEquals(position + 1, driver.allocations.load())
                assertEquals(0, driver.starts.load())
                assertTrue(failures.load() > 0)
            } finally { sink.close() }
        }
    }

    @Test
    fun enqueueFailuresDuringPrimingAndPlaybackStopTheGeneration() {
        for (duringPriming in listOf(true, false)) {
            val driver = Driver()
            val failures = AtomicInt(0)
            driver.rejectEnqueue.store(duringPriming)
            val sink = AppleAudioSink(AudioFormat(48_000), 4, { if (it is AudioSinkEvent.Failed) failures.fetchAndAdd(1) }, driver)
            try {
                sink.start { _, frames -> frames }
                if (!duringPriming) {
                    assertTrue(sink.isRunning)
                    driver.rejectEnqueue.store(true)
                    driver.fireAsync()
                }
                await { driver.disposed.load() }
                driver.flushCallbacks()
                assertFalse(sink.isRunning)
                assertTrue(failures.load() > 0)
                assertEquals(if (duringPriming) 0 else 1, driver.starts.load())
            } finally { sink.close() }
        }
    }

    @Test
    fun failedDisposalRetainsOwnershipAndCanBeRetried() {
        val driver = Driver()
        driver.rejectDispose.store(true)
        val failures = AtomicInt(0)
        val sink = AppleAudioSink(AudioFormat(48_000), 4, {
            if (it is AudioSinkEvent.Failed) failures.fetchAndAdd(1)
        }, driver)
        sink.start { _, frames -> frames }
        sink.stop()
        await { failures.load() > 0 }
        assertFalse(driver.disposed.load())
        assertFalse(sink.isRunning)
        assertFailsWith<IllegalStateException> { sink.start { _, frames -> frames } }
        driver.rejectDispose.store(false)
        sink.close()
        await { driver.disposed.load() }
        assertTrue(driver.disposals.load() >= 2)
    }

    @Test
    fun closingFromADisposalFailureObserverDoesNotCreateARetryLoop() {
        val driver = Driver()
        driver.rejectDispose.store(true)
        val failures = AtomicInt(0)
        lateinit var sink: AudioSink
        sink = AppleAudioSink(AudioFormat(48_000), 4, {
            if (it is AudioSinkEvent.Failed) { failures.fetchAndAdd(1); sink.close() }
        }, driver)
        sink.start { _, frames -> frames }
        sink.stop()
        await { driver.disposals.load() >= 2 }
        driver.flushCallbacks()
        usleep(20_000u)
        assertEquals(1, failures.load())
        assertEquals(2, driver.disposals.load())
        driver.rejectDispose.store(false)
        sink.close()
        await { driver.disposed.load() }
    }

    @Test
    fun callbackCloseAndThrowingObserversCannotEscapeOrEnqueueAfterCancellation() {
        val driver = Driver()
        val calls = AtomicInt(0)
        val sink = AppleAudioSink(AudioFormat(48_000), 4, { error("broken observer") }, driver)
        sink.start { _, frames -> if (calls.fetchAndAdd(1) >= 3) sink.close(); frames }
        driver.fireAsync()
        await { driver.disposed.load() }
        assertEquals(3, driver.enqueues.load())
        assertFalse(sink.isRunning)
    }

    @Test
    fun aStoppedGenerationCanBeReplacedWithoutRevivingItsRenderer() {
        val driver = Driver()
        val oldCalls = AtomicInt(0)
        val newCalls = AtomicInt(0)
        val sink = AppleAudioSink(AudioFormat(48_000), 4, {}, driver)
        sink.start { _, frames -> oldCalls.fetchAndAdd(1); frames }
        sink.stop()
        await { driver.disposed.load() }
        driver.flushCallbacks()
        sink.start { _, frames -> newCalls.fetchAndAdd(1); frames }
        assertTrue(sink.isRunning)
        driver.fireAsync()
        await { newCalls.load() >= 4 }
        sink.close()
        await { driver.disposed.load() }
        assertEquals(3, oldCalls.load())
        assertEquals(2, driver.starts.load())
        assertFalse(driver.lifetimeViolation.load())
    }

    @Test
    fun aBlockedCallbackDoesNotMakeStopOrRejectedRestartWaitForTeardown() {
        val driver = Driver()
        val calls = AtomicInt(0)
        val entered = AtomicBoolean(false)
        val released = AtomicBoolean(false)
        val sink = AppleAudioSink(AudioFormat(48_000), 4, {}, driver)
        sink.start { _, frames ->
            if (calls.fetchAndAdd(1) >= 3) {
                entered.store(true)
                await { released.load() }
            }
            frames
        }
        driver.fireAsync()
        await { entered.load() }
        try {
            sink.stop()
            assertFalse(sink.isRunning)
            assertFailsWith<IllegalStateException> { sink.start { _, frames -> frames } }
            assertFalse(driver.disposed.load())
        } finally {
            released.store(true)
            sink.close()
        }
        await { driver.disposed.load() }
        assertEquals(3, driver.enqueues.load())
    }
}
