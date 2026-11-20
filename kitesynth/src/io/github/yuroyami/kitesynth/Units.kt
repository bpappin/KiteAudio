package io.github.yuroyami.kitesynth

import kotlin.math.pow

/**
 * The units SoundFont speaks.
 *
 * The format never stores seconds or a gain. It stores logarithms, because a logarithm can hold
 * both a millisecond and a minute in two bytes. Every conversion is here, in one place, so a
 * mistake shows up once rather than in six voices.
 */
public object Units {

    /**
     * Timecents to seconds.
     *
     * Zero is one second. -12000 is one millisecond, which is the shortest the format can say
     * and what a generator that is not set usually means.
     */
    public fun timecentsToSeconds(timecents: Int): Double = 2.0.pow(timecents / 1200.0)

    /**
     * Centibels to a gain between 0 and 1.
     *
     * A centibel is a tenth of a decibel, counted downwards: 0 is full volume, 960 is silence
     * for practical purposes. This is an attenuation, so bigger means quieter.
     */
    public fun centibelsToGain(centibels: Int): Double {
        if (centibels <= 0) return 1.0
        if (centibels >= SILENCE_CB) return 0.0
        return 10.0.pow(-centibels / 200.0)
    }

    /** Cents to a frequency ratio. 1200 cents is an octave, so it doubles. */
    public fun centsToRatio(cents: Double): Double = 2.0.pow(cents / 1200.0)

    /**
     * How much a key is worth in cents against the sample's own key.
     *
     * [scaleTuning] is normally 100, meaning a semitone per key. A drum kit sets it to 0 so
     * every key plays the sample at its recorded pitch.
     */
    public fun keyCents(key: Int, rootKey: Int, scaleTuning: Int, tuneCents: Int): Double =
        (key - rootKey).toDouble() * scaleTuning + tuneCents

    /** Past this many centibels nothing is audible, so a voice can stop. */
    public const val SILENCE_CB: Int = 960

    private val gainTable = DoubleArray(SILENCE_CB + 1) { 10.0.pow(-it / 200.0) }

    /**
     * Centibels of attenuation to a gain, from a table with straight lines between its entries:
     * 0 is 1, 960 and past it are silence. Cheap enough to call for every voice at the control rate.
     */
    public fun gainOf(centibels: Double): Double {
        if (centibels <= 0.0) return 1.0
        if (centibels >= SILENCE_CB) return 0.0
        val index = centibels.toInt()
        val fraction = centibels - index
        return gainTable[index] + (gainTable[index + 1] - gainTable[index]) * fraction
    }
}
