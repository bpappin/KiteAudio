package io.github.yuroyami.kiteaudio.synth

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A windowed sinc low-pass that reads a sample at any position, for [Interpolation.BandLimited].
 *
 * The table holds one side of the kernel, sinc(t) times the window, for t from 0 to [zeroCrossings].
 * A read at a lower cutoff stretches the same table, so one table serves every step. Every voice
 * shares one read-only instance, [shared]. A [Synth] builds it the first time it selects the mode.
 *
 * @param zeroCrossings the zero crossings of the sinc on each side, at a cutoff of the sample's Nyquist limit.
 * @param resolution table entries per zero crossing. A read draws a straight line between two entries.
 * @param window the window for 0 (the center) to 1 (the end of the kernel). It must be 1 at 0.
 */
internal class SincKernel(
    val zeroCrossings: Int,
    val resolution: Int,
    window: (Double) -> Double,
) {
    // The value and the slope to the next entry, side by side, so one tap touches one cache line.
    private val table: FloatArray

    init {
        val size = zeroCrossings * resolution
        require(size < Int.MAX_VALUE / ONE) { "a table of $size entries does not fit the fixed-point position" }
        val values = DoubleArray(size + 3)
        for (i in 0 until size) {
            val t = i.toDouble() / resolution
            values[i] = sinc(t) * window(t / zeroCrossings)
        }
        // The kernel ends at a zero crossing, so a tap that enters as the cutoff moves enters at zero.
        // One more zero entry takes a last tap that the rounding of the fixed-point position pushes past the end.
        table = FloatArray((size + 2) * 2)
        for (i in 0..size + 1) {
            table[i * 2] = values[i].toFloat()
            table[i * 2 + 1] = (values[i + 1] - values[i]).toFloat()
        }
    }

    /** How far the kernel reaches on each side, in frames, at a cutoff of [ratio] times the Nyquist limit. */
    fun reach(ratio: Double): Double = zeroCrossings / ratio

    /**
     * The value at [index] plus [fraction], low-passed at [ratio] times the Nyquist limit of [frames].
     *
     * [reach] is [reach] of [ratio]. The taps run from `index - ceil(reach) + 1` to `index + ceil(reach)`,
     * and every one of them must lie inside [frames]. The result is scaled to the range -1 to 1.
     */
    fun read(frames: ShortArray, index: Int, fraction: Double, ratio: Double, reach: Double): Float {
        if (ratio == 1.0) return readAtNyquist(frames, index, fraction)
        val table = table
        // The table position in fixed point: the entry in the high bits, the part between entries in the low ones.
        val scale = ratio * resolution * ONE
        val stride = scale.toInt()
        // The taps at and before the position walk left, the taps after it walk right.
        var x = (fraction * scale).toInt()
        var k = index
        var n = ceil(reach - fraction).toInt()
        var left = 0f
        while (n > 0) {
            val at = (x ushr SHIFT) shl 1
            left += frames[k] * (table[at] + (x and MASK) * PART * table[at + 1])
            x += stride
            k--
            n--
        }
        x = ((1.0 - fraction) * scale).toInt()
        k = index + 1
        n = ceil(reach - 1.0 + fraction).toInt()
        var right = 0f
        while (n > 0) {
            val at = (x ushr SHIFT) shl 1
            right += frames[k] * (table[at] + (x and MASK) * PART * table[at + 1])
            x += stride
            k++
            n--
        }
        return (left + right) * (ratio / 32768.0).toFloat()
    }

    /**
     * [read] at a ratio of 1. The taps then lie a whole number of zero crossings apart, so they all
     * share one part between entries. It returns the same value as the general path, for less work.
     */
    private fun readAtNyquist(frames: ShortArray, index: Int, fraction: Double): Float {
        val table = table
        val scale = resolution * ONE.toDouble()
        val jump = resolution shl 1
        val xl = (fraction * scale).toInt()
        val partLeft = (xl and MASK) * PART
        var at = (xl ushr SHIFT) shl 1
        var k = index
        var n = zeroCrossings
        var left = 0f
        while (n > 0) {
            left += frames[k] * (table[at] + partLeft * table[at + 1])
            at += jump
            k--
            n--
        }
        val xr = ((1.0 - fraction) * scale).toInt()
        val partRight = (xr and MASK) * PART
        at = (xr ushr SHIFT) shl 1
        k = index + 1
        // At a whole frame, the last tap on the right is the zero crossing at the end of the kernel.
        n = if (fraction == 0.0) zeroCrossings - 1 else zeroCrossings
        var right = 0f
        while (n > 0) {
            right += frames[k] * (table[at] + partRight * table[at + 1])
            at += jump
            k++
            n--
        }
        return (left + right) * (1f / 32768f)
    }

    companion object {
        /** The kernel that [Interpolation.BandLimited] uses. */
        val shared: SincKernel by lazy { SincKernel(ZERO_CROSSINGS, RESOLUTION) { kaiser(it, BETA) } }

        const val ZERO_CROSSINGS = 16
        const val RESOLUTION = 512
        const val BETA = 10.0

        /**
         * The largest step for which the cutoff still falls. A faster step keeps the cutoff of this
         * one, so the kernel stops growing and the cost stays bounded.
         */
        const val MAX_STEP = 4.0

        private const val SHIFT = 16
        private const val ONE = 1 shl SHIFT
        private const val MASK = ONE - 1
        private const val PART = 1f / ONE

        fun sinc(t: Double): Double = if (t == 0.0) 1.0 else sin(PI * t) / (PI * t)

        /** The Kaiser window at [x] from 0 (the center) to 1 (the end). */
        fun kaiser(x: Double, beta: Double): Double {
            val inside = 1.0 - x * x
            return if (inside <= 0.0) besselI0(0.0) / besselI0(beta) else besselI0(beta * sqrt(inside)) / besselI0(beta)
        }

        /** The zeroth-order modified Bessel function of the first kind, from its power series. */
        fun besselI0(x: Double): Double {
            var sum = 1.0
            var term = 1.0
            val half = x / 2
            var k = 1
            while (term > sum * 1e-17) {
                term *= (half / k) * (half / k)
                sum += term
                k++
            }
            return sum
        }
    }
}
