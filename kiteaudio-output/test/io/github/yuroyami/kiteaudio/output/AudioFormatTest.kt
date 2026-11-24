package io.github.yuroyami.kiteaudio.output

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Converts durations to frames at the boundaries where an Int or a Long product overflows. */
class AudioFormatTest {
    private val format = AudioFormat(48_000)

    @Test
    fun thirteenHoursAtFortyEightKilohertzIsPastTheIntRange() {
        assertEquals(2_246_400_000L, format.microsToFrames(46_800_000_000L))
    }

    @Test
    fun theLongestDurationGivesTheValueOfTheFormula() {
        // Long.MAX_VALUE is 9_223_372_036_854 seconds and 775_807 microseconds.
        // 9_223_372_036_854 * 48_000 = 442_721_857_768_992_000, and 775_807 * 48_000 / 1_000_000 = 37_238.
        assertEquals(442_721_857_769_029_238L, format.microsToFrames(Long.MAX_VALUE))
    }

    @Test
    fun theLongestDurationIsExactAtOneMegahertz() {
        assertEquals(Long.MAX_VALUE, AudioFormat(1_000_000).microsToFrames(Long.MAX_VALUE))
    }

    @Test
    fun theFramesAroundIntMaxValueRoundDown() {
        // Frame n starts at n * 125 / 6 microseconds at 48 kHz.
        assertEquals(Int.MAX_VALUE.toLong() - 1, format.microsToFrames(44_739_242_645L))
        assertEquals(Int.MAX_VALUE.toLong(), format.microsToFrames(44_739_242_646L))
        assertEquals(Int.MAX_VALUE.toLong(), format.microsToFrames(44_739_242_666L))
        assertEquals(Int.MAX_VALUE.toLong() + 1, format.microsToFrames(44_739_242_667L))
    }

    @Test
    fun theOldProductOverflowedPastThisDuration() {
        // micros * 48_000 passes Long.MAX_VALUE above 192_153_584_101_141 microseconds.
        assertEquals(9_223_372_036_854L, format.microsToFrames(192_153_584_101_141L))
        assertEquals(9_223_372_036_854L, format.microsToFrames(192_153_584_101_142L))
    }

    @Test
    fun zeroAndOneFrame() {
        // One frame lasts 20.83 microseconds at 48 kHz.
        assertEquals(0L, format.microsToFrames(0L))
        assertEquals(0L, format.microsToFrames(20L))
        assertEquals(1L, format.microsToFrames(21L))
    }

    @Test
    fun aNegativeDurationThrows() {
        val problem = assertFailsWith<IllegalArgumentException> { format.microsToFrames(-1L) }
        assertTrue("-1" in problem.message.orEmpty(), problem.message)
        assertFailsWith<IllegalArgumentException> { format.microsToFrames(Long.MIN_VALUE) }
    }
}
