package io.github.yuroyami.kiteaudio.synth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A render budget for a General MIDI sized load: 96 held notes over 8 channels, every voice with a
 * filter, both LFOs, a modulation envelope and eight modulators of its own.
 *
 * A local number, not a contract. It asserts on a developer machine and only prints on CI.
 */
class RenderBenchmarkTest {

    private val rate = 48_000
    private val block = 512

    /** The mean and the worst block time in milliseconds. */
    private fun measure(interpolation: Interpolation): Pair<Double, Double> {
        val synth = BusyFont.loaded(rate, interpolation, block)
        val out = FloatArray(block * 2)
        assertEquals(96, synth.activeVoices, "every note should still sound after the warm-up")

        val times = LongArray(400)
        for (i in times.indices) {
            // The mod wheel moves now and then, so the modulators are recomputed inside the timing.
            if (i % 20 == 0) for (channel in 0 until 8) synth.controlChange(channel, 1, 40 + i % 60)
            val start = System.nanoTime()
            synth.render(out, block)
            times[i] = System.nanoTime() - start
        }
        val meanMs = times.average() / 1e6
        val worstMs = times.max() / 1e6
        println("Render budget, $interpolation: mean %.3f ms, worst %.3f ms for %d frames (%.1f ms of audio)"
            .format(meanMs, worstMs, block, block * 1000.0 / rate))
        assertEquals(96, synth.activeVoices, "every note should still sound after the timing")
        return meanMs to worstMs
    }

    @Test
    fun aGeneralMidiLoadFitsInABlockWithRoomToSpare() {
        val (meanMs, worstMs) = measure(Interpolation.Cubic)
        if (System.getenv("CI") != null) return  // a shared runner is too noisy for a budget
        assertTrue(meanMs < 1.6, "the mean block took %.3f ms, the budget is 1.6".format(meanMs))
        assertTrue(worstMs < 5.0, "the worst block took %.3f ms, the ceiling is 5".format(worstMs))
    }

    /** Prints the same load through the band-limited kernel. It has no budget, because its cost depends on the step. */
    @Test
    fun aGeneralMidiLoadThroughTheBandLimitedKernel() {
        measure(Interpolation.BandLimited)
    }
}
