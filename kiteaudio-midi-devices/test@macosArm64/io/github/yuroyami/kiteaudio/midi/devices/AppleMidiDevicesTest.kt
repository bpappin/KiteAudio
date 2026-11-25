package io.github.yuroyami.kiteaudio.midi.devices

import io.github.yuroyami.kiteaudio.midi.NoteOn
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.value
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringRef
import platform.CoreMIDI.MIDIClientCreate
import platform.CoreMIDI.MIDIClientDispose
import platform.CoreMIDI.MIDIClientRef
import platform.CoreMIDI.MIDIClientRefVar
import platform.CoreMIDI.MIDIEndpointDispose
import platform.CoreMIDI.MIDIEndpointRef
import platform.CoreMIDI.MIDIEndpointRefVar
import platform.CoreMIDI.MIDIPacketList
import platform.CoreMIDI.MIDIPacketListAdd
import platform.CoreMIDI.MIDIPacketListInit
import platform.CoreMIDI.MIDIReceived
import platform.CoreMIDI.MIDISourceCreate
import platform.CoreVideo.CVGetCurrentHostTime
import platform.CoreVideo.CVGetHostClockFrequency
import platform.Foundation.CFBridgingRetain
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Talks to the real CoreMIDI on whichever Mac runs this.
 *
 * That machine may have no MIDI hardware at all, so an empty port list is a pass. The last test
 * makes an endpoint of its own so there is always something real to read from.
 */
class AppleMidiDevicesTest {

    private fun scope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Test
    fun enumeratesWithoutThrowing() {
        val scope = scope()
        val devices = appleMidiDevices(scope)
        try {
            devices.refresh()
            val found = devices.ports.value
            println("CoreMIDI reported ${found.size} port(s)")
            for (port in found) {
                println("  ${port.direction} ${port.transport} id=${port.id} name=${port.displayName}")
                assertTrue(port.id.isNotBlank(), "a port came back with a blank id")
                assertTrue(port.name.isNotBlank(), "a port came back with a blank name")
                val expectedPrefix = if (port.direction == MidiDirection.INPUT) "in:" else "out:"
                assertTrue(port.id.startsWith(expectedPrefix), "id ${port.id} does not match its direction")
            }
            assertEquals(found.size, found.map { it.id }.toSet().size, "two ports share an id")
        } finally {
            devices.close()
            scope.cancel()
        }
    }

    @Test
    fun closingTwiceIsSafe() {
        val scope = scope()
        val devices = appleMidiDevices(scope)
        devices.close()
        devices.close()
        // A refresh after closing is ignored rather than reopening anything.
        devices.refresh()
        assertTrue(devices.ports.value.isEmpty(), "ports should be empty once closed")
        scope.cancel()
    }

    @Test
    fun aCallbackCanCloseItsInputAndOwnerAndDisableFurtherDelivery() = runBlocking {
        val scope = scope()
        val devices = appleMidiDevices(scope)
        val helper = VirtualSource("KiteAudio native callback close source")
        val inputState = MutableStateFlow<MidiInput?>(null)
        val deliveries = MutableStateFlow(0)
        val closeReturned = MutableStateFlow(false)
        try {
            devices.refresh()
            val port = assertNotNull(devices.ports.value.firstOrNull {
                it.direction == MidiDirection.INPUT && it.name.contains(helper.name)
            })
            inputState.value = devices.openInput(port) {
                deliveries.update { it + 1 }
                inputState.value?.close()
                devices.close()
                closeReturned.value = true
            }
            helper.send(NoteOn(0, 60, 100).toBytes())
            withTimeout(5_000) { closeReturned.first { it } }
            helper.send(NoteOn(0, 62, 100).toBytes())
            delay(250)
            assertEquals(1, deliveries.value, "closed input must not deliver the second send")
            assertTrue(devices.ports.value.isEmpty())
            // The signal proves both close calls returned from their callback and delivery
            // stopped during this bounded window. Native disposal is deferred, so this is
            // not an assertion that all native allocations have already been freed.
        } finally {
            inputState.value?.close()
            devices.close()
            helper.dispose()
            scope.cancel()
        }
    }

    @Test
    fun aVirtualSourceIsFoundAndItsMessagesArrive() = runBlocking {
        val scope = scope()
        val devices = appleMidiDevices(scope)
        val helper = VirtualSource("KiteAudio test source")
        val received = MutableStateFlow<List<TimedMidiMessage>>(emptyList())
        try {
            devices.refresh()
            val port = devices.ports.value.firstOrNull {
                it.direction == MidiDirection.INPUT && it.name.contains(helper.name)
            }
            assertNotNull(port, "the virtual source this test created was not in the port list")
            println("found virtual port: id=${port.id} name=${port.displayName} transport=${port.transport}")
            // No driver sits behind an endpoint an app made for itself.
            assertEquals(MidiTransport.VIRTUAL, port.transport)

            val input = devices.openInput(port) { message -> received.update { it + message } }
            try {
                helper.send(NoteOn(channel = 0, key = 60, velocity = 100).toBytes())
                val arrived = withTimeout(5_000) { received.first { it.isNotEmpty() } }
                println("received: ${arrived.first()}")
                assertEquals(NoteOn(channel = 0, key = 60, velocity = 100), arrived.first().message)

                // A stamp of zero means "now", so it should land within a few seconds of the
                // host clock read here. This is what proves the tick to nanosecond maths.
                val nowNanos = (CVGetCurrentHostTime().toDouble() / CVGetHostClockFrequency() * 1e9).toLong()
                val stamp = arrived.first().timestampNanos
                assertTrue(stamp > 0, "timestamp was $stamp")
                assertTrue(abs(nowNanos - stamp) < 5_000_000_000L, "timestamp $stamp is far from $nowNanos")
            } finally {
                input.close()
                input.close()
            }
        } finally {
            helper.dispose()
            devices.close()
            scope.cancel()
        }
    }
}

/** A CoreMIDI endpoint owned by the test, so there is always one port to find and read. */
private class VirtualSource(val name: String) {

    private var client: MIDIClientRef = 0u
    private var endpoint: MIDIEndpointRef = 0u

    init {
        memScoped {
            val clientOut = alloc<MIDIClientRefVar>()
            val clientStatus = withTestCfString("KiteAudio test client") { cfName ->
                MIDIClientCreate(cfName, null, null, clientOut.ptr)
            }
            check(clientStatus == 0) { "MIDIClientCreate failed with $clientStatus" }
            client = clientOut.value

            val sourceOut = alloc<MIDIEndpointRefVar>()
            val sourceStatus = withTestCfString(name) { cfName ->
                MIDISourceCreate(client, cfName, sourceOut.ptr)
            }
            check(sourceStatus == 0) { "MIDISourceCreate failed with $sourceStatus" }
            endpoint = sourceOut.value
        }
    }

    fun send(bytes: ByteArray) {
        memScoped {
            val listBytes = 32 + bytes.size
            val storage = allocArray<UIntVar>(listBytes / 4)
            val list = storage.reinterpret<MIDIPacketList>()
            val data = allocArray<UByteVar>(bytes.size)
            for (i in bytes.indices) data[i] = bytes[i].toUByte()
            val first = MIDIPacketListInit(list)
            MIDIPacketListAdd(list, listBytes.toULong(), first, 0uL, bytes.size.toULong(), data)
            val status = MIDIReceived(endpoint, list)
            check(status == 0) { "MIDIReceived failed with $status" }
        }
    }

    fun dispose() {
        if (endpoint != 0u) MIDIEndpointDispose(endpoint)
        if (client != 0u) MIDIClientDispose(client)
        endpoint = 0u
        client = 0u
    }
}

private inline fun <T> withTestCfString(text: String, block: (CFStringRef?) -> T): T {
    val ref: CFStringRef? = CFBridgingRetain(text)?.reinterpret()
    try {
        return block(ref)
    } finally {
        if (ref != null) CFRelease(ref)
    }
}
