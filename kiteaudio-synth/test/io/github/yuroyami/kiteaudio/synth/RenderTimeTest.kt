package io.github.yuroyami.kiteaudio.synth

import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.TimeSource

/**
 * Prints the render time of the General MIDI load of [BusyFont] on every platform, for [Interpolation.Cubic]
 * and [Interpolation.BandLimited]. It asserts nothing about the time, so a loaded machine cannot fail it.
 */
class RenderTimeTest {

    private val rate = 48_000
    private val block = 512

    @Test
    fun printsTheRenderTimeOfBothModes() {
        val cubic = BusyFont.loaded(rate, Interpolation.Cubic, block)
        val band = BusyFont.loaded(rate, Interpolation.BandLimited, block)
        val out = FloatArray(block * 2)
        val cubicNanos = LongArray(40)
        val bandNanos = LongArray(40)
        // The two modes take turns, so a change in the machine's load reaches both.
        for (i in cubicNanos.indices) {
            var mark = TimeSource.Monotonic.markNow()
            cubic.render(out, block)
            cubicNanos[i] = mark.elapsedNow().inWholeNanoseconds
            mark = TimeSource.Monotonic.markNow()
            band.render(out, block)
            bandNanos[i] = mark.elapsedNow().inWholeNanoseconds
        }
        assertEquals(96, cubic.activeVoices)
        assertEquals(96, band.activeVoices)
        val cubicMean = cubicNanos.average()
        val bandMean = bandNanos.average()
        println(
            "Render time for $block frames: Cubic mean ${ms(cubicMean)} ms, worst ${ms(cubicNanos.max().toDouble())} ms; " +
                "BandLimited mean ${ms(bandMean)} ms, worst ${ms(bandNanos.max().toDouble())} ms; " +
                "ratio ${(bandMean / cubicMean * 100).roundToInt() / 100.0}",
        )
    }

    private fun ms(nanos: Double): Double = (nanos / 1e3).roundToInt() / 1000.0
}
