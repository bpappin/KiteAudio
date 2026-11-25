package io.github.yuroyami.kiteaudio.synth

import kotlin.math.PI
import kotlin.math.sin

/**
 * Fonts with a left and a right sample, and the tools to measure what each output channel plays.
 *
 * Sample 0 is the left side and sample 1 the right side. Sample 2 is a stray left sample that links
 * to sample 1, and only the extra zone of the stealing test plays it. When sample 1 links to sample 2,
 * those two are a pair and sample 0 is left out. All three hold the same looped sine, so a channel's
 * pitch and phase show in its zero crossings.
 * The left zone pans hard left and the right zone hard right, so each output channel holds one side.
 */
internal object StereoFonts {
    const val RATE = 48_000

    /** Frames per cycle of the sine: 1000 Hz at the sample's own key and rate. */
    const val PERIOD = 48
    const val FRAMES = PERIOD * 100
    const val MONO = 1
    const val RIGHT = 2
    const val LEFT = 4

    class Side(
        val extras: List<Pair<Int, Int>> = emptyList(),
        val type: Int,
        val link: Int,
        val keyLow: Int = 0,
        val keyHigh: Int = 127,
        val velLow: Int = 0,
        val velHigh: Int = 127,
        val rootKey: Int = 60,
        val correction: Int = 0,
        val rate: Int = RATE,
        val frames: Int = FRAMES,
        /** False ends the sample once instead of looping it. */
        val loop: Boolean = true,
        val modulators: List<Sf2Builder.ModSpec> = emptyList(),
        /** Null pans the left side hard left and the right side hard right. */
        val pan: Int? = null,
    )

    /** A left side that links to the right one, with [extras] on its zone. */
    fun left(extras: List<Pair<Int, Int>> = emptyList(), type: Int = LEFT, link: Int = 1) = Side(extras, type, link)

    /** A right side that links to the left one, with [extras] on its zone. */
    fun right(extras: List<Pair<Int, Int>> = emptyList(), type: Int = RIGHT, link: Int = 0) = Side(extras, type, link)

    /** The same side with both samples marked mono and unlinked, which is how a font plays without pairs. */
    fun Side.mono(): Side = Side(extras, MONO, 0, keyLow, keyHigh, velLow, velHigh, rootKey, correction, rate, frames, loop, modulators, pan)

    /**
     * One preset over the two sides. With [split], each side gets its own instrument and preset
     * zone, so the two zones are not in the same instrument. [extraKey] adds a mono zone over the
     * stray sample on that key alone, panned hard right.
     */
    fun font(left: Side, right: Side, split: Boolean = false, extraKey: Int? = null): SoundFont {
        val pool = ShortArray(left.frames + right.frames + FRAMES)
        fun fill(from: Int, count: Int) {
            for (i in 0 until count) pool[from + i] = (sin(2.0 * PI * i / PERIOD) * 20000).toInt().toShort()
        }
        fill(0, left.frames)
        fill(left.frames, right.frames)
        fill(left.frames + right.frames, FRAMES)
        fun spec(name: String, start: Int, side: Side) = Sf2Builder.SampleSpec(
            name, start, start + side.frames, start + PERIOD * 10, start + side.frames - PERIOD * 10,
            side.rate, side.rootKey, side.correction, side.type, side.link,
        )
        val samples = listOf(
            spec("left", 0, left),
            spec("right", left.frames, right),
            Sf2Builder.SampleSpec(
                "stray", left.frames + right.frames, pool.size, left.frames + right.frames + 480, pool.size - 480, RATE, 60,
                type = LEFT, link = 1,
            ),
        )
        fun zone(sampleId: Int, side: Side, pan: Int) = Sf2Builder.ZoneSpec(
            sampleId = sampleId,
            keyLow = side.keyLow, keyHigh = side.keyHigh, velLow = side.velLow, velHigh = side.velHigh,
            extras = listOf(Generator.SampleModes.id to if (side.loop) 1 else 0, Generator.Pan.id to pan) + side.extras,
            modulators = side.modulators,
        )
        val leftZone = zone(0, left, left.pan ?: -500)
        val rightZone = zone(1, right, right.pan ?: 500)
        val extraZone = extraKey?.let { zone(2, Side(type = MONO, link = 0, keyLow = it, keyHigh = it), 500) }
        val instruments = if (split) {
            listOf(Sf2Builder.InstrumentSpec("left inst", listOf(leftZone)), Sf2Builder.InstrumentSpec("right inst", listOfNotNull(rightZone, extraZone)))
        } else {
            listOf(Sf2Builder.InstrumentSpec("pair inst", listOfNotNull(leftZone, rightZone, extraZone)))
        }
        val presetZones = if (split) listOf(Sf2Builder.PresetZoneSpec(0), Sf2Builder.PresetZoneSpec(1)) else listOf(Sf2Builder.PresetZoneSpec(0))
        return KiteSynth.readFont(
            Sf2Builder().buildFont(
                fontName = "Stereo",
                sampleFrames = pool,
                samples = samples,
                instruments = instruments,
                presets = listOf(Sf2Builder.PresetDef("Stereo", 0, 0, presetZones)),
            ),
        )
    }

    /** The left zone's own pitch values: a different tune, root key and vibrato from the right zone's. */
    val leftTune: List<Pair<Int, Int>> = listOf(
        Generator.CoarseTune.id to 7,
        Generator.FineTune.id to -30,
        Generator.OverridingRootKey.id to 55,
        Generator.VibLfoToPitch.id to 30,
        Generator.FreqVibLfo.id to 300,
    )

    /** The right zone's pitch values: its own tune, and a modulation envelope and LFO that move it. */
    val rightTune: List<Pair<Int, Int>> = listOf(
        Generator.CoarseTune.id to 2,
        Generator.ModEnvToPitch.id to 200,
        Generator.AttackModEnv.id to -3_000,
        Generator.DecayModEnv.id to -1_200,
        Generator.SustainModEnv.id to 500,
        Generator.ReleaseModEnv.id to -1_200,
        Generator.ModLfoToPitch.id to 20,
        Generator.FreqModLfo.id to -500,
    )

    /** Half a second of release, so a released note still sounds for a while. */
    private val release = Generator.ReleaseVolEnv.id to -1_200

    val leftPitch: List<Pair<Int, Int>> = leftTune + release
    val rightPitch: List<Pair<Int, Int>> = rightTune + release

    /** A valid pair with different pitch values on its two zones. */
    val validPair: Pair<Side, Side> get() = left(leftPitch) to right(rightPitch)

    /**
     * Every way a stereo link can fail for a note, by name: the left side, the right side, and
     * whether the two zones sit in different instruments. Each must play as two separate voices.
     */
    val fallbacks: Map<String, Triple<Side, Side, Boolean>> get() = mapOf(
        "a link past the last sample" to Triple(left(leftPitch, link = 99), right(rightPitch), false),
        "a link that is not returned" to Triple(left(leftPitch), right(rightPitch, link = 2), false),   // sample 1 pairs with 2
        "two left samples" to Triple(left(leftPitch), right(rightPitch, type = LEFT), false),
        "a partner zone off the key" to Triple(left(leftPitch), Side(rightPitch, RIGHT, 0, keyHigh = 59), false),
        "a partner zone off the velocity" to Triple(left(leftPitch), Side(rightPitch, RIGHT, 0, velHigh = 99), false),
        "a partner in another instrument" to Triple(left(leftPitch), right(rightPitch), true),
        "mono samples" to Triple(left(leftPitch).mono(), right(rightPitch).mono(), false),
    )

    /** A synth with no room sound, so each output channel holds only what its voices played. */
    fun synth(font: SoundFont, maxVoices: Int = 16): Synth = Synth(font, RATE, maxVoices).also { it.reverb.wet = 0.0 }

    /** Renders [frames] more frames from [synth] and adds them to [into]. */
    fun take(synth: Synth, frames: Int, into: MutableList<FloatArray>) {
        val out = FloatArray(frames * 2)
        synth.render(out, frames)
        into += out
    }

    fun joined(parts: List<FloatArray>): FloatArray {
        val all = FloatArray(parts.sumOf { it.size })
        var at = 0
        for (part in parts) {
            part.copyInto(all, at)
            at += part.size
        }
        return all
    }

    /**
     * A fixed performance: a note, a bend and the mod wheel while it sounds, then its release.
     * The fallback tests compare it bit for bit between fonts.
     */
    fun perform(font: SoundFont, key: Int = 60, velocity: Int = 100): FloatArray {
        val synth = synth(font)
        val parts = ArrayList<FloatArray>()
        synth.noteOn(0, key, velocity)
        take(synth, 4_800, parts)
        synth.pitchBend(0, 3_000)
        synth.controlChange(0, 1, 90)
        take(synth, 4_800, parts)
        synth.noteOff(0, key)
        take(synth, 9_600, parts)
        return joined(parts)
    }

    /** Where [channel] (0 left, 1 right) crosses zero going up, in frames, between two frames by a straight line. */
    fun crossings(buffer: FloatArray, channel: Int, from: Int, to: Int): DoubleArray {
        val found = ArrayList<Double>()
        var previous = buffer[from * 2 + channel]
        for (frame in from + 1 until to) {
            val value = buffer[frame * 2 + channel]
            if (previous <= 0f && value > 0f) found += frame - 1 + previous / (previous - value).toDouble()
            previous = value
        }
        return found.toDoubleArray()
    }

    /** The mean frequency of a run of crossings, in hertz. */
    fun frequency(crossings: DoubleArray): Double =
        (crossings.size - 1) * RATE / (crossings.last() - crossings.first())

    /** An FNV-1a hash of every value's bits, to pin an output exactly. */
    fun hash(buffer: FloatArray): Long {
        var hash = -0x340d631b7bdddcdbL
        for (value in buffer) {
            hash = (hash xor value.toRawBits().toLong()) * 0x100000001b3L
        }
        return hash
    }
}
