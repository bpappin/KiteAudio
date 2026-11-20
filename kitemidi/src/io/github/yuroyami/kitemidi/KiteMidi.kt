package io.github.yuroyami.kitemidi

import io.github.yuroyami.kitemidi.internal.ByteCursor
import io.github.yuroyami.kitemidi.internal.checkDeltas
import io.github.yuroyami.kitemidi.internal.readSmf
import io.github.yuroyami.kitemidi.internal.writeSmf

/** The entry point. Everything this library does starts from one of these calls. */
public object KiteMidi {

    /**
     * Reads a Standard MIDI File.
     *
     * @throws MidiFormatException when the bytes are not a file this library can read. Nothing
     *   else is thrown: a truncated or hostile file ends here, not in an index error.
     */
    @Throws(MidiFormatException::class)
    public fun read(bytes: ByteArray): SmfFile = readSmf(bytes)

    /**
     * Writes a Standard MIDI File. Reading the result gives back the same events.
     * Wire system messages require explicit [SysExChunk] framing.
     * @throws IllegalArgumentException for unsupported wire events, invalid deltas or events after EndOfTrack.
     */
    @Throws(IllegalArgumentException::class)
    public fun write(file: SmfFile): ByteArray {
        checkDeltas(file)
        return writeSmf(file)
    }

    /** Reads a file straight into the merged, absolute-tick form a player wants. */
    @Throws(MidiFormatException::class, IllegalArgumentException::class)
    public fun readTimeline(bytes: ByteArray): MidiTimeline = read(bytes).toTimeline()

    /**
     * Reads one complete, already separated message out of [bytes] starting at [offset].
     *
     * Interleaved real-time messages must be separated first; use MidiStreamParser in
     * kitemidi-io for arbitrary wire streams and fragmented input. This single-result API
     * rejects status bytes inside channel data or SysEx rather than losing another message.
     *
     * @param runningStatus the status byte to assume when the byte at [offset] is a data byte,
     *   or -1 when there is none. A file or stream that opens with a data byte and no running
     *   status is malformed, and this throws.
     * @return the message and how to continue, or null when [offset] is at or past the end.
     */
    @Throws(MidiFormatException::class, IllegalArgumentException::class)
    public fun parseMessage(bytes: ByteArray, offset: Int = 0, runningStatus: Int = -1): ParsedMessage? {
        require(offset >= 0) { "offset must be nonnegative, got $offset" }
        if (offset >= bytes.size) return null
        val cursor = ByteCursor(bytes, offset)
        val first = cursor.peekByte()

        // A system real-time byte may appear anywhere, even inside another message, and it
        // never disturbs running status.
        if (first >= 0xF8) {
            cursor.readByte()
            val message = when (first) {
                0xF8 -> TimingClock
                0xFA -> Start
                0xFB -> Continue
                0xFC -> Stop
                0xFE -> ActiveSensing
                0xFF -> SystemReset
                else -> cursor.fail("0x${first.toString(16)} is undefined in the system real-time range")
            }
            return ParsedMessage(message, cursor.position - offset, runningStatus)
        }

        val status: Int
        if (first >= 0x80) {
            status = cursor.readByte()
        } else {
            if (runningStatus < 0) cursor.fail("data byte 0x${first.toString(16)} with no running status")
            status = runningStatus
        }

        // System common clears running status; a channel message sets it.
        val nextRunningStatus = if (status in 0x80..0xEF) status else -1

        val message: MidiMessage = when {
            status in 0x80..0xEF -> {
                val channel = status and 0x0F
                when (status and 0xF0) {
                    0x80 -> NoteOff(channel, cursor.readDataByte(), cursor.readDataByte())
                    0x90 -> NoteOn(channel, cursor.readDataByte(), cursor.readDataByte())
                    0xA0 -> PolyPressure(channel, cursor.readDataByte(), cursor.readDataByte())
                    0xB0 -> ControlChange(channel, cursor.readDataByte(), cursor.readDataByte())
                    0xC0 -> ProgramChange(channel, cursor.readDataByte())
                    0xD0 -> ChannelPressure(channel, cursor.readDataByte())
                    else -> {
                        val lsb = cursor.readDataByte()
                        val msb = cursor.readDataByte()
                        PitchBend(channel, ((msb shl 7) or lsb) - 8192)
                    }
                }
            }
            status == 0xF0 -> {
                val body = ArrayList<Byte>()
                while (true) {
                    if (cursor.remaining == 0) cursor.fail("system exclusive with no closing 0xF7")
                    val b = cursor.readByte()
                    if (b == 0xF7) break
                    if (b >= 0x80) cursor.fail("status 0x${b.toString(16)} inside SysEx; separate interleaved messages first")
                    body.add(b.toByte())
                }
                SysEx(body.toByteArray())
            }
            status == 0xF1 -> TimeCodeQuarterFrame(cursor.readDataByte())
            status == 0xF2 -> {
                val lsb = cursor.readDataByte()
                val msb = cursor.readDataByte()
                SongPosition((msb shl 7) or lsb)
            }
            status == 0xF3 -> SongSelect(cursor.readDataByte())
            status == 0xF6 -> TuneRequest
            else -> cursor.fail("0x${status.toString(16)} is not a message this library reads")
        }
        return ParsedMessage(message, cursor.position - offset, nextRunningStatus)
    }
}

/** Reads one data byte, rejecting a status byte where a value belongs. */
private fun ByteCursor.readDataByte(): Int {
    val value = readByte()
    if (value >= 0x80) fail("expected a data byte, found status 0x${value.toString(16)}")
    return value
}
