package io.github.yuroyami.kiteaudio.midi

/** A tempo in force from [tick] until the next entry. */
public data class TempoEntry(public val tick: Long, public val microsPerQuarter: Int)

/**
 * Converts between ticks and microseconds, both ways.
 *
 * A file states its tempo as a list of changes, so the conversion is a walk over the segments
 * between them rather than one multiplication. When the file states no tempo, the
 * specification's default of 500000 microseconds per quarter note applies, which is 120 beats
 * per minute. A file that counts in SMPTE ticks ignores tempo entirely: its ticks are already
 * real time.
 */
public class TempoMap(
    public val division: Division,
    entries: List<TempoEntry>,
) {
    /** The tempo changes, in tick order, always starting at tick 0. */
    public val entries: List<TempoEntry> = buildList {
        val sorted = entries.sortedBy { it.tick }
        if (sorted.firstOrNull()?.tick != 0L) add(TempoEntry(0L, DEFAULT_MICROS_PER_QUARTER))
        addAll(sorted)
    }

    /** Microseconds elapsed from tick 0 to [tick]. */
    public fun tickToMicros(tick: Long): Long {
        require(tick >= 0) { "a tick cannot be negative, got $tick" }
        val ppq = (division as? Division.Ppq)?.ticksPerQuarter
            ?: run {
                val (num, den) = smpteTicksPerSecond()
                val scale = 1_000_000L * den
                return tick / num * scale + (tick % num) * scale / num
            }

        var micros = 0L
        var remainder = 0L
        for (index in entries.indices) {
            val start = entries[index].tick
            if (start >= tick) break
            val end = entries.getOrNull(index + 1)?.tick ?: Long.MAX_VALUE
            val span = minOf(tick, end) - start
            val mpq = entries[index].microsPerQuarter.toLong()
            val fractional = (span % ppq) * mpq + remainder
            micros += span / ppq * mpq + fractional / ppq
            remainder = fractional % ppq
        }
        return micros
    }

    /** The tick that [micros] lands on, rounded to the nearest one. */
    public fun microsToTick(micros: Long): Long {
        require(micros >= 0) { "a duration cannot be negative, got $micros" }
        val ppq = (division as? Division.Ppq)?.ticksPerQuarter
            ?: run {
                val (num, den) = smpteTicksPerSecond()
                val scale = 1_000_000L * den
                return (micros / scale) * num + ((micros % scale) * num + scale / 2) / scale
            }

        var elapsed = 0L
        var remainder = 0L
        for (index in entries.indices) {
            val mpq = entries[index].microsPerQuarter.toLong()
            val start = entries[index].tick
            val end = entries.getOrNull(index + 1)?.tick
            if (end != null) {
                val span = end - start
                val fractional = (span % ppq) * mpq + remainder
                val endMicros = elapsed + span / ppq * mpq + fractional / ppq
                val endRemainder = fractional % ppq
                if (micros > endMicros || (micros == endMicros && endRemainder == 0L)) {
                    elapsed = endMicros
                    remainder = endRemainder
                    continue
                }
            }
            val remaining = micros - elapsed
            // Divide before multiplying, and subtract the carried fraction before rounding.
            val wholeTicks = remaining / mpq * ppq
            val partial = (remaining % mpq) * ppq - remainder
            val rounded = (partial + mpq / 2) / mpq
            return start + wholeTicks + rounded
        }
        return entries.lastOrNull()?.tick ?: 0L
    }

    /** The tempo in force at [tick], said the way musicians say it. */
    public fun bpmAt(tick: Long): Double = 60_000_000.0 / microsPerQuarterAt(tick)

    /** The tempo in force at [tick], in microseconds per quarter note. */
    public fun microsPerQuarterAt(tick: Long): Int {
        var current = DEFAULT_MICROS_PER_QUARTER
        for (entry in entries) {
            if (entry.tick > tick) break
            current = entry.microsPerQuarter
        }
        return current
    }

    /**
     * Ticks per second as a fraction, because one of the four frame rates is not a whole number.
     *
     * A division byte of -29 means 30 drop frame, which really runs at 30000/1001 frames a
     * second. Treating it as 29 makes every conversion for such a file 3.4 percent long.
     */
    private fun smpteTicksPerSecond(): Pair<Long, Long> {
        val smpte = division as Division.Smpte
        val ticksPerFrame = smpte.ticksPerFrame.toLong()
        return if (smpte.framesPerSecond == 29) {
            30_000L * ticksPerFrame to 1001L
        } else {
            smpte.framesPerSecond.toLong() * ticksPerFrame to 1L
        }
    }

    public companion object {
        /** What the specification says to assume when a file states no tempo: 120 beats per minute. */
        public const val DEFAULT_MICROS_PER_QUARTER: Int = 500_000
    }
}
