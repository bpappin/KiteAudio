package io.github.yuroyami.kiteaudio.midi.devices

import io.github.yuroyami.kiteaudio.midi.NoteOn
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JvmMidiClosedPortTest {

    private fun withDevices(block: suspend (FakeMidiDevice, JvmMidiDevices, List<MidiPort>) -> Unit) = runBlocking {
        val system = FakeMidiSystem()
        val fake = FakeMidiDevice("Keys")
        system.attached = listOf(fake)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val devices = JvmMidiDevices(scope, system::infos, system::deviceFor)
        try {
            val ports = withTimeout(5_000) { devices.ports.first { it.isNotEmpty() } }
            block(fake, devices, ports)
        } finally {
            devices.close()
            scope.cancel()
        }
    }

    @Test fun sendOnAClosedOutputThrows() = withDevices { _, devices, ports ->
        val output = devices.openOutput(ports.single { it.direction == MidiDirection.OUTPUT })
        output.close()
        val error = assertFailsWith<MidiPortException> { output.send(NoteOn(0, 60, 100)) }
        assertTrue("closed" in error.message.orEmpty(), error.message)
        assertFailsWith<MidiPortException> { output.sendRaw(byteArrayOf(0x90.toByte(), 60, 100)) }
    }

    @Test fun sendRawOfAnEmptyArrayOnAClosedOutputThrows() = withDevices { _, devices, ports ->
        val output = devices.openOutput(ports.single { it.direction == MidiDirection.OUTPUT })
        output.sendRaw(ByteArray(0))
        output.close()
        val error = assertFailsWith<MidiPortException> { output.sendRaw(ByteArray(0)) }
        assertTrue("closed" in error.message.orEmpty(), error.message)
    }

    @Test fun noCallbackIsDeliveredAfterCloseReturns() = withDevices { fake, devices, ports ->
        val heard = CopyOnWriteArrayList<TimedMidiMessage>()
        val input = devices.openInput(ports.single { it.direction == MidiDirection.INPUT }) { heard += it }
        val transmitter = fake.transmitters.single()
        transmitter.playNoteOn(60)
        assertEquals(listOf(NoteOn(0, 60, 100)), heard.map { it.message })
        input.close()
        // The platform thread can still call the receiver after close. Nothing may reach the app.
        transmitter.playNoteOn(62)
        assertEquals(1, heard.size)
    }

    @Test fun closeWaitsForACallbackThatAlreadyRuns() = withDevices { fake, devices, ports ->
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val heard = CopyOnWriteArrayList<TimedMidiMessage>()
        val input = devices.openInput(ports.single { it.direction == MidiDirection.INPUT }) {
            heard += it
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
        }
        val transmitter = fake.transmitters.single()
        val platform = thread { transmitter.playNoteOn(60) }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val closeReturned = CountDownLatch(1)
        val closer = thread { input.close(); closeReturned.countDown() }
        assertTrue(!closeReturned.await(200, TimeUnit.MILLISECONDS), "close returned while a callback ran")
        release.countDown()
        assertTrue(closeReturned.await(5, TimeUnit.SECONDS))
        platform.join()
        closer.join()
        transmitter.playNoteOn(62)
        assertEquals(1, heard.size)
    }
}
