package io.github.yuroyami.kiteaudio.output

import android.media.AudioDeviceInfo
import io.github.yuroyami.kiteaudio.core.AudioFormat
import io.github.yuroyami.kiteaudio.core.ChannelLayout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Device lists, device choice and format policy on Android, on the host JVM with a fake track factory. */
class AndroidAudioOutputDeviceTest {

    private class FakeTrack(override val deviceFormat: AudioFormat? = null) : AndroidPcmTrack {
        override val bufferSizeInFrames: Int = 1024
        override fun play() {}
        override fun pause() {}
        override fun flush() {}
        override fun release() {}
        override fun write(data: ShortArray, offset: Int, count: Int): Int { Thread.sleep(1); return count }
        override val underrunCount: Int get() = 0
        override fun raiseWorkerPriority() {}
    }

    /** Builds tracks only for the formats that [accepts] allows, and records every request. */
    private class FakeFactory(
        val hardware: AudioFormat? = null,
        val acceptsRoute: (Any?) -> Boolean = { true },
        val accepts: (rate: Int, channels: Int) -> Boolean,
    ) : AndroidTrackFactory {
        val tried = mutableListOf<Pair<Int, Int>>()
        val routes = mutableListOf<Any?>()
        override fun create(format: AudioFormat, bufferFrames: Int, route: Any?): AndroidPcmTrack {
            tried += format.sampleRate to format.channels
            routes += route
            if (!acceptsRoute(route)) throw AndroidOpenFailure("the route is gone", "the route is gone", tryOthers = false)
            if (!accepts(format.sampleRate, format.channels)) throw AndroidOpenFailure("AudioTrack would not initialise", "AudioTrack would not initialise")
            return FakeTrack(hardware)
        }
    }

    private val stereo48 = AudioFormat(48_000, ChannelLayout.STEREO)

    @AfterTest
    fun forgetTheDevices() {
        rememberAndroidDevices(emptyList())
    }

    @Test
    fun aRejectedRateFailsExactAndNearestOpensAndReportsTheOtherRate() {
        val factory = FakeFactory { rate, _ -> rate == 44_100 }
        val events = mutableListOf<AudioOutputEvent>()
        assertFailsWith<UnsupportedOperationException> { openAndroidOutput(stereo48, 4, null, AudioFormatPolicy.Exact, { events += it }, factory) }
        assertEquals("AudioTrack would not initialise", assertIs<AudioOutputEvent.Failed>(events.single()).reason)
        assertEquals(listOf(48_000 to 2), factory.tried)

        val started = AtomicReference<AudioOutputEvent.Started?>()
        val latch = CountDownLatch(1)
        val output = openAndroidOutput(stereo48, 4, null, AudioFormatPolicy.Nearest, { event ->
            if (event is AudioOutputEvent.Started) { started.set(event); latch.countDown() }
        }, factory)
        try {
            assertEquals(AudioFormat(44_100, ChannelLayout.STEREO), output.format)
            output.start { _, frames -> frames }
            assertTrue(latch.await(3, TimeUnit.SECONDS))
            assertEquals(AudioFormat(44_100, ChannelLayout.STEREO), started.get()!!.format)
        } finally { output.close() }
    }

    @Test
    fun nothingOpensUnderNearestAndTheOpenFailsAsExactDoes() {
        val factory = FakeFactory { _, _ -> false }
        val events = mutableListOf<AudioOutputEvent>()
        assertFailsWith<UnsupportedOperationException> { openAndroidOutput(stereo48, 4, null, AudioFormatPolicy.Nearest, { events += it }, factory) }
        assertEquals("AudioTrack would not initialise", assertIs<AudioOutputEvent.Failed>(events.single()).reason)
        assertEquals(DEFAULT_CANDIDATE_RATES.size * 2, factory.tried.size)
    }

    @Test
    fun aDeviceAtAnotherHardwareRateReportsBothFormats() {
        val hardware = AudioFormat(96_000, ChannelLayout.STEREO)
        val output = openAndroidOutput(stereo48, 4, null, AudioFormatPolicy.Exact, {}, FakeFactory(hardware) { _, _ -> true })
        try {
            assertEquals(stereo48, output.format)
            assertEquals(hardware, output.deviceFormat)
        } finally { output.close() }
        val unknown = openAndroidOutput(stereo48, 4, null, AudioFormatPolicy.Exact, {}, FakeFactory { _, _ -> true })
        try { assertNull(unknown.deviceFormat) } finally { unknown.close() }
    }

    @Test
    fun aMonoOnlyDeviceOpensMonoAndAStereoOnlyDeviceOpensStereo() {
        val monoOnly = FakeFactory { _, channels -> channels == 1 }
        val mono = openAndroidOutput(stereo48, 4, null, AudioFormatPolicy.Nearest, {}, monoOnly)
        try { assertEquals(AudioFormat(48_000, ChannelLayout.MONO), mono.format) } finally { mono.close() }
        assertTrue(monoOnly.tried.take(DEFAULT_CANDIDATE_RATES.size).all { it.second == 2 })

        val stereoOnly = FakeFactory { _, channels -> channels == 2 }
        val stereo = openAndroidOutput(AudioFormat(22_050, ChannelLayout.MONO), 4, null, AudioFormatPolicy.Nearest, {}, stereoOnly)
        try { assertEquals(AudioFormat(22_050, ChannelLayout.STEREO), stereo.format) } finally { stereo.close() }
    }

    @Test
    fun aChosenDeviceOpensOnItsPlatformObjectAndItsRatesLeadNearest() {
        val token = Any()
        val usb = androidOutputDevice(7, AudioDeviceInfo.TYPE_USB_DEVICE, "Scarlett", intArrayOf(96_000, 44_100), intArrayOf(2))
        val devices = rememberAndroidDevices(listOf(usb to token))
        val factory = FakeFactory { rate, _ -> rate == 96_000 }
        val output = openAndroidOutput(stereo48, 4, devices.single { it.id == "7" }, AudioFormatPolicy.Nearest, {}, factory)
        try {
            assertEquals(AudioFormat(96_000, ChannelLayout.STEREO), output.format)
            // 48 kHz first, then the device's own rates by distance: 44.1 kHz, then 96 kHz.
            assertEquals(listOf(48_000 to 2, 44_100 to 2, 96_000 to 2), factory.tried)
            factory.routes.forEach { assertSame(token, it) }
        } finally { output.close() }
    }

    @Test
    fun theSystemRouteOpensWithNoPreferredDevice() {
        val factory = FakeFactory { _, _ -> true }
        val output = openAndroidOutput(stereo48, 4, runSuspending { audioOutputDevices() }.single(), AudioFormatPolicy.Exact, {}, factory)
        try { assertEquals(listOf<Any?>(null), factory.routes) } finally { output.close() }
    }

    @Test
    fun anUnknownIdFailsTheOpenAndNeverFallsBackToTheSystemRoute() {
        rememberAndroidDevices(listOf(androidOutputDevice(3, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, "", intArrayOf(), intArrayOf()) to Any()))
        val gone = AudioOutputDevice("9", "Bluetooth (Buds)", false, emptyList(), emptyList())
        val factory = FakeFactory { _, _ -> true }
        for (policy in AudioFormatPolicy.entries) {
            val events = mutableListOf<AudioOutputEvent>()
            val problem = assertFailsWith<UnsupportedOperationException> { openAndroidOutput(stereo48, 4, gone, policy, { events += it }, factory) }
            assertTrue("\"9\"" in problem.message.orEmpty() && "audioOutputDevices(context)" in problem.message.orEmpty(), "${problem.message}")
            assertEquals(problem.message, assertIs<AudioOutputEvent.Failed>(events.single()).reason)
        }
        assertTrue(factory.tried.isEmpty())
    }

    @Test
    fun aRouteThatAndroidRefusesStopsNearestAtOnce() {
        val token = Any()
        val device = rememberAndroidDevices(listOf(androidOutputDevice(4, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "Buds", intArrayOf(), intArrayOf()) to token))[1]
        val factory = FakeFactory(acceptsRoute = { it !== token }) { _, _ -> true }
        val events = mutableListOf<AudioOutputEvent>()
        assertFailsWith<UnsupportedOperationException> { openAndroidOutput(stereo48, 4, device, AudioFormatPolicy.Nearest, { events += it }, factory) }
        assertEquals(1, factory.tried.size)
        assertEquals("the route is gone", assertIs<AudioOutputEvent.Failed>(events.single()).reason)
    }

    @Test
    fun theListsHoldTheSystemRouteFirstAndAsTheOnlyDefault() {
        assertEquals(listOf(AudioOutputDevice(ANDROID_SYSTEM_ROUTE_ID, "System route", true, emptyList(), emptyList())), runSuspending { audioOutputDevices() })
        val full = rememberAndroidDevices(
            listOf(
                androidOutputDevice(2, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, "Pixel 9", intArrayOf(48_000, 44_100, 48_000), intArrayOf(2, 1)) to Any(),
                androidOutputDevice(5, AudioDeviceInfo.TYPE_USB_HEADSET, "USB", intArrayOf(), intArrayOf()) to Any(),
            ),
        )
        assertEquals(listOf(ANDROID_SYSTEM_ROUTE_ID, "2", "5"), full.map { it.id })
        assertEquals(listOf(true, false, false), full.map { it.isDefault })
        assertEquals("Built-in speaker (Pixel 9)", full[1].name)
        assertEquals("USB", full[2].name)
        assertEquals(listOf(44_100, 48_000), full[1].sampleRates)
        assertEquals(listOf(1, 2), full[1].channelCounts)
        assertEquals(emptyList(), full[2].sampleRates)
    }

    @Test
    fun aLayoutOfMoreChannelsFailsBeforeTheFactoryUnderBothPolicies() {
        val factory = FakeFactory { _, _ -> true }
        for (policy in AudioFormatPolicy.entries) {
            assertFailsWith<IllegalArgumentException> { openAndroidOutput(AudioFormat(48_000, 6), 4, null, policy, {}, factory) }
        }
        assertTrue(factory.tried.isEmpty())
    }
}
