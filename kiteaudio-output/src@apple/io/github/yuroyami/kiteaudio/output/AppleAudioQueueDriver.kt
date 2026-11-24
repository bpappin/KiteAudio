@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.yuroyami.kiteaudio.output

import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.value
import platform.AudioToolbox.AudioQueueAddPropertyListener
import platform.AudioToolbox.AudioQueueAllocateBuffer
import platform.AudioToolbox.AudioQueueBuffer
import platform.AudioToolbox.AudioQueueBufferRef
import platform.AudioToolbox.AudioQueueDispose
import platform.AudioToolbox.AudioQueueEnqueueBuffer
import platform.AudioToolbox.AudioQueueGetProperty
import platform.AudioToolbox.AudioQueueNewOutputWithDispatchQueue
import platform.AudioToolbox.AudioQueuePropertyListenerProc
import platform.AudioToolbox.AudioQueueRef
import platform.AudioToolbox.AudioQueueRefVar
import platform.AudioToolbox.AudioQueueRemovePropertyListener
import platform.AudioToolbox.AudioQueueStart
import platform.AudioToolbox.AudioQueueStop
import platform.AudioToolbox.kAudioQueueProperty_IsRunning
import platform.CoreAudioTypes.AudioStreamBasicDescription
import platform.darwin.dispatch_queue_t

/** Narrow native boundary; deterministic tests supply buffers without opening audio hardware. */
internal interface AppleAudioQueueDriver {
    fun create(format: CPointer<AudioStreamBasicDescription>, output: CPointer<AudioQueueRefVar>, queue: dispatch_queue_t,
               callback: (AudioQueueRef?, AudioQueueBufferRef?) -> Unit): Int
    fun allocate(queue: AudioQueueRef, bytes: UInt, output: CPointer<CPointerVar<AudioQueueBuffer>>): Int
    fun enqueue(queue: AudioQueueRef, buffer: AudioQueueBufferRef): Int
    fun start(queue: AudioQueueRef): Int
    fun stop(queue: AudioQueueRef): Int
    fun dispose(queue: AudioQueueRef): Int

    /** Asks AudioQueue to call [listener] with [userData] on a system thread when its running state changes. */
    fun addRunningListener(queue: AudioQueueRef, listener: AudioQueuePropertyListenerProc, userData: COpaquePointer): Int
    fun removeRunningListener(queue: AudioQueueRef, listener: AudioQueuePropertyListenerProc, userData: COpaquePointer): Int

    /** The running state that the queue reports, or null when the queue does not answer. */
    fun isRunning(queue: AudioQueueRef): Boolean?
}

internal object NativeAudioQueueDriver : AppleAudioQueueDriver {
    override fun create(format: CPointer<AudioStreamBasicDescription>, output: CPointer<AudioQueueRefVar>, queue: dispatch_queue_t,
                        callback: (AudioQueueRef?, AudioQueueBufferRef?) -> Unit): Int =
        AudioQueueNewOutputWithDispatchQueue(output, format, 0u, queue, callback)
    override fun allocate(queue: AudioQueueRef, bytes: UInt, output: CPointer<CPointerVar<AudioQueueBuffer>>): Int =
        AudioQueueAllocateBuffer(queue, bytes, output)
    override fun enqueue(queue: AudioQueueRef, buffer: AudioQueueBufferRef): Int = AudioQueueEnqueueBuffer(queue, buffer, 0u, null)
    override fun start(queue: AudioQueueRef): Int = AudioQueueStart(queue, null)
    override fun stop(queue: AudioQueueRef): Int = AudioQueueStop(queue, true)
    override fun dispose(queue: AudioQueueRef): Int = AudioQueueDispose(queue, true)
    override fun addRunningListener(queue: AudioQueueRef, listener: AudioQueuePropertyListenerProc, userData: COpaquePointer): Int =
        AudioQueueAddPropertyListener(queue, kAudioQueueProperty_IsRunning, listener, userData)
    override fun removeRunningListener(queue: AudioQueueRef, listener: AudioQueuePropertyListenerProc, userData: COpaquePointer): Int =
        AudioQueueRemovePropertyListener(queue, kAudioQueueProperty_IsRunning, listener, userData)
    override fun isRunning(queue: AudioQueueRef): Boolean? = memScoped {
        val running = alloc<UIntVar>()
        val size = alloc<UIntVar>()
        size.value = sizeOf<UIntVar>().toUInt()
        if (AudioQueueGetProperty(queue, kAudioQueueProperty_IsRunning, running.ptr, size.ptr) != 0) null else running.value != 0u
    }
}
