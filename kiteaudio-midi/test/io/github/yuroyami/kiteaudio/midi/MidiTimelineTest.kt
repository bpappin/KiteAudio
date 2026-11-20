package io.github.yuroyami.kiteaudio.midi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MidiTimelineTest {

    private fun timelineOf(vararg tracks: List<SmfEvent>): MidiTimeline =
        SmfFile(1, Division.Ppq(96), tracks.map { SmfTrack(it) }).toTimeline()

    @Test
    fun deltasBecomeAbsoluteTicks() {
        val timeline = timelineOf(listOf(
            SmfEvent(0, NoteOn(0, 60, 100)),
            SmfEvent(96, NoteOff(0, 60, 0)),
            SmfEvent(96, NoteOn(0, 62, 100)),
            SmfEvent(0, EndOfTrack),
        ))
        assertEquals(listOf(0L, 96L, 192L, 192L), timeline.events.map { it.tick })
    }

    @Test
    fun tracksMergeInTickOrderKeepingTheirIndex() {
        val timeline = timelineOf(
            listOf(SmfEvent(0, NoteOn(0, 60, 100)), SmfEvent(200, EndOfTrack)),
            listOf(SmfEvent(100, NoteOn(1, 64, 100)), SmfEvent(100, EndOfTrack)),
        )
        val ticks = timeline.events.map { it.tick }
        assertEquals(ticks.sorted(), ticks, "events must come out in tick order")
        assertEquals(1, timeline.events.first { it.event is NoteOn && (it.event as NoteOn).key == 64 }.track)
    }

    @Test
    fun aNoteOffClosesTheMatchingNote() {
        val notes = timelineOf(listOf(
            SmfEvent(0, NoteOn(0, 60, 100)),
            SmfEvent(96, NoteOff(0, 60, 64)),
            SmfEvent(0, EndOfTrack),
        )).notes
        assertEquals(1, notes.size)
        assertEquals(MidiNote(0, 96, 60, 100, 64, 0, 0), notes[0])
    }

    @Test
    fun aNoteOnWithVelocityZeroClosesTheNote() {
        val notes = timelineOf(listOf(
            SmfEvent(0, NoteOn(0, 60, 100)),
            SmfEvent(96, NoteOn(0, 60, 0)),
            SmfEvent(0, EndOfTrack),
        )).notes
        assertEquals(1, notes.size)
        assertEquals(96L, notes[0].endTick)
    }

    @Test
    fun stackedNotesOnOneKeyPairFirstInFirstOut() {
        val notes = timelineOf(listOf(
            SmfEvent(0, NoteOn(0, 60, 100)),
            SmfEvent(48, NoteOn(0, 60, 80)),
            SmfEvent(48, NoteOff(0, 60, 0)),
            SmfEvent(48, NoteOff(0, 60, 0)),
            SmfEvent(0, EndOfTrack),
        )).notes.sortedBy { it.startTick }
        assertEquals(2, notes.size)
        assertEquals(0L, notes[0].startTick); assertEquals(96L, notes[0].endTick)
        assertEquals(48L, notes[1].startTick); assertEquals(144L, notes[1].endTick)
    }

    @Test
    fun aNoteOffWithNoOpenNoteIsIgnored() {
        val notes = timelineOf(listOf(
            SmfEvent(0, NoteOff(0, 60, 64)),
            SmfEvent(0, EndOfTrack),
        )).notes
        assertTrue(notes.isEmpty())
    }

    @Test
    fun aNoteStillOpenAtTheEndClosesAtTheLastTick() {
        val notes = timelineOf(listOf(
            SmfEvent(0, NoteOn(0, 60, 100)),
            SmfEvent(240, EndOfTrack),
        )).notes
        assertEquals(1, notes.size)
        assertEquals(240L, notes[0].endTick)
    }

    @Test
    fun notesOnDifferentChannelsDoNotPairWithEachOther() {
        val notes = timelineOf(listOf(
            SmfEvent(0, NoteOn(0, 60, 100)),
            SmfEvent(0, NoteOn(1, 60, 90)),
            SmfEvent(96, NoteOff(1, 60, 0)),
            SmfEvent(96, NoteOff(0, 60, 0)),
            SmfEvent(0, EndOfTrack),
        )).notes
        assertEquals(2, notes.size)
        assertEquals(96L, notes.first { it.channel == 1 }.endTick)
        assertEquals(192L, notes.first { it.channel == 0 }.endTick)
    }

    @Test
    fun theTempoMapComesFromEveryTrack() {
        val timeline = timelineOf(
            listOf(SmfEvent(0, SetTempo(400_000)), SmfEvent(96, EndOfTrack)),
            listOf(SmfEvent(48, SetTempo(300_000)), SmfEvent(48, EndOfTrack)),
        )
        assertEquals(400_000.0, 60_000_000.0 / timeline.tempoMap.bpmAt(0))
        assertEquals(300_000.0, 60_000_000.0 / timeline.tempoMap.bpmAt(48))
    }

    @Test
    fun readTimelineIsAShortcutForReadThenToTimeline() {
        assertEquals(KiteMidi.read(MINIMAL_SMF).toTimeline().notes, KiteMidi.readTimeline(MINIMAL_SMF).notes)
    }
}
