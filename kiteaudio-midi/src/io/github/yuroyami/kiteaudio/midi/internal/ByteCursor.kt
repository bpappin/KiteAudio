package io.github.yuroyami.kiteaudio.midi.internal

import io.github.yuroyami.kiteaudio.midi.MidiFormatException

/**
 * A bounds-checked big-endian reader over a byte array.
 *
 * Every read checks the bound first and throws [MidiFormatException] carrying the offset, so a
 * truncated file never reaches an index error.
 */
internal class ByteCursor(private val bytes: ByteArray, position: Int = 0) {

    var position: Int = position
        private set

    val remaining: Int get() = bytes.size - position

    fun fail(message: String): Nothing = throw MidiFormatException(message, position)

    private fun require(count: Int) {
        if (count < 0) fail("negative read length $count")
        if (remaining < count) fail("wanted $count byte(s), only $remaining left")
    }

    /** Reads one byte as an unsigned value, 0 to 255. */
    fun readByte(): Int {
        require(1)
        return bytes[position++].toInt() and 0xFF
    }

    /** Reads one byte as a signed value, -128 to 127. Key signatures store sharps this way. */
    fun readSignedByte(): Int {
        require(1)
        return bytes[position++].toInt()
    }

    /** Peeks the next byte without consuming it, or -1 at the end. */
    fun peekByte(): Int = if (remaining > 0) (bytes[position].toInt() and 0xFF) else -1

    fun readUShortBe(): Int {
        require(2)
        return (readByte() shl 8) or readByte()
    }

    /** Chunk lengths are unsigned 32-bit, so they widen to Long rather than overflow. */
    fun readUIntBe(): Long {
        require(4)
        var value = 0L
        repeat(4) { value = (value shl 8) or readByte().toLong() }
        return value
    }

    fun readBytes(count: Int): ByteArray {
        require(count)
        val out = bytes.copyOfRange(position, position + count)
        position += count
        return out
    }

    fun skip(count: Int) {
        require(count)
        position += count
    }

    /**
     * Reads a variable-length quantity: seven bits per byte, the top bit set on every byte but
     * the last. The format allows at most four bytes, so 0x0FFFFFFF is the largest value.
     */
    fun readVarLen(): Int {
        var value = 0
        var bytesRead = 0
        while (true) {
            val byte = readByte()
            bytesRead++
            value = (value shl 7) or (byte and 0x7F)
            if (byte and 0x80 == 0) return value
            if (bytesRead == 4) fail("variable-length quantity longer than four bytes")
        }
    }
}
