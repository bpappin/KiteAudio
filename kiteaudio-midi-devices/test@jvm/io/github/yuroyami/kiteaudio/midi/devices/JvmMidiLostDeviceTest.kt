package io.github.yuroyami.kiteaudio.midi.devices

import io.github.yuroyami.kiteaudio.midi.NoteOn
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Identical devices on the JVM. After a twin is unplugged, the JDK hands the twin that remains
 * the first old Info and its device object, so the library must drop every handle it cannot trust.
 */
class JvmMidiLostDeviceTest {

    private val system = FakeMidiSystem()
    private val twinA = FakeMidiDevice("Twin")
    private val twinB = FakeMidiDevice("Twin")

    private fun withTwins(block: suspend (JvmMidiDevices, List<MidiPort>) -> Unit) = runBlocking {
        system.attached = listOf(twinA, twinB)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val devices = JvmMidiDevices(scope, system::infos, system::deviceFor)
        try {
            val ports = withTimeout(5_000) { devices.ports.first { it.size == 4 } }
            block(devices, ports)
        } finally {
            devices.close()
            scope.cancel()
        }
    }

    // The scan that is running may have read the list before the change, so wait for the one after.
    private suspend fun scanAgain(devices: JvmMidiDevices) {
        val target = devices.scansCompleted.value + 2
        devices.refresh()
        withTimeout(5_000) { devices.scansCompleted.first { it >= target } }
    }

    private fun List<MidiPort>.output(ordinal: Int) = single { it.direction == MidiDirection.OUTPUT && it.id.endsWith("|$ordinal|out") }
    private fun List<MidiPort>.input(ordinal: Int) = single { it.direction == MidiDirection.INPUT && it.id.endsWith("|$ordinal|in") }

    @Test fun aDeviceWhoseKeyIsGoneIsLost() = withTwins { devices, ports ->
        val output = devices.openOutput(ports.output(1))
        output.send(NoteOn(0, 60, 100))
        system.attached = listOf(twinA)
        scanAgain(devices)
        val error = assertFailsWith<MidiPortException> { output.send(NoteOn(0, 60, 100)) }
        assertTrue("lost" in error.message.orEmpty(), error.message)
        assertFailsWith<MidiPortException> { output.sendRaw(ByteArray(0)) }
        assertEquals(1, twinB.closes.get(), "the javax device of a lost entry must be closed")
        output.close()
    }

    @Test fun aGroupThatShrankLosesItsOpenDevices() = withTwins { devices, ports ->
        val output = devices.openOutput(ports.output(0))
        val heard = CopyOnWriteArrayList<TimedMidiMessage>()
        val input = devices.openInput(ports.input(0)) { heard += it }
        val transmitter = twinA.transmitters.single()
        transmitter.playNoteOn(60)
        assertEquals(1, heard.size)
        // The JDK keeps A's Info and device object for the twin that remains.
        system.attached = listOf(twinA)
        scanAgain(devices)
        val error = assertFailsWith<MidiPortException> { output.send(NoteOn(0, 60, 100)) }
        assertTrue("lost" in error.message.orEmpty(), error.message)
        transmitter.playNoteOn(62)
        assertEquals(1, heard.size, "a lost input must deliver nothing")
        assertEquals(1, twinA.closes.get())
        input.close()
        output.close()
        assertEquals(1, twinA.closes.get())
    }

    @Test fun aLostHandleDoesNotReleaseTheNewerDeviceUnderTheSameKey() = withTwins { devices, ports ->
        val old = devices.openOutput(ports.output(0))
        system.attached = listOf(twinA)
        scanAgain(devices)
        val fresh = devices.openOutput(devices.ports.value.output(0))
        assertEquals(2, twinA.opens.get(), "the next open must reach the current hardware")
        old.close()
        old.close()
        assertEquals(1, twinA.closes.get(), "closing a lost handle must not close the newer device")
        fresh.send(NoteOn(0, 64, 100))
        assertEquals(listOf(0x90, 64, 100), twinA.sent.last().map { it.toInt() and 0xFF })
        fresh.close()
        assertEquals(2, twinA.closes.get())
    }

    @Test fun aGroupThatGrowsKeepsItsOpenDevices() = withTwins { devices, ports ->
        val output = devices.openOutput(ports.output(0))
        system.attached = listOf(twinA, twinB, FakeMidiDevice("Twin"))
        scanAgain(devices)
        output.send(NoteOn(0, 60, 100))
        assertEquals(0, twinA.closes.get())
        output.close()
    }
}
