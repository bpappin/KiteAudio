package io.github.yuroyami.kiteaudio.midi.devices

import io.github.yuroyami.kiteaudio.midi.MidiMessage
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
                    release(held)
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
                if (handle != null) handle?.close() else release(held)
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
            // One lock for the reading and the loss check, so an open between them cannot see a mix.
            synchronized(lock) {
                val infos = deviceInfos()
                val found = scanPorts(infos)
                loseVanishedDevices(infos)
                if (!closed.get()) portsState.value = found
            }
        } catch (error: Exception) {
            // A reading that fails leaves the previous list alone. The next one will try again.
        }
        scansState.update { it + 1 }
    }

    private fun scanPorts(infos: Array<MidiDevice.Info>): List<MidiPort> {
        val found = ArrayList<MidiPort>()
        val ordinals = HashMap<String, Int>()
        for (info in infos) {
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
     * Marks an open device as lost when its key is gone or its group of identical devices got smaller.
     *
     * When the device count changes, the JDK reuses the first old Info with equal strings and its
     * device object, so it cannot say which twin left. Closing the javax device lets the next open
     * reach the hardware that is there now.
     */
    private fun loseVanishedDevices(infos: Array<MidiDevice.Info>) {
        val groupSizes = HashMap<String, Int>()
        val keys = infos.mapTo(HashSet()) { deviceKey(it, groupSizes) }
        for (open in openDevices.values.toList()) {
            val size = groupSizes[groupOf(open.key)] ?: 0
            if (open.key in keys && size >= open.groupSize) {
                open.groupSize = size
                continue
            }
            open.lost = true
            openDevices.remove(open.key)
            runCatching { open.device.close() }
        }
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
            val (device, groupSize) = resolve(port, key)
            try {
                device.open()
            } catch (error: Exception) {
                throw MidiPortException("could not open ${port.name}", error)
            }
            val opened = OpenDevice(key, device, groupSize)
            opened.handles = 1
            openDevices[key] = opened
            opened
        }
    }

    // A lost device is already closed and out of the map, where a newer device may now use its key.
    private fun release(open: OpenDevice) {
        synchronized(lock) {
            if (open.lost) return
            open.handles--
            if (open.handles > 0) return
            openDevices.remove(open.key)
            runCatching { open.device.close() }
        }
    }

    /**
     * Looks the device up again by name, from a list read just now.
     *
     * A `MidiDevice.Info` must never be kept between two readings. The JDK rebuilds its cache
     * only when the number of devices changes, and marks an Info whose device has gone as dead,
     * after which asking for its device throws.
     *
     * Also returns the size of the device's group of identical devices in that list.
     */
    private fun resolve(port: MidiPort, key: String): Pair<MidiDevice, Int> {
        val ordinals = HashMap<String, Int>()
        var match: MidiDevice.Info? = null
        for (info in deviceInfos()) {
            if (deviceKey(info, ordinals) == key) match = info
        }
        val info = match ?: throw MidiPortException("no device on this machine matches port ${port.id}")
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
        return device to (ordinals[groupOf(key)] ?: 0)
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

    /** One javax device that at least one open port is using. Guarded by [lock]. */
    private class OpenDevice(val key: String, val device: MidiDevice, var groupSize: Int) {
        var handles: Int = 0

        // Handles read this without the lock, on every send and every incoming message.
        @Volatile var lost: Boolean = false
    }

    private inner class JvmMidiInput(
        override val port: MidiPort,
        private val device: OpenDevice,
        private val transmitter: Transmitter,
        onMessage: (TimedMidiMessage) -> Unit,
    ) : MidiInput, AutoCloseable {

        private val parserLock = Any()
        private var arrivedAtNanos: Long = 0L

        // Guarded by parserLock, so close() cannot slip in between this check and a callback.
        private var shut = false

        // javax hands over whole messages, so no reassembly is needed here. The bytes go
        // through the parser anyway, so every platform reaches a MidiMessage the same way.
        private val parser = MidiStreamParser { message ->
            if (!shut && !device.lost) onMessage(TimedMidiMessage(message, arrivedAtNanos))
        }

        init {
            transmitter.receiver = object : Receiver {
                override fun send(message: JavaxMidiMessage, timeStamp: Long) {
                    val bytes = message.message ?: return
                    synchronized(parserLock) {
                        if (shut || device.lost) return
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
            synchronized(parserLock) {
                if (shut) return
                shut = true
            }
            runCatching { transmitter.close() }
            release(device)
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
            if (shut.get()) throw MidiPortException("the output to ${port.name} is closed")
            if (device.lost) throw lostError()
            if (bytes.isEmpty()) return
            try {
                receiver.send(RawMidiMessage(bytes.copyOf()), SEND_NOW)
            } catch (error: IllegalStateException) {
                // javax throws this when the device closed under the send: a loss, or a close() on another thread.
                if (device.lost) throw lostError(error)
                if (shut.get()) throw MidiPortException("the output to ${port.name} is closed", error)
                throw error
            }
        }

        private fun lostError(cause: Throwable? = null) =
            MidiPortException("the device behind ${port.name} was lost", cause)

        override fun close() {
            if (!shut.compareAndSet(false, true)) return
            runCatching { receiver.close() }
            release(device)
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

/** The key without its ordinal: what identical devices share. */
private fun groupOf(deviceKey: String): String = deviceKey.substringBeforeLast('|')

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
