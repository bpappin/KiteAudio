package io.github.yuroyami.kitemidi

import io.github.yuroyami.kitemidi.internal.ByteCursor
import io.github.yuroyami.kitemidi.internal.writeVarLen
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The Standard MIDI File specification prints a table of variable-length quantities.
 * Every pair below is copied from it, so this test fails if the encoder drifts from the spec
 * rather than from our own idea of it.
 */
class VarLenTest {

    private val specTable = listOf(
        0x00000000 to byteArrayOf(0x00),
        0x00000040 to byteArrayOf(0x40),
        0x0000007F to byteArrayOf(0x7F),
        0x00000080 to byteArrayOf(0x81.toByte(), 0x00),
        0x00002000 to byteArrayOf(0xC0.toByte(), 0x00),
        0x00003FFF to byteArrayOf(0xFF.toByte(), 0x7F),
        0x00004000 to byteArrayOf(0x81.toByte(), 0x80.toByte(), 0x00),
        0x00100000 to byteArrayOf(0xC0.toByte(), 0x80.toByte(), 0x00),
        0x001FFFFF to byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0x7F),
        0x00200000 to byteArrayOf(0x81.toByte(), 0x80.toByte(), 0x80.toByte(), 0x00),
        0x08000000 to byteArrayOf(0xC0.toByte(), 0x80.toByte(), 0x80.toByte(), 0x00),
        0x0FFFFFFF to byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x7F),
    )

    @Test
    fun writesTheSpecCanonicalTable() {
        for ((value, expected) in specTable) {
            val out = ArrayList<Byte>()
            out.writeVarLen(value)
            assertContentEquals(expected, out.toByteArray(), "encoding 0x${value.toString(16)}")
        }
    }

    @Test
    fun readsTheSpecCanonicalTable() {
        for ((expected, bytes) in specTable) {
            val cursor = ByteCursor(bytes)
            assertEquals(expected, cursor.readVarLen(), "decoding ${bytes.joinToString(" ")}")
            assertEquals(bytes.size, cursor.position, "consumed length for 0x${expected.toString(16)}")
        }
    }

    @Test
    fun rejectsAQuantityLongerThanFourBytes() {
        val fiveContinuationBytes = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x7F)
        assertFailsWith<MidiFormatException> { ByteCursor(fiveContinuationBytes).readVarLen() }
    }

    @Test
    fun rejectsAQuantityThatRunsPastTheEnd() {
        val unterminated = byteArrayOf(0x81.toByte(), 0x80.toByte())
        assertFailsWith<MidiFormatException> { ByteCursor(unterminated).readVarLen() }
    }

    @Test
    fun rejectsANegativeValueOnWrite() {
        assertFailsWith<IllegalArgumentException> { ArrayList<Byte>().writeVarLen(-1) }
    }

    @Test
    fun rejectsAValueAboveTheFourByteMaximumOnWrite() {
        assertFailsWith<IllegalArgumentException> { ArrayList<Byte>().writeVarLen(0x10000000) }
    }
}
