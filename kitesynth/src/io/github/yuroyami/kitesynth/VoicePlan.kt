package io.github.yuroyami.kitesynth

/**
 * Everything one voice needs, already worked out.
 *
 * Values are in the specification's own units, because converting early loses precision and
 * hides where a number came from. The voice converts once, when it starts.
 */
public class VoicePlan(
    public val sample: SampleHeader,
    /** Which key was pressed, which is not always the key the sample plays at. */
    public val key: Int,
    public val velocity: Int,
    public val loopMode: LoopMode,
    /** Where playback begins in the sample's own frames, after the start offset generators. */
    public val startFrame: Int,
    public val endFrame: Int,
    public val loopStartFrame: Int,
    public val loopEndFrame: Int,
    /** The key this sample really sounds at, after any override. */
    public val rootKey: Int,
    /** Tuning in cents, from coarse, fine, and the sample's own correction. */
    public val tuneCents: Int,
    /** How many cents the pitch moves per key. 100 is normal; 0 makes the sample one pitch. */
    public val scaleTuning: Int,
    /** Loudness cut in centibels. Higher is quieter. */
    public val attenuationCb: Int,
    /** Left to right, from -500 to 500. */
    public val pan: Int,
    public val volumeEnvelope: EnvelopeSpec,
    /** Notes in the same class cut each other off. Zero means no class. */
    public val exclusiveClass: Int,
    /**
     * Every generator's value for this voice, indexed by operator number: the format's default,
     * then the instrument's value, then the preset's offset added. What the fields above do not name.
     */
    public val generators: IntArray = defaultGenerators(),
    /** What the voice's modulators are: the format's defaults, the instrument's and the preset's, merged. */
    public val modulators: List<Modulator> = DefaultModulators,
) {
    /** Peak sample amplitude cached during preparation, so voice stealing does not scan audio in render. */
    internal var samplePeak: Double = 1.0

    /** One generator's value, as [generators] holds it. */
    public operator fun get(generator: Generator): Int = generators[generator.id]

    /** The same list as an array, so a voice walks it without an iterator. */
    internal val modulatorArray: Array<Modulator> = modulators.toTypedArray()

    /** Which controllers the modulators read, so a controller change only reaches the voices it moves. */
    internal val watchedControllers: BooleanArray = BooleanArray(128).also { watched ->
        for (modulator in modulators) for (source in intArrayOf(modulator.source, modulator.amountSource)) {
            if ((source and CC_FLAG) != 0) watched[source and 0x7F] = true
        }
    }
    internal val watchesPressure: Boolean = modulators.any { reads(it, SOURCE_PRESSURE) }
    internal val watchesPolyPressure: Boolean = modulators.any { reads(it, SOURCE_POLY_PRESSURE) }
    internal val watchesWheel: Boolean = modulators.any { reads(it, SOURCE_WHEEL) || reads(it, SOURCE_WHEEL_RANGE) }

    override fun toString(): String = "VoicePlan(${sample.name}, key $key, vel $velocity)"
}

/** A volume envelope, in the units the file stores. */
public class EnvelopeSpec(
    public val delayTimecents: Int,
    public val attackTimecents: Int,
    public val holdTimecents: Int,
    public val decayTimecents: Int,
    /** How far it falls before sustaining, in centibels. 0 is no drop, 1000 is silence. */
    public val sustainCb: Int,
    public val releaseTimecents: Int,
) {
    public companion object {
        /**
         * What the specification says a generator means when nobody sets it.
         *
         * -12000 timecents is one millisecond, which is the shortest the format can say and
         * reads as instant. A missing attack must be instant, not a second long.
         */
        public val Default: EnvelopeSpec = EnvelopeSpec(
            delayTimecents = -12000,
            attackTimecents = -12000,
            holdTimecents = -12000,
            decayTimecents = -12000,
            sustainCb = 0,
            releaseTimecents = -12000,
        )
    }
}

/**
 * Picks the voices a note should start.
 *
 * A note can start more than one. A layered sound is exactly that: two preset zones that both
 * answer to the same key, each pointing at its own instrument.
 *
 * The merge rule is the part worth reading. Inside one level a global zone sets defaults and a
 * local zone overrides them. Between levels the specification is different: a preset generator
 * **adds to** the instrument's value rather than replacing it, so a preset that says "quieter by
 * 60" means sixty quieter than whatever the instrument chose, not exactly sixty. Getting this
 * backwards makes every layered font too loud or too quiet in a way that is hard to hear as a
 * bug and easy to hear as bad.
 */
public fun SoundFont.voicesFor(preset: Preset, key: Int, velocity: Int): List<VoicePlan> {
    val plans = ArrayList<VoicePlan>(2)

    for (presetZone in preset.zones) {
        if (key !in presetZone.keys || velocity !in presetZone.velocities) continue
        val instrument = instruments.getOrNull(presetZone.instrumentId ?: continue) ?: continue

        for (instrumentZone in instrument.zones) {
            if (key !in instrumentZone.keys || velocity !in instrumentZone.velocities) continue
            val sample = samples.getOrNull(instrumentZone.sampleId ?: continue) ?: continue
            plans.add(plan(sample, instrumentZone, presetZone, key, velocity))
        }
    }
    return plans
}

/**
 * The same, choosing the preset the way a [Synth] channel does. Bank 128 holds drum kits: a
 * missing kit is kit 0, and a font with no kit plays nothing. Any other missing bank tries bank 0,
 * then the font's first preset.
 */
public fun SoundFont.voicesFor(bank: Int, program: Int, key: Int, velocity: Int): List<VoicePlan> {
    val chosen = if (bank == 128) {
        preset(128, program) ?: preset(128, 0) ?: return emptyList()
    } else {
        preset(bank, program) ?: preset(0, program) ?: defaultPreset ?: return emptyList()
    }
    return voicesFor(chosen, key, velocity)
}

/**
 * Immutable synthesis data compiled before a [Synth] can reach a render callback.
 *
 * A region is one preset-zone/instrument-zone pair. Its voice values never change from note to
 * note; only the pressed key and velocity do. Keeping those two values outside [VoicePlan] lets
 * the callback reuse this object for every matching note instead of rebuilding plans and lists.
 */
internal class PreparedVoiceRegion(
    val plan: VoicePlan,
    val keyLow: Int,
    val keyHigh: Int,
    val velocityLow: Int,
    val velocityHigh: Int,
) {
    fun matches(key: Int, velocity: Int): Boolean =
        key >= keyLow && key <= keyHigh &&
            velocity >= velocityLow && velocity <= velocityHigh
}

internal class PreparedPreset(
    val bank: Int,
    val program: Int,
    val regions: Array<PreparedVoiceRegion>,
    val source: Preset,
)

/** A compact, allocation-free lookup table for the presets of one exact SoundFont. */
internal class PreparedSoundFont private constructor(
    private val presets: Array<PreparedPreset>,
    /** The font's first preset, as [SoundFont.defaultPreset] picks it. */
    val fallback: PreparedPreset?,
) {
    /** The preset at exactly this bank and program, or null. What a miss means is the synthesiser's call. */
    fun preset(bank: Int, program: Int): PreparedPreset? {
        var index = 0
        while (index < presets.size) {
            val candidate = presets[index]
            if (candidate.bank == bank && candidate.program == program) return candidate
            index++
        }
        return null
    }

    companion object {
        fun build(font: SoundFont, maxRegions: Int, maxModulators: Int, maxPairs: Long): PreparedSoundFont {
            // A still-compressed sample has no frames yet, so it would play as silence or worse.
            require(font.samples.none { it.compressed != null }) {
                "the font still has compressed samples; play the font that SoundFont.decoded returns"
            }
            require(maxRegions >= 0) { "prepared region budget must not be negative" }
            require(maxModulators >= 0) { "prepared modulator budget must not be negative" }
            require(maxPairs >= 0) { "prepared candidate pair budget must not be negative" }
            var regionCount = 0
            var modulatorCount = 0L
            var examinedPairs = 0L
            // Count all compatible pairs before allocating any plans. The voice pool bounds
            // playback polyphony, but does not bound this preset/instrument Cartesian product.
            for (preset in font.presets) for (presetZone in preset.zones) {
                val instrument = font.instruments.getOrNull(presetZone.instrumentId ?: continue) ?: continue
                for (instrumentZone in instrument.zones) {
                    // Empty intersections and missing samples still cost a candidate visit.
                    // Check before incrementing so even Long.MAX_VALUE cannot overflow.
                    if (examinedPairs == maxPairs) {
                        throw SoundFontFormatException("font exceeds the $maxPairs prepared candidate pair budget")
                    }
                    examinedPairs++
                    if (font.samples.getOrNull(instrumentZone.sampleId ?: continue) == null) continue
                    if (!presetZone.overlaps(instrumentZone)) continue
                    if (regionCount == maxRegions) {
                        throw SoundFontFormatException("font exceeds the $maxRegions prepared region budget")
                    }
                    // Match mergedModulators without constructing its list. Shared global
                    // routes would otherwise multiply independently in every prepared plan.
                    val defaults = DefaultModulators.count { default ->
                        instrumentZone.modulators.none { it.sameAs(default) }
                    }
                    modulatorCount += defaults.toLong() + instrumentZone.modulators.size + presetZone.modulators.size
                    if (modulatorCount > maxModulators.toLong()) {
                        throw SoundFontFormatException("font exceeds the $maxModulators prepared modulator entry budget")
                    }
                    regionCount++
                }
            }
            val samplePeaks = DoubleArray(font.samples.size) { index ->
                var peak = 0
                for (frame in font.samples[index].data) peak = maxOf(peak, kotlin.math.abs(frame.toInt()))
                peak / 32768.0
            }
            val prepared = Array(font.presets.size) { index ->
                font.prepare(font.presets[index], samplePeaks)
            }
            var fallback: PreparedPreset? = null
            var fallbackOrder = Int.MAX_VALUE
            var index = 0
            while (index < prepared.size) {
                val candidate = prepared[index]
                val order = candidate.bank * 1000 + candidate.program
                if (fallback == null || order < fallbackOrder) {
                    fallback = candidate
                    fallbackOrder = order
                }
                index++
            }
            return PreparedSoundFont(prepared, fallback)
        }
    }
}

private fun PresetZone.overlaps(zone: InstrumentZone): Boolean =
    maxOf(keys.low, zone.keys.low) <= minOf(keys.high, zone.keys.high) &&
        maxOf(velocities.low, zone.velocities.low) <= minOf(velocities.high, zone.velocities.high)

/** Flattens zone relationships once, while construction is still off the audio callback. */
private fun SoundFont.prepare(preset: Preset, samplePeaks: DoubleArray): PreparedPreset {
    val regions = ArrayList<PreparedVoiceRegion>()
    var presetIndex = 0
    while (presetIndex < preset.zones.size) {
        val presetZone = preset.zones[presetIndex]
        val instrumentId = presetZone.instrumentId
        val instrument = if (instrumentId == null) null else instruments.getOrNull(instrumentId)
        if (instrument != null) {
            var instrumentIndex = 0
            while (instrumentIndex < instrument.zones.size) {
                val instrumentZone = instrument.zones[instrumentIndex]
                val sampleId = instrumentZone.sampleId ?: -1
                val sample = samples.getOrNull(sampleId)
                if (sample != null) {
                    val keyLow = maxOf(presetZone.keys.low, instrumentZone.keys.low)
                    val keyHigh = minOf(presetZone.keys.high, instrumentZone.keys.high)
                    val velocityLow = maxOf(
                        presetZone.velocities.low,
                        instrumentZone.velocities.low,
                    )
                    val velocityHigh = minOf(
                        presetZone.velocities.high,
                        instrumentZone.velocities.high,
                    )
                    if (keyLow <= keyHigh && velocityLow <= velocityHigh) {
                        regions.add(
                            PreparedVoiceRegion(
                                plan = plan(
                                    sample,
                                    instrumentZone,
                                    presetZone,
                                    keyLow,
                                    velocityLow,
                                ).also { it.samplePeak = samplePeaks[sampleId] },
                                keyLow = keyLow,
                                keyHigh = keyHigh,
                                velocityLow = velocityLow,
                                velocityHigh = velocityHigh,
                            ),
                        )
                    }
                }
                instrumentIndex++
            }
        }
        presetIndex++
    }
    return PreparedPreset(preset.bank, preset.program, regions.toTypedArray(), preset)
}

private fun plan(
    sample: SampleHeader,
    instrumentZone: InstrumentZone,
    presetZone: PresetZone,
    key: Int,
    velocity: Int,
): VoicePlan {
    val instrumentGens = instrumentZone.generators
    val presetGens = presetZone.generators
    /** Instrument value, plus the preset's offset if it has one. */
    fun value(generator: Generator, default: Int): Int =
        instrumentGens.get(generator, default) + presetGens.get(generator, 0)

    // Sample addresses are the exception: they move within the sample, and a preset has no
    // business moving them, so only the instrument level is read.
    fun address(coarse: Generator, fine: Generator): Int =
        instrumentGens.get(coarse, 0) * 32768 + instrumentGens.get(fine, 0)

    val start = address(Generator.StartAddrsCoarseOffset, Generator.StartAddrsOffset)
    val end = sample.frameCount + address(Generator.EndAddrsCoarseOffset, Generator.EndAddrsOffset)
    val loopStart = sample.loopStart +
        address(Generator.StartLoopAddrsCoarseOffset, Generator.StartLoopAddrsOffset)
    val loopEnd = sample.loopEnd +
        address(Generator.EndLoopAddrsCoarseOffset, Generator.EndLoopAddrsOffset)

    val envelope = EnvelopeSpec(
        delayTimecents = value(Generator.DelayVolEnv, EnvelopeSpec.Default.delayTimecents),
        attackTimecents = value(Generator.AttackVolEnv, EnvelopeSpec.Default.attackTimecents),
        holdTimecents = value(Generator.HoldVolEnv, EnvelopeSpec.Default.holdTimecents),
        decayTimecents = value(Generator.DecayVolEnv, EnvelopeSpec.Default.decayTimecents),
        sustainCb = value(Generator.SustainVolEnv, 0).coerceIn(0, MAX_ATTENUATION_CB),
        releaseTimecents = value(Generator.ReleaseVolEnv, EnvelopeSpec.Default.releaseTimecents),
    )

    val generators = defaultGenerators()
    for ((id, raw) in instrumentGens.all()) if (id < GENERATOR_COUNT) generators[id] = signedValue(id, raw)
    for ((id, raw) in presetGens.all()) if (id < GENERATOR_COUNT && id !in INSTRUMENT_ONLY) generators[id] += signedValue(id, raw)

    return VoicePlan(
        sample = sample,
        key = key,
        velocity = velocity,
        loopMode = LoopMode.of(instrumentGens.get(Generator.SampleModes, 0)),
        startFrame = start,
        endFrame = end,
        loopStartFrame = loopStart,
        loopEndFrame = loopEnd,
        // A font can say "play this sample as if it were recorded at key 60" even when it was
        // not. -1 means it said nothing, so the sample's own root key stands.
        rootKey = instrumentGens.get(Generator.OverridingRootKey, -1)
            .takeIf { it in 0..127 } ?: sample.originalKey.takeIf { it in 0..127 } ?: 60,
        tuneCents = value(Generator.CoarseTune, 0) * 100 +
            value(Generator.FineTune, 0) +
            sample.correctionCents,
        // Clamped, not trusted. A preset offset can push a sum outside what the format allows,
        // and a font that asks for something illegal still has to end up somewhere sensible
        // rather than silencing the note or sending its pitch to two to the fiftieth.
        scaleTuning = value(Generator.ScaleTuning, 100).coerceIn(0, 1200),
        attenuationCb = value(Generator.InitialAttenuation, 0).coerceIn(0, MAX_ATTENUATION_CB),
        pan = value(Generator.Pan, 0).coerceIn(-500, 500),
        volumeEnvelope = envelope,
        exclusiveClass = instrumentGens.get(Generator.ExclusiveClass, 0),
        generators = generators,
        modulators = mergedModulators(instrumentZone.modulators, presetZone.modulators),
    )
}

/** Operator numbers run from 0 to 60 in SoundFont 2.04. */
internal const val GENERATOR_COUNT = 61

/** What the format says each generator is when nobody sets it. */
public fun defaultGenerators(): IntArray = IntArray(GENERATOR_COUNT).also { values ->
    values[Generator.InitialFilterFc.id] = 13500
    for (timing in listOf(
        Generator.DelayModLfo, Generator.DelayVibLfo, Generator.DelayModEnv, Generator.AttackModEnv,
        Generator.HoldModEnv, Generator.DecayModEnv, Generator.ReleaseModEnv, Generator.DelayVolEnv,
        Generator.AttackVolEnv, Generator.HoldVolEnv, Generator.DecayVolEnv, Generator.ReleaseVolEnv,
    )) values[timing.id] = -12000
    values[Generator.Keynum.id] = -1
    values[Generator.Velocity.id] = -1
    values[Generator.ScaleTuning.id] = 100
    values[Generator.OverridingRootKey.id] = -1
}

/** Generators a preset may not offset: sample addresses, fixed values and pointers. */
private val INSTRUMENT_ONLY: Set<Int> = listOf(
    Generator.StartAddrsOffset, Generator.EndAddrsOffset, Generator.StartLoopAddrsOffset, Generator.EndLoopAddrsOffset,
    Generator.StartAddrsCoarseOffset, Generator.EndAddrsCoarseOffset, Generator.StartLoopAddrsCoarseOffset,
    Generator.EndLoopAddrsCoarseOffset, Generator.Keynum, Generator.Velocity, Generator.SampleModes,
    Generator.ExclusiveClass, Generator.OverridingRootKey, Generator.SampleId, Generator.Instrument,
    Generator.KeyRange, Generator.VelRange,
).mapTo(HashSet()) { it.id }

/** A raw amount read the way its generator means it; an unnamed one reads as signed. */
private fun signedValue(id: Int, raw: Int): Int = Generator.of(id)?.read(raw) ?: if (raw >= 0x8000) raw - 0x10000 else raw

// The format's own limit for attenuation and for a sustain level, in centibels. Well past the
// point where a voice is inaudible, and it is the format's number rather than this synthesiser's
// idea of silence.
private const val MAX_ATTENUATION_CB = 1440
