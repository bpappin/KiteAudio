package io.github.yuroyami.kiteaudio.midi

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Each test here pins a defect an adversarial review found and confirmed by running the code.
 * They are grouped together so it stays obvious what they are: regression guards, not a
 * description of the design.
 */
class ReviewFixesTest {

    // A track name in Latin-1: "a", e-acute, "b". Not valid UTF-8.
    @Test
    fun textThatIsNotUtf8SurvivesReadAndWrite() {
        val trackNameMeta = bytesOf(0x00, 0xFF, 0x03, 0x03, 0x61, 0xE9, 0x62, 0x00, 0xFF, 0x2F, 0x00)
        val file = KiteMidi.read(smfOf(trackNameMeta))
        val written = KiteMidi.write(file)
        assertContentEquals(
            smfOf(trackNameMeta),
            written,
            "a Latin-1 track name must come back out exactly as it went in",
        )
    }

    @Test
    fun shiftJisLyricsSurviveReadAndWrite() {
        val lyric = bytesOf(0x00, 0xFF, 0x05, 0x04, 0x82, 0xA0, 0x82, 0xA2, 0x00, 0xFF, 0x2F, 0x00)
        assertContentEquals(smfOf(lyric), KiteMidi.write(KiteMidi.read(smfOf(lyric))))
    }

    @Test
    fun textStillReadsAsAStringWhenItIsValidUtf8() {
        val event = MetaEvent.parse(TextKind.TRACK_NAME.type, "Gymnopédie".encodeToByteArray())
        assertEquals(TextEvent(TextKind.TRACK_NAME, "Gymnopédie"), event)
        assertEquals("Gymnopédie", (event as TextEvent).text)
    }

    @Test
    fun aTimeSignatureExponentTooLargeToShiftStaysRawBytes() {
        // 1 shl 31 wraps negative and 1 shl 32 silently becomes 1, because Kotlin masks the
        // shift count to five bits. Neither may become a TimeSignature, and the file still loads.
        for (exponent in listOf(0x1F, 0x20, 0xFF)) {
            val track = bytesOf(0x00, 0xFF, 0x58, 0x04, 0x04, exponent, 0x18, 0x08, 0x00, 0xFF, 0x2F, 0x00)
            val file = KiteMidi.read(smfOf(track))
            assertIs<UnknownMeta>(file.tracks[0].events[0].event, "exponent $exponent")
            assertContentEquals(smfOf(track), KiteMidi.write(file), "exponent $exponent")
        }
    }

    @Test
    fun everyTooShortPayloadThrowsTheDocumentedTypeAndNothingElse() {
        // The README promises MidiFormatException is the only thing the reader throws.
        val cases = listOf(
            "tempo of two bytes" to bytesOf(0x00, 0xFF, 0x51, 0x02, 0x07, 0xA1, 0x00, 0xFF, 0x2F, 0x00),
            "smpte offset of four bytes" to bytesOf(0x00, 0xFF, 0x54, 0x04, 0x01, 0x02, 0x03, 0x04, 0x00, 0xFF, 0x2F, 0x00),
            "time signature of three bytes" to bytesOf(0x00, 0xFF, 0x58, 0x03, 0x04, 0x02, 0x18, 0x00, 0xFF, 0x2F, 0x00),
            "key signature of one byte" to bytesOf(0x00, 0xFF, 0x59, 0x01, 0x02, 0x00, 0xFF, 0x2F, 0x00),
        )
        for ((name, track) in cases) {
            val thrown = assertFailsWith<Throwable>(name) { KiteMidi.read(smfOf(track)) }
            assertTrue(
                thrown is MidiFormatException,
                "$name threw ${thrown::class.simpleName}, but the reader documents MidiFormatException",
            )
        }
    }

    @Test
    fun anImpossibleSmpteFrameRateIsRejectedAsAFormatProblem() {
        val bad = MINIMAL_SMF.copyOf()
        bad[12] = 0xF0.toByte()   // 256 - 240 = 16 frames a second, which is not a rate
        bad[13] = 0x28.toByte()
        assertFailsWith<MidiFormatException> { KiteMidi.read(bad) }
    }

    @Test
    fun thirtyDropFrameCountsAtItsRealRate() {
        // The division byte -29 means 30 drop frame, which really runs at 30000/1001 frames a
        // second, not 29. At 80 ticks a frame, one second is 2397.6 ticks.
        val map = TempoMap(Division.Smpte(29, 80), emptyList())
        val oneSecond = map.tickToMicros(2398)
        assertTrue(
            oneSecond in 999_000..1_001_000,
            "2398 ticks of 30 drop frame is about one second, got $oneSecond microseconds",
        )
        // The exact rates are unaffected.
        assertEquals(1_000_000L, TempoMap(Division.Smpte(25, 40), emptyList()).tickToMicros(1000))
        assertEquals(1_000_000L, TempoMap(Division.Smpte(30, 80), emptyList()).tickToMicros(2400))
    }

    @Test
    fun aNoteReleasedOnAnotherTrackStillPairs() {
        // In a format 1 file every track shares the same sixteen channels, so a note started on
        // one track is legitimately released from another.
        val file = SmfFile(
            format = 1,
            division = Division.Ppq(96),
            tracks = listOf(
                SmfTrack(listOf(SmfEvent(0, NoteOn(0, 60, 64)), SmfEvent(0, EndOfTrack))),
                SmfTrack(listOf(SmfEvent(96, NoteOff(0, 60, 64)), SmfEvent(0, EndOfTrack))),
            ),
        )
        val notes = file.toTimeline().notes
        assertEquals(1, notes.size)
        assertEquals(0L, notes[0].startTick)
        assertEquals(96L, notes[0].endTick, "the note lasts until the other track releases it")
    }

    @Test
    fun aNoteIsStillPreferentiallyClosedByItsOwnTrack() {
        // Two tracks both holding the same channel and key: each release should close its own
        // track's note first, not the other one's.
        val file = SmfFile(
            format = 1,
            division = Division.Ppq(96),
            tracks = listOf(
                SmfTrack(listOf(SmfEvent(0, NoteOn(0, 60, 100)), SmfEvent(192, NoteOff(0, 60, 0)), SmfEvent(0, EndOfTrack))),
                SmfTrack(listOf(SmfEvent(48, NoteOn(0, 60, 80)), SmfEvent(48, NoteOff(0, 60, 0)), SmfEvent(0, EndOfTrack))),
            ),
        )
        val notes = file.toTimeline().notes.sortedBy { it.startTick }
        assertEquals(2, notes.size)
        assertEquals(0L, notes[0].startTick); assertEquals(192L, notes[0].endTick)
        assertEquals(48L, notes[1].startTick); assertEquals(96L, notes[1].endTick)
    }

    @Test
    fun aVeryLongPieceConvertsWithoutOverflowingToANegativeDuration() {
        val map = TempoMap(Division.Ppq(96), listOf(TempoEntry(0, 500_000)))
        // Far longer than any real piece, and still what the reader will happily produce.
        val hugeTick = 18_790_481_850_000L
        val micros = map.tickToMicros(hugeTick)
        assertTrue(micros > 0, "a duration must never come back negative, got $micros")
        // Worked out the same way the conversion does, because writing it as
        // hugeTick * 500_000 / 96 would overflow here in the test as well.
        assertEquals(hugeTick / 96L * 500_000L + (hugeTick % 96L) * 500_000L / 96L, micros)
        assertEquals(97_867_092_968_750_000L, micros)
    }

    private fun smfOf(track: ByteArray): ByteArray {
        val header = bytesOf(0x4D, 0x54, 0x68, 0x64, 0x00, 0x00, 0x00, 0x06, 0x00, 0x00, 0x00, 0x01, 0x00, 0x60)
        val len = track.size
        return header + bytesOf(0x4D, 0x54, 0x72, 0x6B) +
            byteArrayOf((len shr 24).toByte(), (len shr 16).toByte(), (len shr 8).toByte(), len.toByte()) + track
    }
}
