package io.github.yuroyami.kitemidi.io

import io.github.yuroyami.kitemidi.MidiMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import javax.sound.midi.MidiDevice
import javax.sound.midi.MidiSystem
import javax.sound.midi.Receiver
import javax.sound.midi.Sequencer
import javax.sound.midi.Synthesizer
import javax.sound.midi.Transmitter
import javax.sound.midi.MidiMessage as JavaxMidiMessage

/** How long to wait between two readings of the device list. */
private const val POLL_INTERVAL_MILLIS: Long = 1000L

/**
 * The timestamp that means "play this as soon as you can".
 *
 * Any other value a receiver is given is microseconds on that one device's own clock, which
 * starts again at zero every time the device is opened and cannot be compared with another
 * device's. Scheduling against it would buy nothing here.
 */
private const val SEND_NOW: Long = -1L

/**
 * The MIDI ports of this machine, through javax.sound.midi.
 *
 * javax has no callback for a cable being plugged in or pulled out, so this reads the device
 * list again every second on [scope]. [MidiDevices.refresh] reads it straight away instead of
 * waiting for the next reading.
 *
 * [scope] stays yours. Cancelling it stops the readings. [MidiDevices.close] does that too, and
 * also closes every port this opened.
 */
public fun jvmMidiDevices(scope: CoroutineScope): MidiDevices = JvmMidiDevices(scope)

/** The javax.sound.midi backing for [MidiDevices]. */
internal class JvmMidiDevices(
    scope: CoroutineScope,
    private val deviceInfos: () -> Array<MidiDevice.Info> = MidiSystem::getMidiDeviceInfo,
    private val deviceFor: (MidiDevice.Info) -> MidiDevice = MidiSystem::getMidiDevice,
) : MidiDevices {

    private val portsState = MutableStateFlow<List<MidiPort>>(emptyList())
    override val ports: StateFlow<List<MidiPort>> = portsState.asStateFlow()

    private val scansState = MutableStateFlow(0)

    /** How many readings of the device list have finished. Tests wait on this. */
    internal val scansCompleted: StateFlow<Int> = scansState.asStateFlow()

    private val refreshRequests = Channel<Unit>(Channel.CONFLATED)
    private val closed = AtomicBoolean(false)

    private val lock = Any()
    private val openDevices = HashMap<String, OpenDevice>()
    private val openHandles = LinkedHashSet<AutoCloseable>()

    // Reading the device list is a blocking native call, so it stays off the caller's thread.
    private val pollJob: Job = scope.launch(Dispatchers.IO) {
        while (isActive) {
            rescan()
            withTimeoutOrNull(POLL_INTERVAL_MILLIS) { refreshRequests.receive() }
        }
    }

    override fun refresh() {
        if (closed.get()) return
        refreshRequests.trySend(Unit)
    }

    override suspend fun openInput(port: MidiPort, onMessage: (TimedMidiMessage) -> Unit): MidiInput {
        if (port.direction != MidiDirection.INPUT) {
            throw MidiPortException("${port.name} was asked to be an input but it is an ${port.direction}")
        }
        return openOnIo {
            val held = acquire(port)
            var handle: JvmMidiInput? = null
            var transmitter: Transmitter? = null
            try {
                transmitter = held.device.transmitter
                JvmMidiInput(port, held, transmitter, onMessage).also {
                    handle = it
                    register(it)
                }
            } catch (error: Throwable) {
                if (handle != null) handle?.close() else {
                    runCatching { transmitter?.close() }
                    release(held.key)
                }
                throw MidiPortException("could not read from ${port.name}", error)
            }
        }
    }

    override suspend fun openOutput(port: MidiPort): MidiOutput {
        if (port.direction != MidiDirection.OUTPUT) {
            throw MidiPortException("${port.name} was asked to be an output but it is an ${port.direction}")
        }
        return openOnIo {
            val held = acquire(port)
            var handle: JvmMidiOutput? = null
            try {
                JvmMidiOutput(port, held, held.device.receiver).also {
                    handle = it
                    register(it)
                }
            } catch (error: Throwable) {
                if (handle != null) handle?.close() else release(held.key)
                throw MidiPortException("could not write to ${port.name}", error)
            }
        }
    }

    // A result can be discarded while withContext dispatches back to a cancelled caller.
    private suspend fun <T : AutoCloseable> openOnIo(create: () -> T): T {
        var created: T? = null
        try {
            return withContext(Dispatchers.IO) { create().also { created = it } }
        } catch (error: Throwable) {
            runCatching { created?.close() }
            throw error
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        pollJob.cancel()
        val handles = synchronized(lock) { openHandles.toList() }
        for (handle in handles) runCatching { handle.close() }
    }

    private fun rescan() {
        try {
            val found = scanPorts()
            synchronized(lock) { if (!closed.get()) portsState.value = found }
        } catch (error: Exception) {
            // A reading that fails leaves the previous list alone. The next one will try again.
        }
        scansState.update { it + 1 }
    }

    private fun scanPorts(): List<MidiPort> {
        val found = ArrayList<MidiPort>()
        val ordinals = HashMap<String, Int>()
        for (info in deviceInfos()) {
            // The key is counted before the lookup, so a device that fails to answer does not
            // shift the numbering of the ones after it.
            val key = deviceKey(info, ordinals)
            val device = try {
                deviceFor(info)
            } catch (error: Exception) {
                continue
            }
            val maker = info.vendor?.takeIf { it.isNotBlank() }
            val transport = transportOf(device)
            // -1 means "as many as you like" and 0 means "this device cannot do that at all".
            if (device.maxTransmitters != 0) {
                found += MidiPort(portId(key, MidiDirection.INPUT), info.name, maker, MidiDirection.INPUT, transport)
            }
            if (device.maxReceivers != 0) {
                found += MidiPort(portId(key, MidiDirection.OUTPUT), info.name, maker, MidiDirection.OUTPUT, transport)
            }
        }
        return found
    }

    /**
     * Finds and opens the device behind [port], counting how many handles are on it.
     *
     * One device can carry both an input and an output port, and javax closes the whole device
     * at once, so the last handle to go is the one that closes it.
     */
    private fun acquire(port: MidiPort): OpenDevice = synchronized(lock) {
        if (closed.get()) throw MidiPortException("these MidiDevices are closed")
        val key = keyOf(port)
        val already = openDevices[key]
        if (already != null) {
            already.handles++
            already
        } else {
            val device = resolve(port, key)
            try {
                device.open()
            } catch (error: Exception) {
                throw MidiPortException("could not open ${port.name}", error)
            }
            val opened = OpenDevice(key, device)
            opened.handles = 1
            openDevices[key] = opened
            opened
        }
    }

    private fun release(key: String) {
        synchronized(lock) {
            val open = openDevices[key] ?: return
            open.handles--
            if (open.handles > 0) return
            openDevices.remove(key)
            runCatching { open.device.close() }
        }
    }

    /**
     * Looks the device up again by name, from a list read just now.
     *
     * A `MidiDevice.Info` must never be kept between two readings. The JDK rebuilds its cache
     * only when the number of devices changes, and marks an Info whose device has gone as dead,
     * after which asking for its device throws.
     */
    private fun resolve(port: MidiPort, key: String): MidiDevice {
        val ordinals = HashMap<String, Int>()
        for (info in deviceInfos()) {
            val candidate = deviceKey(info, ordinals)
            if (candidate != key) continue
            val device = try {
                deviceFor(info)
            } catch (error: Exception) {
                throw MidiPortException("${port.name} is listed but cannot be reached", error)
            }
            val supported = when (port.direction) {
                MidiDirection.INPUT -> device.maxTransmitters != 0
                MidiDirection.OUTPUT -> device.maxReceivers != 0
            }
            if (!supported) throw MidiPortException("${port.name} cannot act as an ${port.direction}")
            return device
        }
        throw MidiPortException("no device on this machine matches port ${port.id}")
    }

    private fun register(handle: AutoCloseable) {
        synchronized(lock) {
            if (closed.get()) throw MidiPortException("these MidiDevices are closed")
            openHandles += handle
        }
    }

    private fun unregister(handle: AutoCloseable) {
        synchronized(lock) { openHandles -= handle }
    }

    /** One javax device that at least one open port is using. */
    private class OpenDevice(val key: String, val device: MidiDevice) {
        var handles: Int = 0
    }

    private inner class JvmMidiInput(
        override val port: MidiPort,
        private val device: OpenDevice,
        private val transmitter: Transmitter,
        onMessage: (TimedMidiMessage) -> Unit,
    ) : MidiInput, AutoCloseable {

        private val parserLock = Any()
        private var arrivedAtNanos: Long = 0L

        // javax hands over whole messages, so no reassembly is needed here. The bytes go
        // through the parser anyway, so every platform reaches a MidiMessage the same way.
        private val parser = MidiStreamParser { message ->
            onMessage(TimedMidiMessage(message, arrivedAtNanos))
        }
        private val shut = AtomicBoolean(false)

        init {
            transmitter.receiver = object : Receiver {
                override fun send(message: JavaxMidiMessage, timeStamp: Long) {
                    val bytes = message.message ?: return
                    synchronized(parserLock) {
                        // The host clock: javax only offers getMicrosecondPosition, which
                        // returns -1 for a device with no clock. The parser calls back inside
                        // feed, so it sees this stamp.
                        arrivedAtNanos = System.nanoTime()
                        parser.feed(bytes)
                    }
                }

                override fun close() = Unit
            }
        }

        override fun close() {
            if (!shut.compareAndSet(false, true)) return
            runCatching { transmitter.close() }
            release(device.key)
            unregister(this)
        }
    }

    private inner class JvmMidiOutput(
        override val port: MidiPort,
        private val device: OpenDevice,
        private val receiver: Receiver,
    ) : MidiOutput, AutoCloseable {

        private val shut = AtomicBoolean(false)

        override fun send(message: MidiMessage) {
            sendRaw(message.toBytes())
        }

        override fun sendRaw(bytes: ByteArray) {
            // After close there is nothing to write to. Dropping beats throwing at a caller who
            // cannot know a device was pulled out a moment ago.
            if (shut.get() || bytes.isEmpty()) return
            receiver.send(RawMidiMessage(bytes.copyOf()), SEND_NOW)
        }

        override fun close() {
            if (!shut.compareAndSet(false, true)) return
            runCatching { receiver.close() }
            release(device.key)
            unregister(this)
        }
    }
}

/**
 * Names one device well enough to find it again in a later reading.
 *
 * Two identical instruments give the same name, maker and version, so [ordinals] numbers them in
 * the order the platform lists them.
 */
private fun deviceKey(info: MidiDevice.Info, ordinals: MutableMap<String, Int>): String {
    val base = "jvm|${info.name}|${info.vendor}|${info.version}"
    val ordinal = ordinals.getOrElse(base) { 0 }
    ordinals[base] = ordinal + 1
    return "$base|$ordinal"
}

private fun portId(deviceKey: String, direction: MidiDirection): String =
    "$deviceKey|${if (direction == MidiDirection.INPUT) "in" else "out"}"

private fun keyOf(port: MidiPort): String = port.id.substringBeforeLast('|')

/**
 * javax never says how a port is attached, so a real socket can only be [MidiTransport.UNKNOWN].
 * A synthesiser or a sequencer is software, which is the one case worth naming.
 */
private fun transportOf(device: MidiDevice): MidiTransport = when (device) {
    is Synthesizer, is Sequencer -> MidiTransport.VIRTUAL
    else -> MidiTransport.UNKNOWN
}

/**
 * Bytes handed to javax as the message type a `Receiver` expects.
 *
 * This library already built the bytes, so rebuilding them as a `ShortMessage` or a
 * `SysexMessage` would only add somewhere to get them wrong.
 */
private class RawMidiMessage(bytes: ByteArray) : JavaxMidiMessage(bytes) {
    override fun clone(): Any = RawMidiMessage(message)
}
