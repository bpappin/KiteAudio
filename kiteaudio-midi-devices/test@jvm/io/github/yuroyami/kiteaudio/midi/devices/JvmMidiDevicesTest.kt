package io.github.yuroyami.kiteaudio.midi.devices

import io.github.yuroyami.kiteaudio.midi.NoteOff
import io.github.yuroyami.kiteaudio.midi.NoteOn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Runs against the devices this JDK really has, with no stand-ins.
 *
 * Every JDK ships Gervill, a software synthesiser that only takes messages, and a software
 * sequencer that both takes and sends them. That is enough to test both directions on any
 * machine, plugged-in hardware or not.
 *
 * Gervill is listed on every machine, but it opens only where there is audio output, because
 * it plays into an audio line. The tests that open it return early on a machine with no sound
 * card, such as a build server.
 */
class JvmMidiDevicesTest {

    private val gervill = "Gervill"
    private val sequencer = "Real Time Sequencer"

    // The line that Gervill asks for when it opens: 44.1 kHz, 16-bit, stereo.
    private val gervillCanOpen: Boolean = runCatching {
        val format = AudioFormat(44_100f, 16, 2, true, false)
        AudioSystem.isLineSupported(DataLine.Info(SourceDataLine::class.java, format))
    }.getOrDefault(false)

    // The polling lives on a scope of the test's own, so cancelling it cannot leave a loop
    // running after the test ends.
    private fun pollingScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Waits in real time. A delay on the test dispatcher would be skipped, and the poll runs on
    // a real thread.
    private suspend fun awaitPorts(devices: MidiDevices): List<MidiPort> =
        withContext(Dispatchers.Default) {
            withTimeout(20.seconds) { devices.ports.first { it.isNotEmpty() } }
        }

    private suspend fun refreshAndWait(devices: JvmMidiDevices) {
        val before = devices.scansCompleted.value
        devices.refresh()
        withContext(Dispatchers.Default) {
            withTimeout(20.seconds) { devices.scansCompleted.first { it > before } }
        }
    }

    @Test
    fun enumerationFindsGervillAsAnOutputAndNeverAsAnInput() = runTest(timeout = 60.seconds) {
        val scope = pollingScope()
        val devices = jvmMidiDevices(scope)
        try {
            val ports = awaitPorts(devices)
            assertTrue(ports.isNotEmpty(), "expected at least one port")
            val mine = ports.filter { it.name == gervill }
            assertEquals(1, mine.size, "Gervill should give one port, got $mine")
            assertEquals(MidiDirection.OUTPUT, mine.single().direction)
            assertTrue(ports.none { it.name == gervill && it.direction == MidiDirection.INPUT })
        } finally {
            devices.close()
            scope.cancel()
        }
    }

    @Test
    fun sendingNotesToGervillCompletes() = runTest(timeout = 60.seconds) {
        if (!gervillCanOpen) return@runTest
        val scope = pollingScope()
        val devices = jvmMidiDevices(scope)
        try {
            val port = awaitPorts(devices).single { it.name == gervill && it.direction == MidiDirection.OUTPUT }
            val output = devices.openOutput(port)
            output.send(NoteOn(0, 60, 100))
            output.send(NoteOff(0, 60, 0))
            output.sendRaw(byteArrayOf(0xB0.toByte(), 123, 0))
            output.close()
        } finally {
            devices.close()
            scope.cancel()
        }
    }

    @Test
    fun closingAnOutputTwiceIsSafe() = runTest(timeout = 60.seconds) {
        if (!gervillCanOpen) return@runTest
        val scope = pollingScope()
        val devices = jvmMidiDevices(scope)
        try {
            val port = awaitPorts(devices).single { it.name == gervill && it.direction == MidiDirection.OUTPUT }
            val output = devices.openOutput(port)
            output.close()
            output.close()
            // Sending after the close is dropped rather than thrown.
            output.send(NoteOn(0, 60, 100))
        } finally {
            devices.close()
            scope.cancel()
        }
    }

    @Test
    fun openingTheSequencerForReadingCompletes() = runTest(timeout = 60.seconds) {
        val scope = pollingScope()
        val devices = jvmMidiDevices(scope)
        try {
            val port = awaitPorts(devices).single { it.name == sequencer && it.direction == MidiDirection.INPUT }
            val arrived = ArrayList<TimedMidiMessage>()
            val input = devices.openInput(port) { arrived += it }
            assertEquals(port, input.port)
            input.close()
            input.close()
        } finally {
            devices.close()
            scope.cancel()
        }
    }

    @Test
    fun twoRefreshesLeaveThePortIdsAlone() = runTest(timeout = 60.seconds) {
        val scope = pollingScope()
        val devices = jvmMidiDevices(scope) as JvmMidiDevices
        try {
            val before = awaitPorts(devices).map { it.id }
            refreshAndWait(devices)
            refreshAndWait(devices)
            assertEquals(before, devices.ports.value.map { it.id })
        } finally {
            devices.close()
            scope.cancel()
        }
    }

    @Test
    fun openingAPortThatIsNotListedThrows() = runTest(timeout = 60.seconds) {
        val scope = pollingScope()
        val devices = jvmMidiDevices(scope)
        try {
            awaitPorts(devices)
            val ghost = MidiPort(
                id = "jvm|Nothing At All|Nobody|0.0|0|out",
                name = "Nothing At All",
                manufacturer = null,
                direction = MidiDirection.OUTPUT,
                transport = MidiTransport.UNKNOWN,
            )
            val outputFailure = runCatching { devices.openOutput(ghost) }.exceptionOrNull()
            assertTrue(outputFailure is MidiPortException, "expected MidiPortException, got $outputFailure")

            // Gervill is real but takes messages only, so reading from it is not on offer either.
            val wrongWay = MidiPort(
                id = "jvm|$gervill|OpenJDK|1.0|0|in",
                name = gervill,
                manufacturer = "OpenJDK",
                direction = MidiDirection.INPUT,
                transport = MidiTransport.VIRTUAL,
            )
            val inputFailure = runCatching { devices.openInput(wrongWay) { } }.exceptionOrNull()
            assertTrue(inputFailure is MidiPortException, "expected MidiPortException, got $inputFailure")
        } finally {
            devices.close()
            scope.cancel()
        }
    }
}
