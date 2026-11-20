package io.github.yuroyami.kitemidi

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

class MetaEventTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun setTempoEncodesThreeBytesOfMicrosecondsPerQuarter() {
        assertContentEquals(bytes(0xFF, 0x51, 0x03, 0x07, 0xA1, 0x20), SetTempo(500_000).toBytes())
        assertEquals(SetTempo(500_000), MetaEvent.parse(0x51, bytes(0x07, 0xA1, 0x20)))
    }

    @Test
    fun setTempoConvertsToBeatsPerMinute() {
        assertEquals(120.0, SetTempo(500_000).beatsPerMinute)
        assertEquals(60.0, SetTempo(1_000_000).beatsPerMinute)
    }

    @Test
    fun timeSignatureStoresTheDenominatorAsItsBaseTwoLogarithm() {
        // 4/4 with 24 clocks per click and 8 thirty-second notes per quarter.
        val fourFour = TimeSignature(numerator = 4, denominator = 4, clocksPerClick = 24, thirtySecondsPerQuarter = 8)
        assertContentEquals(bytes(0xFF, 0x58, 0x04, 0x04, 0x02, 0x18, 0x08), fourFour.toBytes())
        assertEquals(fourFour, MetaEvent.parse(0x58, bytes(0x04, 0x02, 0x18, 0x08)))

        val sixEight = TimeSignature(numerator = 6, denominator = 8, clocksPerClick = 24, thirtySecondsPerQuarter = 8)
        assertContentEquals(bytes(0xFF, 0x58, 0x04, 0x06, 0x03, 0x18, 0x08), sixEight.toBytes())
    }

    @Test
    fun keySignatureStoresSharpsAsASignedByte() {
        assertContentEquals(bytes(0xFF, 0x59, 0x02, 0x02, 0x00), KeySignature(sharps = 2, minor = false).toBytes())
        assertContentEquals(bytes(0xFF, 0x59, 0x02, 0xFD, 0x01), KeySignature(sharps = -3, minor = true).toBytes())
        assertEquals(KeySignature(-3, true), MetaEvent.parse(0x59, bytes(0xFD, 0x01)))
    }

    @Test
    fun endOfTrackEncodesWithNoPayload() {
        assertContentEquals(bytes(0xFF, 0x2F, 0x00), EndOfTrack.toBytes())
        assertEquals(EndOfTrack, MetaEvent.parse(0x2F, ByteArray(0)))
    }

    @Test
    fun everyTextKindRoundTripsIncludingNonAsciiText() {
        for (kind in TextKind.entries) {
            val event = TextEvent(kind, "Gymnopedie no 1 café 日本")
            val encoded = event.toBytes()
            assertEquals(0xFF, encoded[0].toInt() and 0xFF)
            assertEquals(kind.type, encoded[1].toInt() and 0xFF)
            val payload = encoded.copyOfRange(3 + varLenExtraBytes(event.text), encoded.size)
            assertEquals(event, MetaEvent.parse(kind.type, payload))
        }
    }

    private fun varLenExtraBytes(text: String): Int {
        val size = text.encodeToByteArray().size
        return if (size < 0x80) 0 else 1
    }

    @Test
    fun sequenceNumberAndPrefixesRoundTrip() {
        assertEquals(SequenceNumber(258), MetaEvent.parse(0x00, bytes(0x01, 0x02)))
        assertEquals(ChannelPrefix(3), MetaEvent.parse(0x20, bytes(0x03)))
        assertEquals(PortPrefix(1), MetaEvent.parse(0x21, bytes(0x01)))
    }

    @Test
    fun smpteOffsetRoundTrips() {
        val offset = SmpteOffset(hours = 1, minutes = 2, seconds = 3, frames = 4, subFrames = 5)
        assertContentEquals(bytes(0xFF, 0x54, 0x05, 0x01, 0x02, 0x03, 0x04, 0x05), offset.toBytes())
        assertEquals(offset, MetaEvent.parse(0x54, bytes(0x01, 0x02, 0x03, 0x04, 0x05)))
    }

    @Test
    fun anUnknownTypeKeepsItsBytesInsteadOfThrowing() {
        val parsed = MetaEvent.parse(0x7A, bytes(0xDE, 0xAD))
        val unknown = assertIs<UnknownMeta>(parsed)
        assertEquals(0x7A, unknown.type)
        assertContentEquals(bytes(0xDE, 0xAD), unknown.data)
        assertContentEquals(bytes(0xFF, 0x7A, 0x02, 0xDE, 0xAD), unknown.toBytes())
    }

    @Test
    fun sequencerSpecificKeepsItsBytes() {
        val parsed = MetaEvent.parse(0x7F, bytes(0x43, 0x01))
        val specific = assertIs<SequencerSpecific>(parsed)
        assertContentEquals(bytes(0x43, 0x01), specific.data)
    }
}
