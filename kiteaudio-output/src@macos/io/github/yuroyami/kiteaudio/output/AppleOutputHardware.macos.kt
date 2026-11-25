@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.yuroyami.kiteaudio.output

import io.github.yuroyami.kiteaudio.core.AudioFormat
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.DoubleVar
import kotlinx.cinterop.MemScope
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.value
import platform.CoreAudio.AudioObjectGetPropertyData
import platform.CoreAudio.AudioObjectGetPropertyDataSize
import platform.CoreAudio.AudioObjectPropertyAddress
import platform.CoreAudio.kAudioDevicePropertyAvailableNominalSampleRates
import platform.CoreAudio.kAudioDevicePropertyDeviceUID
import platform.CoreAudio.kAudioDevicePropertyNominalSampleRate
import platform.CoreAudio.kAudioDevicePropertyStreamConfiguration
import platform.CoreAudio.kAudioHardwarePropertyDefaultOutputDevice
import platform.CoreAudio.kAudioHardwarePropertyDevices
import platform.CoreAudio.kAudioObjectPropertyElementMain
import platform.CoreAudio.kAudioObjectPropertyName
import platform.CoreAudio.kAudioObjectPropertyScopeGlobal
import platform.CoreAudio.kAudioObjectPropertyScopeOutput
import platform.CoreAudio.kAudioObjectSystemObject
import platform.CoreAudio.kAudioObjectUnknown
import platform.CoreAudioTypes.AudioBuffer
import platform.CoreAudioTypes.AudioBufferList
import platform.CoreAudioTypes.AudioValueRange
import platform.CoreFoundation.CFStringRefVar
import platform.Foundation.CFBridgingRelease

internal actual fun platformOutputHardware(): AppleOutputHardware = MacOutputHardware

/** The CoreAudio hardware API. Every call answers also while no stream runs. */
internal object MacOutputHardware : AppleOutputHardware {
    override val setsQueueDevice: Boolean get() = true

    override fun devices(): List<AudioOutputDevice> {
        val default = defaultOutputDevice()
        return deviceIds().mapNotNull { device ->
            val channels = outputChannels(device)
            if (channels <= 0) return@mapNotNull null
            val uid = stringProperty(device, kAudioDevicePropertyDeviceUID) ?: return@mapNotNull null
            AudioOutputDevice(
                id = uid,
                name = stringProperty(device, kAudioObjectPropertyName) ?: uid,
                isDefault = device == default,
                sampleRates = nominalRates(device),
                channelCounts = listOf(channels),
            )
        }
    }

    override fun deviceFormat(id: String?): AudioFormat? {
        val device = if (id == null) defaultOutputDevice() else deviceIds().firstOrNull { stringProperty(it, kAudioDevicePropertyDeviceUID) == id }
        if (device == null || device == kAudioObjectUnknown) return null
        val rate = nominalRate(device)
        val channels = outputChannels(device)
        return if (rate > 0 && channels > 0) AudioFormat(rate, channels) else null
    }
}

private val systemObject: UInt = kAudioObjectSystemObject.toUInt()

private fun MemScope.address(selector: UInt, scope: UInt = kAudioObjectPropertyScopeGlobal): CPointer<AudioObjectPropertyAddress> =
    alloc<AudioObjectPropertyAddress>().apply {
        mSelector = selector
        mScope = scope
        mElement = kAudioObjectPropertyElementMain
    }.ptr

private fun deviceIds(): List<UInt> = memScoped {
    val where = address(kAudioHardwarePropertyDevices)
    val size = alloc<UIntVar>()
    if (AudioObjectGetPropertyDataSize(systemObject, where, 0u, null, size.ptr) != 0) return emptyList()
    val count = (size.value / sizeOf<UIntVar>().toUInt()).toInt()
    if (count == 0) return emptyList()
    val ids = allocArray<UIntVar>(count)
    if (AudioObjectGetPropertyData(systemObject, where, 0u, null, size.ptr, ids) != 0) return emptyList()
    List((size.value / sizeOf<UIntVar>().toUInt()).toInt()) { ids[it] }
}

private fun defaultOutputDevice(): UInt? = memScoped {
    val device = alloc<UIntVar>()
    val size = alloc<UIntVar>().apply { value = sizeOf<UIntVar>().toUInt() }
    if (AudioObjectGetPropertyData(systemObject, address(kAudioHardwarePropertyDefaultOutputDevice), 0u, null, size.ptr, device.ptr) != 0) return null
    device.value.takeIf { it != kAudioObjectUnknown }
}

private fun stringProperty(device: UInt, selector: UInt): String? = memScoped {
    val string = alloc<CFStringRefVar>()
    val size = alloc<UIntVar>().apply { value = sizeOf<CFStringRefVar>().toUInt() }
    if (AudioObjectGetPropertyData(device, address(selector), 0u, null, size.ptr, string.ptr) != 0) return null
    // The caller owns the returned string, and the release hands it to Kotlin.
    CFBridgingRelease(string.value) as? String
}

/** The output channels of all output streams of [device]. Zero for a device that has no output. */
private fun outputChannels(device: UInt): Int = memScoped {
    val where = address(kAudioDevicePropertyStreamConfiguration, kAudioObjectPropertyScopeOutput)
    val size = alloc<UIntVar>()
    if (AudioObjectGetPropertyDataSize(device, where, 0u, null, size.ptr) != 0 || size.value == 0u) return 0
    val storage = allocArray<UIntVar>(((size.value + 3u) / 4u).toInt())
    if (AudioObjectGetPropertyData(device, where, 0u, null, size.ptr, storage) != 0) return 0
    val list = storage.reinterpret<AudioBufferList>().pointed
    val buffers = list.mBuffers.reinterpret<AudioBuffer>()
    (0 until list.mNumberBuffers.toInt()).sumOf { buffers[it].mNumberChannels.toInt() }
}

private fun nominalRate(device: UInt): Int = memScoped {
    val rate = alloc<DoubleVar>()
    val size = alloc<UIntVar>().apply { value = sizeOf<DoubleVar>().toUInt() }
    if (AudioObjectGetPropertyData(device, address(kAudioDevicePropertyNominalSampleRate), 0u, null, size.ptr, rate.ptr) != 0) return 0
    rate.value.toInt()
}

private fun nominalRates(device: UInt): List<Int> = memScoped {
    val where = address(kAudioDevicePropertyAvailableNominalSampleRates)
    val size = alloc<UIntVar>()
    if (AudioObjectGetPropertyDataSize(device, where, 0u, null, size.ptr) != 0) return emptyList()
    val count = (size.value / sizeOf<AudioValueRange>().toUInt()).toInt()
    if (count == 0) return emptyList()
    val ranges = allocArray<AudioValueRange>(count)
    if (AudioObjectGetPropertyData(device, where, 0u, null, size.ptr, ranges) != 0) return emptyList()
    (0 until (size.value / sizeOf<AudioValueRange>().toUInt()).toInt())
        .flatMap { ratesInRange(ranges[it].mMinimum, ranges[it].mMaximum) }
        .distinct()
        .sorted()
}
