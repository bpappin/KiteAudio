package io.github.yuroyami.kitemidi.io

import io.github.yuroyami.kitemidi.ActiveSensing
import io.github.yuroyami.kitemidi.ControlChange
import io.github.yuroyami.kitemidi.MidiMessage
import io.github.yuroyami.kitemidi.NoteOn
import io.github.yuroyami.kitemidi.SysEx
import io.github.yuroyami.kitemidi.TimingClock
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MidiStreamParserTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    private class Collector {
        val messages = ArrayList<MidiMessage>()
        val parser = MidiStreamParser { messages.add(it) }
        fun feed(vararg v: Int) {
            parser.feed(ByteArray(v.size) { v[it].toByte() })
        }
    }

    @Test
    fun aCompleteMessageInOneChunkEmitsOnce() {
        val c = Collector()
        c.feed(0x90, 0x3C, 0x64)
        assertEquals<List<MidiMessage>>(listOf(NoteOn(0, 60, 100)), c.messages)
    }

    @Test
    fun aMessageSplitAcrossChunksEmitsOnlyWhenItIsComplete() {
        val c = Collector()
        c.feed(0x90)
        assertTrue(c.messages.isEmpty(), "nothing to emit after the status byte alone")
        c.feed(0x3C)
        assertTrue(c.messages.isEmpty(), "nothing to emit after one data byte")
        c.feed(0x64)
        assertEquals<List<MidiMessage>>(listOf(NoteOn(0, 60, 100)), c.messages)
    }

    @Test
    fun runningStatusCarriesAcrossChunks() {
        val c = Collector()
        c.feed(0x90, 0x3C, 0x64)
        c.feed(0x3E, 0x50)
        c.feed(0x40)
        c.feed(0x55)
        assertEquals<List<MidiMessage>>(
            listOf(NoteOn(0, 60, 100), NoteOn(0, 62, 80), NoteOn(0, 64, 85)),
            c.messages,
        )
    }

    @Test
    fun aRealTimeByteInsideAnotherMessageIsEmittedAtOnceAndBreaksNothing() {
        val c = Collector()
        c.feed(0x90, 0x3C, 0xF8, 0x64)
        assertEquals<List<MidiMessage>>(listOf(TimingClock, NoteOn(0, 60, 100)), c.messages)
    }

    @Test
    fun aRealTimeByteDoesNotDisturbRunningStatus() {
        val c = Collector()
        c.feed(0x90, 0x3C, 0x64)
        c.feed(0xFE)
        c.feed(0x3E, 0x50)
        assertEquals<List<MidiMessage>>(listOf(NoteOn(0, 60, 100), ActiveSensing, NoteOn(0, 62, 80)), c.messages)
    }

    @Test
    fun aSysExSplitAcrossThreeChunksEmitsOnceWhenItCloses() {
        val c = Collector()
        c.feed(0xF0, 0x7E)
        assertTrue(c.messages.isEmpty())
        c.feed(0x7F, 0x06)
        assertTrue(c.messages.isEmpty())
        c.feed(0x01, 0xF7)
        assertEquals(1, c.messages.size)
        val sysex = assertIs<SysEx>(c.messages[0])
        assertContentEquals(bytes(0x7E, 0x7F, 0x06, 0x01), sysex.data)
    }

    @Test
    fun aRealTimeByteInsideASysExDoesNotEndUpInItsData() {
        val c = Collector()
        c.feed(0xF0, 0x7E, 0xF8, 0x7F, 0x06, 0x01, 0xF7)
        assertEquals(2, c.messages.size)
        assertEquals(TimingClock, c.messages[0])
        assertContentEquals(bytes(0x7E, 0x7F, 0x06, 0x01), assertIs<SysEx>(c.messages[1]).data)
    }

    @Test
    fun anUnterminatedSysExIsDiscardedWhenAStatusByteArrives() {
        val c = Collector()
        c.feed(0xF0, 0x7E, 0x7F)
        c.feed(0x90, 0x3C, 0x64)
        assertEquals<List<MidiMessage>>(listOf(NoteOn(0, 60, 100)), c.messages, "the abandoned sysex must not be emitted")
    }

    @Test
    fun aDataByteWithNoStatusIsIgnoredRatherThanThrowing() {
        val c = Collector()
        c.feed(0x3C, 0x64)
        c.feed(0x90, 0x3C, 0x64)
        assertEquals<List<MidiMessage>>(listOf(NoteOn(0, 60, 100)), c.messages)
    }

    @Test
    fun aTwoByteMessageNeedsOnlyOneDataByte() {
        val c = Collector()
        c.feed(0xB0, 0x40, 0x7F)
        c.feed(0xC0, 0x05)
        assertEquals(ControlChange(0, 64, 127), c.messages[0])
        assertEquals(2, c.messages.size)
    }

    @Test
    fun aSystemCommonMessageClearsRunningStatus() {
        val c = Collector()
        c.feed(0x90, 0x3C, 0x64)
        c.feed(0xF6)
        c.feed(0x3E, 0x50)
        assertEquals(2, c.messages.size, "the two data bytes after a tune request have no status to belong to")
    }

    @Test
    fun resetDropsAnyHalfReadMessage() {
        val c = Collector()
        c.feed(0x90, 0x3C)
        c.parser.reset()
        c.feed(0x64)
        assertTrue(c.messages.isEmpty(), "after a reset the stray data byte belongs to nothing")
    }

    @Test
    fun feedRespectsOffsetAndCount() {
        val messages = ArrayList<MidiMessage>()
        val parser = MidiStreamParser { messages.add(it) }
        val buffer = bytes(0xAA, 0xBB, 0x90, 0x3C, 0x64, 0xCC)
        parser.feed(buffer, offset = 2, count = 3)
        assertEquals<List<MidiMessage>>(listOf(NoteOn(0, 60, 100)), messages)
    }
    @Test
    fun oversizedSysExIsDiscardedAndNextMessagesRecover() {
        val messages = ArrayList<MidiMessage>()
        val parser = MidiStreamParser(maxSysExBytes = 2) { messages += it }
        parser.feed(bytes(0xf0, 1, 2, 3, 0xf8, 0xf7, 0x90, 60, 100))
        assertEquals(listOf(TimingClock, NoteOn(0, 60, 100)), messages)
        parser.feed(bytes(0xf0, 1, 2, 0xf7))
        assertContentEquals(bytes(1, 2), (messages.last() as SysEx).data)
    }

}
