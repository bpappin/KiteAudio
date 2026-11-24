package io.github.yuroyami.kiteaudio.midi.devices

import io.github.yuroyami.kiteaudio.midi.MidiMessage
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.value
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import platform.CoreFoundation.CFRunLoopRunInMode
import platform.CoreFoundation.kCFRunLoopDefaultMode
import platform.Foundation.NSCondition
import platform.Foundation.NSRecursiveLock
import platform.Foundation.NSThread
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.CFStringRefVar
import platform.CoreMIDI.MIDIClientCreate
import platform.CoreMIDI.MIDIClientDispose
import platform.CoreMIDI.MIDIClientRef
import platform.CoreMIDI.MIDIClientRefVar
import platform.CoreMIDI.MIDIEndpointRef
import platform.CoreMIDI.MIDIGetDestination
import platform.CoreMIDI.MIDIGetNumberOfDestinations
import platform.CoreMIDI.MIDIGetNumberOfSources
import platform.CoreMIDI.MIDIGetSource
import platform.CoreMIDI.MIDIInputPortCreate
import platform.CoreMIDI.MIDINotification
import platform.CoreMIDI.MIDIObjectGetIntegerProperty
import platform.CoreMIDI.MIDIObjectGetStringProperty
import platform.CoreMIDI.MIDIObjectRef
import platform.CoreMIDI.MIDIOutputPortCreate
import platform.CoreMIDI.MIDIPacket
import platform.CoreMIDI.MIDIPacketList
import platform.CoreMIDI.MIDIPacketListAdd
import platform.CoreMIDI.MIDIPacketListInit
import platform.CoreMIDI.MIDIPacketNext
import platform.CoreMIDI.MIDIPortConnectSource
import platform.CoreMIDI.MIDIPortDisconnectSource
import platform.CoreMIDI.MIDIPortDispose
import platform.CoreMIDI.MIDIPortRef
import platform.CoreMIDI.MIDIPortRefVar
import platform.CoreMIDI.MIDISend
import platform.CoreMIDI.kMIDIMsgObjectAdded
import platform.CoreMIDI.kMIDIMsgObjectRemoved
import platform.CoreMIDI.kMIDIMsgSetupChanged
import platform.CoreMIDI.kMIDIPropertyDisplayName
import platform.CoreMIDI.kMIDIPropertyDriverOwner
import platform.CoreMIDI.kMIDIPropertyManufacturer
import platform.CoreMIDI.kMIDIPropertyName
import platform.CoreMIDI.kMIDIPropertyUniqueID
import platform.CoreVideo.CVGetCurrentHostTime
import platform.CoreVideo.CVGetHostClockFrequency
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain

/**
 * Opens the MIDI ports of this Mac, iPhone or iPad through CoreMIDI.
 *
 * The returned object holds one CoreMIDI client for as long as it lives, so make one and keep
 * it rather than making a new one per screen. Call [MidiDevices.close] when you are done.
 *
 * [scope] is used for one thing only: re-reading the port list after CoreMIDI says the setup
 * changed, so that work does not sit on CoreMIDI's own notification thread. Nothing else here
 * needs it, and this never cancels it. A dedicated thread services the client notification
 * run loop. Close disables delivery immediately and defers native disposal until callbacks
 * return. Callback failures are logged and the affected packet is discarded.
 *
 * @throws MidiPortException when CoreMIDI refuses this process a client or an output port.
 */
@Throws(MidiPortException::class)
public fun appleMidiDevices(scope: CoroutineScope): MidiDevices = AppleMidiDevices(scope)

/** OSStatus for "nothing went wrong". CoreMIDI returns this from every call that succeeded. */
private const val NO_ERROR: Int = 0

/** Four bytes of list header plus ten of packet header, rounded up for alignment. */
private const val PACKET_LIST_OVERHEAD: Int = 16

private const val NANOS_PER_SECOND: ULong = 1_000_000_000uL

/**
 * CoreMIDI calls this from its own high priority thread, one call per bundle of packets.
 *
 * The refCon is the [StableRef] handed to `MIDIInputPortCreate`, which stays alive until the
 * input is closed.
 */
private val readProc = staticCFunction<CPointer<MIDIPacketList>?, COpaquePointer?, COpaquePointer?, Unit> {
        packets, refCon, _ ->
    containNativeCallbackFailure { refCon?.asStableRef<AppleMidiInput>()?.get()?.onPackets(packets) }
}

/** CoreMIDI calls this when the setup changes, such as a cable being plugged in or pulled out. */
private val notifyProc = staticCFunction<CPointer<MIDINotification>?, COpaquePointer?, Unit> { notification, refCon ->
    val messageId = notification?.pointed?.messageID ?: return@staticCFunction
    val interesting = messageId == kMIDIMsgSetupChanged ||
        messageId == kMIDIMsgObjectAdded ||
        messageId == kMIDIMsgObjectRemoved
    if (interesting) containNativeCallbackFailure { refCon?.asStableRef<AppleMidiDevices>()?.get()?.onSetupChanged() }
}

private class AppleMidiDevices(private val scope: CoroutineScope) : MidiDevices {

    private val lifecycle = NSRecursiveLock()
    private var shutdownQueued = false
    private val portsState = MutableStateFlow<List<MidiPort>>(emptyList())
    override val ports: StateFlow<List<MidiPort>> = portsState.asStateFlow()

    // Rebuilt whole on every enumeration, so a reader never sees a half-filled map.
    private val endpoints = MutableStateFlow<Map<String, MIDIEndpointRef>>(emptyMap())

    private val openInputs = MutableStateFlow<List<AppleMidiInput>>(emptyList())
    private val openOutputs = MutableStateFlow<List<AppleMidiOutput>>(emptyList())

    // A StateFlow gives an atomic compare-and-set, which is what makes closing twice harmless
    // without pulling in an extra atomics dependency.
    private val closed = MutableStateFlow(false)

    // How many host clock ticks make a second. Read once here, on the caller's thread, so no
    // CoreMIDI thread ever has to work it out.
    private val ticksPerSecond: Long = CVGetHostClockFrequency().toLong()

    private val selfRef = StableRef.create(this)
    private var client: MIDIClientRef = 0u
    private var outputPort: MIDIPortRef = 0u

    // Runs last, so every field above is ready before CoreMIDI can call back into this object.
    private val loop = NativeMidiLoop {
        val clientStatus = memScoped {
            val out = alloc<MIDIClientRefVar>()
            val status = withCfString("KiteMIDI") { name ->
                MIDIClientCreate(name, notifyProc, selfRef.asCPointer(), out.ptr)
            }
            if (status == NO_ERROR) client = out.value
            status
        }
        if (clientStatus != NO_ERROR) {
            selfRef.dispose()
            throw MidiPortException("CoreMIDI would not create a client (OSStatus $clientStatus)")
        }

        val portStatus = memScoped {
            val out = alloc<MIDIPortRefVar>()
            val status = withCfString("KiteMIDI out") { name ->
                MIDIOutputPortCreate(client, name, out.ptr)
            }
            if (status == NO_ERROR) outputPort = out.value
            status
        }
        if (portStatus != NO_ERROR) {
            // No run-loop callbacks have run yet, but Create still owns the notification ref.
            var disposal = MIDIClientDispose(client)
            repeat(2) { if (disposal != NO_ERROR) disposal = MIDIClientDispose(client) }
            if (disposal == NO_ERROR) selfRef.dispose() else {
                println("KiteMIDI client disposal failed (OSStatus $disposal); native reference retained")
            }
            throw MidiPortException("CoreMIDI would not create an output port (OSStatus $portStatus)")
        }

    }

    init { refresh() }

    override fun refresh() {
        if (closed.value) return
        val found = ArrayList<MidiPort>()
        val byId = HashMap<String, MIDIEndpointRef>()

        // A CoreMIDI source sends to us, so it is our INPUT. A destination is one we write to.
        var index = 0uL
        val sources = MIDIGetNumberOfSources()
        while (index < sources) {
            val endpoint = MIDIGetSource(index)
            index++
            if (endpoint == 0u) continue
            val port = describe(endpoint, MidiDirection.INPUT)
            found.add(port)
            byId[port.id] = endpoint
        }

        index = 0uL
        val destinations = MIDIGetNumberOfDestinations()
        while (index < destinations) {
            val endpoint = MIDIGetDestination(index)
            index++
            if (endpoint == 0u) continue
            val port = describe(endpoint, MidiDirection.OUTPUT)
            found.add(port)
            byId[port.id] = endpoint
        }

        lifecycle.locked {
            if (!closed.value) {
                endpoints.value = byId
                portsState.value = found
            }
        }
    }

    override suspend fun openInput(port: MidiPort, onMessage: (TimedMidiMessage) -> Unit): MidiInput = lifecycle.locked {
        if (closed.value) throw MidiPortException("CoreMIDI is closed")
        if (port.direction != MidiDirection.INPUT) {
            throw MidiPortException("${port.name} is an output port, so it cannot be read from")
        }
        val endpoint = endpointFor(port)
        val input = AppleMidiInput(this, port, endpoint, ticksPerSecond, onMessage)
        // Adopt before native creation/connection, so failed cleanup still pins the client.
        openInputs.update { it + input }

        try {
            val status = memScoped {
                val out = alloc<MIDIPortRefVar>()
                val result = withCfString("KiteMIDI in ${port.name}") { name ->
                    MIDIInputPortCreate(client, name, readProc, input.refCon, out.ptr)
                }
                if (result == NO_ERROR) input.attach(out.value)
                result
            }
            if (status != NO_ERROR) {
                input.close()
                throw MidiPortException("Could not open ${port.displayName} for reading (OSStatus $status)")
            }

            val connected = MIDIPortConnectSource(input.portRef, endpoint, null)
            if (connected != NO_ERROR) {
                input.close()
                throw MidiPortException("Could not connect to ${port.displayName} (OSStatus $connected)")
            }

            input
        } catch (error: Throwable) {
            input.close()
            throw error
        }
    }

    override suspend fun openOutput(port: MidiPort): MidiOutput = lifecycle.locked {
        if (closed.value) throw MidiPortException("CoreMIDI is closed")
        if (port.direction != MidiDirection.OUTPUT) {
            throw MidiPortException("${port.name} is an input port, so it cannot be written to")
        }
        val output = AppleMidiOutput(this, port, outputPort, endpointFor(port))
        openOutputs.update { it + output }
        output
    }

    override fun close() {
        val handles = lifecycle.locked {
            if (!closed.compareAndSet(expect = false, update = true)) return
            portsState.value = emptyList()
            endpoints.value = emptyMap()
            openInputs.value to openOutputs.value
        }
        handles.first.forEach { it.close() }
        handles.second.forEach { it.close() }
        lifecycle.locked { finishCloseIfReady() }
    }

    private fun finishCloseIfReady() {
        if (!closed.value || shutdownQueued || openInputs.value.isNotEmpty() || openOutputs.value.isNotEmpty()) return
        shutdownQueued = true
        loop.dispose("output port", { if (outputPort == 0u) NO_ERROR else MIDIPortDispose(outputPort) }) {
            outputPort = 0u
            loop.dispose("client", { if (client == 0u) NO_ERROR else MIDIClientDispose(client) }) {
                client = 0u
                selfRef.dispose()
                loop.stop()
            }
        }
    }

    fun disposeInput(port: MIDIPortRef, endpoint: MIDIEndpointRef, onDisposed: () -> Unit) {
        loop.dispose("input port", {
            if (port == 0u) NO_ERROR else {
                MIDIPortDisconnectSource(port, endpoint)
                MIDIPortDispose(port)
            }
        }, onDisposed)
    }

    // Closing these devices closes every output, so the message names the output.
    fun sendIfOpen(port: MidiPort, action: () -> Unit) = lifecycle.locked {
        if (closed.value) throw MidiPortException("the output to ${port.displayName} is closed")
        action()
    }

    /** Called from [notifyProc]. Enumerating asks CoreMIDI a lot, so it goes on [scope]. */
    fun onSetupChanged() {
        if (closed.value) return
        scope.launch { refresh() }
    }

    fun forget(input: AppleMidiInput) {
        lifecycle.locked {
            openInputs.update { it - input }
            finishCloseIfReady()
        }
    }

    fun forget(output: AppleMidiOutput) {
        lifecycle.locked {
            openOutputs.update { it - output }
            finishCloseIfReady()
        }
    }

    private fun endpointFor(port: MidiPort): MIDIEndpointRef {
        endpoints.value[port.id]?.let { return it }
        // The caller may be holding a list from before something was plugged in. Look once more
        // before giving up.
        refresh()
        return endpoints.value[port.id]
            ?: throw MidiPortException("${port.displayName} is no longer attached")
    }

    private fun describe(endpoint: MIDIEndpointRef, direction: MidiDirection): MidiPort {
        val name = stringProperty(endpoint, kMIDIPropertyDisplayName)
            ?: stringProperty(endpoint, kMIDIPropertyName)
            ?: "MIDI port"
        val prefix = if (direction == MidiDirection.INPUT) "in" else "out"
        val unique = intProperty(endpoint, kMIDIPropertyUniqueID)
        // The unique id survives a replug, so prefer it. The endpoint number does not, and is
        // only here so a port without one still gets an id.
        val id = if (unique != null) "$prefix:$unique" else "$prefix:ref:$endpoint"
        return MidiPort(
            id = id,
            name = name,
            manufacturer = stringProperty(endpoint, kMIDIPropertyManufacturer),
            direction = direction,
            transport = transportOf(endpoint),
        )
    }

    private fun transportOf(endpoint: MIDIEndpointRef): MidiTransport {
        // An endpoint an app made for itself has no driver behind it, which is what VIRTUAL
        // means here. Anything else is guessed from the driver bundle's name.
        val driver = stringProperty(endpoint, kMIDIPropertyDriverOwner) ?: return MidiTransport.VIRTUAL
        return when {
            driver.contains("USB", ignoreCase = true) -> MidiTransport.USB
            driver.contains("Bluetooth", ignoreCase = true) -> MidiTransport.BLUETOOTH
            driver.contains("BTLE", ignoreCase = true) -> MidiTransport.BLUETOOTH
            driver.contains("RTP", ignoreCase = true) -> MidiTransport.NETWORK
            driver.contains("Network", ignoreCase = true) -> MidiTransport.NETWORK
            driver.contains("IAC", ignoreCase = true) -> MidiTransport.VIRTUAL
            else -> MidiTransport.UNKNOWN
        }
    }
}

private class AppleMidiInput(
    private val owner: AppleMidiDevices,
    override val port: MidiPort,
    private val endpoint: MIDIEndpointRef,
    private val ticksPerSecond: Long,
    onMessage: (TimedMidiMessage) -> Unit,
) : MidiInput {

    // Set just before the parser is fed and read by the parser's own callback, which runs
    // inside that same feed call on the same thread.
    private var packetNanos: Long = 0

    private val parserLock = NSRecursiveLock()
    private val parser = MidiStreamParser { message -> if (!closed.value) onMessage(TimedMidiMessage(message, packetNanos)) }

    private val selfRef = StableRef.create(this)
    private val closed = MutableStateFlow(false)

    var portRef: MIDIPortRef = 0u
        private set

    val refCon: COpaquePointer get() = selfRef.asCPointer()

    fun attach(created: MIDIPortRef) {
        portRef = created
    }

    /**
     * Turns one bundle of packets into messages.
     *
     * This runs on CoreMIDI's high-priority delivery thread. A per-input lock serializes
     * parser state and timestamp updates; callbacks run inside that lock and must return
     * promptly. Close sets the closed flag inside the same lock, so it waits for a callback on
     * another thread. The lock is recursive, so a callback may still close its own input.
     */
    fun onPackets(list: CPointer<MIDIPacketList>?) = parserLock.locked {
        if (list == null || closed.value) return@locked
        var remaining = list.pointed.numPackets.toInt()
        var packet: CPointer<MIDIPacket>? = list.pointed.packet
        while (remaining > 0 && packet != null) {
            val current = packet.pointed
            val length = current.length.toInt()
            if (length > 0) {
                packetNanos = hostTicksToNanos(current.timeStamp, ticksPerSecond)
                val bytes = ByteArray(length)
                val data = current.data
                for (i in 0 until length) bytes[i] = data[i].toByte()
                try { parser.feed(bytes) } catch (error: Throwable) {
                    parser.reset()
                    throw error
                }
            }
            packet = MIDIPacketNext(packet)
            remaining--
        }
    }

    override fun close() {
        // Inside the parser lock, so no callback starts after close() returns.
        if (!parserLock.locked { closed.compareAndSet(expect = false, update = true) }) return
        owner.disposeInput(portRef, endpoint) {
            portRef = 0u
            // No owner lock is held while disposal waits for callbacks or while this lock drains.
            try { parserLock.locked { parser.reset() } } finally {
                selfRef.dispose()
                owner.forget(this)
            }
        }
    }

}

private class AppleMidiOutput(
    private val owner: AppleMidiDevices,
    override val port: MidiPort,
    private val outputPort: MIDIPortRef,
    private val endpoint: MIDIEndpointRef,
) : MidiOutput {

    private val closed = MutableStateFlow(false)

    /** @throws MidiPortException when CoreMIDI will not take the message. */
    override fun send(message: MidiMessage): Unit = sendRaw(message.toBytes())

    /**
     * Sends [bytes] as one packet. An empty array on an open output sends nothing.
     *
     * @throws MidiPortException when this output is closed, when the bytes are too many for a
     *   single packet, or when CoreMIDI rejects the send.
     */
    override fun sendRaw(bytes: ByteArray) = owner.sendIfOpen(port) {
        if (closed.value) throw MidiPortException("the output to ${port.displayName} is closed")
        if (bytes.isEmpty()) return@sendIfOpen
        if (bytes.size > 65_520) throw MidiPortException("a CoreMIDI send supports at most 65520 bytes; split large SysEx transfers")
        memScoped {
            val listBytes = (PACKET_LIST_OVERHEAD + bytes.size + 3) and 3.inv()
            // MIDIPacketList wants four byte alignment. An array of UInt gives that; an array
            // of Byte does not.
            val storage = allocArray<UIntVar>(listBytes / 4)
            val list = storage.reinterpret<MIDIPacketList>()

            val data = allocArray<UByteVar>(bytes.size)
            for (i in bytes.indices) data[i] = bytes[i].toUByte()

            val first = MIDIPacketListInit(list)
            // A time stamp of zero asks CoreMIDI to send as soon as it can.
            MIDIPacketListAdd(list, listBytes.toULong(), first, 0uL, bytes.size.toULong(), data)
                ?: throw MidiPortException("${bytes.size} bytes do not fit in one packet list")

            val status = MIDISend(outputPort, endpoint, list)
            if (status != NO_ERROR) {
                throw MidiPortException("Sending to ${port.displayName} failed (OSStatus $status)")
            }
        }
    }

    override fun close() {
        if (closed.compareAndSet(expect = false, update = true)) owner.forget(this)
    }
}

/**
 * Turns a CoreMIDI time stamp into nanoseconds.
 *
 * A stamp counts host clock ticks, not nanoseconds, and zero is CoreMIDI's way of saying "now".
 * The division is done in two steps so a long uptime cannot overflow.
 */
private fun hostTicksToNanos(ticks: ULong, ticksPerSecond: Long): Long {
    if (ticksPerSecond <= 0L) return 0L
    val rate = ticksPerSecond.toULong()
    val raw = if (ticks == 0uL) CVGetCurrentHostTime() else ticks
    val seconds = raw / rate
    val rest = raw % rate
    return (seconds * NANOS_PER_SECOND + rest * NANOS_PER_SECOND / rate).toLong()
}

private fun stringProperty(obj: MIDIObjectRef, property: CFStringRef?): String? = memScoped {
    val out = alloc<CFStringRefVar>()
    if (MIDIObjectGetStringProperty(obj, property, out.ptr) != NO_ERROR) return@memScoped null
    val value = out.value ?: return@memScoped null
    // CoreMIDI hands back a string it has already retained. CFBridgingRelease takes that
    // reference over, so there is no separate CFRelease to make.
    (CFBridgingRelease(value) as? String)?.takeIf { it.isNotBlank() }
}

private fun intProperty(obj: MIDIObjectRef, property: CFStringRef?): Int? = memScoped {
    val out = alloc<IntVar>()
    if (MIDIObjectGetIntegerProperty(obj, property, out.ptr) != NO_ERROR) return@memScoped null
    out.value
}

/** Lends [block] a CFString made from [text] and releases it afterwards. */
private inline fun <T> withCfString(text: String, block: (CFStringRef?) -> T): T {
    val ref: CFStringRef? = CFBridgingRetain(text)?.reinterpret()
    try {
        return block(ref)
    } finally {
        if (ref != null) CFRelease(ref)
    }
}

private inline fun <T> NSRecursiveLock.locked(block: () -> T): T {
    lock()
    try { return block() } finally { unlock() }
}

private inline fun containNativeCallbackFailure(block: () -> Unit) {
    try { block() } catch (error: Throwable) {
        try { println("KiteMIDI callback failed; packet dropped: $error") } catch (_: Throwable) { }
    }
}

/** Owns the serviced creator run loop and all deferred native teardown. */
private class NativeMidiLoop(createClient: () -> Unit) {
    private val condition = NSCondition()
    private val work = ArrayDeque<() -> Unit>()
    private var ready = false
    private var stopped = false
    private var failure: Throwable? = null

    init {
        NSThread {
            containNativeCallbackFailure {
                try { createClient() } catch (error: Throwable) { failure = error }
                condition.lock()
                ready = true
                condition.broadcast()
                condition.unlock()
                if (failure == null) {
                    while (true) {
                        condition.lock()
                        val next = work.removeFirstOrNull()
                        val done = stopped && next == null
                        condition.unlock()
                        if (done) break
                        if (next != null) {
                            try { next() } catch (error: Throwable) {
                                println("KiteMIDI cleanup task failed; native references retained: $error")
                            }
                        } else {
                            val result = CFRunLoopRunInMode(kCFRunLoopDefaultMode, 0.05, false)
                            if (result == 1) NSThread.sleepForTimeInterval(0.01)
                        }
                    }
                }
            }
        }.apply { name = "KiteMIDI CoreMIDI"; start() }
        condition.lock()
        while (!ready) condition.wait()
        condition.unlock()
        failure?.let { throw it }
    }

    fun execute(action: () -> Unit) {
        condition.lock()
        try { work.addLast(action) } finally { condition.unlock() }
    }

    fun dispose(label: String, action: () -> Int, onDisposed: () -> Unit) {
        fun attempt(remaining: Int) {
            val status = try { action() } catch (error: Throwable) {
                println("KiteMIDI $label disposal threw; native references retained: $error")
                return
            }
            if (status == NO_ERROR) onDisposed() else if (remaining > 1) {
                NSThread.sleepForTimeInterval(0.01)
                execute { attempt(remaining - 1) }
            } else println("KiteMIDI $label disposal failed (OSStatus $status); native references retained")
        }
        execute { attempt(3) }
    }

    fun stop() {
        condition.lock()
        try { stopped = true } finally { condition.unlock() }
    }
}
