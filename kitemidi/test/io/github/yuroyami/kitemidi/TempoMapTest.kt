package io.github.yuroyami.kitemidi

import kotlin.test.Test
import kotlin.test.assertEquals

class TempoMapTest {

    private val ppq = Division.Ppq(96)

    @Test
    fun aSingleTempoConvertsTicksToMicroseconds() {
        val map = TempoMap(ppq, listOf(TempoEntry(0, 500_000)))
        assertEquals(0L, map.tickToMicros(0))
        assertEquals(250_000L, map.tickToMicros(48))
        assertEquals(500_000L, map.tickToMicros(96))
        assertEquals(1_000_000L, map.tickToMicros(192))
    }

    @Test
    fun aTempoChangeAppliesFromItsOwnTick() {
        val map = TempoMap(ppq, listOf(TempoEntry(0, 500_000), TempoEntry(96, 250_000)))
        assertEquals(500_000L, map.tickToMicros(96))
        assertEquals(750_000L, map.tickToMicros(192))
        assertEquals(625_000L, map.tickToMicros(144))
    }

    @Test
    fun microsecondsConvertBackToTicks() {
        val map = TempoMap(ppq, listOf(TempoEntry(0, 500_000), TempoEntry(96, 250_000)))
        for (tick in listOf(0L, 48L, 96L, 144L, 192L, 500L)) {
            assertEquals(tick, map.microsToTick(map.tickToMicros(tick)), "round trip at tick $tick")
        }
    }

    @Test
    fun aFileWithNoTempoUsesTheSpecifiedDefault() {
        val map = TempoMap(ppq, emptyList())
        assertEquals(500_000L, map.tickToMicros(96))
        assertEquals(120.0, map.bpmAt(0))
    }

    @Test
    fun aFirstEntryAfterTickZeroStillStartsAtTheDefault() {
        val map = TempoMap(ppq, listOf(TempoEntry(96, 250_000)))
        assertEquals(500_000L, map.tickToMicros(96))
        assertEquals(120.0, map.bpmAt(0))
        assertEquals(240.0, map.bpmAt(96))
    }

    @Test
    fun beatsPerMinuteFollowsTheTempoInForce() {
        val map = TempoMap(ppq, listOf(TempoEntry(0, 500_000), TempoEntry(96, 250_000)))
        assertEquals(120.0, map.bpmAt(0))
        assertEquals(120.0, map.bpmAt(95))
        assertEquals(240.0, map.bpmAt(96))
        assertEquals(240.0, map.bpmAt(10_000))
    }

    @Test
    fun smpteDivisionConvertsByFramesAndIgnoresTempo() {
        val map = TempoMap(Division.Smpte(25, 40), listOf(TempoEntry(0, 250_000)))
        // 25 frames a second at 40 ticks a frame is 1000 ticks a second.
        assertEquals(1_000_000L, map.tickToMicros(1000))
        assertEquals(500L, map.microsToTick(500_000))
    }

    @Test
    fun aRubatoFileWithHundredsOfTempoChangesStillRoundTrips() {
        // A performance exported with one tempo change per beat is a real thing, and at a high
        // resolution one tick is only a few hundred microseconds. Truncating once per segment
        // would accumulate until a tick were lost.
        val ppq960 = Division.Ppq(960)
        // The tempos are deliberately awkward: none divides evenly into the tick rate, so every
        // segment truncates. Both directions walk the same segments, so those truncations cancel
        // and only the final partial segment needs rounding. This test is what holds that true.
        val entries = (0 until 600).map { index ->
            TempoEntry(tick = index * 240L, microsPerQuarter = 250_000 + (index % 7) * 1_001 + index % 3)
        }
        val map = TempoMap(ppq960, entries)

        for (tick in 0L until 600L * 240L step 240L) {
            assertEquals(tick, map.microsToTick(map.tickToMicros(tick)), "round trip at tick $tick")
        }
        // And on ticks that land inside a segment rather than on its edge.
        for (tick in listOf(37L, 1_001L, 55_555L, 100_000L, 143_999L)) {
            assertEquals(tick, map.microsToTick(map.tickToMicros(tick)), "round trip at tick $tick")
        }
    }

    @Test
    fun aLongPieceDoesNotOverflow() {
        // Two hours at 960 ticks per quarter and 120 beats per minute is about 13.8 million ticks.
        val map = TempoMap(Division.Ppq(960), listOf(TempoEntry(0, 500_000)))
        val twoHoursOfTicks = 13_824_000L
        assertEquals(7_200_000_000L, map.tickToMicros(twoHoursOfTicks))
        assertEquals(twoHoursOfTicks, map.microsToTick(7_200_000_000L))
    }
}
