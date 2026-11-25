package io.github.yuroyami.kiteaudio.consumer

import io.github.yuroyami.kiteaudio.midi.Division
import io.github.yuroyami.kiteaudio.midi.EndOfTrack
import io.github.yuroyami.kiteaudio.midi.KiteMidi
import io.github.yuroyami.kiteaudio.midi.MidiFormatException
import io.github.yuroyami.kiteaudio.midi.NoteOff
import io.github.yuroyami.kiteaudio.midi.NoteOn
import io.github.yuroyami.kiteaudio.midi.SmfEvent
import io.github.yuroyami.kiteaudio.midi.SmfFile
import io.github.yuroyami.kiteaudio.midi.SmfTrack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MidiConsumerTest {

    @Test
    fun aWrittenFileReadsBackUnchanged() {
        val events = listOf(SmfEvent(0, NoteOn(0, 60, 100)), SmfEvent(96, NoteOff(0, 60, 64)), SmfEvent(0, EndOfTrack))
        val file = SmfFile(0, Division.Ppq(96), listOf(SmfTrack(events)))

        assertEquals(file, KiteMidi.read(KiteMidi.write(file)))
        assertEquals(1, KiteMidi.readTimeline(KiteMidi.write(file)).notes.size)
    }

    @Test
    fun bytesThatAreNotAMidiFileAreRefused() {
        assertFailsWith<MidiFormatException> { KiteMidi.read(byteArrayOf(1, 2, 3)) }
    }
}
