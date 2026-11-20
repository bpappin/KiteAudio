package io.github.yuroyami.kitesynth

/**
 * Reverb, on the Freeverb plan.
 *
 * Eight comb filters in parallel feed four allpass filters in series, per channel, with the right
 * channel's delays offset so the two sides are not identical. Jezar's Freeverb design has been
 * described publicly for decades and the numbers below are that description's.
 *
 * Why a piano needs this: a dry sample sounds like a recording of a piano in a padded box. Even a
 * little room makes the same notes sound like an instrument somebody is playing.
 *
 * Allocates once. [process] runs on the audio thread and does arithmetic only.
 */
public class Reverb(private val sampleRate: Int = 48_000) {

    /** How much of the room is mixed in, 0 to 1. */
    public var wet: Double = 0.25
        set(value) { if (value.isFinite()) field = value.coerceIn(0.0, 1.0) }

    /** How much of the original passes through, 0 to 1. */
    public var dry: Double = 1.0
        set(value) { if (value.isFinite()) field = value.coerceIn(0.0, 1.0) }

    /** How long the room rings, 0 to 1. Larger is longer. */
    public var roomSize: Double = 0.5
        set(value) {
            if (!value.isFinite()) return
            field = value.coerceIn(0.0, 1.0)
            // Freeverb's own numbers: a room of nothing still rings a little, and the size
            // adds to that rather than replacing it. Swapping the two makes every room far too
            // short, and only a size of one lands on the right value by accident.
            feedback = 0.7 + field * 0.28
        }

    /** How quickly the high frequencies die away, 0 to 1. Larger is duller. */
    public var damping: Double = 0.5
        set(value) {
            if (!value.isFinite()) return
            field = value.coerceIn(0.0, 1.0)
            damp1 = field * 0.4
            damp2 = 1.0 - damp1
        }

    /** How wide the room is, 0 to 1. Zero is mono. */
    public var width: Double = 1.0
        set(value) { if (value.isFinite()) field = value.coerceIn(0.0, 1.0) }

    private var bypassed = false

    /** True while the room is empty and nothing is coming in, so [process] only scales the dry sound. */
    private var idle = true

    /** Frames in a row with the input and the room's own output both under the quiet limits. */
    private var quietFrames = 0
    private var feedback = 0.7 + roomSize * 0.28
    private var damp1 = damping * 0.4
    private var damp2 = 1.0 - damp1

    // Freeverb's own delay lengths, in samples at 44,100 Hz. Scaled to whatever rate is in use,
    // because at 48 kHz the untouched numbers make a slightly smaller and brighter room.
    private val combTuning = intArrayOf(1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617)
    private val allpassTuning = intArrayOf(556, 441, 341, 225)

    /** How far the right channel's delays are offset, so the two sides differ. */
    private val stereoSpread = 23

    private class Comb(size: Int) {
        val buffer = FloatArray(size)
        var index = 0
        var store = 0.0f
    }

    private class Allpass(size: Int) {
        val buffer = FloatArray(size)
        var index = 0
    }

    private fun scaled(length: Int): Int =
        (length.toLong() * sampleRate / 44_100L).toInt().coerceAtLeast(4)

    private val combsL = Array(combTuning.size) { Comb(scaled(combTuning[it])) }
    private val combsR = Array(combTuning.size) { Comb(scaled(combTuning[it] + stereoSpread)) }
    private val allpassL = Array(allpassTuning.size) { Allpass(scaled(allpassTuning[it])) }
    private val allpassR = Array(allpassTuning.size) { Allpass(scaled(allpassTuning[it] + stereoSpread)) }

    /** Enough quiet frames for every delay line to have been read through at least once. */
    private val settleFrames = combsR.maxOf { it.buffer.size } + allpassR.sumOf { it.buffer.size }

    /** Empties the room. Call it when the sound stops, or the last note leaks into the next one. */
    public fun clear() {
        idle = true
        quietFrames = 0
        var index = 0
        while (index < combsL.size) {
            val comb = combsL[index]
            comb.buffer.fill(0f)
            comb.store = 0f
            comb.index = 0
            index++
        }

        index = 0
        while (index < combsR.size) {
            val comb = combsR[index]
            comb.buffer.fill(0f)
            comb.store = 0f
            comb.index = 0
            index++
        }

        index = 0
        while (index < allpassL.size) {
            val pass = allpassL[index]
            pass.buffer.fill(0f)
            pass.index = 0
            index++
        }

        index = 0
        while (index < allpassR.size) {
            val pass = allpassR[index]
            pass.buffer.fill(0f)
            pass.index = 0
            index++
        }
    }

    /**
     * Adds a room to an interleaved stereo buffer, in place.
     *
     * @param out interleaved stereo, at least [frames] times two long
     */
    public fun process(out: FloatArray, frames: Int): Unit = process(out, out, 0, frames)

    /**
     * Turns [out] down to [dry] and adds the room fed by [input]. Both are interleaved stereo;
     * [input] is read from its start and [out] written from frame [offset], for [frames] frames.
     * The same array may be both, which is the room hearing the whole mix.
     */
    public fun process(input: FloatArray, out: FloatArray, offset: Int, frames: Int) {
        if (wet <= 0.0 && dry >= 1.0) {
            // Emptied once on the way in, not on every call. Skipping without emptying freezes
            // the tail, so turning the room back on plays a sound that ended minutes ago.
            if (!bypassed) {
                clear()
                bypassed = true
            }
            return
        }
        bypassed = false

        val wet1 = (wet * (width / 2.0 + 0.5)).toFloat()
        val wet2 = (wet * ((1.0 - width) / 2.0)).toFloat()
        val dryGain = dry.toFloat()
        val feedbackF = feedback.toFloat()
        val damp1F = damp1.toFloat()
        val damp2F = damp2.toFloat()

        // An empty room with nothing coming in adds nothing, so only the dry level applies.
        if (idle) {
            if (quietInput(input, frames)) {
                scaleDry(out, offset, frames, dryGain)
                return
            }
            idle = false
        }

        var loudestFeed = 0f
        var loudestRoom = 0f
        var health = 0f
        for (i in 0 until frames) {
            val at = (offset + i) * 2
            // Freeverb's own input gain. Without it eight combs in parallel run away.
            var feed = (input[i * 2] + input[i * 2 + 1]) * 0.015f
            // NaN or infinity never enters the room: fed back through the combs, one would ring forever.
            // The subtraction is plain arithmetic, where isFinite() costs native calls in an Android debug build.
            if (feed - feed != 0f) feed = 0f

            var outL = 0f
            var outR = 0f

            for (c in combsL.indices) {
                outL += comb(combsL[c], feed, feedbackF, damp1F, damp2F)
                outR += comb(combsR[c], feed, feedbackF, damp1F, damp2F)
            }
            for (a in allpassL.indices) {
                outL = allpass(allpassL[a], outL)
                outR = allpass(allpassR[a], outR)
            }

            val left = outL * wet1 + outR * wet2 + out[at] * dryGain
            val right = outR * wet1 + outL * wet2 + out[at + 1] * dryGain
            // Every finite value passes unchanged, including values outside nominal PCM.
            out[at] = if (left - left == 0f) left else 0f
            out[at + 1] = if (right - right == 0f) right else 0f

            health += outL + outR
            val feedSize = if (feed < 0f) -feed else feed
            if (feedSize > loudestFeed) loudestFeed = feedSize
            val sizeL = if (outL < 0f) -outL else outL
            if (sizeL > loudestRoom) loudestRoom = sizeL
            val sizeR = if (outR < 0f) -outR else outR
            if (sizeR > loudestRoom) loudestRoom = sizeR
        }

        if (health - health != 0f) {
            // A value too large for the room got in and overflowed somewhere inside. Start again from silence.
            clear()
            return
        }
        if (loudestFeed < QUIET_FEED && loudestRoom < QUIET_ROOM) {
            quietFrames += frames
            // Nothing audible is left once every delay line has been read through under the limit.
            // Emptying the room also drops the subnormal leftovers that are slow on some processors.
            if (quietFrames >= settleFrames) clear()
        } else {
            quietFrames = 0
        }
    }

    /** True when nothing would reach the room from [input]'s first [frames] frames. */
    private fun quietInput(input: FloatArray, frames: Int): Boolean {
        for (i in 0 until frames) {
            val feed = (input[i * 2] + input[i * 2 + 1]) * 0.015f
            if (feed >= QUIET_FEED || feed <= -QUIET_FEED) return false
        }
        return true
    }

    private fun scaleDry(out: FloatArray, offset: Int, frames: Int, dryGain: Float) {
        for (at in offset * 2 until (offset + frames) * 2) {
            val value = out[at] * dryGain
            out[at] = if (value - value == 0f) value else 0f
        }
    }

    private fun comb(comb: Comb, input: Float, feedback: Float, damp1: Float, damp2: Float): Float {
        val output = comb.buffer[comb.index]
        comb.store = output * damp2 + comb.store * damp1
        comb.buffer[comb.index] = input + comb.store * feedback
        comb.index++
        if (comb.index >= comb.buffer.size) comb.index = 0
        return output
    }

    private fun allpass(pass: Allpass, input: Float): Float {
        val buffered = pass.buffer[pass.index]
        val output = -input + buffered
        pass.buffer[pass.index] = input + buffered * 0.5f
        pass.index++
        if (pass.index >= pass.buffer.size) pass.index = 0
        return output
    }

    public companion object {
        /** About -120 dB of feed into the combs. Below this nothing reaches the room that anyone can hear. */
        private const val QUIET_FEED = 1e-8f

        /** About -120 dB from the room itself. */
        private const val QUIET_ROOM = 1e-6f

        /*
         * Every preset pulls the dry signal down by as much as it adds in wet, so turning the
         * room up does not turn the whole output up. Without that, choosing a hall makes a
         * loud passage clip, which sounds like a fault rather than like a bigger room.
         */

        /** Barely there. For somebody who wants the sample and nothing else. */
        public fun dryRoom(sampleRate: Int): Reverb = Reverb(sampleRate).apply {
            wet = 0.08
            dry = 0.94
            roomSize = 0.3
            damping = 0.7
        }

        /** A practice room. The default: enough to sound like somewhere, not enough to blur. */
        public fun room(sampleRate: Int): Reverb = Reverb(sampleRate).apply {
            wet = 0.22
            dry = 0.84
            roomSize = 0.55
            damping = 0.5
        }

        /** A hall. Long, and too much for practising in. */
        public fun hall(sampleRate: Int): Reverb = Reverb(sampleRate).apply {
            wet = 0.38
            dry = 0.72
            roomSize = 0.85
            damping = 0.3
        }
    }
}
