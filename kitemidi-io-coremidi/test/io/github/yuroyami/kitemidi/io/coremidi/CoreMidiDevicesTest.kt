package io.github.yuroyami.kitemidi.io.coremidi

import io.github.yuroyami.kitemidi.NoteOn
import io.github.yuroyami.kitemidi.io.MidiDirection
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import io.github.yuroyami.kitemidi.io.MidiInput
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import io.github.yuroyami.kitemidi.io.MidiPortException

/** A virtual source is the test's way of being a piano: CoreMIDI lists it like any other. */
private class VirtualSource(name: String) {
    private val arena = Arena.ofShared()
    private val client: Int
    private val endpoint: Int

    init {
        val out = arena.allocate(ValueLayout.JAVA_INT)
        check(CoreMidi.withCfString(arena, "$name client") { cfName -> CoreMidi.clientCreate.invoke(cfName, MemorySegment.NULL, MemorySegment.NULL, out) as Int } == 0)
        client = out.get(ValueLayout.JAVA_INT, 0)
        val endpointOut = arena.allocate(ValueLayout.JAVA_INT)
        check(CoreMidi.withCfString(arena, name) { cfName -> CoreMidi.sourceCreate.invoke(client, cfName, endpointOut) as Int } == 0)
        endpoint = endpointOut.get(ValueLayout.JAVA_INT, 0)
    }

    fun send(bytes: ByteArray) {
        Arena.ofConfined().use { scratch -> check(CoreMidi.received.invoke(endpoint, CoreMidi.packetList(scratch, bytes)) as Int == 0) }
    }

    fun close() {
        CoreMidi.endpointDispose.invoke(endpoint) as Int
        CoreMidi.clientDispose.invoke(client) as Int
        arena.close()
    }
}

class CoreMidiDevicesTest {
    @Test
    fun failedDisposalRetainsOwnershipAndNeverRunsTheFreeStep() = runBlocking {
        val resource = Any()
        val attempts = AtomicInteger()
        val frees = AtomicInteger()
        CoreMidiCleanup.dispose("expected failing fixture", resource, { attempts.incrementAndGet(); -1 }) {
            frees.incrementAndGet()
        }
        withTimeout(5_000) { while (!CoreMidiCleanup.isRetained(resource)) delay(1) }
        assertEquals(3, attempts.get())
        assertEquals(0, frees.get())
    }

    @Test
    fun successfulDisposalRetryFreesOnlyAfterSuccess() = runBlocking {
        val resource = Any()
        val attempts = AtomicInteger()
        val freed = CompletableDeferred<Unit>()
        CoreMidiCleanup.dispose("retry fixture", resource, { if (attempts.incrementAndGet() < 3) -1 else 0 }) {
            freed.complete(Unit)
        }
        withTimeout(5_000) { freed.await() }
        assertEquals(3, attempts.get())
        assertTrue(!CoreMidiCleanup.isRetained(resource))
    }

    @Test
    fun callbackFailuresDoNotEscapeTheBoundary() {
        var reached = false
        containCallbackFailure { reached = true; error("expected test callback failure") }
        assertTrue(reached)
    }

    @Test
    fun packetLengthRejectsOversizedSendsBeforeNarrowing() {
        if (!coreMidiAvailable()) return
        Arena.ofConfined().use { arena ->
            val packet = CoreMidi.packetList(arena, ByteArray(65_520))
            assertEquals(65_520, packet.get(ValueLayout.JAVA_SHORT.withByteAlignment(1), 12).toInt() and 0xffff)
            assertTrue(packet.byteSize() <= 65_536)
            assertFailsWith<MidiPortException> { CoreMidi.packetList(arena, ByteArray(65_521)) }
            assertFailsWith<MidiPortException> { CoreMidi.packetList(arena, ByteArray(65_536)) }
        }
    }

    @Test
    fun aCallbackCanCloseItsInputAndOwnerAndDisableFurtherDelivery() = runBlocking(Dispatchers.Default) {
        if (!coreMidiAvailable()) return@runBlocking
        val devices = coreMidiDevices(this)
        val virtual = VirtualSource("KiteMIDI FFM callback close source")
        val input = AtomicReference<MidiInput?>()
        val deliveries = AtomicInteger()
        val closeReturned = CompletableDeferred<Unit>()
        try {
            devices.refresh()
            val port = devices.ports.value.first {
                it.name == "KiteMIDI FFM callback close source" && it.direction == MidiDirection.INPUT
            }
            input.set(devices.openInput(port) {
                deliveries.incrementAndGet()
                input.get()?.close()
                devices.close()
                closeReturned.complete(Unit)
            })
            virtual.send(NoteOn(0, 60, 100).toBytes())
            withTimeout(5_000) { closeReturned.await() }
            virtual.send(NoteOn(0, 62, 100).toBytes())
            delay(250)
            assertEquals(1, deliveries.get(), "closed input must not deliver the second send")
            assertTrue(devices.ports.value.isEmpty())
            // This signal and bounded delivery window prove callback-close returns safely;
            // they do not assert completion of deferred native disposal or allocation release.
        } finally {
            input.get()?.close()
            devices.close()
            virtual.close()
        }
    }

    @Test
    fun aVirtualSourceIsListedAndItsNoteComesThrough() = runBlocking(Dispatchers.Default) {
        if (!coreMidiAvailable()) return@runBlocking
        val devices = coreMidiDevices(this)
        val virtual = VirtualSource("KiteMIDI test source")
        try {
            val port = withTimeout(5_000L) {
                var found = devices.ports.value.firstOrNull { it.name == "KiteMIDI test source" && it.direction == MidiDirection.INPUT }
                while (found == null) {
                    delay(50L)
                    devices.refresh()
                    found = devices.ports.value.firstOrNull { it.name == "KiteMIDI test source" && it.direction == MidiDirection.INPUT }
                }
                found
            }
            val heard = CompletableDeferred<NoteOn>()
            val input = devices.openInput(port) { timed -> (timed.message as? NoteOn)?.let { heard.complete(it) } }
            virtual.send(byteArrayOf(0x90.toByte(), 60, 100))
            assertEquals(NoteOn(0, 60, 100), withTimeout(5_000L) { heard.await() })
            input.close()
        } finally {
            virtual.close()
            devices.close()
        }
    }

    @Test
    fun aSourceThatAppearsLaterIsSeenWithoutRefresh() = runBlocking(Dispatchers.Default) {
        if (!coreMidiAvailable()) return@runBlocking
        val devices = coreMidiDevices(this)
        try {
            val virtual = VirtualSource("KiteMIDI late source")
            try {
                withTimeout(5_000L) {
                    while (devices.ports.value.none { it.name == "KiteMIDI late source" }) delay(50L)
                }
                assertTrue(true)
            } finally {
                virtual.close()
            }
            withTimeout(5_000L) {
                while (devices.ports.value.any { it.name == "KiteMIDI late source" }) delay(50L)
            }
        } finally {
            devices.close()
        }
    }
}
