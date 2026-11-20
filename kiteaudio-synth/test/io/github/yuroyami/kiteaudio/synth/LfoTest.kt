package io.github.yuroyami.kiteaudio.synth

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LfoTest {
    private val rate = 48_000

    private fun near(wanted: Double, got: Double, slack: Double = 1e-3) = assertTrue(abs(wanted - got) <= slack, "wanted $wanted, got $got")

    @Test
    fun aTriangleRisesToOneInAQuarterAndComesBackInAWhole() {
        val lfo = Lfo().apply { start(delayFrames = 0, hertz = 5.0, sampleRate = rate) }
        near(1.0, lfo.advance(2400))
        near(0.0, lfo.advance(2400))
        near(-1.0, lfo.advance(2400))
        near(0.0, lfo.advance(2400))
    }

    @Test
    fun theDelayHoldsItAtZero() {
        val lfo = Lfo().apply { start(delayFrames = 4800, hertz = 5.0, sampleRate = rate) }
        assertEquals(0.0, lfo.advance(4000))
        assertEquals(0.0, lfo.advance(800))
        near(1.0, lfo.advance(2400), 1e-3)
    }

    @Test
    fun theFormatsFrequencyIsCentsAbove8Hertz() {
        near(8.176, Lfo.hertzOf(0), 1e-3)
        near(16.352, Lfo.hertzOf(1200), 1e-3)
    }
}
