package io.github.yuroyami.kiteaudio.consumer

import io.github.yuroyami.kiteaudio.midi.MidiMessage
import io.github.yuroyami.kiteaudio.midi.NoteOn
import io.github.yuroyami.kiteaudio.midi.coremidi.coreMidiAvailable
import io.github.yuroyami.kiteaudio.midi.devices.MidiStreamParser
import kotlin.test.Test
import kotlin.test.assertEquals

class DevicesConsumerTest {

    @Test
    fun theParserReadsRunningStatus() {
        val messages = mutableListOf<MidiMessage>()
        MidiStreamParser { messages += it }.feed(byteArrayOf(0x90.toByte(), 60, 100, 62, 90))
        assertEquals(listOf<MidiMessage>(NoteOn(0, 60, 100), NoteOn(0, 62, 90)), messages)
    }

    @Test
    fun coreMidiIsAvailableOnMacOsOnly() {
        // The call opens no port. It only links the module and asks the operating system.
        val macOs = System.getProperty("os.name").orEmpty().startsWith("Mac")
        if (!macOs) assertEquals(false, coreMidiAvailable())
    }
}
