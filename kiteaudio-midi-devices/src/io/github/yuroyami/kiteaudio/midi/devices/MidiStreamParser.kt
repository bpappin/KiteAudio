package io.github.yuroyami.kiteaudio.midi.devices

import io.github.yuroyami.kiteaudio.midi.ActiveSensing
import io.github.yuroyami.kiteaudio.midi.ChannelPressure
import io.github.yuroyami.kiteaudio.midi.Continue
import io.github.yuroyami.kiteaudio.midi.ControlChange
import io.github.yuroyami.kiteaudio.midi.MidiMessage
import io.github.yuroyami.kiteaudio.midi.NoteOff
import io.github.yuroyami.kiteaudio.midi.NoteOn
import io.github.yuroyami.kiteaudio.midi.PitchBend
import io.github.yuroyami.kiteaudio.midi.PolyPressure
import io.github.yuroyami.kiteaudio.midi.ProgramChange
import io.github.yuroyami.kiteaudio.midi.SongPosition
import io.github.yuroyami.kiteaudio.midi.SongSelect
import io.github.yuroyami.kiteaudio.midi.Start
import io.github.yuroyami.kiteaudio.midi.Stop
import io.github.yuroyami.kiteaudio.midi.SysEx
import io.github.yuroyami.kiteaudio.midi.SystemReset
import io.github.yuroyami.kiteaudio.midi.TimeCodeQuarterFrame
import io.github.yuroyami.kiteaudio.midi.TimingClock
import io.github.yuroyami.kiteaudio.midi.TuneRequest

/**
 * Turns a stream of bytes into whole messages.
 *
 * A cable delivers bytes, not messages. One read can hand you half a note, three notes and the
 * start of a system exclusive message. This keeps what is left over and emits a message the
 * moment it is complete.
 *
 * Three things make that harder than it sounds, and this handles all three. Running status lets
 * a sender leave out a status byte that repeats. A system real-time byte is allowed to appear
 * in the middle of another message, and must be passed on at once without disturbing it. And a
 * system exclusive message can be any length, so it can be split over many reads.
 *
 * Malformed MIDI bytes are discarded: stray data and abandoned SysEx messages do not throw.
 * Invalid feed bounds or a negative buffer limit throw [IllegalArgumentException]. Exceptions
 * from [onMessage] propagate to a standalone parser caller; native backends contain and log
 * their application callbacks at the native boundary.
 *
 * SysEx buffers at most [maxSysExBytes] payload bytes (1 MiB by default). On overflow,
 * the entire message is discarded; subsequent data is ignored until a fresh status arrives.
 * Real-time messages continue to be delivered. Increase the bound for known large transfers.
 *
 * This class is not safe to use from two threads at once. Give each open port its own.
 */
public class MidiStreamParser @Throws(IllegalArgumentException::class) public constructor(
    private val maxSysExBytes: Int = 1_048_576,
    private val onMessage: (MidiMessage) -> Unit,
) {
    init { require(maxSysExBytes >= 0) { "maxSysExBytes must be nonnegative" } }

    /** Preserves the original callback-only constructor. */
    public constructor(onMessage: (MidiMessage) -> Unit) : this(1_048_576, onMessage)

    private var status: Int = -1
    private val data = ArrayList<Int>(2)
    private var expected: Int = 0
    private var inSysEx: Boolean = false
    private val sysExData = ArrayList<Byte>(64)

    /** Feeds [count] bytes from [buffer] starting at [offset]. */
    @Throws(IllegalArgumentException::class)
    public fun feed(buffer: ByteArray, offset: Int = 0, count: Int = buffer.size - offset) {
        require(offset >= 0 && count >= 0 && offset <= buffer.size && count <= buffer.size - offset) {
            "offset $offset and count $count do not fit a buffer of ${buffer.size}"
        }
        for (index in offset until offset + count) {
            feedByte(buffer[index].toInt() and 0xFF)
        }
    }

    /**
     * Drops anything half-read.
     *
     * Call this when a port is reopened, so a message cut in half by the disconnection cannot
     * join up with the first bytes of the new connection.
     */
    public fun reset() {
        status = -1
        data.clear()
        expected = 0
        inSysEx = false
        sysExData.clear()
    }

    private fun feedByte(byte: Int) {
        // A system real-time byte may appear anywhere, even between the data bytes of another
        // message. It is passed on at once and changes nothing else.
        if (byte >= 0xF8) {
            realTime(byte)?.let(onMessage)
            return
        }

        if (inSysEx) {
            when {
                byte == 0xF7 -> {
                    inSysEx = false
                    val complete = SysEx(sysExData.toByteArray())
                    sysExData.clear()
                    onMessage(complete)
                }
                byte >= 0x80 -> {
                    // A status byte where the closing 0xF7 should be means the sender gave up on
                    // this message. Drop what was collected rather than emitting a fragment.
                    inSysEx = false
                    sysExData.clear()
                    feedByte(byte)
                }
                else -> {
                    if (sysExData.size == maxSysExBytes) {
                        inSysEx = false
                        sysExData.clear()
                    } else sysExData.add(byte.toByte())
                }
            }
            return
        }

        if (byte >= 0x80) {
            data.clear()
            when {
                byte == 0xF0 -> {
                    inSysEx = true
                    sysExData.clear()
                    status = -1
                    expected = 0
                }
                byte <= 0xEF -> {
                    status = byte
                    expected = dataBytesFor(byte)
                }
                else -> {
                    status = byte
                    expected = dataBytesFor(byte)
                    if (expected == 0) {
                        emit()
                        // System common does not set running status.
                        status = -1
                    }
                }
            }
            return
        }

        // A data byte. With no status to belong to it is noise, and dropping it is the only
        // thing a receiver can sensibly do.
        if (status < 0) return
        data.add(byte)
        if (data.size >= expected) {
            emit()
            data.clear()
            // Running status survives a channel message and nothing else.
            if (status > 0xEF) status = -1
        }
    }

    private fun emit() {
        val s = status
        if (s < 0) return
        val message: MidiMessage = when {
            s <= 0xEF -> {
                val channel = s and 0x0F
                when (s and 0xF0) {
                    0x80 -> NoteOff(channel, data[0], data[1])
                    0x90 -> NoteOn(channel, data[0], data[1])
                    0xA0 -> PolyPressure(channel, data[0], data[1])
                    0xB0 -> ControlChange(channel, data[0], data[1])
                    0xC0 -> ProgramChange(channel, data[0])
                    0xD0 -> ChannelPressure(channel, data[0])
                    0xE0 -> PitchBend(channel, ((data[1] shl 7) or data[0]) - 8192)
                    else -> return
                }
            }
            s == 0xF1 -> TimeCodeQuarterFrame(data[0])
            s == 0xF2 -> SongPosition((data[1] shl 7) or data[0])
            s == 0xF3 -> SongSelect(data[0])
            s == 0xF6 -> TuneRequest
            else -> return
        }
        onMessage(message)
    }

    private fun realTime(byte: Int): MidiMessage? = when (byte) {
        0xF8 -> TimingClock
        0xFA -> Start
        0xFB -> Continue
        0xFC -> Stop
        0xFE -> ActiveSensing
        0xFF -> SystemReset
        else -> null
    }

    private fun dataBytesFor(status: Int): Int = when {
        status in 0xC0..0xDF -> 1
        status <= 0xEF -> 2
        status == 0xF1 || status == 0xF3 -> 1
        status == 0xF2 -> 2
        else -> 0
    }
}
