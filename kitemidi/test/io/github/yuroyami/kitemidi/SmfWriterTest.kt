package io.github.yuroyami.kitemidi

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SmfWriterTest {

    @Test
    fun reproducesTheMinimalFileByteForByte() {
        assertContentEquals(MINIMAL_SMF, KiteMidi.write(KiteMidi.read(MINIMAL_SMF)))
    }

    @Test
    fun appendsEndOfTrackWhenTheTrackLacksIt() {
        val file = SmfFile(
            format = 0,
            division = Division.Ppq(96),
            tracks = listOf(SmfTrack(listOf(SmfEvent(0, NoteOn(0, 60, 100))))),
        )
        val reread = KiteMidi.read(KiteMidi.write(file))
        assertEquals(EndOfTrack, reread.tracks[0].events.last().event)
        assertEquals(2, reread.tracks[0].events.size)
    }

    @Test
    fun writeThenReadIsAnIdentityOnAThreeTrackFile() {
        val file = SmfFile(
            format = 1,
            division = Division.Ppq(480),
            tracks = listOf(
                SmfTrack(listOf(
                    SmfEvent(0, TextEvent(TextKind.TRACK_NAME, "Conductor")),
                    SmfEvent(0, SetTempo(400_000)),
                    SmfEvent(1920, SetTempo(500_000)),
                    SmfEvent(0, EndOfTrack),
                )),
                SmfTrack(listOf(
                    SmfEvent(0, ProgramChange(0, 0)),
                    SmfEvent(0, NoteOn(0, 60, 100)),
                    SmfEvent(480, NoteOn(0, 60, 0)),
                    SmfEvent(0, ControlChange(0, 64, 127)),
                    SmfEvent(240, PitchBend(0, -2048)),
                    SmfEvent(0, EndOfTrack),
                )),
                SmfTrack(listOf(
                    SmfEvent(0, SysExChunk(SysExKind.F0, bytesOf(0x7E, 0x7F, 0x06, 0x01, 0xF7))),
                    SmfEvent(10, UnknownMeta(0x7A, bytesOf(0xDE, 0xAD))),
                    SmfEvent(0, EndOfTrack),
                )),
            ),
        )
        assertEquals(file, KiteMidi.read(KiteMidi.write(file)))
    }

    @Test
    fun writesSmpteDivisionBackAsItWasRead() {
        val smpte = MINIMAL_SMF.copyOf()
        smpte[12] = 0xE7.toByte()
        smpte[13] = 0x28.toByte()
        assertContentEquals(smpte, KiteMidi.write(KiteMidi.read(smpte)))
    }

    @Test
    fun rejectsADeltaTooLargeForTheFormat() {
        val file = SmfFile(0, Division.Ppq(96), listOf(SmfTrack(listOf(SmfEvent(0x10000000, EndOfTrack)))))
        assertFailsWith<IllegalArgumentException> { KiteMidi.write(file) }
    }
}
