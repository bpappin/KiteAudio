package io.github.yuroyami.kitemidi.io

import io.github.yuroyami.kitemidi.MidiMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow

/** An open input. Closing it stops the callback. */
public interface MidiInput {
    public val port: MidiPort
    public fun close()
}

/** An open output. */
public interface MidiOutput {
    public val port: MidiPort

    /** Sends one message, as soon as the platform can. */
    @Throws(MidiPortException::class)
    public fun send(message: MidiMessage)

    /** Sends bytes already encoded. Useful for a message this library does not model. */
    @Throws(MidiPortException::class)
    public fun sendRaw(bytes: ByteArray)

    public fun close()
}

/**
 * The MIDI ports on this machine.
 *
 * [ports] is a live list. On Android and Apple the platform reports changes, so it updates when
 * a cable is plugged in. The JVM has no such callback, so that implementation polls, and
 * [refresh] asks it to look now.
 *
 * Every implementation is safe to call from any thread. Callbacks arrive on whatever thread the
 * platform delivers them on, which is usually not the main one, so a caller that touches the
 * user interface must hop threads itself.
 */
public interface MidiDevices {

    /** Every port currently attached. */
    public val ports: StateFlow<List<MidiPort>>

    /** Looks for changes now rather than waiting for the next poll. */
    public fun refresh()

    /**
     * Opens [port] for reading and calls [onMessage] for each message that arrives.
     *
     * @throws MidiPortException when the port cannot be opened.
     */
    @Throws(MidiPortException::class, CancellationException::class)
    public suspend fun openInput(port: MidiPort, onMessage: (TimedMidiMessage) -> Unit): MidiInput

    /**
     * Opens [port] for writing.
     *
     * @throws MidiPortException when the port cannot be opened.
     */
    @Throws(MidiPortException::class, CancellationException::class)
    public suspend fun openOutput(port: MidiPort): MidiOutput

    /** Closes everything this opened and stops watching for changes. */
    public fun close()
}

/** A port could not be opened, or was lost while open. */
public class MidiPortException(message: String, cause: Throwable? = null) : Exception(message, cause)
