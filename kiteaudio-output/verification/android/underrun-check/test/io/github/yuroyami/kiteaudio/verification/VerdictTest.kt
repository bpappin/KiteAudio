package io.github.yuroyami.kiteaudio.verification

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VerdictTest {
    private val second = 1_000_000_000L
    private val gaps = (1..5).map { ForcedGap(it * 3 * second, it * 3 * second + second / 3) }

    private fun event(gap: Int, count: Int, total: Long, platform: Int?) =
        DeviceUnderrunRecord(gaps[gap].endNanos + second / 50, count, total, platform)

    private fun run(
        events: List<DeviceUnderrunRecord> = (0..4).map { event(it, 1, it + 1L, it + 1) },
        platformAtEnd: Int? = 5,
        underruns: Int = 0,
        losses: List<String> = emptyList(),
    ) = RunRecord(
        apiLevel = 35, startedEvents = 1, stoppedEvents = 1, failures = emptyList(), losses = losses,
        underrunEvents = underruns, overrunEvents = 5, deviceUnderruns = events, gaps = gaps, plannedGaps = 5,
        reachedEnd = true, platformAtStart = 0, platformAtEnd = platformAtEnd,
    )

    @Test
    fun aCleanRunPasses() {
        val verdict = judge(run())
        assertTrue(verdict.pass, verdict.problems.toString())
        assertEquals(5, verdict.gapsSeen)
        assertEquals(5L, verdict.countSum)
        assertEquals(0L, verdict.platformOffset)
    }

    @Test
    fun aStartupDropoutKeepsTheOffsetAndPasses() {
        val events = (0..4).map { event(it, 1, it + 1L, it + 2) }
        val verdict = judge(run(events = events, platformAtEnd = 6))
        assertTrue(verdict.pass, verdict.problems.toString())
        assertEquals(1L, verdict.platformOffset)
    }

    @Test
    fun aMissedGapFails() {
        val verdict = judge(run(events = (0..3).map { event(it, 1, it + 1L, it + 1) }, platformAtEnd = 4))
        assertFalse(verdict.pass)
        assertEquals(4, verdict.gapsSeen)
    }

    @Test
    fun aTotalThatSkipsFails() {
        val events = listOf(event(0, 1, 1, 1), event(1, 1, 3, 3), event(2, 1, 4, 4), event(3, 1, 5, 5), event(4, 1, 6, 6))
        assertFalse(judge(run(events = events, platformAtEnd = 6)).pass)
    }

    @Test
    fun aPlatformCounterThatDriftsFails() {
        val events = (0..4).map { event(it, 1, it + 1L, if (it < 3) it + 1 else it + 2) }
        assertFalse(judge(run(events = events, platformAtEnd = 6)).pass)
    }

    @Test
    fun aDropoutTheLibraryMissedAtTheEndFails() {
        assertFalse(judge(run(platformAtEnd = 6)).pass)
    }

    @Test
    fun anUnreadableCounterFallsBackToTheEvents() {
        val events = (0..4).map { event(it, 1, it + 1L, null) }
        val verdict = judge(run(events = events, platformAtEnd = null).copy(platformAtStart = null))
        assertTrue(verdict.pass, verdict.problems.toString())
        assertEquals(null, verdict.platformOffset)
    }

    @Test
    fun aShortRenderOrALossFails() {
        assertFalse(judge(run(underruns = 1)).pass)
        assertFalse(judge(run(losses = listOf("gone"))).pass)
    }

    @Test
    fun anUnforcedEventIsCountedButDoesNotFail() {
        val extra = DeviceUnderrunRecord(gaps[4].endNanos + 3 * second, 1, 6, 6)
        val events = (0..4).map { event(it, 1, it + 1L, it + 1) } + extra
        val verdict = judge(run(events = events, platformAtEnd = 6))
        assertTrue(verdict.pass, verdict.problems.toString())
        assertEquals(1, verdict.unforcedEvents)
    }
}
