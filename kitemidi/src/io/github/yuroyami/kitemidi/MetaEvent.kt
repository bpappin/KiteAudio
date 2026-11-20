package io.github.yuroyami.kitemidi

import io.github.yuroyami.kitemidi.internal.writeVarLen

/**
 * An event that only ever appears inside a file. Tempo, time signature, track names and
 * lyrics all live here. None of it travels down a cable.
 */
public sealed interface MetaEvent : MidiEvent {

    /** The type byte that follows 0xFF. */
    public val type: Int

    /** The payload between the length and the next event. */
    public fun payload(): ByteArray

    /** The full encoding: 0xFF, the type, the payload length as a variable-length quantity, the payload. */
    public fun toBytes(): ByteArray {
        val body = payload()
        val out = ArrayList<Byte>(3 + body.size)
        out.add(0xFF.toByte())
        out.add(type.toByte())
        out.writeVarLen(body.size)
        for (b in body) out.add(b)
        return out.toByteArray()
    }

    public companion object {
        /** Builds the right subtype for [type], falling back to [UnknownMeta] rather than throwing. */
        public fun parse(type: Int, data: ByteArray): MetaEvent = asFormatProblem(type) {
            TextKind.entries.firstOrNull { it.type == type }?.let {
                return@asFormatProblem TextEvent(it, data)
            }
            when (type) {
                0x00 -> SequenceNumber(if (data.size >= 2) ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF) else 0)
                0x20 -> ChannelPrefix(if (data.isNotEmpty()) data[0].toInt() and 0x0F else 0)
                0x21 -> PortPrefix(if (data.isNotEmpty()) data[0].toInt() and 0xFF else 0)
                0x2F -> EndOfTrack
                0x51 -> {
                    if (data.size < 3) throw MidiFormatException("set tempo needs three bytes, got ${data.size}")
                    SetTempo(
                        ((data[0].toInt() and 0xFF) shl 16) or
                            ((data[1].toInt() and 0xFF) shl 8) or
                            (data[2].toInt() and 0xFF),
                    )
                }
                0x54 -> {
                    if (data.size < 5) throw MidiFormatException("smpte offset needs five bytes, got ${data.size}")
                    SmpteOffset(
                        data[0].toInt() and 0xFF, data[1].toInt() and 0xFF, data[2].toInt() and 0xFF,
                        data[3].toInt() and 0xFF, data[4].toInt() and 0xFF,
                    )
                }
                0x58 -> {
                    if (data.size < 4) throw MidiFormatException("time signature needs four bytes, got ${data.size}")
                    // The file stores the denominator's base-2 logarithm. Kotlin's shl uses only
                    // the low five bits of its count, so an exponent of 32 would silently become
                    // 1 and 31 would wrap negative. No real denominator is anywhere near that.
                    val exponent = data[1].toInt() and 0xFF
                    if (exponent > 30) throw MidiFormatException("time signature denominator 2^$exponent does not fit a positive Int")
                    TimeSignature(
                        numerator = data[0].toInt() and 0xFF,
                        denominator = 1 shl exponent,
                        clocksPerClick = data[2].toInt() and 0xFF,
                        thirtySecondsPerQuarter = data[3].toInt() and 0xFF,
                    )
                }
                0x59 -> {
                    if (data.size < 2) throw MidiFormatException("key signature needs two bytes, got ${data.size}")
                    KeySignature(sharps = data[0].toInt(), minor = data[1].toInt() != 0)
                }
                0x7F -> SequencerSpecific(data)
                else -> UnknownMeta(type, data)
            }
        }

        /**
         * Turns a value a file supplied into a format problem rather than an argument problem.
         *
         * The types here validate their own arguments, which is right for code that builds an
         * event. When the value came out of a file, the caller is not at fault and the reader
         * documents one exception type, so this converts.
         */
        private inline fun asFormatProblem(type: Int, block: () -> MetaEvent): MetaEvent = try {
            block()
        } catch (e: MidiFormatException) {
            throw e
        } catch (e: IllegalArgumentException) {
            throw MidiFormatException("meta event 0x${type.toString(16)}: ${e.message}")
        }
    }
}

/** The nine kinds of text a file can carry, each with its own type byte. */
public enum class TextKind(public val type: Int) {
    TEXT(0x01),
    COPYRIGHT(0x02),
    TRACK_NAME(0x03),
    INSTRUMENT_NAME(0x04),
    LYRIC(0x05),
    MARKER(0x06),
    CUE_POINT(0x07),
    PROGRAM_NAME(0x08),
    DEVICE_NAME(0x09),
}

/**
 * Any of the nine text kinds.
 *
 * The bytes are what this holds, not the string. Nothing in the file format says which encoding
 * a text event uses, and real files carry Latin-1 copyright lines and Shift-JIS karaoke lyrics.
 * Decoding those as UTF-8 and re-encoding would replace every byte it did not understand with a
 * question mark, so a file read and written back would come out damaged. [text] decodes for
 * display; the bytes travel untouched.
 */
public class TextEvent(public val kind: TextKind, public val bytes: ByteArray) : MetaEvent {

    /** Builds one from a string, which is encoded as UTF-8. */
    public constructor(kind: TextKind, text: String) : this(kind, text.encodeToByteArray())

    /** The text, read as UTF-8. Bytes that are not valid UTF-8 show as a replacement character. */
    public val text: String get() = bytes.decodeToString()

    override val type: Int get() = kind.type
    override fun payload(): ByteArray = bytes

    override fun equals(other: Any?): Boolean =
        other is TextEvent && kind == other.kind && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = 31 * kind.hashCode() + bytes.contentHashCode()
    override fun toString(): String = "TextEvent($kind, \"$text\")"
}

/** The sequence number, used to pick a track out of a format 2 file. */
public data class SequenceNumber(public val number: Int) : MetaEvent {
    override val type: Int get() = 0x00
    override fun payload(): ByteArray = byteArrayOf(((number shr 8) and 0xFF).toByte(), (number and 0xFF).toByte())
}

/** Says which channel the following meta events describe. */
public data class ChannelPrefix(public val channel: Int) : MetaEvent {
    override val type: Int get() = 0x20
    override fun payload(): ByteArray = byteArrayOf(channel.toByte())
}

/** Says which port the following events are for. Not in the original specification, but common. */
public data class PortPrefix(public val port: Int) : MetaEvent {
    override val type: Int get() = 0x21
    override fun payload(): ByteArray = byteArrayOf(port.toByte())
}

/** The last event of every track. A writer adds one if the track lacks it. */
public data object EndOfTrack : MetaEvent {
    override val type: Int get() = 0x2F
    override fun payload(): ByteArray = ByteArray(0)
}

/**
 * How long a quarter note lasts, in microseconds. The default when a file states none is
 * 500000, which is 120 beats per minute.
 */
public data class SetTempo(public val microsPerQuarter: Int) : MetaEvent {
    init { require(microsPerQuarter in 1..0xFFFFFF) { "microseconds per quarter must be 1 to 16777215, got $microsPerQuarter" } }
    override val type: Int get() = 0x51
    override fun payload(): ByteArray = byteArrayOf(
        ((microsPerQuarter shr 16) and 0xFF).toByte(),
        ((microsPerQuarter shr 8) and 0xFF).toByte(),
        (microsPerQuarter and 0xFF).toByte(),
    )

    /** The same tempo said the way musicians say it. */
    public val beatsPerMinute: Double get() = 60_000_000.0 / microsPerQuarter
}

/** Where the piece sits on a timecode reel. */
public data class SmpteOffset(
    public val hours: Int,
    public val minutes: Int,
    public val seconds: Int,
    public val frames: Int,
    public val subFrames: Int,
) : MetaEvent {
    override val type: Int get() = 0x54
    override fun payload(): ByteArray =
        byteArrayOf(hours.toByte(), minutes.toByte(), seconds.toByte(), frames.toByte(), subFrames.toByte())
}

/**
 * The time signature.
 *
 * [denominator] is the real note value, so 4 means a quarter note. The file stores its base-2
 * logarithm instead, and this type does that conversion both ways.
 */
public data class TimeSignature(
    public val numerator: Int,
    public val denominator: Int,
    public val clocksPerClick: Int = 24,
    public val thirtySecondsPerQuarter: Int = 8,
) : MetaEvent {
    init {
        require(numerator in 1..255) { "numerator must be 1 to 255, got $numerator" }
        require(denominator > 0 && (denominator and (denominator - 1)) == 0) {
            "denominator must be a power of two, got $denominator"
        }
    }
    override val type: Int get() = 0x58
    override fun payload(): ByteArray {
        var log = 0
        var d = denominator
        while (d > 1) { d = d shr 1; log++ }
        return byteArrayOf(numerator.toByte(), log.toByte(), clocksPerClick.toByte(), thirtySecondsPerQuarter.toByte())
    }
}

/**
 * The key signature.
 *
 * [sharps] counts sharps when positive and flats when negative, so -3 is three flats.
 */
public data class KeySignature(public val sharps: Int, public val minor: Boolean) : MetaEvent {
    init { require(sharps in -7..7) { "sharps must be -7 to 7, got $sharps" } }
    override val type: Int get() = 0x59
    override fun payload(): ByteArray = byteArrayOf(sharps.toByte(), if (minor) 1 else 0)
}

/** A manufacturer's own data, kept as sent. */
public class SequencerSpecific(public val data: ByteArray) : MetaEvent {
    override val type: Int get() = 0x7F
    override fun payload(): ByteArray = data
    override fun equals(other: Any?): Boolean = other is SequencerSpecific && data.contentEquals(other.data)
    override fun hashCode(): Int = data.contentHashCode()
    override fun toString(): String = "SequencerSpecific(${data.size} bytes)"
}

/**
 * A meta event this library does not name.
 *
 * Keeping the bytes rather than throwing is what lets a file written by an unfamiliar program
 * be read, edited elsewhere and written back without losing anything.
 */
public class UnknownMeta(override val type: Int, public val data: ByteArray) : MetaEvent {
    override fun payload(): ByteArray = data
    override fun equals(other: Any?): Boolean =
        other is UnknownMeta && type == other.type && data.contentEquals(other.data)
    override fun hashCode(): Int = 31 * type + data.contentHashCode()
    override fun toString(): String = "UnknownMeta(type=0x${type.toString(16)}, ${data.size} bytes)"
}
