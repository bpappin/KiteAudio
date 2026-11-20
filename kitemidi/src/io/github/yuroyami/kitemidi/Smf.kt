package io.github.yuroyami.kitemidi

/** How a file counts time. */
public sealed interface Division {

    /** Ticks per quarter note. Almost every file uses this. */
    public data class Ppq(public val ticksPerQuarter: Int) : Division {
        init { require(ticksPerQuarter in 1..0x7FFF) { "ticks per quarter must be 1 to 32767, got $ticksPerQuarter" } }
    }

    /**
     * Ticks tied to real time rather than to the beat, for scoring to picture.
     * Tempo events have no effect on a file that counts this way.
     */
    public data class Smpte(public val framesPerSecond: Int, public val ticksPerFrame: Int) : Division {
        init {
            require(framesPerSecond in listOf(24, 25, 29, 30)) { "frames per second must be 24, 25, 29 or 30, got $framesPerSecond" }
            require(ticksPerFrame in 1..255) { "ticks per frame must be 1 to 255, got $ticksPerFrame" }
        }
    }
}

/** Which framing byte introduced a system exclusive chunk inside a file. */
public enum class SysExKind {
    /** A normal message. Its data usually ends with 0xF7. */
    F0,

    /** An escape or continuation chunk. Its bytes go on the wire exactly as stored. */
    F7,
}

/**
 * A system exclusive chunk exactly as a file stores it.
 *
 * The bytes are kept verbatim, framing included, because that is the only way a file written by
 * an unfamiliar program survives being read and written back.
 */
public class SysExChunk(public val kind: SysExKind, public val data: ByteArray) : MidiEvent {

    /**
     * The chunk as a message that can go on a cable, or null when it is an escape chunk or a
     * fragment of a message split across several chunks.
     */
    public fun toMessage(): SysEx? {
        if (kind != SysExKind.F0) return null
        if (data.isEmpty() || (data.last().toInt() and 0xFF) != 0xF7) return null
        return SysEx(data.copyOfRange(0, data.size - 1))
    }

    override fun equals(other: Any?): Boolean =
        other is SysExChunk && kind == other.kind && data.contentEquals(other.data)

    override fun hashCode(): Int = 31 * kind.hashCode() + data.contentHashCode()

    override fun toString(): String = "SysExChunk($kind, ${data.size} bytes)"
}

/** One event on a track, timed relative to the event before it. */
public data class SmfEvent(public val delta: Int, public val event: MidiEvent) {
    init { require(delta >= 0) { "a delta cannot be negative, got $delta" } }
}

/** One track. */
public data class SmfTrack(public val events: List<SmfEvent>)

/**
 * A Standard MIDI File.
 *
 * @param format 0 for one track, 1 for several played together, 2 for several independent ones.
 */
public data class SmfFile(
    public val format: Int,
    public val division: Division,
    public val tracks: List<SmfTrack>,
) {
    init { require(format in 0..2) { "format must be 0, 1 or 2, got $format" } }
}
