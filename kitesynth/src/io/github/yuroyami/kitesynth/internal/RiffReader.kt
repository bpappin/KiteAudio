package io.github.yuroyami.kitesynth.internal

import io.github.yuroyami.kitesynth.SoundFontFormatException

/**
 * A cursor over RIFF bytes.
 *
 * RIFF is little-endian, which is the opposite of Standard MIDI Files, so every read here is
 * low byte first. Every read is also bounds-checked: a truncated font is common enough that
 * walking off the end has to be an error with a position, not an exception from an array.
 */
internal class RiffReader(private val bytes: ByteArray, internal var position: Int = 0) {

    val remaining: Int get() = bytes.size - position

    fun require(count: Int, what: String) {
        if (remaining < count) {
            throw SoundFontFormatException(
                "the file ends before $what: wanted $count more bytes, $remaining left",
                position,
            )
        }
    }

    fun u8(): Int {
        require(1, "a byte")
        return bytes[position++].toInt() and 0xFF
    }

    fun s8(): Int {
        require(1, "a signed byte")
        return bytes[position++].toInt()
    }

    /** Little-endian unsigned 16-bit. */
    fun u16(): Int {
        require(2, "a 16-bit value")
        val value = (bytes[position].toInt() and 0xFF) or ((bytes[position + 1].toInt() and 0xFF) shl 8)
        position += 2
        return value
    }

    /** Little-endian signed 16-bit. Generator amounts are signed and it matters. */
    fun s16(): Int {
        val value = u16()
        return if (value >= 0x8000) value - 0x10000 else value
    }

    /** Little-endian unsigned 32-bit, returned as Long because it does not fit in a signed Int. */
    fun u32(): Long {
        require(4, "a 32-bit value")
        var value = 0L
        for (i in 0 until 4) {
            value = value or ((bytes[position + i].toLong() and 0xFF) shl (8 * i))
        }
        position += 4
        return value
    }

    /** A four character chunk tag, read as ASCII. */
    fun fourCC(): String {
        require(4, "a chunk tag")
        val tag = buildString(4) {
            for (i in 0 until 4) append((bytes[position + i].toInt() and 0xFF).toChar())
        }
        position += 4
        return tag
    }

    /**
     * A fixed-width name, as SoundFont stores them.
     *
     * The field is padded and the padding is not always zero, so the name stops at the first
     * zero byte and anything after it is ignored.
     */
    fun name(width: Int): String {
        require(width, "a name")
        var end = position
        val limit = position + width
        while (end < limit && bytes[end].toInt() != 0) end++
        val text = buildString(end - position) {
            for (i in position until end) append((bytes[i].toInt() and 0xFF).toChar())
        }
        position = limit
        return text.trimEnd()
    }

    fun skip(count: Int) {
        require(count, "padding")
        position += count
    }

    fun slice(start: Int, length: Int): ByteArray {
        // Subtracting rather than adding: a chunk claiming two billion bytes overflows the sum
        // into a negative number, the check passes, and the copy below asks for two gigabytes.
        if (start < 0 || length < 0 || start > bytes.size || length > bytes.size - start) {
            throw SoundFontFormatException("a chunk claims $length bytes at $start, past the end", start)
        }
        return bytes.copyOfRange(start, start + length)
    }

    /** [count] little-endian signed 16-bit values from [start], decoded with no copy in between. */
    fun shorts(start: Int, count: Int): ShortArray {
        if (start < 0 || count < 0 || start > bytes.size || count > (bytes.size - start) / 2) {
            throw SoundFontFormatException("$count frames at $start run past the end", start)
        }
        val out = ShortArray(count)
        var at = start
        for (i in 0 until count) {
            out[i] = ((bytes[at].toInt() and 0xFF) or (bytes[at + 1].toInt() shl 8)).toShort()
            at += 2
        }
        return out
    }
}

/** One RIFF chunk: what it is, where its body starts and how long the body is. */
internal class Chunk(val id: String, val start: Int, val size: Int) {
    val end: Int get() = start + size
}

/**
 * Reads a chunk header and leaves the cursor on the body.
 *
 * RIFF pads odd-sized chunks to an even boundary. Forgetting that pad shifts everything after
 * it by one byte, which reads as a completely different file rather than as an error.
 */
internal fun RiffReader.readChunkHeader(): Chunk {
    val id = fourCC()
    val size = u32()
    if (size > Int.MAX_VALUE) {
        throw SoundFontFormatException("chunk $id claims $size bytes, which is not a real size", position)
    }
    return Chunk(id, position, size.toInt())
}

/** Moves past a chunk body, including RIFF's pad byte on odd sizes. */
internal fun RiffReader.skipChunkBody(chunk: Chunk) {
    position = chunk.end
    if (chunk.size % 2 == 1 && remaining > 0) position++
}
