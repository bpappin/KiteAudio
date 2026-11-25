package io.github.yuroyami.kiteaudio.verification

/** One DeviceUnderrun event, with the platform counter that the app read when the event arrived. */
data class DeviceUnderrunRecord(
    val timeNanos: Long,
    val count: Int,
    val total: Long,
    /** AudioTrack.getUnderrunCount() of the same track, or null when the app cannot read it. */
    val platformCount: Int?,
)

/** A forced dropout: the render callback slept from [startNanos] to [endNanos]. */
data class ForcedGap(val startNanos: Long, val endNanos: Long)

/** Everything that the check measured in one run. */
data class RunRecord(
    val apiLevel: Int,
    val startedEvents: Int,
    val stoppedEvents: Int,
    val failures: List<String>,
    val losses: List<String>,
    val underrunEvents: Int,
    val overrunEvents: Int,
    val deviceUnderruns: List<DeviceUnderrunRecord>,
    val gaps: List<ForcedGap>,
    val plannedGaps: Int,
    val reachedEnd: Boolean,
    /** The platform counter when Started arrived, or null when the app cannot read it. */
    val platformAtStart: Int?,
    /** The platform counter after Stopped, or null when the app cannot read it. */
    val platformAtEnd: Int?,
)

/** The result of [judge]. */
data class Verdict(
    val pass: Boolean,
    val problems: List<String>,
    val countSum: Long,
    val lastTotal: Long?,
    /** The platform counter minus the library total: dropouts before the first block of the stream. */
    val platformOffset: Long?,
    val gapsSeen: Int,
    /** DeviceUnderrun events that no forced gap explains. */
    val unforcedEvents: Int,
)

/** How long after a forced gap its DeviceUnderrun event may arrive. */
const val GAP_DETECTION_WINDOW_NANOS: Long = 1_000_000_000L

/**
 * Applies the PASS rule. Every rule that fails adds one problem. README.md states the same rule.
 */
fun judge(run: RunRecord): Verdict {
    val problems = mutableListOf<String>()
    if (run.apiLevel < 24) problems += "API level ${run.apiLevel} has no AudioTrack.getUnderrunCount(), so the output sends no DeviceUnderrun"
    if (run.startedEvents != 1) problems += "expected 1 Started event, got ${run.startedEvents}"
    if (run.stoppedEvents != 1) problems += "expected 1 Stopped event, got ${run.stoppedEvents}"
    run.failures.forEach { problems += "Failed: $it" }
    run.losses.forEach { problems += "DeviceLost: $it" }
    if (!run.reachedEnd) problems += "the tone did not reach its planned length"
    if (run.underrunEvents != 0) problems += "expected 0 Underrun events, since every render was full, got ${run.underrunEvents}"
    if (run.gaps.size != run.plannedGaps) problems += "expected ${run.plannedGaps} forced gaps, the renderer made ${run.gaps.size}"

    val events = run.deviceUnderruns
    var previous = 0L
    for ((index, event) in events.withIndex()) {
        if (event.count < 1) problems += "DeviceUnderrun ${index + 1} has count ${event.count}"
        if (event.total != previous + event.count) {
            problems += "DeviceUnderrun ${index + 1} has total ${event.total}, expected ${previous + event.count}"
        }
        previous = event.total
    }
    val countSum = events.sumOf { it.count.toLong() }
    val lastTotal = events.lastOrNull()?.total

    val seen = run.gaps.count { gap ->
        events.any { it.timeNanos >= gap.startNanos && it.timeNanos <= gap.endNanos + GAP_DETECTION_WINDOW_NANOS }
    }
    if (seen != run.gaps.size) problems += "only $seen of ${run.gaps.size} forced gaps were followed by a DeviceUnderrun"
    val unforced = events.count { event ->
        run.gaps.none { event.timeNanos >= it.startNanos && event.timeNanos <= it.endNanos + GAP_DETECTION_WINDOW_NANOS }
    }

    // The library counts from its first block, so platform minus total must stay the same offset.
    val offsets = events.mapNotNull { event -> event.platformCount?.let { it - event.total } }
    val baseline = offsets.firstOrNull() ?: run.platformAtStart?.toLong()
    val platformOffset = run.platformAtEnd?.let { end -> end - (lastTotal ?: 0L) }
    if (baseline != null) {
        offsets.withIndex().filter { it.value != baseline }.forEach {
            problems += "at DeviceUnderrun ${it.index + 1} the platform counter is ${it.value - baseline} away from the library total"
        }
        if (platformOffset != null && platformOffset != baseline) {
            problems += "at the end the platform counter is ${run.platformAtEnd}, but the library total ${lastTotal ?: 0} plus the offset $baseline is ${(lastTotal ?: 0) + baseline}"
        }
        if (baseline < 0) problems += "the platform counter is below the library total"
    }

    return Verdict(
        pass = problems.isEmpty(),
        problems = problems,
        countSum = countSum,
        lastTotal = lastTotal,
        platformOffset = platformOffset,
        gapsSeen = seen,
        unforcedEvents = unforced,
    )
}
