package io.github.yuroyami.kiteaudio.midi

/** One event at an absolute tick, with the track it came from. */
public data class TimedEvent(public val tick: Long, public val track: Int, public val event: MidiEvent)

/**
 * One note, from the key going down to it coming back up.
 *
 * @param offVelocity how fast the key was released. Few instruments send it, so it is often 0.
 */
public data class MidiNote(
    public val startTick: Long,
    public val endTick: Long,
    public val key: Int,
    public val velocity: Int,
    public val offVelocity: Int,
    public val channel: Int,
    public val track: Int,
) {
    /** How long the key was held, in ticks. */
    public val durationTicks: Long get() = endTick - startTick
}

/**
 * Every track merged into one list at absolute ticks, with the tempo map and the notes paired.
 *
 * This is what a player reads. The file's own shape, with its per-track delta times, is what
 * [SmfFile] keeps.
 */
public class MidiTimeline(
    public val division: Division,
    public val events: List<TimedEvent>,
    public val tempoMap: TempoMap,
) {

    /** Every note, paired from its start to its end, in start order. */
    public val notes: List<MidiNote> by lazy { pairNotes() }

    /** The last tick anything happens on. */
    public val endTick: Long get() = events.lastOrNull()?.tick ?: 0L

    /** How long the piece lasts, in microseconds. */
    public val durationMicros: Long get() = tempoMap.tickToMicros(endTick)

    private fun pairNotes(): List<MidiNote> {
        // A key can be struck again before it is released, so each key holds a queue and the
        // oldest press is the one a release closes.
        val open = HashMap<Int, ArrayDeque<TimedEvent>>()
        val out = ArrayList<MidiNote>()
        val trackEnd = HashMap<Int, Long>()

        for (timed in events) {
            trackEnd[timed.track] = timed.tick
            val message = timed.event
            if (message !is MidiMessage.ChannelMessage) continue

            when {
                message is NoteOn && message.velocity > 0 -> {
                    open.getOrPut(voiceKey(message.channel, message.key)) { ArrayDeque() }.addLast(timed)
                }
                message is NoteOff || (message is NoteOn && message.velocity == 0) -> {
                    val note = if (message is NoteOff) message.key else (message as NoteOn).key
                    val offVelocity = if (message is NoteOff) message.velocity else 0
                    val queue = open[voiceKey(message.channel, note)] ?: continue
                    // Prefer a press on this same track, because that is almost always the one
                    // meant. Falling back to another track matters for a format 1 file, where
                    // every track shares the same sixteen channels and a note started on one
                    // track is legitimately released from another.
                    val sameTrack = queue.indexOfFirst { it.track == timed.track }
                    val start = if (sameTrack >= 0) queue.removeAt(sameTrack) else queue.removeFirstOrNull()
                    if (start == null) continue
                    val on = start.event as NoteOn
                    out.add(
                        MidiNote(
                            startTick = start.tick,
                            endTick = timed.tick,
                            key = on.key,
                            velocity = on.velocity,
                            offVelocity = offVelocity,
                            channel = on.channel,
                            track = start.track,
                        ),
                    )
                }
            }
        }

        // A file can end with keys still down. Close them where their own track ends, which is
        // what a player would do anyway.
        for (queue in open.values) {
            for (start in queue) {
                val on = start.event as NoteOn
                out.add(
                    MidiNote(
                        startTick = start.tick,
                        endTick = trackEnd[start.track] ?: start.tick,
                        key = on.key,
                        velocity = on.velocity,
                        offVelocity = 0,
                        channel = on.channel,
                        track = start.track,
                    ),
                )
            }
        }
        return out.sortedWith(compareBy({ it.startTick }, { it.key }))
    }

    // Channel and note only. The track is deliberately not here: see the note-off branch.
    private fun voiceKey(channel: Int, note: Int): Int = (channel shl 8) or note
}

/**
 * Merges every track of a format 0 or 1 file into one absolute-tick timeline.
 *
 * Format 2 sequences must be selected explicitly by constructing a format 0 [SmfFile]
 * containing the desired track. An implicit merge is rejected with [IllegalArgumentException].
 *
 * Events that land on the same tick keep the order their tracks appear in the file, so a
 * program change written before a note on the next track still comes first.
 */
@Throws(IllegalArgumentException::class)
public fun SmfFile.toTimeline(): MidiTimeline {
    require(format != 2) { "format 2 contains independent sequences; select one track and construct a format 0 SmfFile before converting" }
    val merged = ArrayList<TimedEvent>()
    val tempos = ArrayList<TempoEntry>()

    for ((trackIndex, track) in tracks.withIndex()) {
        var tick = 0L
        for (event in track.events) {
            tick += event.delta
            merged.add(TimedEvent(tick, trackIndex, event.event))
            val payload = event.event
            if (payload is SetTempo) tempos.add(TempoEntry(tick, payload.microsPerQuarter))
        }
    }
    // sortedBy is stable, so the track order survives a tie.
    return MidiTimeline(division, merged.sortedBy { it.tick }, TempoMap(division, tempos))
}
