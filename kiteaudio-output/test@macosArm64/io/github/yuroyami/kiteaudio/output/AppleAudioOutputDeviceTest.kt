@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package io.github.yuroyami.kiteaudio.output

import io.github.yuroyami.kiteaudio.core.AudioFormat
import io.github.yuroyami.kiteaudio.core.ChannelLayout
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.free
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toLong
import kotlinx.cinterop.value
import kotlin.concurrent.atomics.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import platform.AudioToolbox.AudioQueueBuffer
import platform.AudioToolbox.AudioQueueBufferRef
import platform.AudioToolbox.AudioQueuePropertyListenerProc
import platform.AudioToolbox.AudioQueueRef
import platform.AudioToolbox.AudioQueueRefVar
import platform.CoreAudioTypes.AudioStreamBasicDescription
import platform.CoreAudioTypes.AudioTimeStamp
import platform.darwin.dispatch_queue_t
import platform.posix.usleep

/** Device lists, device choice and format policy on Apple platforms, over a fake AudioQueue and fake hardware. */
class AppleAudioOutputDeviceTest {

    /** A queue that opens only for the formats [accepts] allows. It never calls back after the priming. */
    private class Driver(val accepts: (rate: Int, channels: Int) -> Boolean = { _, _ -> true }) : AppleAudioQueueDriver {
        /** Every format a queue was asked to open with, in order. */
        val created = mutableListOf<Pair<Int, Int>>()
        val devicesSet = mutableListOf<String>()
        var deviceStatus = 0
        var starts = 0
        private val buffers = mutableMapOf<Long, MutableList<AudioQueueBufferRef>>()
        private var running = false

        override fun create(format: CPointer<AudioStreamBasicDescription>, output: CPointer<AudioQueueRefVar>, queue: dispatch_queue_t,
                            callback: (AudioQueueRef?, AudioQueueBufferRef?) -> Unit): Int {
            val rate = format.pointed.mSampleRate.toInt()
            val channels = format.pointed.mChannelsPerFrame.toInt()
            created += rate to channels
            if (!accepts(rate, channels)) return KAUDIO_FORMAT_UNSUPPORTED
            val storage = nativeHeap.alloc<ByteVar>()
            val ref: AudioQueueRef = storage.ptr.reinterpret()
            buffers[ref.toLong()] = mutableListOf()
            output.pointed.value = ref
            return 0
        }
        override fun allocate(queue: AudioQueueRef, bytes: UInt, output: CPointer<CPointerVar<AudioQueueBuffer>>): Int {
            val buffer = nativeHeap.alloc<AudioQueueBuffer>()
            buffer.mAudioData = nativeHeap.allocArray<ByteVar>(bytes.toInt())
            buffer.mAudioDataBytesCapacity = bytes
            output.pointed.value = buffer.ptr
            buffers.getValue(queue.toLong()) += buffer.ptr
            return 0
        }
        override fun enqueue(queue: AudioQueueRef, buffer: AudioQueueBufferRef): Int = 0
        override fun start(queue: AudioQueueRef): Int { starts++; running = true; return 0 }
        override fun stop(queue: AudioQueueRef): Int { running = false; return 0 }
        override fun dispose(queue: AudioQueueRef): Int {
            buffers.remove(queue.toLong())?.forEach { nativeHeap.free(it.pointed.mAudioData!!); nativeHeap.free(it) }
            nativeHeap.free(queue)
            return 0
        }
        override fun addRunningListener(queue: AudioQueueRef, listener: AudioQueuePropertyListenerProc, userData: COpaquePointer): Int = 0
        override fun removeRunningListener(queue: AudioQueueRef, listener: AudioQueuePropertyListenerProc, userData: COpaquePointer): Int = 0
        override fun isRunning(queue: AudioQueueRef): Boolean? = running
        // An invalid time, so the output never counts a device underrun.
        override fun currentTime(queue: AudioQueueRef, stamp: CPointer<AudioTimeStamp>): Int = -1
        override fun setCurrentDevice(queue: AudioQueueRef, uid: String): Int { devicesSet += uid; return deviceStatus }

        /** True while a queue is open. */
        val openQueues: Int get() = buffers.size
    }

    private class Hardware(
        val list: List<AudioOutputDevice>,
        val hardware: AudioFormat? = null,
        override val setsQueueDevice: Boolean = true,
    ) : AppleOutputHardware {
        var listings = 0
        val formatReads = mutableListOf<String?>()
        override fun devices(): List<AudioOutputDevice> { listings++; return list }
        override fun deviceFormat(id: String?): AudioFormat? { formatReads += id; return hardware }
    }

    private class Events {
        private val list = AtomicReference<List<AudioOutputEvent>>(emptyList())
        val all: List<AudioOutputEvent> get() = list.load()
        fun add(event: AudioOutputEvent) {
            while (true) {
                val current = list.load()
                if (list.compareAndSet(current, current + event)) return
            }
        }
    }

    private companion object {
        // kAudioFormatUnsupportedDataFormatError, '?fmt'.
        const val KAUDIO_FORMAT_UNSUPPORTED = 1718449215
    }

    private val stereo48 = AudioFormat(48_000, ChannelLayout.STEREO)
    private val speakers = AudioOutputDevice("speakers-uid", "Speakers", true, listOf(44_100, 48_000), listOf(2))
    private val usb = AudioOutputDevice("usb-uid", "USB interface", false, listOf(96_000), listOf(2))

    private fun await(what: String, condition: () -> Boolean) {
        repeat(3000) { if (condition()) return; usleep(1_000u) }
        assertTrue(condition(), what)
    }

    private fun startAndAwait(output: AudioOutput, events: Events): AudioOutputEvent {
        output.start { out, frames -> out.fill(0f); frames }
        await("no Started or Failed: ${events.all}") { events.all.any { it is AudioOutputEvent.Started || it is AudioOutputEvent.Failed } }
        return events.all.first { it is AudioOutputEvent.Started || it is AudioOutputEvent.Failed }
    }

    @Test
    fun aRejectedRateFailsExactAndNearestOpensAndReportsTheOtherRate() {
        val driver = Driver { rate, _ -> rate == 44_100 }
        val hardware = Hardware(listOf(speakers))
        val exactEvents = Events()
        val exact = openAppleOutput(stereo48, 64, null, AudioFormatPolicy.Exact, exactEvents::add, driver, hardware)
        try {
            // AudioQueue checks the format when the queue opens, at start, as before.
            assertEquals(stereo48, exact.format)
            val failed = assertIs<AudioOutputEvent.Failed>(startAndAwait(exact, exactEvents))
            assertTrue("would not open" in failed.reason, failed.reason)
            assertEquals(0, driver.starts)
        } finally { exact.close() }

        val events = Events()
        driver.created.clear()
        val output = openAppleOutput(stereo48, 64, null, AudioFormatPolicy.Nearest, events::add, driver, hardware)
        try {
            assertEquals(AudioFormat(44_100, ChannelLayout.STEREO), output.format)
            assertEquals(listOf(48_000 to 2, 44_100 to 2), driver.created)
            val started = assertIs<AudioOutputEvent.Started>(startAndAwait(output, events))
            assertEquals(AudioFormat(44_100, ChannelLayout.STEREO), started.format)
        } finally { output.close() }
        await("a queue was not disposed") { driver.openQueues == 0 }
    }

    @Test
    fun nothingOpensUnderNearestAndTheStartFailsAsExactDoes() {
        val driver = Driver { _, _ -> false }
        val events = Events()
        val output = openAppleOutput(stereo48, 64, null, AudioFormatPolicy.Nearest, events::add, driver, Hardware(emptyList()))
        try {
            assertEquals(stereo48, output.format)
            assertEquals(DEFAULT_CANDIDATE_RATES.size * 2, driver.created.size)
            assertIs<AudioOutputEvent.Failed>(startAndAwait(output, events))
        } finally { output.close() }
    }

    @Test
    fun aDeviceAtAnotherHardwareRateReportsBothFormats() {
        val hardware = Hardware(listOf(speakers, usb), hardware = AudioFormat(96_000, ChannelLayout.STEREO))
        val output = openAppleOutput(stereo48, 64, null, AudioFormatPolicy.Exact, {}, Driver(), hardware)
        try {
            assertEquals(stereo48, output.format)
            assertEquals(AudioFormat(96_000, ChannelLayout.STEREO), output.deviceFormat)
        } finally { output.close() }
        val chosen = openAppleOutput(stereo48, 64, usb, AudioFormatPolicy.Exact, {}, Driver(), hardware)
        try { chosen.deviceFormat } finally { chosen.close() }
        assertEquals(listOf(null, "usb-uid"), hardware.formatReads)
        val silent = openAppleOutput(stereo48, 64, null, AudioFormatPolicy.Exact, {}, Driver(), Hardware(emptyList()))
        try { assertNull(silent.deviceFormat) } finally { silent.close() }
    }

    @Test
    fun aMonoOnlyDeviceOpensMonoAndAStereoOnlyDeviceOpensStereo() {
        val monoOnly = Driver { _, channels -> channels == 1 }
        val mono = openAppleOutput(stereo48, 64, null, AudioFormatPolicy.Nearest, {}, monoOnly, Hardware(listOf(speakers)))
        try { assertEquals(AudioFormat(48_000, ChannelLayout.MONO), mono.format) } finally { mono.close() }
        // The default device reports 44.1 and 48 kHz, so two stereo tries come first.
        assertEquals(listOf(48_000 to 2, 44_100 to 2, 48_000 to 1), monoOnly.created)

        val stereoOnly = Driver { _, channels -> channels == 2 }
        val stereo = openAppleOutput(AudioFormat(44_100, ChannelLayout.MONO), 64, null, AudioFormatPolicy.Nearest, {}, stereoOnly, Hardware(listOf(speakers)))
        try { assertEquals(AudioFormat(44_100, ChannelLayout.STEREO), stereo.format) } finally { stereo.close() }
    }

    @Test
    fun aChosenDeviceIsSetOnTheQueueOnMacOs() {
        val driver = Driver()
        val events = Events()
        val output = openAppleOutput(stereo48, 64, usb, AudioFormatPolicy.Exact, events::add, driver, Hardware(listOf(speakers, usb)))
        try {
            assertIs<AudioOutputEvent.Started>(startAndAwait(output, events))
            assertEquals(listOf("usb-uid"), driver.devicesSet)
        } finally { output.close() }
    }

    @Test
    fun aChosenDeviceThatTheQueueRefusesFailsTheStartWithoutAFallback() {
        val driver = Driver().apply { deviceStatus = -50 }
        val events = Events()
        val output = openAppleOutput(stereo48, 64, usb, AudioFormatPolicy.Exact, events::add, driver, Hardware(listOf(speakers, usb)))
        try {
            val failed = assertIs<AudioOutputEvent.Failed>(startAndAwait(output, events))
            assertTrue("usb-uid" in failed.reason, failed.reason)
            assertEquals(0, driver.starts)
        } finally { output.close() }
    }

    @Test
    fun onIosTheChosenRouteIsCheckedButNotSetOnTheQueue() {
        val route = AudioOutputDevice("speaker-uid", "Speaker", true, emptyList(), emptyList())
        val driver = Driver()
        val events = Events()
        val output = openAppleOutput(stereo48, 64, route, AudioFormatPolicy.Exact, events::add, driver, Hardware(listOf(route), setsQueueDevice = false))
        try {
            assertIs<AudioOutputEvent.Started>(startAndAwait(output, events))
            assertTrue(driver.devicesSet.isEmpty())
        } finally { output.close() }
    }

    @Test
    fun anUnknownIdFailsTheOpenAndNeverFallsBackToTheDefault() {
        val driver = Driver()
        for (policy in AudioFormatPolicy.entries) {
            val events = mutableListOf<AudioOutputEvent>()
            val problem = assertFailsWith<UnsupportedOperationException> {
                openAppleOutput(stereo48, 64, usb, policy, { events += it }, driver, Hardware(listOf(speakers)))
            }
            assertTrue("usb-uid" in problem.message.orEmpty(), "${problem.message}")
            assertIs<AudioOutputEvent.Failed>(events.single())
        }
        assertTrue(driver.created.isEmpty())
    }

    @Test
    fun aLayoutOfMoreChannelsFailsBeforeAnyDeviceAccessUnderBothPolicies() {
        val driver = Driver()
        val hardware = Hardware(listOf(speakers))
        for (policy in AudioFormatPolicy.entries) {
            assertFailsWith<IllegalArgumentException> { openAppleOutput(AudioFormat(48_000, 6), 64, speakers, policy, {}, driver, hardware) }
        }
        assertEquals(0, hardware.listings)
        assertTrue(driver.created.isEmpty())
    }

    @Test
    fun aRangeOfNominalRatesGivesTheStandardRatesInsideIt() {
        assertEquals(listOf(44_100), ratesInRange(44_100.0, 44_100.0))
        assertEquals(listOf(48_000, 44_100, 32_000, 22_050, 16_000), ratesInRange(16_000.0, 48_000.0))
        assertEquals(emptyList(), ratesInRange(0.0, 0.0))
    }
}
