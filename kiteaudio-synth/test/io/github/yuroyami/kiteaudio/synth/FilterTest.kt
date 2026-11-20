package io.github.yuroyami.kiteaudio.synth

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FilterTest {
    private val rate = 48_000

    private fun rms(values: FloatArray): Double = sqrt(values.sumOf { it.toDouble() * it } / values.size)

    private fun sine(hertz: Double, frames: Int = rate) = FloatArray(frames) { sin(2 * PI * hertz * it / rate).toFloat() }

    private fun through(filter: LowPassFilter, input: FloatArray) = FloatArray(input.size) { filter.process(input[it]) }

    @Test
    fun aThousandHertzCutoffDarkensNoise() {
        val random = Random(7)
        val noise = FloatArray(rate) { random.nextFloat() * 2 - 1 }
        val filter = LowPassFilter().apply { set(cutoffCents = 1200 * kotlin.math.log2(1000 / 8.176), resonanceCb = 0.0, sampleRate = rate) }
        val output = through(filter, noise)
        // A first difference is a rough high pass: it keeps what changes fast.
        fun roughness(x: FloatArray) = rms(FloatArray(x.size - 1) { x[it + 1] - x[it] })
        val drop = 20 * log10(roughness(noise) / roughness(output))
        assertTrue(drop > 24.0, "the highs fell by only $drop dB")
    }

    @Test
    fun theTopOfTheRangeWithNoResonanceIsBypassed() {
        val filter = LowPassFilter().apply { set(cutoffCents = 13500.0, resonanceCb = 0.0, sampleRate = rate) }
        assertTrue(filter.bypassed)
        val input = sine(3000.0, 480)
        val output = through(filter, input)
        for (i in input.indices) assertEquals(input[i], output[i])
    }

    @Test
    fun resonanceLiftsTheCutoff() {
        val cutoff = 1200 * kotlin.math.log2(1000 / 8.176)
        fun level(hertz: Double): Double {
            val filter = LowPassFilter().apply { set(cutoffCents = cutoff, resonanceCb = 960.0, sampleRate = rate) }
            return rms(through(filter, sine(hertz)).copyOfRange(rate / 2, rate))
        }
        assertTrue(level(1000.0) > level(300.0), "a 1 kHz sine at a resonant 1 kHz cutoff should beat 300 Hz")
    }

    @Test
    fun aResetFilterForgetsItsPast() {
        val filter = LowPassFilter().apply { set(cutoffCents = 6000.0, resonanceCb = 0.0, sampleRate = rate) }
        through(filter, sine(200.0, 480))
        filter.reset()
        assertEquals(0f, filter.process(0f))
        assertFalse(filter.bypassed)
    }

    @Test
    fun anInvalidSampleComesOutAsSilenceAndTheFilterRecovers() {
        val filter = LowPassFilter().apply { set(cutoffCents = 6000.0, resonanceCb = 0.0, sampleRate = rate) }
        repeat(100) { filter.process(0.5f) }
        assertEquals(0f, filter.process(Float.NaN))
        assertEquals(0f, filter.process(Float.POSITIVE_INFINITY))
        val after = FloatArray(2_000) { filter.process(0.5f) }
        assertTrue(after.all { it - it == 0f }, "a NaN stayed in the filter")
        assertTrue(abs(after.last() - 0.5f) < 0.01f, "the filter did not settle again: ${after.last()}")
    }
}
