package io.github.yuroyami.kiteaudio.output

import io.github.yuroyami.kiteaudio.core.AudioFormat
import io.github.yuroyami.kiteaudio.core.ChannelLayout
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.LineUnavailableException
import javax.sound.sampled.SourceDataLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import javax.sound.sampled.AudioFormat as JavaAudioFormat

/** Device lists, device choice and format policy on the JVM, over a fake Java Sound. No device is opened. */
class JvmAudioOutputDeviceTest {

    /** A fake mixer whose lines open only for the formats that [accepts] allows. */
    private class FakeMixer(val name: String, val accepts: (rate: Int, channels: Int) -> Boolean, val formats: List<JavaAudioFormat> = emptyList()) {
        /** Every format that a line of this mixer was asked to open, in order. */
        val tried = mutableListOf<Pair<Int, Int>>()

        fun line(): SourceDataLine = Proxy.newProxyInstance(SourceDataLine::class.java.classLoader, arrayOf(SourceDataLine::class.java)) { _, method, args ->
            when (method.name) {
                "open" -> {
                    val format = args!![0] as JavaAudioFormat
                    tried += format.sampleRate.toInt() to format.channels
                    if (!accepts(format.sampleRate.toInt(), format.channels)) throw LineUnavailableException("$name refuses $format")
                    null
                }
                "write" -> { Thread.sleep(1); args!![2] as Int }
                "getBufferSize" -> 4096
                "available" -> 0
                "isOpen", "isRunning", "isActive" -> true
                "getLongFramePosition", "getMicrosecondPosition" -> 0L
                "getFramePosition" -> 0
                "getLevel" -> 0f
                else -> null
            }
        } as SourceDataLine

        fun asJvmMixer(mixing: Boolean = true) = JvmMixer(name, formats, mixing) { line() }
    }

    private class FakeSound(vararg mixers: FakeMixer) : JvmSound {
        val fakes = mixers.toList()
        var defaultLines = 0
        var listings = 0
        override fun mixers(): List<JvmMixer> { listings++; return fakes.map { it.asJvmMixer() } }
        override fun defaultLine(info: DataLine.Info): SourceDataLine { defaultLines++; return fakes.first().line() }
    }

    private val stereo48 = AudioFormat(48_000, ChannelLayout.STEREO)

    private fun pcm16(rate: Float, channels: Int) = JavaAudioFormat(rate, 16, channels, true, false)

    @Test
    fun aRejectedRateFailsExactAndNearestOpensAndReportsTheOtherRate() {
        val mixer = FakeMixer("Speakers", { rate, _ -> rate == 44_100 })
        val events = mutableListOf<AudioOutputEvent>()
        val problem = assertFailsWith<LineUnavailableException> {
            openJvmOutput(stereo48, 4, null, AudioFormatPolicy.Exact, { events += it }, FakeSound(mixer))
        }
        assertTrue("48000" in problem.message.orEmpty(), "${problem.message}")
        assertIs<AudioOutputEvent.Failed>(events.single())
        assertEquals(listOf(48_000 to 2), mixer.tried)

        val started = CountDownLatch(1)
        val startedEvents = mutableListOf<AudioOutputEvent.Started>()
        val output = openJvmOutput(stereo48, 4, null, AudioFormatPolicy.Nearest, { event ->
            if (event is AudioOutputEvent.Started) { synchronized(startedEvents) { startedEvents += event }; started.countDown() }
        }, FakeSound(mixer))
        try {
            assertEquals(AudioFormat(44_100, ChannelLayout.STEREO), output.format)
            output.start { _, frames -> frames }
            assertTrue(started.await(3, TimeUnit.SECONDS))
            assertEquals(AudioFormat(44_100, ChannelLayout.STEREO), synchronized(startedEvents) { startedEvents.single().format })
        } finally { output.close() }
    }

    @Test
    fun nothingOpensUnderNearestAndTheOpenFailsAsExactDoes() {
        val mixer = FakeMixer("Speakers", { _, _ -> false })
        val events = mutableListOf<AudioOutputEvent>()
        assertFailsWith<LineUnavailableException> {
            openJvmOutput(stereo48, 4, null, AudioFormatPolicy.Nearest, { events += it }, FakeSound(mixer))
        }
        assertIs<AudioOutputEvent.Failed>(events.single())
        assertEquals(nearestFormatCandidates(stereo48, emptyList()).map { it.sampleRate to it.channels }, mixer.tried)
    }

    @Test
    fun javaSoundDoesNotSayTheHardwareFormat() {
        // The fake line runs at 96 kHz behind the scenes. Java Sound has no call that says so.
        val output = openJvmOutput(stereo48, 4, null, AudioFormatPolicy.Exact, {}, FakeSound(FakeMixer("Speakers", { _, _ -> true })))
        try {
            assertEquals(stereo48, output.format)
            assertNull(output.deviceFormat)
        } finally { output.close() }
    }

    @Test
    fun aMonoOnlyDeviceOpensMonoAfterEveryStereoRate() {
        val mixer = FakeMixer("Phone speaker", { _, channels -> channels == 1 })
        val output = openJvmOutput(stereo48, 4, null, AudioFormatPolicy.Nearest, {}, FakeSound(mixer))
        try {
            assertEquals(AudioFormat(48_000, ChannelLayout.MONO), output.format)
            val stereoTries = DEFAULT_CANDIDATE_RATES.size
            assertEquals(stereoTries + 1, mixer.tried.size)
            assertTrue(mixer.tried.take(stereoTries).all { it.second == 2 })
        } finally { output.close() }
    }

    @Test
    fun aStereoOnlyDeviceOpensStereoForAMonoRequest() {
        val mixer = FakeMixer("Headphones", { _, channels -> channels == 2 })
        val mono = AudioFormat(44_100, ChannelLayout.MONO)
        assertFailsWith<LineUnavailableException> { openJvmOutput(mono, 4, null, AudioFormatPolicy.Exact, {}, FakeSound(mixer)) }
        val output = openJvmOutput(mono, 4, null, AudioFormatPolicy.Nearest, {}, FakeSound(mixer))
        try { assertEquals(AudioFormat(44_100, ChannelLayout.STEREO), output.format) } finally { output.close() }
    }

    @Test
    fun aChosenDeviceOpensOnItsOwnMixer() {
        val first = FakeMixer("Speakers", { _, _ -> true })
        val second = FakeMixer("USB interface", { _, _ -> true })
        val sound = FakeSound(first, second)
        val usb = jvmOutputDevices(sound.mixers()).map { it.first }.single { it.name == "USB interface" }
        val output = openJvmOutput(stereo48, 4, usb, AudioFormatPolicy.Exact, {}, sound)
        try {
            assertEquals(listOf(48_000 to 2), second.tried)
            assertTrue(first.tried.isEmpty())
            assertEquals(0, sound.defaultLines)
        } finally { output.close() }
    }

    @Test
    fun anUnknownIdFailsTheOpenAndNeverFallsBackToTheDefault() {
        val first = FakeMixer("Speakers", { _, _ -> true })
        val sound = FakeSound(first)
        val gone = AudioOutputDevice("USB interface", "USB interface", false, emptyList(), emptyList())
        for (policy in AudioFormatPolicy.entries) {
            val events = mutableListOf<AudioOutputEvent>()
            val problem = assertFailsWith<LineUnavailableException> { openJvmOutput(stereo48, 4, gone, policy, { events += it }, sound) }
            assertTrue("USB interface" in problem.message.orEmpty(), "the reason does not name the device: ${problem.message}")
            assertIs<AudioOutputEvent.Failed>(events.single())
        }
        assertTrue(first.tried.isEmpty())
        assertEquals(0, sound.defaultLines)
    }

    @Test
    fun theListHasUniqueIdsOneDefaultAndOnlyTheNumbersJavaSoundGives() {
        val unspecified = AudioSystem.NOT_SPECIFIED.toFloat()
        val formats = listOf(
            pcm16(unspecified, 1),
            pcm16(unspecified, 2),
            pcm16(44_100f, 2),
            // Not the signed 16-bit PCM that the output writes, so its numbers do not count.
            JavaAudioFormat(96_000f, 24, 6, true, false),
        )
        val mixers = listOf(
            JvmMixer("Port", formats, mixing = false) { error("not opened") },
            JvmMixer("Speakers", formats, mixing = true) { error("not opened") },
            JvmMixer("Speakers", emptyList(), mixing = true) { error("not opened") },
            JvmMixer("Speakers #2", emptyList(), mixing = true) { error("not opened") },
        )
        val devices = jvmOutputDevices(mixers).map { it.first }
        assertEquals(listOf("Port", "Speakers", "Speakers #2", "Speakers #2 #2"), devices.map { it.id })
        assertEquals(devices.map { it.id }.distinct(), devices.map { it.id })
        assertEquals(listOf(false, true, false, false), devices.map { it.isDefault })
        assertEquals(listOf(44_100), devices[1].sampleRates)
        assertEquals(listOf(1, 2), devices[1].channelCounts)
        assertEquals(emptyList(), devices[2].sampleRates)
    }

    @Test
    fun aLayoutOfMoreChannelsFailsBeforeAnyDeviceAccessUnderBothPolicies() {
        val sound = FakeSound(FakeMixer("Speakers", { _, _ -> true }))
        for (policy in AudioFormatPolicy.entries) {
            assertFailsWith<IllegalArgumentException> { openJvmOutput(AudioFormat(48_000, 6), 4, null, policy, {}, sound) }
        }
        assertEquals(0, sound.listings)
        assertEquals(0, sound.defaultLines)
    }
}
