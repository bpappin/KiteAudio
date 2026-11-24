package io.github.yuroyami.kiteaudio.midi

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The value ranges of the meta event and tempo models, and how the reader treats bytes outside them. */
class MetaEventRangeTest {

    @Test
    fun theCallsFromTheIssueThrow() {
        assertFailsWith<IllegalArgumentException> { ChannelPrefix(16) }
        assertFailsWith<IllegalArgumentException> { SequenceNumber(70000) }
        assertFailsWith<IllegalArgumentException> { SmpteOffset(hours = 300, minutes = 0, seconds = 0, frames = 0, subFrames = 0) }
        assertFailsWith<IllegalArgumentException> { TempoEntry(0, 0) }
        assertFailsWith<IllegalArgumentException> { TempoEntry(-1, 500_000) }
    }

    @Test
    fun theMessagesNameTheRange() {
        assertMessage("0 to 15") { ChannelPrefix(16) }
        assertMessage("0 to 65535") { SequenceNumber(70000) }
        assertMessage("0 to 127") { PortPrefix(128) }
        assertMessage("0 to 23") { SmpteOffset(300, 0, 0, 0, 0) }
        assertMessage("1 to 16777215") { TempoEntry(0, 0) }
        assertMessage("0 or more") { TempoEntry(-1, 500_000) }
        assertMessage("0 to 255") { TimeSignature(4, 4, clocksPerClick = 256) }
    }

    @Test
    fun sequenceNumberBoundaries() {
        SequenceNumber(0); SequenceNumber(65535)
        assertFailsWith<IllegalArgumentException> { SequenceNumber(-1) }
        assertFailsWith<IllegalArgumentException> { SequenceNumber(65536) }
    }

    @Test
    fun channelAndPortPrefixBoundaries() {
        ChannelPrefix(0); ChannelPrefix(15)
        assertFailsWith<IllegalArgumentException> { ChannelPrefix(-1) }
        assertFailsWith<IllegalArgumentException> { ChannelPrefix(16) }
        PortPrefix(0); PortPrefix(127)
        assertFailsWith<IllegalArgumentException> { PortPrefix(-1) }
        assertFailsWith<IllegalArgumentException> { PortPrefix(128) }
    }

    @Test
    fun tempoEntryBoundaries() {
        TempoEntry(0, 1); TempoEntry(Long.MAX_VALUE, 0xFFFFFF)
        assertFailsWith<IllegalArgumentException> { TempoEntry(-1, 1) }
        assertFailsWith<IllegalArgumentException> { TempoEntry(0, 0) }
        assertFailsWith<IllegalArgumentException> { TempoEntry(0, -1) }
        assertFailsWith<IllegalArgumentException> { TempoEntry(0, 0x1000000) }
    }

    @Test
    fun timeSignatureClockFieldBoundaries() {
        TimeSignature(4, 4, clocksPerClick = 0, thirtySecondsPerQuarter = 0)
        TimeSignature(4, 4, clocksPerClick = 255, thirtySecondsPerQuarter = 255)
        assertFailsWith<IllegalArgumentException> { TimeSignature(4, 4, clocksPerClick = -1) }
        assertFailsWith<IllegalArgumentException> { TimeSignature(4, 4, clocksPerClick = 256) }
        assertFailsWith<IllegalArgumentException> { TimeSignature(4, 4, thirtySecondsPerQuarter = -1) }
        assertFailsWith<IllegalArgumentException> { TimeSignature(4, 4, thirtySecondsPerQuarter = 256) }
    }

    @Test
    fun smpteOffsetFieldBoundaries() {
        SmpteOffset(0, 0, 0, 0, 0)
        SmpteOffset(23, 59, 59, 23, 99)
        for ((hours, minutes, seconds, subFrames) in listOf(
            listOf(-1, 0, 0, 0), listOf(24, 0, 0, 0),
            listOf(0, -1, 0, 0), listOf(0, 60, 0, 0),
            listOf(0, 0, -1, 0), listOf(0, 0, 60, 0),
            listOf(0, 0, 0, -1), listOf(0, 0, 0, 100),
        )) {
            assertFailsWith<IllegalArgumentException>("$hours:$minutes:$seconds .$subFrames") {
                SmpteOffset(hours, minutes, seconds, 0, subFrames)
            }
        }
        assertFailsWith<IllegalArgumentException> { SmpteOffset(0, 0, 0, -1, 0) }
    }

    @Test
    fun smpteOffsetFramesStayBelowTheFrameCountOfTheRate() {
        assertEquals(24, SmpteOffset(0, 0, 0, 0, 0).framesPerSecond)
        // 29 means 30 drop-frame, which still numbers its frames 0 to 29.
        for ((rate, frameCount) in listOf(24 to 24, 25 to 25, 29 to 30, 30 to 30)) {
            SmpteOffset(0, 0, 0, frameCount - 1, 0, rate)
            assertFailsWith<IllegalArgumentException>("rate $rate") { SmpteOffset(0, 0, 0, frameCount, 0, rate) }
        }
        for (rate in listOf(0, 23, 26, 31, 60)) {
            assertFailsWith<IllegalArgumentException>("rate $rate") { SmpteOffset(0, 0, 0, 0, 0, rate) }
        }
    }

    @Test
    fun smpteOffsetOfEachRateRoundTripsByteForByte() {
        // Bits 5 and 6 of the hours byte hold the rate: 00 is 24, 01 is 25, 10 is 30 drop-frame, 11 is 30.
        for ((rate, bits) in listOf(24 to 0, 25 to 1, 29 to 2, 30 to 3)) {
            val lastFrame = if (rate == 24 || rate == 25) rate - 1 else 29
            val offset = SmpteOffset(23, 59, 58, lastFrame, 99, rate)
            val payload = bytesOf((bits shl 5) or 23, 59, 58, lastFrame, 99)
            assertContentEquals(payload, offset.payload(), "rate $rate")
            assertEquals(offset, MetaEvent.parse(0x54, payload), "rate $rate")
            assertRoundTrips(0x54, payload)
        }
    }

    @Test
    fun outOfRangePayloadsReadAsUnknownMetaAndWriteBackUnchanged() {
        val cases = listOf(
            0x20 to bytesOf(0x10), 0x20 to bytesOf(0xFF),
            0x21 to bytesOf(0x80), 0x21 to bytesOf(0xFF),
            0x51 to bytesOf(0x00, 0x00, 0x00),
            0x54 to bytesOf(0x18, 0, 0, 0, 0), // 24 hours
            0x54 to bytesOf(0x80, 0, 0, 0, 0), // bit 7 of the hours byte
            0x54 to bytesOf(0, 60, 0, 0, 0),
            0x54 to bytesOf(0, 0, 60, 0, 0),
            0x54 to bytesOf(0, 0, 0, 24, 0), // frame 24 at 24 frames a second
            0x54 to bytesOf(0x20, 0, 0, 25, 0), // frame 25 at 25 frames a second
            0x54 to bytesOf(0x60, 0, 0, 30, 0), // frame 30 at 30 frames a second
            0x54 to bytesOf(0, 0, 0, 0, 100),
            0x58 to bytesOf(0x00, 0x02, 0x18, 0x08), // numerator 0
            0x58 to bytesOf(0x04, 0x1F, 0x18, 0x08), // 2^31 overflows an Int
            0x58 to bytesOf(0x04, 0x20, 0x18, 0x08),
            0x58 to bytesOf(0x04, 0xFF, 0x18, 0x08),
            0x59 to bytesOf(0x08, 0x00), // 8 sharps
            0x59 to bytesOf(0xF8, 0x00), // 8 flats
            0x59 to bytesOf(0x00, 0x02), // a mode that is neither major nor minor
        )
        for ((type, payload) in cases) {
            val name = "type 0x${type.toString(16)} ${payload.joinToString { (it.toInt() and 0xFF).toString(16) }}"
            val unknown = assertIs<UnknownMeta>(MetaEvent.parse(type, payload), name)
            assertEquals(type, unknown.type, name)
            assertContentEquals(payload, unknown.data, name)
            assertRoundTrips(type, payload)
        }
    }

    @Test
    fun everyByteAtEveryPayloadPositionReadsAndWritesBackUnchanged() {
        val valid = listOf(
            0x00 to bytesOf(0x01, 0x02),
            0x20 to bytesOf(0x03),
            0x21 to bytesOf(0x01),
            0x51 to bytesOf(0x07, 0xA1, 0x20),
            0x54 to bytesOf(0x01, 0x02, 0x03, 0x04, 0x05),
            0x58 to bytesOf(0x04, 0x02, 0x18, 0x08),
            0x59 to bytesOf(0x02, 0x01),
        )
        for ((type, base) in valid) {
            for (position in base.indices) {
                for (value in 0..255) {
                    val payload = base.copyOf().also { it[position] = value.toByte() }
                    assertRoundTrips(type, payload)
                }
            }
        }
    }

    // Reads a one-track file holding the event, writes it back and compares every byte.
    private fun assertRoundTrips(type: Int, payload: ByteArray) {
        val track = bytesOf(0x00, 0xFF, type, payload.size) + payload + bytesOf(0x00, 0xFF, 0x2F, 0x00)
        val file = smfOf(track)
        val name = "type 0x${type.toString(16)} ${payload.joinToString { (it.toInt() and 0xFF).toString(16) }}"
        val event = MetaEvent.parse(type, payload)
        assertContentEquals(payload, event.payload(), name)
        assertContentEquals(file, KiteMidi.write(KiteMidi.read(file)), name)
    }

    private fun assertMessage(range: String, block: () -> Unit) {
        val thrown = assertFailsWith<IllegalArgumentException> { block() }
        assertTrue(thrown.message.orEmpty().contains(range), "\"${thrown.message}\" should name $range")
    }

    private fun smfOf(track: ByteArray): ByteArray {
        val header = bytesOf(0x4D, 0x54, 0x68, 0x64, 0x00, 0x00, 0x00, 0x06, 0x00, 0x00, 0x00, 0x01, 0x00, 0x60)
        val len = track.size
        return header + bytesOf(0x4D, 0x54, 0x72, 0x6B) +
            byteArrayOf((len shr 24).toByte(), (len shr 16).toByte(), (len shr 8).toByte(), len.toByte()) + track
    }
}
