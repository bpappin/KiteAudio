package io.github.yuroyami.kiteaudio.midi.internal

import io.github.yuroyami.kiteaudio.midi.Division
import io.github.yuroyami.kiteaudio.midi.EndOfTrack
import io.github.yuroyami.kiteaudio.midi.MetaEvent
import io.github.yuroyami.kiteaudio.midi.MidiMessage
import io.github.yuroyami.kiteaudio.midi.SmfFile
import io.github.yuroyami.kiteaudio.midi.SmfTrack
import io.github.yuroyami.kiteaudio.midi.SysExChunk

/**
 * Writes a file back out.
 *
 * Status bytes are always written in full. Running status would make the file smaller and is
 * perfectly legal, but leaving it out keeps the writer simple and the output easy to read in a
 * hex dump. Reading the result gives back the same events either way.
 */
internal fun writeSmf(file: SmfFile): ByteArray {
    val out = ArrayList<Byte>(1024)
    out.writeAscii("MThd")
    out.writeUIntBe(6)
    out.writeUShortBe(file.format)
    out.writeUShortBe(file.tracks.size)
    out.writeUShortBe(
        when (val d = file.division) {
            is Division.Ppq -> d.ticksPerQuarter
            is Division.Smpte -> (((256 - d.framesPerSecond) and 0xFF) shl 8) or d.ticksPerFrame
        },
    )
    for (track in file.tracks) {
        val body = writeTrack(track)
        out.writeAscii("MTrk")
        out.writeUIntBe(body.size)
        for (b in body) out.add(b)
    }
    return out.toByteArray()
}

private fun writeTrack(track: SmfTrack): ByteArray {
    val out = ArrayList<Byte>(256)
    for ((index, event) in track.events.withIndex()) {
        require(event.event !== EndOfTrack || index == track.events.lastIndex) {
            "EndOfTrack must be the final event of a track"
        }
        out.writeVarLen(event.delta)
        when (val e = event.event) {
            is MetaEvent -> for (b in e.toBytes()) out.add(b)
            is SysExChunk -> {
                out.add(if (e.kind == io.github.yuroyami.kiteaudio.midi.SysExKind.F0) 0xF0.toByte() else 0xF7.toByte())
                out.writeVarLen(e.data.size)
                for (b in e.data) out.add(b)
            }
            is MidiMessage -> {
                require(e is MidiMessage.ChannelMessage) {
                    "SMF system messages require explicit SysExChunk F0/F7 framing; wire ${e::class.simpleName} is not an SMF event"
                }
                for (b in e.toBytes()) out.add(b)
            }
        }
    }
    // Every track ends with end of track. Add it when the caller did not.
    if (track.events.lastOrNull()?.event !== EndOfTrack) {
        out.writeVarLen(0)
        for (b in EndOfTrack.toBytes()) out.add(b)
    }
    return out.toByteArray()
}

private fun MutableList<Byte>.writeAscii(text: String) {
    for (c in text) add(c.code.toByte())
}

private fun MutableList<Byte>.writeUShortBe(value: Int) {
    add(((value shr 8) and 0xFF).toByte())
    add((value and 0xFF).toByte())
}

private fun MutableList<Byte>.writeUIntBe(value: Int) {
    add(((value shr 24) and 0xFF).toByte())
    add(((value shr 16) and 0xFF).toByte())
    add(((value shr 8) and 0xFF).toByte())
    add((value and 0xFF).toByte())
}

/** Kept so the writer's own delta check reports the same message the encoder would. */
internal fun checkDeltas(file: SmfFile) {
    for (track in file.tracks) {
        for (event in track.events) {
            require(event.delta <= MAX_VAR_LEN) {
                "a delta tops out at $MAX_VAR_LEN ticks, got ${event.delta}"
            }
        }
    }
}
