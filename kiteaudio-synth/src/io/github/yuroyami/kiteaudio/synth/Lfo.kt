package io.github.yuroyami.kiteaudio.synth

import kotlin.math.min
import kotlin.math.pow

/**
 * A triangle between -1 and 1 that starts at 0 going up, once its delay is over. Both of the
 * format's LFOs are this shape; they differ only in what they are wired to.
 */
public class Lfo {
    private var delayLeft = 0
    private var phase = 0.0
    private var increment = 0.0

    /** Where the LFO is now: 0 while it waits out its delay. */
    public val value: Double
        get() = if (delayLeft > 0) 0.0 else triangle(phase)

    public fun start(delayFrames: Int, hertz: Double, sampleRate: Int) {
        delayLeft = delayFrames
        phase = 0.0
        increment = hertz / sampleRate
    }

    /** Moves on by [frames] and says where it lands. */
    public fun advance(frames: Int): Double {
        var left = frames
        if (delayLeft > 0) {
            val waited = min(delayLeft, left)
            delayLeft -= waited
            left -= waited
        }
        if (delayLeft == 0) {
            val advanced = phase + increment * left
            // Almost every control step stays inside this cycle. Floating-point remainder
            // cannot change such a value; reserve it for actual wraps and large advances.
            phase = if (advanced > -1.0 && advanced < 1.0) advanced else advanced % 1.0
        }
        return value
    }

    private fun triangle(at: Double): Double = when {
        at < 0.25 -> 4.0 * at
        at < 0.75 -> 2.0 - 4.0 * at
        else -> 4.0 * at - 4.0
    }

    public companion object {
        /** The format's LFO frequency: cents above 8.176 Hz, so 0 is 8.176 Hz and 1200 is twice that. */
        public fun hertzOf(cents: Int): Double = 8.176 * 2.0.pow(cents / 1200.0)
    }
}
