package io.github.yuroyami.kitemidi

/**
 * Anything that can sit on a track: a [MidiMessage] that can travel down a cable, or a
 * [MetaEvent] that only ever lives inside a file.
 */
public sealed interface MidiEvent

/** A message that can travel down a MIDI cable. */
public sealed interface MidiMessage : MidiEvent {

    /** The bytes this message becomes on the wire, status byte included. */
    public fun toBytes(): ByteArray

    /** A message addressed to one of the sixteen channels. */
    public sealed interface ChannelMessage : MidiMessage {
        public val channel: Int
        /** The status byte for this message: its type in the high nibble, channel in the low. */
        public val status: Int
    }
}

private fun checkChannel(channel: Int) {
    require(channel in 0..15) { "channel must be 0 to 15, got $channel" }
}

private fun check7Bit(value: Int, name: String) {
    require(value in 0..127) { "$name must be 0 to 127, got $value" }
}

/** A key released. Velocity is how fast, which few instruments send and fewer act on. */
public data class NoteOff(override val channel: Int, val key: Int, val velocity: Int) : MidiMessage.ChannelMessage {
    init { checkChannel(channel); check7Bit(key, "key"); check7Bit(velocity, "velocity") }
    override val status: Int get() = 0x80 or channel
    override fun toBytes(): ByteArray = byteArrayOf(status.toByte(), key.toByte(), velocity.toByte())
}

/**
 * A key pressed. A velocity of zero is how most files and most instruments say "released",
 * but this type keeps it as sent: rewriting it here would stop a writer reproducing the file.
 * Note pairing is where that meaning is applied.
 */
public data class NoteOn(override val channel: Int, val key: Int, val velocity: Int) : MidiMessage.ChannelMessage {
    init { checkChannel(channel); check7Bit(key, "key"); check7Bit(velocity, "velocity") }
    override val status: Int get() = 0x90 or channel
    override fun toBytes(): ByteArray = byteArrayOf(status.toByte(), key.toByte(), velocity.toByte())
}

/** Pressure on one held key, sometimes called polyphonic aftertouch. */
public data class PolyPressure(override val channel: Int, val key: Int, val pressure: Int) : MidiMessage.ChannelMessage {
    init { checkChannel(channel); check7Bit(key, "key"); check7Bit(pressure, "pressure") }
    override val status: Int get() = 0xA0 or channel
    override fun toBytes(): ByteArray = byteArrayOf(status.toByte(), key.toByte(), pressure.toByte())
}

/** A controller moved. Controller 64 is the sustain pedal. */
public data class ControlChange(override val channel: Int, val controller: Int, val value: Int) : MidiMessage.ChannelMessage {
    init { checkChannel(channel); check7Bit(controller, "controller"); check7Bit(value, "value") }
    override val status: Int get() = 0xB0 or channel
    override fun toBytes(): ByteArray = byteArrayOf(status.toByte(), controller.toByte(), value.toByte())

    public companion object {
        public const val SUSTAIN_PEDAL: Int = 64
        public const val PORTAMENTO_PEDAL: Int = 65
        public const val SOSTENUTO_PEDAL: Int = 66
        public const val SOFT_PEDAL: Int = 67
        public const val ALL_SOUND_OFF: Int = 120
        public const val RESET_ALL_CONTROLLERS: Int = 121
        public const val LOCAL_CONTROL: Int = 122
        public const val ALL_NOTES_OFF: Int = 123
    }
}

/** A sound selected, within the bank chosen by controllers 0 and 32. */
public data class ProgramChange(override val channel: Int, val program: Int) : MidiMessage.ChannelMessage {
    init { checkChannel(channel); check7Bit(program, "program") }
    override val status: Int get() = 0xC0 or channel
    override fun toBytes(): ByteArray = byteArrayOf(status.toByte(), program.toByte())
}

/** Pressure across the whole channel, sometimes called channel aftertouch. */
public data class ChannelPressure(override val channel: Int, val pressure: Int) : MidiMessage.ChannelMessage {
    init { checkChannel(channel); check7Bit(pressure, "pressure") }
    override val status: Int get() = 0xD0 or channel
    override fun toBytes(): ByteArray = byteArrayOf(status.toByte(), pressure.toByte())
}

/**
 * The pitch wheel, as a signed value centred on zero.
 *
 * On the wire it is fourteen unsigned bits with 0x2000 in the middle. This type does that
 * conversion so a caller never has to remember which end is which.
 */
public data class PitchBend(override val channel: Int, val value: Int) : MidiMessage.ChannelMessage {
    init { checkChannel(channel); require(value in -8192..8191) { "pitch bend must be -8192 to 8191, got $value" } }
    override val status: Int get() = 0xE0 or channel
    override fun toBytes(): ByteArray {
        val raw = value + 8192
        return byteArrayOf(status.toByte(), (raw and 0x7F).toByte(), ((raw shr 7) and 0x7F).toByte())
    }
}

/**
 * A system exclusive message, the escape hatch every manufacturer uses for its own settings.
 *
 * [data] holds what sits between the framing bytes: it excludes the leading 0xF0 and the
 * trailing 0xF7, both of which [toBytes] adds back.
 */
public class SysEx(public val data: ByteArray) : MidiMessage {
    override fun toBytes(): ByteArray = byteArrayOf(0xF0.toByte()) + data + byteArrayOf(0xF7.toByte())
    override fun equals(other: Any?): Boolean = other is SysEx && data.contentEquals(other.data)
    override fun hashCode(): Int = data.contentHashCode()
    override fun toString(): String = "SysEx(${data.joinToString(" ") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }})"
}

/** One quarter of a timecode frame. Eight of them make a full position. */
public data class TimeCodeQuarterFrame(val data: Int) : MidiMessage {
    init { check7Bit(data, "data") }
    override fun toBytes(): ByteArray = byteArrayOf(0xF1.toByte(), data.toByte())
}

/** Where a sequencer should jump to, counted in sixteenth notes from the start. */
public data class SongPosition(val beats: Int) : MidiMessage {
    init { require(beats in 0..16383) { "song position must be 0 to 16383, got $beats" } }
    override fun toBytes(): ByteArray =
        byteArrayOf(0xF2.toByte(), (beats and 0x7F).toByte(), ((beats shr 7) and 0x7F).toByte())
}

/** Which song a sequencer should load. */
public data class SongSelect(val song: Int) : MidiMessage {
    init { check7Bit(song, "song") }
    override fun toBytes(): ByteArray = byteArrayOf(0xF3.toByte(), song.toByte())
}

/** Asks an analogue instrument to retune its oscillators. */
public data object TuneRequest : MidiMessage {
    override fun toBytes(): ByteArray = byteArrayOf(0xF6.toByte())
}

/** Twenty-four of these mark each quarter note when a device follows an external clock. */
public data object TimingClock : MidiMessage {
    override fun toBytes(): ByteArray = byteArrayOf(0xF8.toByte())
}

/** Start playing from the beginning. */
public data object Start : MidiMessage {
    override fun toBytes(): ByteArray = byteArrayOf(0xFA.toByte())
}

/** Resume playing from where it stopped. */
public data object Continue : MidiMessage {
    override fun toBytes(): ByteArray = byteArrayOf(0xFB.toByte())
}

/** Stop playing. */
public data object Stop : MidiMessage {
    override fun toBytes(): ByteArray = byteArrayOf(0xFC.toByte())
}

/** Sent about three times a second so a receiver can tell a silent cable from an unplugged one. */
public data object ActiveSensing : MidiMessage {
    override fun toBytes(): ByteArray = byteArrayOf(0xFE.toByte())
}

/** Reset everything to power-on state. Rare, and rude in the middle of a piece. */
public data object SystemReset : MidiMessage {
    override fun toBytes(): ByteArray = byteArrayOf(0xFF.toByte())
}

/**
 * One message read out of a byte stream, with what the reader needs to continue.
 *
 * @param length how many bytes the message occupied, so a caller can advance.
 * @param runningStatus the status byte to assume for a following message that has none, or -1
 *   when the message just read cleared it.
 */
public data class ParsedMessage(
    public val message: MidiMessage,
    public val length: Int,
    public val runningStatus: Int,
)
