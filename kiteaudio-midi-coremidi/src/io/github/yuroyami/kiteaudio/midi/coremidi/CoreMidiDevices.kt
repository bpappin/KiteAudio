package io.github.yuroyami.kiteaudio.midi.coremidi

import io.github.yuroyami.kiteaudio.midi.MidiMessage
import io.github.yuroyami.kiteaudio.midi.devices.MidiDevices
import io.github.yuroyami.kiteaudio.midi.devices.MidiDirection
import io.github.yuroyami.kiteaudio.midi.devices.MidiInput
import io.github.yuroyami.kiteaudio.midi.devices.MidiOutput
import io.github.yuroyami.kiteaudio.midi.devices.MidiPort
import io.github.yuroyami.kiteaudio.midi.devices.MidiPortException
import io.github.yuroyami.kiteaudio.midi.devices.MidiStreamParser
import io.github.yuroyami.kiteaudio.midi.devices.MidiTransport
import io.github.yuroyami.kiteaudio.midi.devices.TimedMidiMessage
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * CoreMIDI on the JVM. Ports appear and disappear the moment macOS says so; no polling.
 *
 * Call [coreMidiAvailable] first. On another platform, or under a JDK older than 22, this throws.
 * Callback failures are logged and the affected packet is dropped. Close disables delivery
 * immediately; native port/client cleanup runs on a dedicated thread after callbacks return.
 */
public fun coreMidiDevices(scope: CoroutineScope, clientName: String = "KiteMIDI"): MidiDevices = CoreMidiDevices(scope, clientName)

internal class CoreMidiDevices(private val scope: CoroutineScope, clientName: String) : MidiDevices {
    private val arena = Arena.ofShared()
    private val client: Int
    private val portsState = MutableStateFlow<List<MidiPort>>(emptyList())
    override val ports: StateFlow<List<MidiPort>> = portsState.asStateFlow()
    private val notifyStub = CoreMidi.notifyProcStub(this, "onNotification", arena)
    private val timebase: Double
    private val lock = Any()
    private val inputs = LinkedHashSet<CoreMidiInput>()
    private val outputs = LinkedHashSet<CoreMidiOutput>()
    @Volatile private var closed = false
    private var shutdownQueued = false
    @Volatile private var runLoop: MemorySegment = MemorySegment.NULL
    private val loopThread: Thread

    init {
        val out = arena.allocate(ValueLayout.JAVA_INT)
        var status = -1
        val created = CountDownLatch(1)
        // Notifications are delivered on the run loop of the thread that made the client, so
        // that thread keeps pumping its loop, a second at a time, until the devices are closed.
        loopThread = Thread({
            runLoop = CoreMidi.retain.invoke(CoreMidi.runLoopGetCurrent.invoke() as MemorySegment) as MemorySegment
            try {
                status = CoreMidi.withCfString(arena, clientName) { name ->
                    CoreMidi.clientCreate.invoke(name, notifyStub, MemorySegment.NULL, out) as Int
                }
            } finally { created.countDown() }
            while (status == 0 && !closed) {
                val result = CoreMidi.runLoopRunInMode.invoke(CoreMidi.runLoopDefaultMode, 1.0, 0.toByte()) as Int
                // 1 is finished: nothing is registered on the loop yet, so do not spin.
                if (result == 1) Thread.sleep(50L)
            }
        }, "KiteMIDI CoreMIDI").apply { isDaemon = true }
        loopThread.start()
        created.await()
        if (status != 0) {
            loopThread.join()
            if (runLoop != MemorySegment.NULL) CoreMidi.release.invoke(runLoop)
            arena.close()
            throw MidiPortException("CoreMIDI would not create a client (OSStatus $status)")
        }
        client = out.get(ValueLayout.JAVA_INT, 0)
        val info = arena.allocate(8)
        CoreMidi.timebaseInfo.invoke(info) as Int
        timebase = info.get(ValueLayout.JAVA_INT, 0).toDouble() / info.get(ValueLayout.JAVA_INT, 4).toDouble()
        refresh()
    }

    /** Called by CoreMIDI on the loop thread whenever the setup changes. */
    @Suppress("unused")
    fun onNotification(message: MemorySegment, refCon: MemorySegment) {
        containCallbackFailure {
            val id = message.reinterpret(4).get(ValueLayout.JAVA_INT, 0)
            if (id in 1..3 && !closed) scope.launch(Dispatchers.IO) { refresh() }
        }
    }

    override fun refresh() {
        if (closed) return
        val found = ArrayList<MidiPort>()
        val sources = CoreMidi.getNumberOfSources.invoke() as Long
        for (i in 0 until sources) found += describe(CoreMidi.getSource.invoke(i) as Int, MidiDirection.INPUT)
        val destinations = CoreMidi.getNumberOfDestinations.invoke() as Long
        for (i in 0 until destinations) found += describe(CoreMidi.getDestination.invoke(i) as Int, MidiDirection.OUTPUT)
        synchronized(lock) { if (!closed) portsState.value = found }
    }

    private fun describe(endpoint: Int, direction: MidiDirection): MidiPort {
        val name = stringProperty(endpoint, CoreMidi.propertyDisplayName).ifBlank { stringProperty(endpoint, CoreMidi.propertyName) }.ifBlank { "MIDI port" }
        val maker = stringProperty(endpoint, CoreMidi.propertyManufacturer).takeIf { it.isNotBlank() }
        val prefix = if (direction == MidiDirection.INPUT) "in" else "out"
        val unique = integerProperty(endpoint, CoreMidi.propertyUniqueId)
        // The unique id survives a replug; the endpoint number does not.
        val id = if (unique != null) "$prefix:$unique" else "$prefix:ref:$endpoint"
        return MidiPort(id = id, name = name, manufacturer = maker, direction = direction, transport = transportOf(endpoint))
    }

    private fun transportOf(endpoint: Int): MidiTransport {
        val driver = stringProperty(endpoint, CoreMidi.propertyDriverOwner)
        return when {
            driver.isBlank() -> MidiTransport.VIRTUAL
            driver.contains("USB", ignoreCase = true) -> MidiTransport.USB
            driver.contains("Bluetooth", ignoreCase = true) -> MidiTransport.BLUETOOTH
            driver.contains("BTLE", ignoreCase = true) -> MidiTransport.BLUETOOTH
            driver.contains("RTP", ignoreCase = true) -> MidiTransport.NETWORK
            driver.contains("Network", ignoreCase = true) -> MidiTransport.NETWORK
            driver.contains("IAC", ignoreCase = true) -> MidiTransport.VIRTUAL
            else -> MidiTransport.UNKNOWN
        }
    }

    private fun stringProperty(obj: Int, property: MemorySegment): String = Arena.ofConfined().use { scratch ->
        val out = scratch.allocate(ValueLayout.ADDRESS)
        val status = CoreMidi.objectGetStringProperty.invoke(obj, property, out) as Int
        if (status != 0) return ""
        val ref = out.get(ValueLayout.ADDRESS, 0)
        if (ref == MemorySegment.NULL) return ""
        try {
            CoreMidi.stringOf(ref)
        } finally {
            CoreMidi.release.invoke(ref)
        }
    }

    private fun integerProperty(obj: Int, property: MemorySegment): Int? = Arena.ofConfined().use { scratch ->
        val out = scratch.allocate(ValueLayout.JAVA_INT)
        val status = CoreMidi.objectGetIntegerProperty.invoke(obj, property, out) as Int
        if (status == 0) out.get(ValueLayout.JAVA_INT, 0) else null
    }

    private fun endpointFor(port: MidiPort): Int {
        val isInput = port.direction == MidiDirection.INPUT
        val count = if (isInput) CoreMidi.getNumberOfSources.invoke() as Long else CoreMidi.getNumberOfDestinations.invoke() as Long
        for (i in 0 until count) {
            val endpoint = if (isInput) CoreMidi.getSource.invoke(i) as Int else CoreMidi.getDestination.invoke(i) as Int
            val unique = integerProperty(endpoint, CoreMidi.propertyUniqueId)
            val id = if (unique != null) "${if (isInput) "in" else "out"}:$unique" else "${if (isInput) "in" else "out"}:ref:$endpoint"
            if (id == port.id) return endpoint
        }
        throw MidiPortException("${port.name} is no longer attached")
    }

    override suspend fun openInput(port: MidiPort, onMessage: (TimedMidiMessage) -> Unit): MidiInput {
        if (port.direction != MidiDirection.INPUT) throw MidiPortException("${port.name} does not send notes to the app")
        return synchronized(lock) {
            if (closed) throw MidiPortException("CoreMIDI is closed")
            val input = CoreMidiInput(this, port, endpointFor(port), onMessage, timebase)
            inputs += input
            try { input.connect(); input } catch (error: Throwable) {
                input.close()
                throw error
            }
        }
    }

    override suspend fun openOutput(port: MidiPort): MidiOutput {
        if (port.direction != MidiDirection.OUTPUT) throw MidiPortException("the app cannot send notes to ${port.name}")
        return synchronized(lock) {
            if (closed) throw MidiPortException("CoreMIDI is closed")
            CoreMidiOutput(this, port, endpointFor(port)).also { outputs += it }
        }
    }

    override fun close() {
        val handles = synchronized(lock) {
            if (closed) return
            closed = true
            portsState.value = emptyList()
            inputs.toList() to outputs.toList()
        }
        handles.first.forEach { it.close() }
        handles.second.forEach { it.close() }
        synchronized(lock) { finishCloseIfReady() }
    }

    private fun finishCloseIfReady() {
        if (!closed || shutdownQueued || inputs.isNotEmpty() || outputs.isNotEmpty()) return
        shutdownQueued = true
        CoreMidiCleanup.execute {
            if (runLoop != MemorySegment.NULL) CoreMidi.runLoopStop.invoke(runLoop)
            loopThread.join()
            CoreMidiCleanup.dispose("client", this, { CoreMidi.clientDispose.invoke(client) as Int }) {
                if (runLoop != MemorySegment.NULL) CoreMidi.release.invoke(runLoop)
                CoreMidiCleanup.closeArena(arena)
            }
        }
    }

    internal val clientRef: Int get() = client
    internal fun forget(input: CoreMidiInput) = synchronized(lock) {
        inputs -= input
        finishCloseIfReady()
    }
    internal fun forget(output: CoreMidiOutput) = synchronized(lock) {
        outputs -= output
        finishCloseIfReady()
    }

}

internal class CoreMidiInput(
    private val owner: CoreMidiDevices,
    override val port: MidiPort,
    private val endpoint: Int,
    private val onMessage: (TimedMidiMessage) -> Unit,
    private val timebase: Double,
) : MidiInput {
    private val arena = Arena.ofShared()
    private val stub = CoreMidi.readProcStub(this, "onPackets", arena)
    private val portRef: Int
    private val parserLock = Any()
    private var arrivedAtNanos = 0L
    private val parser = MidiStreamParser { message -> if (open.get()) onMessage(TimedMidiMessage(message, arrivedAtNanos)) }
    private val open = AtomicBoolean(true)

    init {
        val out = arena.allocate(ValueLayout.JAVA_INT)
        val status = CoreMidi.withCfString(arena, "KiteMIDI in ${port.name}") { name ->
            CoreMidi.inputPortCreate.invoke(owner.clientRef, name, stub, MemorySegment.NULL, out) as Int
        }
        if (status != 0) {
            arena.close()
            throw MidiPortException("CoreMIDI would not create an input port (OSStatus $status)")
        }
        portRef = out.get(ValueLayout.JAVA_INT, 0)
    }

    fun connect() {
        val connected = CoreMidi.portConnectSource.invoke(portRef, endpoint, MemorySegment.NULL) as Int
        if (connected != 0) throw MidiPortException("CoreMIDI would not connect ${port.name} (OSStatus $connected)")
    }

    /** CoreMIDI's thread. Host ticks become nanoseconds on the clock System.nanoTime reads. */
    @Suppress("unused")
    fun onPackets(list: MemorySegment, readRefCon: MemorySegment, srcRefCon: MemorySegment) {
        containCallbackFailure {
            if (!open.get()) return@containCallbackFailure
            CoreMidi.forEachPacket(list) { ticks, bytes ->
                synchronized(parserLock) {
                    if (open.get()) {
                        arrivedAtNanos = if (ticks == 0L) System.nanoTime() else (ticks.toULong().toDouble() * timebase).toLong()
                        try { parser.feed(bytes) } catch (error: Throwable) {
                            parser.reset()
                            throw error
                        }
                    }
                }
            }
        }
    }

    override fun close() {
        // Inside the parser lock, so no callback starts after close() returns.
        if (!synchronized(parserLock) { open.compareAndSet(true, false) }) return
        CoreMidiCleanup.dispose("input ${port.name}", this, {
            CoreMidi.portDisconnectSource.invoke(portRef, endpoint) as Int
            CoreMidi.portDispose.invoke(portRef) as Int
        }) {
            // Wait for parser/user code without holding the owner lock or disposing in an upcall.
            try { synchronized(parserLock) { parser.reset() } } finally {
                owner.forget(this)
                CoreMidiCleanup.closeArena(arena)
            }
        }
    }
}

internal class CoreMidiOutput(private val owner: CoreMidiDevices, override val port: MidiPort, private val endpoint: Int) : MidiOutput {
    private val arena = Arena.ofShared()
    private val portRef: Int
    private val sendLock = Any()
    private var open = true

    init {
        val out = arena.allocate(ValueLayout.JAVA_INT)
        val status = CoreMidi.withCfString(arena, "KiteMIDI out ${port.name}") { name ->
            CoreMidi.outputPortCreate.invoke(owner.clientRef, name, out) as Int
        }
        if (status != 0) {
            arena.close()
            throw MidiPortException("CoreMIDI would not create an output port (OSStatus $status)")
        }
        portRef = out.get(ValueLayout.JAVA_INT, 0)
    }

    override fun send(message: MidiMessage): Unit = sendRaw(message.toBytes())

    override fun sendRaw(bytes: ByteArray) {
        synchronized(sendLock) {
            if (!open) throw MidiPortException("the output to ${port.name} is closed")
            if (bytes.isEmpty()) return
            Arena.ofConfined().use { scratch ->
                val status = CoreMidi.send.invoke(portRef, endpoint, CoreMidi.packetList(scratch, bytes)) as Int
                if (status != 0) throw MidiPortException("CoreMIDI would not send to ${port.name} (OSStatus $status)")
            }
        }
    }

    override fun close() {
        synchronized(sendLock) {
            if (!open) return
            open = false
            CoreMidiCleanup.dispose("output ${port.name}", this, { CoreMidi.portDispose.invoke(portRef) as Int }) {
                owner.forget(this)
                arena.close()
            }
        }
    }
}

/** All upcalls contain application/decoder failures; the affected packet is dropped and logged. */
internal inline fun containCallbackFailure(block: () -> Unit) {
    try { block() } catch (error: Throwable) {
        try { System.getLogger("KiteMIDI").log(System.Logger.Level.WARNING, "MIDI callback failed; packet dropped", error) }
        catch (_: Throwable) { /* Nothing may escape a foreign-function upcall. */ }
    }
}

/** A callback can close its own input. Dispose only after returning through the native stub. */
internal object CoreMidiCleanup {
    private val executor = Executors.newSingleThreadScheduledExecutor { action ->
        Thread(action, "KiteMIDI cleanup").apply { isDaemon = true }
    }
    // Failed disposal must retain the object and its arena; native code may still own the stub.
    private val retainedFailures = java.util.concurrent.ConcurrentHashMap.newKeySet<Any>()
    fun isRetained(resource: Any): Boolean = resource in retainedFailures
    fun execute(action: () -> Unit) {
        executor.execute {
            try { action() } catch (error: Throwable) {
                System.getLogger("KiteMIDI").log(System.Logger.Level.ERROR, "cleanup failed; native references retained", error)
            }
        }
    }
    fun dispose(label: String, resource: Any, action: () -> Int, onDisposed: () -> Unit) {
        fun attempt(remaining: Int) {
            val status = try { action() } catch (error: Throwable) {
                retainedFailures += resource
                System.getLogger("KiteMIDI").log(System.Logger.Level.ERROR, "$label disposal threw; native references retained", error)
                return
            }
            if (status == 0) {
                try {
                    onDisposed()
                    retainedFailures -= resource
                } catch (error: Throwable) {
                    retainedFailures += resource
                    System.getLogger("KiteMIDI").log(System.Logger.Level.ERROR, "$label cleanup failed; references retained", error)
                }
            } else if (remaining > 1) {
                executor.schedule({ attempt(remaining - 1) }, 10, TimeUnit.MILLISECONDS)
            } else {
                retainedFailures += resource
                System.getLogger("KiteMIDI").log(System.Logger.Level.ERROR, "$label disposal failed (OSStatus $status); native references retained")
            }
        }
        executor.execute { attempt(3) }
    }
    fun closeArena(arena: Arena) {
        try { arena.close() } catch (_: IllegalStateException) {
            // The JVM refuses to close a shared arena while an upcall is still returning.
            if (arena.scope().isAlive) executor.schedule({ closeArena(arena) }, 1, TimeUnit.MILLISECONDS)
        }
    }
}
