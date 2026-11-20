package io.github.yuroyami.kiteaudio.midi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AuditRegressionTest {
    private fun file(vararg events: MidiEvent) = SmfFile(0, Division.Ppq(96),
        listOf(SmfTrack(events.map { SmfEvent(0, it) })))

    @Test fun zeroDivisionsAreFormatFailures() {
        for (high in listOf(0, 0xe7)) {
            val bytes = MINIMAL_SMF.copyOf()
            bytes[12] = high.toByte()
            bytes[13] = 0
            assertFailsWith<MidiFormatException> { KiteMidi.read(bytes) }
        }
    }

    @Test fun missingTracksAndPartialHeadersAreRejected() {
        val two = SmfFile(1, Division.Ppq(96), List(2) { SmfTrack(listOf(SmfEvent(0, EndOfTrack))) })
        val bytes = KiteMidi.write(two)
        for (cut in bytes.size - 12 until bytes.size) {
            assertFailsWith<MidiFormatException>("cut $cut") { KiteMidi.read(bytes.copyOf(cut)) }
        }
    }

    @Test fun writerRejectsUnframedSystemMessagesAndEarlyTerminator() {
        for (event in listOf(UniversalSysEx.deviceInquiry(), SystemReset, TuneRequest, TimingClock)) {
            assertFailsWith<IllegalArgumentException> { KiteMidi.write(file(event)) }
        }
        assertFailsWith<IllegalArgumentException> { KiteMidi.write(file(EndOfTrack, NoteOn(0, 60, 100))) }
        val framed = file(SysExChunk(SysExKind.F0, byteArrayOf(0x7e, 0x7f, 0x06, 0x01, 0xf7.toByte())), EndOfTrack)
        assertEquals(framed, KiteMidi.read(KiteMidi.write(framed)))
    }

    @Test fun formatTwoRequiresExplicitSequenceSelection() {
        val independent = file(NoteOn(0, 60, 100)).copy(format = 2)
        assertFailsWith<IllegalArgumentException> { independent.toTimeline() }
        assertEquals(1, independent.copy(format = 0).toTimeline().notes.size)
    }

    @Test fun everyRepresentablePowerOfTwoDenominatorRoundTrips() {
        for (exponent in 0..30) {
            val event = TimeSignature(4, 1 shl exponent)
            assertEquals(event, MetaEvent.parse(event.type, event.payload()))
        }
    }

    @Test fun separatedMessageParserRejectsInteriorStatus() {
        for (status in listOf(0x90, 0xf0, 0xf8, 0xff)) {
            assertFailsWith<MidiFormatException> {
                KiteMidi.parseMessage(byteArrayOf(0xf0.toByte(), 0x7e, status.toByte(), 0xf7.toByte()))
            }
        }
        assertEquals(TimingClock, KiteMidi.parseMessage(byteArrayOf(0xf8.toByte()))!!.message)
    }

    @Test fun fractionalTimeSurvivesTempoBoundaries() {
        val map = TempoMap(Division.Ppq(3), listOf(TempoEntry(0, 500_000), TempoEntry(1, 500_000), TempoEntry(2, 500_000)))
        assertEquals(500_000, map.tickToMicros(3))
        assertEquals(3, map.microsToTick(500_000))
        val changes = TempoMap(Division.Ppq(3), listOf(TempoEntry(0, 500_000), TempoEntry(1, 500_002)))
        assertEquals(333_334, changes.tickToMicros(2))
        assertEquals(2, changes.microsToTick(333_334))
    }

    @Test fun largeRepresentableInverseAvoidsIntermediateOverflow() {
        val map = TempoMap(Division.Ppq(32767), emptyList())
        assertEquals(15_259_254_737_998_596, map.tickToMicros(1_000_000_000_000_000))
        assertEquals(1_000_000_000_000_000, map.microsToTick(15_259_254_737_998_596))
    }
}
