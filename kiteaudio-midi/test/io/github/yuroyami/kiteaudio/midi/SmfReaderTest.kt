package io.github.yuroyami.kiteaudio.midi

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class SmfReaderTest {

    @Test
    fun readsTheMinimalFile() {
        val file = KiteMidi.read(MINIMAL_SMF)
        assertEquals(0, file.format)
        assertEquals(Division.Ppq(96), file.division)
        assertEquals(1, file.tracks.size)

        val events = file.tracks[0].events
        assertEquals(5, events.size)
        assertContentEquals(listOf(0, 0, 0, 96, 0), events.map { it.delta })
        assertEquals(SetTempo(500_000), events[0].event)
        assertEquals(TimeSignature(4, 4, 24, 8), events[1].event)
        assertEquals(NoteOn(0, 60, 100), events[2].event)
        assertEquals(NoteOff(0, 60, 64), events[3].event)
        assertEquals(EndOfTrack, events[4].event)
    }

    @Test
    fun readsSmpteDivision() {
        val smpte = MINIMAL_SMF.copyOf()
        smpte[12] = 0xE7.toByte()
        smpte[13] = 0x28.toByte()
        val division = KiteMidi.read(smpte).division
        assertEquals(Division.Smpte(framesPerSecond = 25, ticksPerFrame = 40), division)
    }

    @Test
    fun readsRunningStatus() {
        // One status byte, then a second note with none.
        val track = bytesOf(0x00, 0x90, 0x3C, 0x64, 0x10, 0x3E, 0x50, 0x00, 0xFF, 0x2F, 0x00)
        val file = KiteMidi.read(smfOf(track))
        val events = file.tracks[0].events
        assertEquals(NoteOn(0, 60, 100), events[0].event)
        assertEquals(NoteOn(0, 62, 80), events[1].event)
        assertEquals(0x10, events[1].delta)
    }

    @Test
    fun readsAFileLevelSysExWithItsOwnLength() {
        val track = bytesOf(0x00, 0xF0, 0x05, 0x7E, 0x7F, 0x06, 0x01, 0xF7, 0x00, 0xFF, 0x2F, 0x00)
        val event = KiteMidi.read(smfOf(track)).tracks[0].events[0].event
        val chunk = assertIs<SysExChunk>(event)
        assertEquals(SysExKind.F0, chunk.kind)
        assertContentEquals(bytesOf(0x7E, 0x7F, 0x06, 0x01, 0xF7), chunk.data)
        assertEquals(SysEx(bytesOf(0x7E, 0x7F, 0x06, 0x01)), chunk.toMessage())
    }

    @Test
    fun readsAnEscapeSysEx() {
        val track = bytesOf(0x00, 0xF7, 0x02, 0xF8, 0xF8, 0x00, 0xFF, 0x2F, 0x00)
        val chunk = assertIs<SysExChunk>(KiteMidi.read(smfOf(track)).tracks[0].events[0].event)
        assertEquals(SysExKind.F7, chunk.kind)
        assertContentEquals(bytesOf(0xF8, 0xF8), chunk.data)
    }

    @Test
    fun readsAFormatOneFileWithThreeTracks() {
        val header = bytesOf(0x4D, 0x54, 0x68, 0x64, 0x00, 0x00, 0x00, 0x06, 0x00, 0x01, 0x00, 0x03, 0x00, 0x60)
        val track = bytesOf(0x00, 0xFF, 0x2F, 0x00)
        var out = header
        repeat(3) { out += bytesOf(0x4D, 0x54, 0x72, 0x6B, 0x00, 0x00, 0x00, 0x04) + track }
        val file = KiteMidi.read(out)
        assertEquals(1, file.format)
        assertEquals(3, file.tracks.size)
    }

    @Test
    fun skipsAChunkThatIsNotATrack() {
        val header = bytesOf(0x4D, 0x54, 0x68, 0x64, 0x00, 0x00, 0x00, 0x06, 0x00, 0x00, 0x00, 0x01, 0x00, 0x60)
        val alien = bytesOf(0x58, 0x59, 0x5A, 0x5A, 0x00, 0x00, 0x00, 0x02, 0xAA, 0xBB)
        val track = bytesOf(0x4D, 0x54, 0x72, 0x6B, 0x00, 0x00, 0x00, 0x04, 0x00, 0xFF, 0x2F, 0x00)
        val file = KiteMidi.read(header + alien + track)
        assertEquals(1, file.tracks.size)
    }

    @Test
    fun rejectsTheWrongMagic() {
        val wrong = MINIMAL_SMF.copyOf()
        wrong[0] = 0x00
        assertFailsWith<MidiFormatException> { KiteMidi.read(wrong) }
    }

    @Test
    fun rejectsATruncatedHeader() {
        assertFailsWith<MidiFormatException> { KiteMidi.read(MINIMAL_SMF.copyOfRange(0, 10)) }
    }

    @Test
    fun rejectsATrackShorterThanItsDeclaredLength() {
        val truncated = MINIMAL_SMF.copyOfRange(0, MINIMAL_SMF.size - 6)
        assertFailsWith<MidiFormatException> { KiteMidi.read(truncated) }
    }

    @Test
    fun rejectsAnUnknownFormat() {
        val wrong = MINIMAL_SMF.copyOf()
        wrong[9] = 0x09
        assertFailsWith<MidiFormatException> { KiteMidi.read(wrong) }
    }

    @Test
    fun rejectsRunningStatusAsTheFirstEventOfATrack() {
        val track = bytesOf(0x00, 0x3C, 0x64, 0x00, 0xFF, 0x2F, 0x00)
        assertFailsWith<MidiFormatException> { KiteMidi.read(smfOf(track)) }
    }

    @Test
    fun rejectsAnEmptyInput() {
        assertFailsWith<MidiFormatException> { KiteMidi.read(ByteArray(0)) }
    }

    private fun smfOf(track: ByteArray): ByteArray {
        val header = bytesOf(0x4D, 0x54, 0x68, 0x64, 0x00, 0x00, 0x00, 0x06, 0x00, 0x00, 0x00, 0x01, 0x00, 0x60)
        val len = track.size
        return header + bytesOf(0x4D, 0x54, 0x72, 0x6B) +
            byteArrayOf((len shr 24).toByte(), (len shr 16).toByte(), (len shr 8).toByte(), len.toByte()) + track
    }
}
