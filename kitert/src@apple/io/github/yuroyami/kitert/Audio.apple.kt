@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package io.github.yuroyami.kitert

import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicReference
import kotlin.native.concurrent.ThreadLocal
import platform.AudioToolbox.AudioQueueBuffer
import platform.AudioToolbox.AudioQueueBufferRef
import platform.AudioToolbox.AudioQueueRef
import platform.AudioToolbox.AudioQueueRefVar
import platform.CoreAudioTypes.AudioStreamBasicDescription
import platform.CoreAudioTypes.kAudioFormatFlagIsPacked
import platform.CoreAudioTypes.kAudioFormatFlagIsSignedInteger
import platform.CoreAudioTypes.kAudioFormatLinearPCM
import platform.darwin.dispatch_async
import platform.darwin.dispatch_queue_create
import platform.darwin.dispatch_sync
import platform.posix.memcpy

// Detect only our own user-callback reentrancy, without a lock or heap allocation per block.
@ThreadLocal
private var callbackSink: AppleAudioSink? = null

/**
 * AudioQueue owns the callback block, which keeps its generation alive. A separate control
 * queue disposes AudioQueue only after active rendering has unwound. No StableRef is needed.
 */
internal class AppleAudioSink(
    override val format: AudioFormat,
    private val bufferFrames: Int,
    private val onEvent: (AudioSinkEvent) -> Unit,
    private val driver: AppleAudioQueueDriver = NativeAudioQueueDriver,
) : AudioSink {
    override val latency: Latency
        get() = Latency(bufferFrames * BUFFER_COUNT, LatencyQuality.Estimated)

    private class Generation(samples: Int, callback: AudioRenderCallback) {
        val active = AtomicBoolean(true)
        val started = AtomicBoolean(false)
        val released = AtomicBoolean(false)
        val disposalFailureReported = AtomicBoolean(false)
        val scratch = FloatArray(samples)
        val pcm = ShortArray(samples)
        var callback: AudioRenderCallback? = callback
        var queue: AudioQueueRef? = null
        lateinit var observer: (AudioSinkEvent) -> Unit
    }

    private val controlQueue = dispatch_queue_create("KiteRT-audio-control", null)
    private val renderQueue = dispatch_queue_create("KiteRT-audio-render", null)
    private val closed = AtomicBoolean(false)
    private val startPending = AtomicBoolean(false)
    private val generation = AtomicReference<Generation?>(null)
    override val isRunning: Boolean
        get() = generation.load()?.let { it.active.load() && it.started.load() } == true

    override fun start(callback: AudioRenderCallback) {
        check(!closed.load()) { "the audio sink is closed" }
        if (callbackSink === this) {
            if (generation.load()?.active?.load() == true) return
            error("the previous audio callback is still stopping")
        }
        val current = generation.load()
        if (current?.active?.load() == true) return
        check(current == null) { "the previous audio callback is still stopping" }
        check(startPending.compareAndSet(false, true)) { "an audio start is already in progress" }
        try {
            // Recheck after claiming startup so a concurrent stop never leaves this call
            // queued behind teardown waiting for an arbitrary application callback.
            val latest = generation.load()
            if (latest?.active?.load() == true) return
            check(latest == null) { "the previous audio callback is still stopping" }
            // Throw Kotlin errors after dispatch returns, never through an ObjC block.
            var failure: Throwable? = null
            dispatch_sync(controlQueue) {
                try { startOnControlQueue(callback) } catch (problem: Throwable) { failure = problem }
            }
            failure?.let { throw it }
        } finally {
            startPending.store(false)
        }
    }

    private fun startOnControlQueue(callback: AudioRenderCallback) {
        check(!closed.load()) { "the audio sink is closed" }
        val previous = generation.load()
        if (previous?.active?.load() == true) return
        check(previous == null) { "the previous audio callback is still stopping" }
        val next = Generation(bufferFrames * format.channels, callback)
        next.observer = { event ->
            if (event is AudioSinkEvent.Failed) next.active.store(false)
            deliverAudioEvent(onEvent, event)
        }
        generation.store(next)
        if (closed.load()) {
            next.active.store(false)
            releaseOnControlQueue(next)
            error("the audio sink is closed")
        }
        try {
            val status = memScoped {
                val description = alloc<AudioStreamBasicDescription>().apply {
                    mSampleRate = format.sampleRate.toDouble()
                    mFormatID = kAudioFormatLinearPCM
                    mFormatFlags = kAudioFormatFlagIsSignedInteger or kAudioFormatFlagIsPacked
                    mBitsPerChannel = 16u
                    mChannelsPerFrame = format.channels.toUInt()
                    mFramesPerPacket = 1u
                    mBytesPerFrame = (2 * format.channels).toUInt()
                    mBytesPerPacket = (2 * format.channels).toUInt()
                }
                val out = alloc<AudioQueueRefVar>()
                out.value = null
                val result = driver.create(description.ptr, out.ptr, renderQueue) { queue, buffer ->
                    inCallback {
                        try { fill(next, queue, buffer) } catch (problem: Throwable) {
                            fail(next, "the audio callback failed", problem)
                        }
                    }
                }
                next.queue = out.value
                result
            }
            if (status != 0 || next.queue == null) {
                fail(next, "AudioQueue would not open, status $status")
                releaseOnControlQueue(next)
                return
            }
            val queue = next.queue!!
            repeat(BUFFER_COUNT) {
                if (!next.active.load()) return@repeat
                memScoped {
                    val buffer = alloc<CPointerVar<AudioQueueBuffer>>()
                    buffer.value = null
                    val allocation = driver.allocate(queue, (next.pcm.size * 2).toUInt(), buffer.ptr)
                    val allocated = buffer.value
                    if (allocation != 0 || allocated == null) {
                        fail(next, "AudioQueue buffer allocation failed, status $allocation")
                    } else {
                        dispatch_sync(renderQueue) {
                            inCallback {
                                try { fill(next, queue, allocated) } catch (problem: Throwable) {
                                    fail(next, "audio buffer priming failed", problem)
                                }
                            }
                        }
                    }
                }
            }
            if (!next.active.load()) {
                releaseOnControlQueue(next)
                return
            }
            val startStatus = driver.start(queue)
            if (startStatus != 0) {
                fail(next, "AudioQueue would not start, status $startStatus")
                releaseOnControlQueue(next)
                return
            }
            next.started.store(true)
            dispatch_async(renderQueue) {
                inCallback {
                    if (next.active.load()) deliverAudioEvent(onEvent, AudioSinkEvent.Started(format, latency))
                }
            }
        } catch (problem: Throwable) {
            fail(next, "the audio output would not start", problem)
            releaseOnControlQueue(next)
        }
    }

    private inline fun inCallback(action: () -> Unit) {
        val previous = callbackSink
        callbackSink = this
        try { action() } finally { callbackSink = previous }
    }

    private fun fill(current: Generation, queue: AudioQueueRef?, buffer: AudioQueueBufferRef?) {
        if (!current.active.load() || queue == null || buffer == null) return
        val callback = current.callback ?: return
        if (!renderAudioBlock(callback, current.scratch, bufferFrames, format.framesToMicros(bufferFrames), current.observer)) {
            current.active.store(false)
            requestRelease(current)
            return
        }
        // User callbacks may have stopped/closed us. Disposal waits for this entire block.
        if (!current.active.load()) return
        for (i in current.scratch.indices) {
            current.pcm[i] = (current.scratch[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
        }
        val destination = buffer.pointed.mAudioData
        if (destination == null) {
            fail(current, "AudioQueue returned a buffer without sample storage")
            return
        }
        current.pcm.usePinned { pinned ->
            memcpy(destination, pinned.addressOf(0), (current.pcm.size * 2).convert())
        }
        buffer.pointed.mAudioDataByteSize = (current.pcm.size * 2).toUInt()
        if (!current.active.load()) return
        val status = driver.enqueue(queue, buffer)
        if (status != 0) fail(current, "AudioQueue could not enqueue a buffer, status $status")
    }

    private fun fail(current: Generation, reason: String, cause: Throwable? = null) {
        current.active.store(false)
        current.started.store(false)
        // Delivery is always on the render queue, so observers may safely cancel/close.
        dispatch_async(renderQueue) { inCallback { deliverAudioEvent(onEvent, AudioSinkEvent.Failed(reason, cause)) } }
        requestRelease(current)
    }

    private fun requestRelease(current: Generation) {
        dispatch_async(controlQueue) { releaseOnControlQueue(current) }
    }

    private fun releaseOnControlQueue(current: Generation) {
        if (generation.load() !== current || current.released.load()) return
        current.active.store(false)
        current.started.store(false)
        // This runs off the callback queue. After the barrier, late callbacks only return;
        // successful synchronous disposal guarantees no callback survives its return.
        dispatch_sync(renderQueue) { }
        current.callback = null
        val queue = current.queue
        if (queue != null) {
            val stopStatus = driver.stop(queue)
            val disposeStatus = driver.dispose(queue)
            if (disposeStatus != 0) {
                // The callback block and generation must remain owned while native code may
                // still call them. A later stop/close retries teardown; restart stays blocked.
                // An observer may respond to Failed by closing. Report this retained
                // resource only once, so an unsuccessful retry cannot feed back forever.
                if (current.disposalFailureReported.compareAndSet(false, true)) {
                    dispatch_async(renderQueue) {
                        inCallback { deliverAudioEvent(onEvent, AudioSinkEvent.Failed("AudioQueue disposal failed, status $disposeStatus")) }
                    }
                }
                return
            }
            current.queue = null
            if (stopStatus != 0) {
                dispatch_async(renderQueue) {
                    inCallback { deliverAudioEvent(onEvent, AudioSinkEvent.Failed("AudioQueue stop failed, status $stopStatus")) }
                }
            }
        }
        current.released.store(true)
        dispatch_async(renderQueue) {
            try { inCallback { deliverAudioEvent(onEvent, AudioSinkEvent.Stopped) } }
            finally { generation.compareAndSet(current, null) }
        }
    }

    override fun stop() {
        val current = generation.load() ?: return
        current.active.store(false)
        current.started.store(false)
        requestRelease(current)
    }

    override fun close() {
        closed.store(true)
        stop()
    }

    private companion object { const val BUFFER_COUNT = 3 }
}

@Throws(Exception::class)
public actual fun openAudioSink(
    preferredFormat: AudioFormat,
    bufferFrames: Int,
    onEvent: (AudioSinkEvent) -> Unit,
): AudioSink {
    validateBufferFrames(preferredFormat, bufferFrames)
    return AppleAudioSink(preferredFormat, bufferFrames, onEvent)
}

public actual fun audioOutputAvailable(): Boolean = true
