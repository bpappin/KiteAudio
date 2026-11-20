@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.yuroyami.kitert

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import platform.AudioToolbox.AudioQueueAllocateBuffer
import platform.AudioToolbox.AudioQueueBuffer
import platform.AudioToolbox.AudioQueueBufferRef
import platform.AudioToolbox.AudioQueueDispose
import platform.AudioToolbox.AudioQueueEnqueueBuffer
import platform.AudioToolbox.AudioQueueNewOutputWithDispatchQueue
import platform.AudioToolbox.AudioQueueRef
import platform.AudioToolbox.AudioQueueRefVar
import platform.AudioToolbox.AudioQueueStart
import platform.AudioToolbox.AudioQueueStop
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
}
