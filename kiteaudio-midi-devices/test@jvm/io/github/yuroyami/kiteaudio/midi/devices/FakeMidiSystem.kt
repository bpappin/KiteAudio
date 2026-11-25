package io.github.yuroyami.kiteaudio.midi.devices

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import javax.sound.midi.MidiDevice
import javax.sound.midi.MidiMessage
import javax.sound.midi.Receiver
import javax.sound.midi.ShortMessage
import javax.sound.midi.Transmitter

/** A javax device list that a test edits, to plug and unplug devices with no hardware. */
internal class FakeMidiSystem {
    @Volatile var attached: List<FakeMidiDevice> = emptyList()

    fun infos(): Array<MidiDevice.Info> = attached.map { it.info }.toTypedArray()

    fun deviceFor(info: MidiDevice.Info): MidiDevice = attached.first { it.info === info }
}

/** A device that both sends and takes messages. Two with the same [name] are identical twins. */
internal class FakeMidiDevice(name: String) : MidiDevice {
    val info: MidiDevice.Info = object : MidiDevice.Info(name, "KiteAudio", "fixture", "1") {}
    val sent = CopyOnWriteArrayList<ByteArray>()
    val transmitters = CopyOnWriteArrayList<FakeTransmitter>()
    val opens = AtomicInteger()
    val closes = AtomicInteger()
    @Volatile private var open = false

    override fun getDeviceInfo() = info
    override fun open() { open = true; opens.incrementAndGet() }
    override fun close() { open = false; closes.incrementAndGet() }
    override fun isOpen() = open
    override fun getMicrosecondPosition() = -1L
    override fun getMaxReceivers() = -1
    override fun getMaxTransmitters() = -1
    override fun getReceiver(): Receiver = object : Receiver {
        // javax throws IllegalStateException when a receiver of a closed device is used.
        override fun send(message: MidiMessage, timeStamp: Long) {
            check(open) { "device is closed" }
            sent += message.message
        }
        override fun close() = Unit
    }
    override fun getReceivers(): List<Receiver> = emptyList()
    override fun getTransmitter(): Transmitter = FakeTransmitter().also { transmitters += it }
    override fun getTransmitters(): List<Transmitter> = transmitters.toList()
}

/** Plays messages into whatever receiver the library set, as the platform thread would. */
internal class FakeTransmitter : Transmitter {
    @Volatile private var target: Receiver? = null
    override fun setReceiver(receiver: Receiver?) { target = receiver }
    override fun getReceiver(): Receiver? = target
    override fun close() = Unit

    fun playNoteOn(key: Int) {
        target?.send(ShortMessage(ShortMessage.NOTE_ON, 0, key, 100), -1L)
    }
}
