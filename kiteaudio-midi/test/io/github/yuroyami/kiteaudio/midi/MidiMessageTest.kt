package io.github.yuroyami.kiteaudio.midi

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class MidiMessageTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    private fun roundTrip(message: MidiMessage) {
        val encoded = message.toBytes()
        val parsed = KiteMidi.parseMessage(encoded)
        assertEquals(message, parsed?.message, "round trip of $message")
        assertEquals(encoded.size, parsed?.length, "consumed length of $message")
    }

    @Test
    fun everyChannelMessageRoundTrips() {
        roundTrip(NoteOff(channel = 0, key = 60, velocity = 64))
        roundTrip(NoteOn(channel = 9, key = 36, velocity = 100))
        roundTrip(PolyPressure(channel = 3, key = 72, pressure = 40))
        roundTrip(ControlChange(channel = 0, controller = 64, value = 127))
        roundTrip(ProgramChange(channel = 15, program = 5))
        roundTrip(ChannelPressure(channel = 2, pressure = 90))
        roundTrip(PitchBend(channel = 0, value = 0))
    }

    @Test
    fun noteOnEncodesStatusChannelKeyVelocity() {
        assertContentEquals(bytes(0x90, 0x3C, 0x64), NoteOn(0, 60, 100).toBytes())
        assertContentEquals(bytes(0x9F, 0x3C, 0x64), NoteOn(15, 60, 100).toBytes())
        assertContentEquals(bytes(0x80, 0x3C, 0x40), NoteOff(0, 60, 64).toBytes())
    }

    @Test
    fun aNoteOnWithVelocityZeroStaysANoteOnAtTheCodecLayer() {
        // Whether it means "note off" is a pairing decision, not a parsing one. The parser
        // must not silently rewrite the message, or a writer would not reproduce the file.
        val parsed = KiteMidi.parseMessage(bytes(0x90, 0x3C, 0x00))?.message
        assertEquals(NoteOn(0, 60, 0), parsed)
    }

    @Test
    fun pitchBendUsesFourteenBitsAroundACentreOfZero() {
        assertContentEquals(bytes(0xE0, 0x00, 0x40), PitchBend(0, 0).toBytes())
        assertContentEquals(bytes(0xE0, 0x00, 0x00), PitchBend(0, -8192).toBytes())
        assertContentEquals(bytes(0xE0, 0x7F, 0x7F), PitchBend(0, 8191).toBytes())
        assertEquals(PitchBend(0, 0), KiteMidi.parseMessage(bytes(0xE0, 0x00, 0x40))?.message)
        assertEquals(PitchBend(0, -8192), KiteMidi.parseMessage(bytes(0xE0, 0x00, 0x00))?.message)
        assertEquals(PitchBend(0, 8191), KiteMidi.parseMessage(bytes(0xE0, 0x7F, 0x7F))?.message)
    }

    @Test
    fun runningStatusParsesASecondMessageWithNoStatusByte() {
        val first = KiteMidi.parseMessage(bytes(0x90, 0x3C, 0x64))!!
        assertEquals(NoteOn(0, 60, 100), first.message)
        assertEquals(0x90, first.runningStatus)

        val second = KiteMidi.parseMessage(bytes(0x3E, 0x50), offset = 0, runningStatus = first.runningStatus)!!
        assertEquals(NoteOn(0, 62, 80), second.message)
        assertEquals(2, second.length)
    }

    @Test
    fun everySystemRealTimeByteMapsToItsObject() {
        assertEquals(TimingClock, KiteMidi.parseMessage(bytes(0xF8))?.message)
        assertEquals(Start, KiteMidi.parseMessage(bytes(0xFA))?.message)
        assertEquals(Continue, KiteMidi.parseMessage(bytes(0xFB))?.message)
        assertEquals(Stop, KiteMidi.parseMessage(bytes(0xFC))?.message)
        assertEquals(ActiveSensing, KiteMidi.parseMessage(bytes(0xFE))?.message)
        assertEquals(SystemReset, KiteMidi.parseMessage(bytes(0xFF))?.message)
    }

    @Test
    fun systemCommonMessagesRoundTrip() {
        roundTrip(SongPosition(beats = 1000))
        roundTrip(SongSelect(song = 7))
        roundTrip(TimeCodeQuarterFrame(data = 0x25))
        roundTrip(TuneRequest)
    }

    @Test
    fun sysExEncodesWithBothFramingBytes() {
        val sysex = SysEx(bytes(0x7E, 0x7F, 0x06, 0x01))
        assertContentEquals(bytes(0xF0, 0x7E, 0x7F, 0x06, 0x01, 0xF7), sysex.toBytes())
        val parsed = KiteMidi.parseMessage(sysex.toBytes())?.message as SysEx
        assertContentEquals(sysex.data, parsed.data)
    }

    @Test
    fun aDataByteWithNoRunningStatusFails() {
        assertFailsWith<MidiFormatException> { KiteMidi.parseMessage(bytes(0x3C, 0x64)) }
    }

    @Test
    fun anEmptyInputParsesToNull() {
        assertNull(KiteMidi.parseMessage(ByteArray(0)))
    }

    @Test
    fun aTruncatedChannelMessageFails() {
        assertFailsWith<MidiFormatException> { KiteMidi.parseMessage(bytes(0x90, 0x3C)) }
    }

    @Test
    fun outOfRangeArgumentsAreRejectedAtConstruction() {
        assertFailsWith<IllegalArgumentException> { NoteOn(channel = 16, key = 60, velocity = 100) }
        assertFailsWith<IllegalArgumentException> { NoteOn(channel = -1, key = 60, velocity = 100) }
        assertFailsWith<IllegalArgumentException> { NoteOn(channel = 0, key = 128, velocity = 100) }
        assertFailsWith<IllegalArgumentException> { NoteOn(channel = 0, key = 60, velocity = 128) }
        assertFailsWith<IllegalArgumentException> { ControlChange(channel = 0, controller = 128, value = 0) }
        assertFailsWith<IllegalArgumentException> { PitchBend(channel = 0, value = 8192) }
        assertFailsWith<IllegalArgumentException> { PitchBend(channel = 0, value = -8193) }
    }
}
