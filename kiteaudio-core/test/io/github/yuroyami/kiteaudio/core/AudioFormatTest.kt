package io.github.yuroyami.kiteaudio.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Converts between frames and durations at the boundaries where an Int or a Long product overflows. */
class AudioFormatTest {
    private val format = AudioFormat(48_000, 2)

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
        assertEquals(Long.MAX_VALUE, AudioFormat(1_000_000, 2).microsToFrames(Long.MAX_VALUE))
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

    @Test
    fun aDurationWithMoreFramesThanALongHoldsThrows() {
        // At 2 MHz, 4_611_686_018_427_387_903 microseconds is the longest duration whose frames fit a Long.
        val fast = AudioFormat(2_000_000, 1)
        assertEquals(9_223_372_036_854_775_806L, fast.microsToFrames(4_611_686_018_427_387_903L))
        assertFailsWith<IllegalArgumentException> { fast.microsToFrames(4_611_686_018_427_387_904L) }
        assertFailsWith<IllegalArgumentException> { AudioFormat(Int.MAX_VALUE, 1).microsToFrames(Long.MAX_VALUE) }
    }

    @Test
    fun framesToMicrosRoundsDown() {
        assertEquals(0L, format.framesToMicros(0))
        assertEquals(20L, format.framesToMicros(1))
        assertEquals(1_000_000L, format.framesToMicros(48_000))
        assertEquals(44_739_242_645L, format.framesToMicros(Int.MAX_VALUE))
    }

    @Test
    fun framesToMicrosTakesALongCountPastTheIntRange() {
        // 2_246_400_000 frames are thirteen hours at 48 kHz.
        assertEquals(46_800_000_000L, format.framesToMicros(2_246_400_000L))
        assertEquals(format.framesToMicros(48_000), format.framesToMicros(48_000L))
    }

    @Test
    fun theLongestFrameCountIsExactAtOneMegahertz() {
        assertEquals(Long.MAX_VALUE, AudioFormat(1_000_000, 2).framesToMicros(Long.MAX_VALUE))
    }

    @Test
    fun aFrameCountThatLastsLongerThanALongHoldsThrows() {
        // At 48 kHz, 442_721_857_769_029_238 frames is the longest count whose duration fits a Long.
        assertEquals(9_223_372_036_854_775_791L, format.framesToMicros(442_721_857_769_029_238L))
        assertFailsWith<IllegalArgumentException> { format.framesToMicros(442_721_857_769_029_239L) }
        assertFailsWith<IllegalArgumentException> { format.framesToMicros(Long.MAX_VALUE) }
    }

    @Test
    fun aNegativeFrameCountThrows() {
        val problem = assertFailsWith<IllegalArgumentException> { format.framesToMicros(-1) }
        assertTrue("-1" in problem.message.orEmpty(), problem.message)
        assertFailsWith<IllegalArgumentException> { format.framesToMicros(-1L) }
        assertFailsWith<IllegalArgumentException> { format.framesToMicros(Long.MIN_VALUE) }
    }

    @Test
    fun theConstructorsRejectImpossibleFormats() {
        assertFailsWith<IllegalArgumentException> { AudioFormat(0, 2) }
        assertFailsWith<IllegalArgumentException> { AudioFormat(-44_100, ChannelLayout.STEREO) }
        assertFailsWith<IllegalArgumentException> { AudioFormat(48_000, 0) }
        assertFailsWith<IllegalArgumentException> { AudioFormat(48_000, -1) }
        assertFailsWith<IllegalArgumentException> { AudioFormat(48_000, 4097) }
    }

    @Test
    fun aChannelCountGetsTheCanonicalLayout() {
        assertEquals(ChannelLayout.SURROUND_5_1, AudioFormat(48_000, 6).channelLayout)
        assertEquals(6, AudioFormat(48_000, 6).channels)
        assertEquals(ChannelLayout.unknown(7), AudioFormat(48_000, 7).channelLayout)
    }

    @Test
    fun formatsWithTheSameRateAndLayoutAreEqual() {
        val other = AudioFormat(48_000, ChannelLayout.STEREO)
        assertEquals(format, other)
        assertEquals(format.hashCode(), other.hashCode())
        assertNotEquals(format, AudioFormat(44_100, 2))
        assertNotEquals(
            format,
            AudioFormat(48_000, ChannelLayout.of(AudioChannel.FRONT_RIGHT, AudioChannel.FRONT_LEFT)),
        )
        assertNotEquals(format, AudioFormat(48_000, ChannelLayout.unknown(2)))
    }

    @Test
    fun toStringNamesTheRateAndTheChannels() {
        assertEquals("AudioFormat(48000Hz, ChannelLayout(FRONT_LEFT, FRONT_RIGHT))", format.toString())
    }
}
