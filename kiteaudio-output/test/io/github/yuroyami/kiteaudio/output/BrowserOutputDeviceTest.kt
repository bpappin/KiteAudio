package io.github.yuroyami.kiteaudio.output

import io.github.yuroyami.kiteaudio.core.AudioFormat
import io.github.yuroyami.kiteaudio.core.ChannelLayout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Device lists, device choice and format policy of the browser output, over a stand-in for Web
 * Audio that plays by scheduled buffers and lets the page play at once.
 */
class BrowserOutputDeviceTest {

    private class Handle(val kind: String, val samples: FloatArray = FloatArray(0))

    private class FakeWebAudio(
        val sinks: Boolean = true,
        val rates: (Int) -> Boolean = { true },
        val outputs: List<Pair<String, String>> = emptyList(),
    ) : WebAudioApi<Handle> {
        /** Every context request: its rate and its sink id, null for none. */
        val contexts = mutableListOf<Pair<Int, String?>>()
        var error: ((String) -> Unit)? = null
        var listFailure: String? = null

        private fun context(rate: Int, sink: String?): Handle {
            contexts += rate to sink
            if (!rates(rate)) throw IllegalStateException("NotSupportedError: $rate Hz")
            return Handle("context")
        }
        override fun hasAudioContext() = true
        override fun hasWorklet() = false
        override fun newContext(sampleRate: Int) = context(sampleRate, null)
        override fun canChooseSink() = sinks
        override fun newContextOn(sampleRate: Int, sinkId: String) = context(sampleRate, sinkId)
        override fun onError(context: Handle, failed: (String) -> Unit) { error = failed }
        override fun listOutputs(done: (List<Pair<String, String>>) -> Unit, failed: (String) -> Unit) {
            listFailure?.let { failed(it); return }
            done(outputs)
        }
        override fun baseLatencySeconds(context: Handle) = 0.0
        override fun currentTime(context: Handle) = 0.0
        override fun loadProcessor(context: Handle, source: String, done: () -> Unit, failed: (String) -> Unit) = error("no worklet")
        override fun onStateChange(context: Handle, changed: (String) -> Unit) {}
        override fun resume(context: Handle, done: () -> Unit, failed: (String) -> Unit) = done()
        override fun suspend(context: Handle) {}
        override fun close(context: Handle) {}
        override fun newNode(context: Handle, name: String, channels: Int) = error("no worklet")
        override fun listen(node: Handle, returned: (Int, Int, Handle) -> Unit, failed: (String) -> Unit) = error("no worklet")
        override fun release(node: Handle) {}
        override fun newGain(context: Handle) = Handle("gain")
        override fun disconnect(node: Handle) {}
        override fun playBuffer(context: Handle, destination: Handle, channels: Int, frames: Int, block: Handle, at: Double) {}
        override fun startTimer(intervalMs: Int, tick: () -> Unit) = Handle("timer")
        override fun stopTimer(timer: Handle) {}
        override fun newBlock(samples: Int) = Handle("block", FloatArray(samples))
        override fun fill(block: Handle, samples: FloatArray) {}
        override fun post(node: Handle, slot: Int, block: Handle) {}
    }

    private val stereo48 = AudioFormat(48_000, ChannelLayout.STEREO)
    private val outputs = listOf("default" to "Default - Speakers", "abc" to "Speakers", "xyz" to "")
    private val silent = AudioRenderCallback { out, frames -> out.fill(0f); frames }

    private fun open(
        web: FakeWebAudio,
        format: AudioFormat = stereo48,
        device: AudioOutputDevice? = null,
        policy: AudioFormatPolicy = AudioFormatPolicy.Exact,
        devices: BrowserOutputDevices<Handle>? = null,
        events: MutableList<AudioOutputEvent> = mutableListOf(),
    ) = openBrowserOutput(web, format, 256, BrowserTechnique.Buffers, device, policy, devices) { events += it }

    @Test
    fun aRejectedRateFailsExactAndNearestOpensAndReportsTheOtherRate() {
        val web = FakeWebAudio(rates = { it == 44_100 })
        val events = mutableListOf<AudioOutputEvent>()
        assertFailsWith<IllegalArgumentException> { open(web, events = events) }
        assertIs<AudioOutputEvent.Failed>(events.single())
        assertEquals(listOf<Pair<Int, String?>>(48_000 to null), web.contexts)

        events.clear()
        web.contexts.clear()
        val output = open(web, policy = AudioFormatPolicy.Nearest, events = events)
        assertEquals(AudioFormat(44_100, ChannelLayout.STEREO), output.format)
        assertEquals(listOf<Pair<Int, String?>>(48_000 to null, 44_100 to null), web.contexts)
        output.start(silent)
        assertEquals(AudioFormat(44_100, ChannelLayout.STEREO), assertIs<AudioOutputEvent.Started>(events.first()).format)
        output.close()
    }

    @Test
    fun nothingOpensUnderNearestAndTheOpenFailsAsExactDoesWithOneTryForEachRate() {
        val web = FakeWebAudio(rates = { false })
        val events = mutableListOf<AudioOutputEvent>()
        val problem = assertFailsWith<IllegalArgumentException> { open(web, policy = AudioFormatPolicy.Nearest, events = events) }
        assertTrue("48000" in problem.message.orEmpty(), "${problem.message}")
        assertIs<AudioOutputEvent.Failed>(events.single())
        assertEquals(nearestFormatCandidates(stereo48, emptyList()).map { it.sampleRate }.distinct(), web.contexts.map { it.first })
    }

    @Test
    fun aMonoRequestStaysMonoAndTheBrowserDoesNotSayTheHardwareFormat() {
        val output = open(FakeWebAudio(), format = AudioFormat(22_050, ChannelLayout.MONO), policy = AudioFormatPolicy.Nearest)
        assertEquals(AudioFormat(22_050, ChannelLayout.MONO), output.format)
        assertNull(output.deviceFormat)
        output.close()
    }

    @Test
    fun theListHoldsTheOutputsWithTheFirstAsTheDefault() {
        val devices = runSuspending { BrowserOutputDevices(FakeWebAudio(outputs = outputs + ("abc" to "Speakers"))).list() }
        assertEquals(listOf("default", "abc", "xyz"), devices.map { it.id })
        assertEquals(listOf(true, false, false), devices.map { it.isDefault })
        assertEquals("", devices[2].name)
        assertTrue(devices.all { it.sampleRates.isEmpty() && it.channelCounts.isEmpty() })
        assertEquals(emptyList(), runSuspending { BrowserOutputDevices(FakeWebAudio()).list() })
        val refusing = FakeWebAudio().apply { listFailure = "NotAllowedError" }
        assertFailsWith<IllegalStateException> { runSuspending { BrowserOutputDevices(refusing).list() } }
    }

    @Test
    fun aChosenDeviceOpensItsContextWithItsSinkId() {
        val web = FakeWebAudio(outputs = outputs)
        val devices = BrowserOutputDevices(web)
        val speakers = runSuspending { devices.list() }.single { it.id == "abc" }
        val output = open(web, device = speakers, devices = devices)
        assertEquals(listOf<Pair<Int, String?>>(48_000 to "abc"), web.contexts)
        output.close()
    }

    @Test
    fun theDefaultEntryOpensWithNoSinkIdAlsoWhereTheBrowserCannotChoose() {
        val web = FakeWebAudio(sinks = false, outputs = outputs)
        val devices = BrowserOutputDevices(web)
        val default = runSuspending { devices.list() }.single { it.isDefault }
        val output = open(web, device = default, devices = devices)
        assertEquals(listOf<Pair<Int, String?>>(48_000 to null), web.contexts)
        assertNull(web.error, "a context with no sinkId needs no error watch")
        output.close()
    }

    @Test
    fun anotherEntryFailsWhereTheBrowserCannotChooseAnOutput() {
        val web = FakeWebAudio(sinks = false, outputs = outputs)
        val devices = BrowserOutputDevices(web)
        val speakers = runSuspending { devices.list() }.single { it.id == "abc" }
        val events = mutableListOf<AudioOutputEvent>()
        val problem = assertFailsWith<UnsupportedOperationException> { open(web, device = speakers, devices = devices, events = events) }
        assertTrue("sinkId" in problem.message.orEmpty(), "${problem.message}")
        assertIs<AudioOutputEvent.Failed>(events.single())
        assertTrue(web.contexts.isEmpty())
    }

    @Test
    fun anUnknownIdFailsTheOpenAndNeverFallsBackToTheDefault() {
        val web = FakeWebAudio(outputs = outputs)
        val devices = BrowserOutputDevices(web)
        runSuspending { devices.list() }
        val gone = AudioOutputDevice("usb", "USB interface", false, emptyList(), emptyList())
        for (policy in AudioFormatPolicy.entries) {
            for (known in listOf(devices, null)) {
                val events = mutableListOf<AudioOutputEvent>()
                val problem = assertFailsWith<IllegalArgumentException> { open(web, device = gone, policy = policy, devices = known, events = events) }
                assertTrue("\"usb\"" in problem.message.orEmpty(), "${problem.message}")
                assertIs<AudioOutputEvent.Failed>(events.single())
            }
        }
        assertTrue(web.contexts.isEmpty())
    }

    @Test
    fun anOutputThatTheContextReportsAsFailedEndsTheStreamWithDeviceLostAndRefusesTheNextStart() {
        val web = FakeWebAudio(outputs = outputs)
        val devices = BrowserOutputDevices(web)
        val speakers = runSuspending { devices.list() }.single { it.id == "abc" }
        val events = mutableListOf<AudioOutputEvent>()
        val output = open(web, device = speakers, devices = devices, events = events)
        output.start(silent)
        assertIs<AudioOutputEvent.Started>(events.first())
        web.error!!("the audio context reported an error")
        assertTrue(events.any { it is AudioOutputEvent.DeviceLost && "device failed" in it.reason }, "$events")
        assertTrue(events.none { it is AudioOutputEvent.Failed }, "$events")
        assertEquals(AudioOutputEvent.Stopped, events.last())
        events.clear()
        output.start(silent)
        assertIs<AudioOutputEvent.Failed>(events.first())
        assertEquals(listOf(AudioOutputEvent.Stopped), events.drop(1))
        output.close()
    }

    @Test
    fun aLayoutOfMoreChannelsFailsBeforeAnythingOpensUnderBothPolicies() {
        val web = FakeWebAudio(outputs = outputs)
        val devices = BrowserOutputDevices(web)
        val speakers = runSuspending { devices.list() }.single { it.id == "abc" }
        for (policy in AudioFormatPolicy.entries) {
            assertFailsWith<IllegalArgumentException> { open(web, format = AudioFormat(48_000, 6), device = speakers, policy = policy, devices = devices) }
        }
        assertTrue(web.contexts.isEmpty())
    }
}
