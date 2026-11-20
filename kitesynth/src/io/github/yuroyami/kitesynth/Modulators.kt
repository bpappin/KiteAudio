package io.github.yuroyami.kitesynth

import kotlin.math.log10

/**
 * The modulators the format switches on when a font says nothing, SoundFont 2.04 section 8.4. The
 * pitch wheel's own is built into the synthesiser as the bend, so nine are left.
 */
public val DefaultModulators: List<Modulator> = listOf(
    // Velocity to loudness, concave and falling: exactly the old velocity-squared gain.
    Modulator(0x0502, Generator.InitialAttenuation.id, 960, 0, 0),
    // Velocity to brightness: a soft note is up to two octaves darker.
    Modulator(0x0102, Generator.InitialFilterFc.id, -2400, 0, 0),
    Modulator(0x000D, Generator.VibLfoToPitch.id, 50, 0, 0),
    Modulator(0x0081, Generator.VibLfoToPitch.id, 50, 0, 0),
    Modulator(0x0587, Generator.InitialAttenuation.id, 960, 0, 0),
    // Half the format's 1000, so the ends of the pan knob are the ends of the stereo field.
    Modulator(0x028A, Generator.Pan.id, 500, 0, 0),
    Modulator(0x058B, Generator.InitialAttenuation.id, 960, 0, 0),
    Modulator(0x00DB, Generator.ReverbEffectsSend.id, 200, 0, 0),
    Modulator(0x00DD, Generator.ChorusEffectsSend.id, 200, 0, 0),
)

/**
 * A raw source value through its curve, polarity and direction. [raw] runs 0 to 127, or 0 to
 * 16383 when the source is the pitch wheel. A unipolar source gives 0 to 1, a bipolar one -1 to 1.
 */
public fun sourceCurve(source: Int, raw: Int): Double {
    val wheel = (source and CC_FLAG) == 0 && (source and 0x7F) == SOURCE_WHEEL
    val top = if (wheel) 16383 else 127
    val bipolar = (source and 0x200) != 0
    // MIDI's middle is 64, or 8192 on the wheel: a bipolar source is exactly zero there, not a hair past it.
    val centre = (top + 1) / 2
    val clamped = raw.coerceIn(0, top)
    val x = when {
        !bipolar -> clamped.toDouble() / top
        clamped >= centre -> 0.5 + 0.5 * (clamped - centre) / (top - centre)
        else -> 0.5 * clamped / centre
    }
    val t = if ((source and 0x100) != 0) 1.0 - x else x
    val type = (source shr 10) and 0x3F
    if (!bipolar) return shape(type, t)
    // Bipolar: zero in the middle, and each half the unipolar shape, mirrored.
    return when (type) {
        SWITCH -> if (t >= 0.5) 1.0 else -1.0
        LINEAR -> 2.0 * t - 1.0
        else -> if (t > 0.5) shape(type, 2.0 * t - 1.0) else -shape(type, 1.0 - 2.0 * t)
    }
}

private fun shape(type: Int, t: Double): Double = when (type) {
    CONCAVE -> concave(t)
    CONVEX -> 1.0 - concave(1.0 - t)
    SWITCH -> if (t >= 0.5) 1.0 else 0.0
    else -> t
}

/** Slow then fast: the format's own curve, the one a velocity or a volume knob is heard through. */
private fun concave(t: Double): Double = when {
    t <= 0.0 -> 0.0
    t >= 1.0 -> 1.0
    else -> (-20.0 / 96.0 * log10((1.0 - t) * (1.0 - t))).coerceIn(0.0, 1.0)
}

/**
 * The modulators one voice runs: the defaults, less any an identical instrument modulator
 * replaces, then the instrument's, then the preset's, which add to whatever they match.
 */
internal fun mergedModulators(instrument: List<Modulator>, preset: List<Modulator>): List<Modulator> {
    val merged = ArrayList<Modulator>(DefaultModulators.size + instrument.size + preset.size)
    for (default in DefaultModulators) if (instrument.none { it.sameAs(default) }) merged += default
    merged += instrument
    merged += preset
    return merged
}

/** True when [modulator] reads the non-controller source [index], as its source or its amount source. */
internal fun reads(modulator: Modulator, index: Int): Boolean =
    ((modulator.source and CC_FLAG) == 0 && (modulator.source and 0x7F) == index) ||
        ((modulator.amountSource and CC_FLAG) == 0 && (modulator.amountSource and 0x7F) == index)

internal const val CC_FLAG = 0x80
internal const val SOURCE_NONE = 0
internal const val SOURCE_VELOCITY = 2
internal const val SOURCE_KEY = 3
internal const val SOURCE_POLY_PRESSURE = 10
internal const val SOURCE_PRESSURE = 13
internal const val SOURCE_WHEEL = 14
internal const val SOURCE_WHEEL_RANGE = 16

private const val LINEAR = 0
private const val CONCAVE = 1
private const val CONVEX = 2
private const val SWITCH = 3
