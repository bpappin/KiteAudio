@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package io.github.yuroyami.kiteaudio.output

import io.github.yuroyami.kiteaudio.core.AudioFormat
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.convert
import kotlinx.cinterop.free
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.native.concurrent.ThreadLocal
import platform.AudioToolbox.AudioQueueBuffer
import platform.AudioToolbox.AudioQueueBufferRef
import platform.AudioToolbox.AudioQueuePropertyID
import platform.AudioToolbox.AudioQueuePropertyListenerProc
import platform.AudioToolbox.AudioQueueRef
import platform.AudioToolbox.AudioQueueRefVar
import platform.CoreAudioTypes.AudioStreamBasicDescription
import platform.CoreAudioTypes.AudioTimeStamp
import platform.CoreAudioTypes.kAudioFormatFlagIsPacked
import platform.CoreAudioTypes.kAudioFormatFlagIsSignedInteger
import platform.CoreAudioTypes.kAudioFormatLinearPCM
import platform.CoreAudioTypes.kAudioTimeStampSampleTimeValid
import platform.darwin.dispatch_async
import platform.darwin.dispatch_queue_create
import platform.darwin.dispatch_sync
import platform.posix.memcpy

// Detect only our own user-callback reentrancy, without a lock or heap allocation per block.
@ThreadLocal
private var callbackOutput: AppleAudioOutput? = null

/**
 * AudioQueue owns the callback block, which keeps its generation alive. A separate control
 * queue disposes AudioQueue only after active rendering has unwound. The running-state listener
 * holds one StableRef per generation, freed after a successful disposal.
 *
 * The system can end a stream: the queue stops running, or [watchSession] reports a notice of
 * the audio session. Both hop to the control queue, which sends DeviceLost, then Stopped.
 */
internal class AppleAudioOutput(
    override val format: AudioFormat,
    private val bufferFrames: Int,
    private val onEvent: (AudioOutputEvent) -> Unit,
    private val driver: AppleAudioQueueDriver = NativeAudioQueueDriver,
    private val watchSession: (AppleAudioOutput) -> () -> Unit = ::watchAudioSession,
) : AudioOutput {
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
        lateinit var observer: (AudioOutputEvent) -> Unit

        // Only the render queue reads or writes the fields below, until the release frees the clock.
        val clock = nativeHeap.alloc<AudioTimeStamp>()
        var enqueuedFrames = 0L
        var dry = false

        // Only the control queue reads or writes the fields below.
        var listener: StableRef<RunningWatch>? = null
        var listening = false
        var stopWatchingSession: (() -> Unit)? = null
        // The system media services were lost or reset, so a failed disposal abandons the queue.
        var queueInvalid = false
    }

    /** The target of the running-state listener. AudioQueue calls it on its own thread. */
    private class RunningWatch(val output: AppleAudioOutput, val generation: Generation) {
        fun changed() {
            dispatch_async(output.controlQueue) { output.queueRunningChanged(generation) }
        }
    }

    private val controlQueue = dispatch_queue_create("KiteRT-audio-control", null)
    private val renderQueue = dispatch_queue_create("KiteRT-audio-render", null)
    private val closed = AtomicBoolean(false)
    private val startPending = AtomicBoolean(false)
    private val generation = AtomicReference<Generation?>(null)
    private val listenerRefs = AtomicInt(0)
    // Only the render queue uses it.
    private val deviceUnderruns = DeviceUnderrunCounter()

    /** The listener StableRefs that this output holds now. Tests check it for leaks. */
    internal val openListenerRefs: Int get() = listenerRefs.load()
    override val isRunning: Boolean
        get() = generation.load()?.let { it.active.load() && it.started.load() } == true

    override fun start(callback: AudioRenderCallback) {
        check(!closed.load()) { "the audio output is closed" }
        if (callbackOutput === this) {
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
        check(!closed.load()) { "the audio output is closed" }
        val previous = generation.load()
        if (previous?.active?.load() == true) return
        check(previous == null) { "the previous audio callback is still stopping" }
        val next = Generation(bufferFrames * format.channels, callback)
        next.observer = { event ->
            if (event is AudioOutputEvent.Failed) next.active.store(false)
            deliverAudioEvent(onEvent, event)
        }
        generation.store(next)
        if (closed.load()) {
            next.active.store(false)
            releaseOnControlQueue(next)
            error("the audio output is closed")
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
            val watch = StableRef.create(RunningWatch(this, next))
            listenerRefs.fetchAndAdd(1)
            next.listener = watch
            val listening = driver.addRunningListener(queue, runningChanged, watch.asCPointer())
            if (listening != 0) {
                fail(next, "AudioQueue would not report its running state, status $listening")
                releaseOnControlQueue(next)
                return
            }
            next.listening = true
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
            // Watch before the start, so a notice during the start ends the new stream.
            next.stopWatchingSession = watchSession(this)
            val startStatus = driver.start(queue)
            if (startStatus != 0) {
                fail(next, "AudioQueue would not start, status $startStatus")
                releaseOnControlQueue(next)
                return
            }
            next.started.store(true)
            dispatch_async(renderQueue) {
                inCallback {
                    if (next.active.load()) deliverAudioEvent(onEvent, AudioOutputEvent.Started(format, latency))
                }
            }
        } catch (problem: Throwable) {
            fail(next, "the audio output would not start", problem)
            releaseOnControlQueue(next)
        }
    }

    private inline fun inCallback(action: () -> Unit) {
        val previous = callbackOutput
        callbackOutput = this
        try { action() } finally { callbackOutput = previous }
    }

    private fun fill(current: Generation, queue: AudioQueueRef?, buffer: AudioQueueBufferRef?) {
        if (!current.active.load() || queue == null || buffer == null) return
        val callback = current.callback ?: return
        val filled = renderAudioBlock(callback, current.scratch, bufferFrames, format.framesToMicros(bufferFrames), current.observer)
        if (filled == RENDER_FAILED) {
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
        if (status == 0) {
            // Read the clock only after a good enqueue, so a slow call never delays the checks of a refused buffer.
            // A short render already sent Underrun, so its block counts no device underrun.
            if (queueRanDry(current, queue) && filled == bufferFrames) deviceUnderruns.report(1, onEvent)
            current.enqueuedFrames += bufferFrames
            return
        }
        // A stream that the app or the system already ended expects its buffers to be refused.
        if (!current.active.load()) return
        // A queue that the system stopped hands its buffers back and refuses them. That is a loss.
        if (current.started.load() && driver.isRunning(queue) != true) {
            dispatch_async(controlQueue) { queueRunningChanged(current) }
        } else if (current.active.compareAndSet(true, false)) {
            // Only the side that clears active ends the stream, so a loss on the control queue sends no Failed.
            fail(current, "AudioQueue could not enqueue a buffer, status $status")
        }
    }

    /**
     * True when the clock of the queue has passed the end of every frame enqueued before the
     * newest block. The clock keeps running while the queue has no buffer. The queue counts as dry
     * until the enqueued frames lead its clock by a block again, so one dropout counts once.
     */
    private fun queueRanDry(current: Generation, queue: AudioQueueRef): Boolean {
        if (!current.started.load()) return false
        val clock = current.clock
        if (driver.currentTime(queue, clock.ptr) != 0 || (clock.mFlags and kAudioTimeStampSampleTimeValid) == 0u) return false
        val lead = current.enqueuedFrames - clock.mSampleTime
        if (current.dry) {
            if (lead >= bufferFrames) current.dry = false
            return false
        }
        if (lead >= 0) return false
        current.dry = true
        return true
    }

    private fun fail(current: Generation, reason: String, cause: Throwable? = null) {
        current.active.store(false)
        current.started.store(false)
        // Delivery is always on the render queue, so observers may safely cancel/close.
        dispatch_async(renderQueue) { inCallback { deliverAudioEvent(onEvent, AudioOutputEvent.Failed(reason, cause)) } }
        requestRelease(current)
    }

    private fun requestRelease(current: Generation) {
        dispatch_async(controlQueue) { releaseOnControlQueue(current) }
    }

    /** Ends the stream of a queue that stopped running without a request from the app. */
    private fun queueRunningChanged(current: Generation) {
        val queue = current.queue ?: return
        if (generation.load() !== current || current.released.load() || !current.started.load()) return
        // A queue that starts, or that moves to a new default device, keeps running.
        if (driver.isRunning(queue) == true) return
        endBySystem(current, "the system stopped the audio queue", invalidatesQueue = false)
    }

    /**
     * Ends a running stream for a notice of the audio session. Safe from any thread: it only hops
     * to the control queue. The iOS session observers call it.
     */
    internal fun onSessionNotice(notice: SessionNotice) {
        dispatch_async(controlQueue) {
            generation.load()?.let { endBySystem(it, notice.reason, notice.invalidatesQueue) }
        }
    }

    /** Returns after the work queued on the control queue so far has run. Tests use it as a barrier. */
    internal fun awaitControlQueue() {
        dispatch_sync(controlQueue) { }
    }

    // Runs on the control queue. A stream the app stopped already sends no DeviceLost.
    private fun endBySystem(current: Generation, reason: String, invalidatesQueue: Boolean) {
        if (generation.load() !== current || current.released.load()) return
        if (invalidatesQueue) current.queueInvalid = true
        if (current.started.load() && current.active.compareAndSet(true, false)) {
            current.started.store(false)
            // The render queue is serial, so DeviceLost arrives before the Stopped of the release.
            dispatch_async(renderQueue) { inCallback { deliverAudioEvent(onEvent, AudioOutputEvent.DeviceLost(reason)) } }
            releaseOnControlQueue(current)
        } else if (invalidatesQueue && !current.active.load()) {
            // A queue whose disposal failed may be invalid now. Retry, so a restart is possible.
            releaseOnControlQueue(current)
        }
    }

    private fun releaseOnControlQueue(current: Generation) {
        if (generation.load() !== current || current.released.load()) return
        current.active.store(false)
        current.started.store(false)
        current.stopWatchingSession?.let { stopWatching ->
            current.stopWatchingSession = null
            try { stopWatching() } catch (_: Throwable) { }
        }
        // This runs off the callback queue. After the barrier, late callbacks only return;
        // successful synchronous disposal guarantees no callback survives its return.
        dispatch_sync(renderQueue) { }
        current.callback = null
        val queue = current.queue
        if (queue != null) {
            // Remove the listener first, so the stop below does not report itself.
            if (current.listening) {
                current.listening = false
                current.listener?.let { driver.removeRunningListener(queue, runningChanged, it.asCPointer()) }
            }
            val stopStatus = driver.stop(queue)
            val disposeStatus = driver.dispose(queue)
            if (disposeStatus != 0 && current.queueInvalid) {
                // The system already dropped this queue. Keep the listener StableRef, since a
                // stale queue could still call it, and let a new start build a new queue.
                current.listener = null
                current.queue = null
                nativeHeap.free(current.clock)
                current.released.store(true)
                dispatch_async(renderQueue) {
                    try { inCallback { deliverAudioEvent(onEvent, AudioOutputEvent.Stopped) } }
                    finally { generation.compareAndSet(current, null) }
                }
                return
            }
            if (disposeStatus != 0) {
                // The callback block and generation must remain owned while native code may
                // still call them. A later stop/close retries teardown; restart stays blocked.
                // An observer may respond to Failed by closing. Report this retained
                // resource only once, so an unsuccessful retry cannot feed back forever.
                if (current.disposalFailureReported.compareAndSet(false, true)) {
                    dispatch_async(renderQueue) {
                        inCallback { deliverAudioEvent(onEvent, AudioOutputEvent.Failed("AudioQueue disposal failed, status $disposeStatus")) }
                    }
                }
                return
            }
            current.queue = null
            current.listener?.let { listener ->
                current.listener = null
                listener.dispose()
                listenerRefs.fetchAndAdd(-1)
            }
            if (stopStatus != 0 && !current.queueInvalid) {
                dispatch_async(renderQueue) {
                    inCallback { deliverAudioEvent(onEvent, AudioOutputEvent.Failed("AudioQueue stop failed, status $stopStatus")) }
                }
            }
        }
        nativeHeap.free(current.clock)
        current.released.store(true)
        dispatch_async(renderQueue) {
            try { inCallback { deliverAudioEvent(onEvent, AudioOutputEvent.Stopped) } }
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

    private companion object {
        const val BUFFER_COUNT = 3

        // AudioQueue calls this on its own thread. It only hops to the control queue.
        val runningChanged: AudioQueuePropertyListenerProc = staticCFunction { userData: COpaquePointer?, _: AudioQueueRef?, _: AudioQueuePropertyID ->
            userData?.asStableRef<RunningWatch>()?.get()?.changed()
        }
    }
}

@Throws(Exception::class)
public actual fun openAudioOutput(
    preferredFormat: AudioFormat,
    bufferFrames: Int,
    onEvent: (AudioOutputEvent) -> Unit,
): AudioOutput {
    requireSupportedLayout(preferredFormat)
    validateBufferFrames(preferredFormat, bufferFrames)
    return AppleAudioOutput(preferredFormat, bufferFrames, onEvent)
}

public actual fun audioOutputAvailable(): Boolean = true
