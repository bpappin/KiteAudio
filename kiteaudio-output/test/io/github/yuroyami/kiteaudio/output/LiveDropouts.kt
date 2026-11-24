package io.github.yuroyami.kiteaudio.output

import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Counts the gaps between render calls that are long enough to empty a full device buffer.
 *
 * A busy machine can delay the render thread for longer than the device holds, and that is a real
 * dropout. The live tests allow one dropout for each such gap. [renderMicros] holds the start time
 * of each of the [rendered] render calls. A gap counts when it is longer than [holdsMicros] and
 * does not start at a render listed in [stalls]. Gaps before render [firstChecked] do not count,
 * because the output checks nothing at the start of a stream.
 */
internal fun longRenderGaps(renderMicros: LongArray, rendered: Int, holdsMicros: Long, stalls: Set<Int>, firstChecked: Int): Int {
    var gaps = 0
    for (render in maxOf(1, firstChecked) until minOf(rendered, renderMicros.size)) {
        if (render - 1 !in stalls && renderMicros[render] - renderMicros[render - 1] > holdsMicros) gaps++
    }
    return gaps
}

/**
 * Checks the device underruns of a live run: at least [deliberate] dropouts, at most one more for
 * each of the [longGaps], totals that add up, and no [AudioOutputEvent.Underrun].
 */
internal fun assertDeviceUnderruns(events: List<AudioOutputEvent>, deliberate: Int, longGaps: Int) {
    val dropouts = events.filterIsInstance<AudioOutputEvent.DeviceUnderrun>()
    val counted = dropouts.sumOf { it.count }
    println("live run: $counted dropouts, $deliberate deliberate, $longGaps long gaps")
    assertTrue(counted in deliberate..deliberate + longGaps, "$counted dropouts, $deliberate deliberate, $longGaps long gaps: $events")
    var total = 0L
    for (dropout in dropouts) {
        total += dropout.count
        assertEquals(total, dropout.total, "the totals do not add up: $events")
    }
    assertTrue(events.none { it is AudioOutputEvent.Underrun }, "$events")
}
