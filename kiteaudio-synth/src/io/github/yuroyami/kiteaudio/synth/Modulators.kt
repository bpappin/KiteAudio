package io.github.yuroyami.kiteaudio.synth

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
 * A link counts places in its own level's list, so it is renumbered to count places in the result.
 */
internal fun mergedModulators(instrument: List<Modulator>, preset: List<Modulator>): List<Modulator> {
    val merged = ArrayList<Modulator>(DefaultModulators.size + instrument.size + preset.size)
    for (default in DefaultModulators) if (instrument.none { it.sameAs(default) }) merged += default
    merged.addLevel(instrument)
    merged.addLevel(preset)
    return merged
}

private fun MutableList<Modulator>.addLevel(level: List<Modulator>) {
    val start = size
    for (modulator in level) add(relinked(modulator, start, level.size))
}

/**
 * [modulator] with its link moved [start] places on. A link past the end of its own list of [count]
 * becomes [NO_LINK_TARGET], so it cannot land in a list laid after it.
 */
private fun relinked(modulator: Modulator, start: Int, count: Int): Modulator {
    val destination = modulator.destinationId
    if ((destination and LINK_FLAG) == 0) return modulator
    val index = destination and NO_LINK_TARGET
    val moved = if (index < count && start + index < NO_LINK_TARGET) start + index else NO_LINK_TARGET
    if (moved == index) return modulator
    return Modulator(modulator.source, LINK_FLAG or moved, modulator.amount, modulator.amountSource, modulator.transform)
}

/** True when [source] is the link source: the output of the modulators that link to this one. */
internal fun isLinkSource(source: Int): Boolean = (source and 0xFF) == SOURCE_LINK

/**
 * The evaluation order of a plan whose modulators link to each other (SoundFont 2.04, section 9.5.4).
 * It is built once, with the plan, so a voice only walks it.
 *
 * A modulator is in [order] when its links lead to a generator. Each link must land inside the list,
 * on a modulator whose source is the link source. A modulator in a cycle, one that links to itself or
 * past the end, and every modulator that feeds one of these, contribute nothing (sections 7.4 and 7.8).
 */
internal class ModulatorLinks private constructor(
    /** Indices into the plan's modulators. Each feeder comes before its target, and generators are reached in list order. */
    val order: IntArray,
    /** For each modulator, the modulator its output feeds, or -1 when it feeds a generator. */
    val target: IntArray,
) {
    companion object {
        private const val UNKNOWN = 0
        private const val ON_PATH = 1
        private const val VALID = 2
        private const val INVALID = 3
        private const val TERMINAL = -1
        private const val BROKEN = -2

        /** The order for [modulators], or null when none of them links, so the voice keeps its plain walk. */
        fun of(modulators: Array<Modulator>): ModulatorLinks? {
            if (modulators.none { (it.destinationId and LINK_FLAG) != 0 }) return null
            val count = modulators.size
            // Where each modulator's output goes: a modulator, a generator, or nowhere.
            val next = IntArray(count) { index ->
                val destination = modulators[index].destinationId
                if ((destination and LINK_FLAG) == 0) {
                    if (destination in 0 until GENERATOR_COUNT) TERMINAL else BROKEN
                } else {
                    val to = destination and NO_LINK_TARGET
                    if (to < count && to != NO_LINK_TARGET && isLinkSource(modulators[to].source)) to else BROKEN
                }
            }
            // Each walk follows the links until it meets a known modulator, a generator, a break or its own path.
            val state = IntArray(count)
            val root = IntArray(count)
            val depth = IntArray(count)
            val path = IntArray(count)
            for (start in 0 until count) {
                if (state[start] != UNKNOWN) continue
                var length = 0
                var at = start
                var valid: Boolean
                var reached = -1
                var distance = 0
                while (true) {
                    val seen = state[at]
                    if (seen == VALID) {
                        valid = true
                        reached = root[at]
                        distance = depth[at]
                        break
                    }
                    if (seen != UNKNOWN) {
                        valid = false
                        break
                    }
                    state[at] = ON_PATH
                    path[length++] = at
                    val to = next[at]
                    if (to == TERMINAL) {
                        valid = true
                        reached = at
                        distance = -1
                        break
                    }
                    if (to == BROKEN) {
                        valid = false
                        break
                    }
                    at = to
                }
                while (length > 0) {
                    val node = path[--length]
                    if (valid) {
                        distance++
                        state[node] = VALID
                        root[node] = reached
                        depth[node] = distance
                    } else {
                        state[node] = INVALID
                    }
                }
            }
            // By the generator each chain reaches, in list order; inside a chain, the farthest feeder first.
            val order = (0 until count).filter { state[it] == VALID }
                .sortedWith(compareBy<Int>({ root[it] }, { -depth[it] }, { it }))
                .toIntArray()
            val target = IntArray(count) { if (next[it] >= 0) next[it] else -1 }
            return ModulatorLinks(order, target)
        }
    }
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
internal const val SOURCE_LINK = 127

/** Bit 15 of a destination: the destination is another modulator, not a generator. */
internal const val LINK_FLAG = 0x8000

/** The link index for a link that has no target. It is past the end of every list a plan can place. */
internal const val NO_LINK_TARGET = 0x7FFF

private const val LINEAR = 0
private const val CONCAVE = 1
private const val CONVEX = 2
private const val SWITCH = 3
