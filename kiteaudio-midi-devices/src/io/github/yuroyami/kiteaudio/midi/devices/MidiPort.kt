package io.github.yuroyami.kiteaudio.midi.devices

/**
 * Which way messages travel, seen from the app.
 *
 * Every platform names these from its own point of view and they do not agree. Android calls a
 * port that sends us notes an output port, because it is an output of the instrument. This
 * enum is always the app's point of view: [INPUT] is what we read, [OUTPUT] is what we write.
 */
public enum class MidiDirection {
    /** The app reads from this port. A keyboard's keys arrive here. */
    INPUT,

    /** The app writes to this port. Notes sent here make the instrument sound. */
    OUTPUT,
}

/** How the port is attached. */
public enum class MidiTransport { USB, BLUETOOTH, VIRTUAL, NETWORK, UNKNOWN }

/**
 * One direction of one device.
 *
 * A keyboard that both sends and receives appears as two ports, one per direction.
 *
 * @param id stable for as long as the device stays attached. Platform-specific, and not worth
 *   storing between runs: match on [name] to recognise a device the user chose before.
 *   On the JVM, identical devices are numbered by their position in the device list. Their ids
 *   can swap after a device is added or removed. When a group of identical devices gets smaller,
 *   every open port of that group is lost. This also ends a port whose device still works, because
 *   the JDK gives no way to tell the devices apart.
 */
public data class MidiPort(
    public val id: String,
    public val name: String,
    public val manufacturer: String?,
    public val direction: MidiDirection,
    public val transport: MidiTransport,
) {
    /** What to show a person: the manufacturer and the name, without repeating either. */
    public val displayName: String
        get() = when {
            manufacturer.isNullOrBlank() -> name
            name.contains(manufacturer, ignoreCase = true) -> name
            else -> "$manufacturer $name"
        }
}

/**
 * A message with the moment it arrived.
 *
 * @param timestampNanos a monotonic count of nanoseconds. It is the device's own timestamp
 *   where the platform provides one, and the host clock otherwise. It is not a wall clock and
 *   only differences between two of them mean anything.
 */
public data class TimedMidiMessage(
    public val message: io.github.yuroyami.kiteaudio.midi.MidiMessage,
    public val timestampNanos: Long,
)
