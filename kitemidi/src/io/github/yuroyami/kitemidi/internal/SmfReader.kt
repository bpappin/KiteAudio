package io.github.yuroyami.kitemidi.internal

import io.github.yuroyami.kitemidi.Division
import io.github.yuroyami.kitemidi.MetaEvent
import io.github.yuroyami.kitemidi.MidiEvent
import io.github.yuroyami.kitemidi.MidiFormatException
import io.github.yuroyami.kitemidi.SmfEvent
import io.github.yuroyami.kitemidi.SmfFile
import io.github.yuroyami.kitemidi.SmfTrack
import io.github.yuroyami.kitemidi.SysExChunk
import io.github.yuroyami.kitemidi.SysExKind

private const val MTHD = 0x4D546864L
private const val MTRK = 0x4D54726BL

internal fun readSmf(bytes: ByteArray): SmfFile {
    val cursor = ByteCursor(bytes)
    if (cursor.remaining < 14) cursor.fail("a file needs at least a 14-byte header, got ${bytes.size} bytes")

    if (cursor.readUIntBe() != MTHD) throw MidiFormatException("not a MIDI file: the first four bytes are not MThd", 0)
    val headerLength = cursor.readUIntBe()
    if (headerLength < 6) cursor.fail("the header declares $headerLength bytes, which is fewer than the six it needs")

    val format = cursor.readUShortBe()
    if (format !in 0..2) cursor.fail("format $format is not 0, 1 or 2")
    val declaredTracks = cursor.readUShortBe()
    val division = readDivision(cursor)

    // A header longer than six bytes is legal. Skip whatever follows rather than refusing the file.
    if (headerLength > 6) cursor.skip((headerLength - 6).toInt())

    val tracks = ArrayList<SmfTrack>(declaredTracks)
    while (cursor.remaining > 0) {
        if (cursor.remaining < 8) cursor.fail("incomplete chunk header")
        val type = cursor.readUIntBe()
        val length = cursor.readUIntBe()
        if (length > cursor.remaining) {
            cursor.fail("a chunk declares $length bytes but only ${cursor.remaining} are left")
        }
        val chunk = cursor.readBytes(length.toInt())
        // Anything that is not a track is skipped. Some programs write their own chunks.
        if (type == MTRK) tracks.add(readTrack(chunk))
    }
    if (tracks.size != declaredTracks) cursor.fail("header declares $declaredTracks tracks, found ${tracks.size}")
    return SmfFile(format, division, tracks)
}

private fun readDivision(cursor: ByteCursor): Division {
    val offset = cursor.position
    val word = cursor.readUShortBe()
    return if (word and 0x8000 == 0) {
        if (word == 0) throw MidiFormatException("ticks per quarter must be positive", offset)
        Division.Ppq(word and 0x7FFF)
    } else {
        // The high byte is the frame rate as a negative signed byte, so 0xE7 is -25, meaning 25 fps.
        val fps = 256 - ((word shr 8) and 0xFF)
        val ticksPerFrame = word and 0xFF
        if (fps !in listOf(24, 25, 29, 30)) throw MidiFormatException("SMPTE frame rate $fps is not 24, 25, 29 or 30")
        if (ticksPerFrame == 0) throw MidiFormatException("ticks per frame must be positive", offset)
        Division.Smpte(fps, ticksPerFrame)
    }
}

private fun readTrack(chunk: ByteArray): SmfTrack {
    val cursor = ByteCursor(chunk)
    val events = ArrayList<SmfEvent>()
    var runningStatus = -1

    while (cursor.remaining > 0) {
        val delta = cursor.readVarLen()
        val first = cursor.peekByte()

        val event: MidiEvent = when {
            first == 0xFF -> {
                cursor.readByte()
                val type = cursor.readByte()
                val length = cursor.readVarLen()
                val payload = cursor.readBytes(length)
                runningStatus = -1
                MetaEvent.parse(type, payload)
            }
            first == 0xF0 || first == 0xF7 -> {
                val kind = if (cursor.readByte() == 0xF0) SysExKind.F0 else SysExKind.F7
                val length = cursor.readVarLen()
                runningStatus = -1
                SysExChunk(kind, cursor.readBytes(length))
            }
            else -> {
                val parsed = io.github.yuroyami.kitemidi.KiteMidi.parseMessage(chunk, cursor.position, runningStatus)
                    ?: cursor.fail("a track event ran past the end of its chunk")
                cursor.skip(parsed.length)
                runningStatus = parsed.runningStatus
                parsed.message
            }
        }

        events.add(SmfEvent(delta, event))
        if (event === io.github.yuroyami.kitemidi.EndOfTrack) break
    }
    return SmfTrack(events)
}
