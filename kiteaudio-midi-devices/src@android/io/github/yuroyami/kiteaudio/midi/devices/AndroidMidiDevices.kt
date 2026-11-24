package io.github.yuroyami.kiteaudio.midi.devices

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiInputPort
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import io.github.yuroyami.kiteaudio.midi.MidiMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resumeWithException

/**
 * Builds the Android backend, which reads the MIDI ports through `android.media.midi`.
 *
 * A USB instrument needs no permission at all: plug it in and it appears in [MidiDevices.ports].
 * A phone without MIDI hardware support reports an empty list forever instead of failing.
 *
 * [scope] decides how long this lives. Cancelling it closes everything, exactly as
 * [MidiDevices.close] does. The first scan happens while this call runs, so [MidiDevices.ports]
 * is right straight away. Every later scan runs on a private thread, and incoming messages
 * arrive on the framework's own reader thread, so a caller that touches the user interface must
 * hop threads itself.
 *
 * Bluetooth Low Energy needs one extra step, described on [AndroidMidiDevices.openBluetoothMidiDevice].
 * To reach it, keep the concrete type:
 *
 * ```kotlin
 * val devices = androidMidiDevices(context, scope) as AndroidMidiDevices
 * ```
 *
 * @param context any context. Only the application context is kept, so this cannot leak an activity.
 * @param scope the life of this object, as described above.
 */
public fun androidMidiDevices(context: Context, scope: CoroutineScope): MidiDevices =
    AndroidMidiDevices(context.applicationContext, scope)

/**
 * A Bluetooth Low Energy MIDI device held open.
 *
 * The connection is what keeps the device visible. See
 * [AndroidMidiDevices.openBluetoothMidiDevice] for the rule.
 */
public interface BluetoothMidiConnection {

    /** The ports this device offers. They are also in [MidiDevices.ports]. */
    public val ports: List<MidiPort>

    /** Releases the device. Its ports leave [MidiDevices.ports] shortly after. */
    public fun close()
}

/**
 * The Android backend. Build it with [androidMidiDevices].
 *
 * Everything on [MidiDevices] works here. The extra piece is [openBluetoothMidiDevice], which
 * Android has no other way to reach.
 */
public class AndroidMidiDevices internal constructor(
    context: Context,
    scope: CoroutineScope,
) : MidiDevices {

    // Null on a phone that does not carry the android.software.midi feature. Every path below
    // treats that as "no ports, ever" rather than as a failure.
    private val manager: MidiManager? = context.getSystemService(Context.MIDI_SERVICE) as MidiManager?

    // One private thread takes every framework callback and every port scan, so scans are
    // serialised and no binder call lands on the caller's thread.
    private val thread = HandlerThread("kiteaudio-midi-android-midi").apply { start() }
    private val handler = Handler(thread.looper)

    private val portState = MutableStateFlow<List<MidiPort>>(emptyList())
    private val refs = ConcurrentHashMap<String, PortRef>()
    private val openHandles = mutableSetOf<AutoCloseable>()
    private var closed = false

    private val deviceCallback = object : MidiManager.DeviceCallback() {
        override fun onDeviceAdded(device: MidiDeviceInfo) {
            refresh()
        }

        override fun onDeviceRemoved(device: MidiDeviceInfo) {
            refresh()
        }
    }

    init {
        if (manager != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                manager.registerDeviceCallback(
                    MidiManager.TRANSPORT_MIDI_BYTE_STREAM,
                    Executor { command -> handler.post(command) },
                    deviceCallback,
                )
            } else {
                registerLegacyCallback(manager)
            }
            // One quick binder call, so the list is right before the caller ever reads it.
            updatePorts()
        }
        scope.coroutineContext[Job]?.invokeOnCompletion { close() }
    }

    /** Every port currently attached. Empty on a phone with no MIDI support. */
    override val ports: StateFlow<List<MidiPort>> = portState.asStateFlow()

    /**
     * Scans for ports now.
     *
     * Android reports plugging and unplugging on its own, so this is rarely needed. The scan
     * runs on a private thread and [ports] updates when it finishes.
     */
    override fun refresh() {
        handler.post { updatePorts() }
    }

    /**
     * Opens [port] for reading and calls [onMessage] for every message that arrives.
     *
     * Each open input gets its own [MidiStreamParser], so a message split across two reads is
     * still delivered whole. [onMessage] runs on the framework's reader thread.
     *
     * @throws MidiPortException when the port is gone, busy, or not an input.
     */
    override suspend fun openInput(port: MidiPort, onMessage: (TimedMidiMessage) -> Unit): MidiInput {
        val ref = resolve(port, MidiDirection.INPUT)
        val device = openDevice(ref.info)
        // Reading means opening what Android calls an output port. See the note in portRef.
        val readPort = device.openReadPort(ref)
        val receiver = ParsingReceiver(onMessage)
        try {
            readPort.connect(receiver)
        } catch (error: Exception) {
            readPort.closeQuietly()
            device.closeQuietly()
            throw MidiPortException("could not connect ${port.displayName} for reading", error)
        }
        val input = AndroidMidiInput(port, device, readPort, receiver, ::forget)
        remember(input)
        return input
    }

    /**
     * Opens [port] for writing.
     *
     * @throws MidiPortException when the port is gone, busy, or not an output.
     */
    override suspend fun openOutput(port: MidiPort): MidiOutput {
        val ref = resolve(port, MidiDirection.OUTPUT)
        val device = openDevice(ref.info)
        // Writing means opening what Android calls an input port. See the note in portRef.
        val writePort = device.openWritePort(ref)
        val output = AndroidMidiOutput(port, device, writePort, ::forget)
        remember(output)
        return output
    }

    /**
     * Opens a Bluetooth Low Energy MIDI device so its ports join [ports].
     *
     * A BLE MIDI device is missing from the MIDI service until an app opens it this way, and it
     * vanishes again the moment the connection is dropped, including when the garbage collector
     * drops it. The returned [BluetoothMidiConnection] is that connection: hold it for as long
     * as you want the ports, and close it when you are done. This object keeps the device
     * referenced until then, and [close] releases it too.
     *
     * Finding the [device] is the app's job. This library never scans, and scanning needs the
     * BLUETOOTH_SCAN and BLUETOOTH_CONNECT permissions on API 31 and above, which the app must
     * declare and request itself.
     *
     * @throws MidiPortException when the device cannot be opened.
     */
    public suspend fun openBluetoothMidiDevice(device: BluetoothDevice): BluetoothMidiConnection {
        val opened = awaitOpen("the Bluetooth MIDI device") { manager, listener ->
            manager.openBluetoothDevice(device, listener, handler)
        }
        val info = opened.info
        // Register the new ports before returning, so they can be opened without waiting for
        // the scan that the framework callback will trigger anyway.
        val fresh = info.ports.map { portRef(info, it) }
        fresh.forEach { refs[it.port.id] = it }
        refresh()
        val connection = AndroidBluetoothConnection(opened, fresh.map { it.port }, ::forget)
        remember(connection)
        return connection
    }

    /** Closes every port this opened, releases any Bluetooth device, and stops watching. */
    override fun close() {
        synchronized(openHandles) {
            if (closed) return
            closed = true
        }
        manager?.unregisterDeviceCallback(deviceCallback)
        val handles = synchronized(openHandles) { openHandles.toList() }
        handles.forEach { it.closeQuietly() }
        synchronized(openHandles) { openHandles.clear() }
        refs.clear()
        portState.value = emptyList()
        thread.quitSafely()
    }

    private fun remember(handle: AutoCloseable) {
        val alreadyClosed = synchronized(openHandles) {
            if (!closed) openHandles.add(handle)
            closed
        }
        if (alreadyClosed) {
            handle.closeQuietly()
            throw MidiPortException("these MidiDevices are closed")
        }
    }

    private fun forget(handle: AutoCloseable) {
        synchronized(openHandles) { openHandles.remove(handle) }
    }

    private fun updatePorts() {
        val manager = manager ?: return
        val infos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            manager.getDevicesForTransport(MidiManager.TRANSPORT_MIDI_BYTE_STREAM).toList()
        } else {
            legacyDevices(manager)
        }
        val found = ArrayList<MidiPort>()
        val fresh = HashMap<String, PortRef>()
        for (info in infos) {
            for (portInfo in info.ports) {
                val ref = portRef(info, portInfo)
                fresh[ref.port.id] = ref
                found.add(ref.port)
            }
        }
        // Add before pruning, so a port that survived the scan is never missing for an instant.
        synchronized(openHandles) {
            if (closed) return
            refs.putAll(fresh)
            refs.keys.retainAll(fresh.keys)
            portState.value = found
        }
    }

    @Suppress("DEPRECATION")
    private fun legacyDevices(manager: MidiManager): List<MidiDeviceInfo> = manager.devices.toList()

    @Suppress("DEPRECATION")
    private fun registerLegacyCallback(manager: MidiManager) {
        manager.registerDeviceCallback(deviceCallback, handler)
    }

    private fun resolve(port: MidiPort, wanted: MidiDirection): PortRef {
        if (manager == null) throw MidiPortException("this phone has no MIDI support")
        if (port.direction != wanted) {
            throw MidiPortException("${port.displayName} is not an ${wanted.name.lowercase()} port")
        }
        // Every port handed out came from a scan, and that scan filled this map, so a miss here
        // means the device has been unplugged since.
        return refs[port.id] ?: throw MidiPortException("${port.displayName} is no longer attached")
    }

    private suspend fun openDevice(info: MidiDeviceInfo): MidiDevice =
        awaitOpen("MIDI device ${info.id}") { manager, listener ->
            manager.openDevice(info, listener, handler)
        }

    /** Bridges the framework's callback style of opening onto a suspend function. */
    private suspend fun awaitOpen(
        label: String,
        start: (MidiManager, MidiManager.OnDeviceOpenedListener) -> Unit,
    ): MidiDevice {
        val manager = manager ?: throw MidiPortException("this phone has no MIDI support")
        synchronized(openHandles) {
            if (closed) throw MidiPortException("these MidiDevices are closed")
        }
        return suspendCancellableCoroutine { continuation ->
            val listener = MidiManager.OnDeviceOpenedListener { device ->
                when {
                    // A null device is how the framework says the open failed.
                    device == null -> continuation.resumeWithException(
                        MidiPortException("Android could not open $label"),
                    )
                    // The caller may have given up while the open was still in flight.
                    !continuation.isActive -> device.closeQuietly()
                    else -> continuation.resume(device) { _, undelivered, _ -> undelivered.closeQuietly() }
                }
            }
            start(manager, listener)
        }
    }
}

/** One port of one device, with what is needed to open it again. */
private class PortRef(val port: MidiPort, val info: MidiDeviceInfo, val portNumber: Int)

private fun portRef(info: MidiDeviceInfo, portInfo: MidiDeviceInfo.PortInfo): PortRef {
    // THE DIRECTION TRAP. Android names a port after what it does for the instrument, not for
    // us. TYPE_OUTPUT is an output of the instrument, so it is what this app reads: INPUT here.
    // TYPE_INPUT is an input to the instrument, so it is what this app writes: OUTPUT here.
    // Reading uses device.openOutputPort, writing uses device.openInputPort.
    val direction = if (portInfo.type == MidiDeviceInfo.PortInfo.TYPE_OUTPUT) {
        MidiDirection.INPUT
    } else {
        MidiDirection.OUTPUT
    }
    val properties = info.properties
    val deviceName = properties.getString(MidiDeviceInfo.PROPERTY_NAME)
        ?: properties.getString(MidiDeviceInfo.PROPERTY_PRODUCT)
        ?: "MIDI device ${info.id}"
    val portName = portInfo.name
    val name = if (portName.isNullOrBlank() || portName == deviceName) deviceName else "$deviceName $portName"
    val port = MidiPort(
        // Port numbers restart at zero per type, so the type belongs in the id.
        id = "${info.id}/${portInfo.type}/${portInfo.portNumber}",
        name = name,
        // On a USB device PROPERTY_NAME is already the maker and the product joined together,
        // and MidiPort.displayName drops the repeat.
        manufacturer = properties.getString(MidiDeviceInfo.PROPERTY_MANUFACTURER),
        direction = direction,
        transport = when (info.type) {
            MidiDeviceInfo.TYPE_USB -> MidiTransport.USB
            MidiDeviceInfo.TYPE_VIRTUAL -> MidiTransport.VIRTUAL
            MidiDeviceInfo.TYPE_BLUETOOTH -> MidiTransport.BLUETOOTH
            else -> MidiTransport.UNKNOWN
        },
    )
    return PortRef(port, info, portInfo.portNumber)
}

private fun MidiDevice.openReadPort(ref: PortRef): MidiOutputPort {
    val opened = try {
        openOutputPort(ref.portNumber)
    } catch (error: Exception) {
        closeQuietly()
        throw MidiPortException("could not open ${ref.port.displayName} for reading", error)
    }
    // Null means another client holds the port, or the port number is no longer valid.
    if (opened == null) {
        closeQuietly()
        throw MidiPortException("${ref.port.displayName} is busy or gone")
    }
    return opened
}

private fun MidiDevice.openWritePort(ref: PortRef): MidiInputPort {
    val opened = try {
        openInputPort(ref.portNumber)
    } catch (error: Exception) {
        closeQuietly()
        throw MidiPortException("could not open ${ref.port.displayName} for writing", error)
    }
    if (opened == null) {
        closeQuietly()
        throw MidiPortException("${ref.port.displayName} is busy or gone")
    }
    return opened
}

/** Feeds what the framework delivers into a parser and passes on whole messages. */
private class ParsingReceiver(onMessage: (TimedMidiMessage) -> Unit) : MidiReceiver() {

    private val lock = Any()
    private var arrival: Long = 0L

    // Guarded by lock. The dispatcher can still call onSend after disconnect returns.
    private var shut = false
    private val parser = MidiStreamParser { message ->
        if (!shut) onMessage(TimedMidiMessage(message, arrival))
    }

    override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) {
        synchronized(lock) {
            if (shut) return
            // The framework's timestamp is a System.nanoTime() reading, which is what
            // TimedMidiMessage wants. The parser calls back from inside feed, so setting the field
            // first is enough to give every message the right moment.
            arrival = if (timestamp == 0L) System.nanoTime() else timestamp
            parser.feed(msg, offset, count)
        }
    }

    /** Stops delivery. Waits for a callback that runs on another thread. */
    fun stopDelivery() {
        synchronized(lock) { shut = true }
    }
}

private class AndroidMidiInput(
    override val port: MidiPort,
    private val device: MidiDevice,
    private val readPort: MidiOutputPort,
    private val receiver: ParsingReceiver,
    private val onClosed: (AutoCloseable) -> Unit,
) : MidiInput, AutoCloseable {

    private val closed = AtomicBoolean(false)

    override fun close() {
        // Before the flag, so even a second close() returns only after delivery has stopped.
        receiver.stopDelivery()
        if (!closed.compareAndSet(false, true)) return
        readPort.disconnect(receiver)
        readPort.closeQuietly()
        device.closeQuietly()
        onClosed(this)
    }
}

private class AndroidMidiOutput(
    override val port: MidiPort,
    private val device: MidiDevice,
    private val writePort: MidiInputPort,
    private val onClosed: (AutoCloseable) -> Unit,
) : MidiOutput, AutoCloseable {

    private val closed = AtomicBoolean(false)

    override fun send(message: MidiMessage) {
        sendRaw(message.toBytes())
    }

    override fun sendRaw(bytes: ByteArray) {
        if (closed.get()) throw closedError()
        try {
            // 0L means "now". The framework has no public name for it: its own source carries a
            // TODO about adding one. send splits anything too long for the port, onSend does not.
            writePort.send(bytes, 0, bytes.size, 0L)
        } catch (error: IOException) {
            // A close() on another thread shuts the port under this send.
            if (closed.get()) throw closedError(error)
            throw MidiPortException("lost ${port.displayName} while sending", error)
        }
    }

    private fun closedError(cause: Throwable? = null) =
        MidiPortException("the output to ${port.displayName} is closed", cause)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        writePort.closeQuietly()
        device.closeQuietly()
        onClosed(this)
    }
}

private class AndroidBluetoothConnection(
    private val device: MidiDevice,
    override val ports: List<MidiPort>,
    private val onClosed: (AutoCloseable) -> Unit,
) : BluetoothMidiConnection, AutoCloseable {

    private val closed = AtomicBoolean(false)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        device.closeQuietly()
        onClosed(this)
    }
}

private fun AutoCloseable.closeQuietly() {
    try {
        close()
    } catch (ignored: Exception) {
        // Whatever this was is already gone, and there is nothing useful left to do about it.
    }
}
