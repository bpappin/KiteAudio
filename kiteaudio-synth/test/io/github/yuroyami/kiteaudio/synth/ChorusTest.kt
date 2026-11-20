package io.github.yuroyami.kiteaudio.synth

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

class ChorusTest {
    private val rate = 48_000

    @Test
    fun silenceInIsSilenceOut() {
        val chorus = Chorus(rate)
        val input = FloatArray(4800 * 2)
        val out = FloatArray(4800 * 2)
        chorus.process(input, out, 0, 4800)
        assertTrue(out.all { it == 0f })
    }

    @Test
    fun anImpulseComesBackAboutThirtyMillisecondsLaterOnBothSides() {
        val chorus = Chorus(rate)
        val input = FloatArray(4800 * 2).also { it[0] = 1f; it[1] = 1f }
        val out = FloatArray(4800 * 2)
        chorus.process(input, out, 0, 4800)
        fun energy(channel: Int, from: Int, to: Int) = (from until to).sumOf { abs(out[it * 2 + channel]).toDouble() }
        val early = rate * 20 / 1000
        val window = (rate * 25 / 1000) until (rate * 36 / 1000)
        for (channel in 0..1) {
            assertTrue(energy(channel, 0, early) == 0.0, "nothing comes back before 20 ms on side $channel")
            assertTrue(energy(channel, window.first, window.last) > 0.0, "the echo lands between 25 and 36 ms on side $channel")
        }
    }
}
