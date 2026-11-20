package io.github.yuroyami.kiteaudio.synth

/**
 * A generator: one number that shapes a voice.
 *
 * SoundFont 2.04 defines sixty of these. Each one is an operator number and an amount, and the
 * amount's meaning depends entirely on the operator: some are cents, some are timecents, some
 * are centibels, some are a pair of bytes packed into a short.
 *
 * Only the operators this synthesiser reads are named. The rest are kept by number, because a
 * font is allowed to carry them and throwing them away would make a written copy differ from
 * what was read.
 */
public enum class Generator(public val id: Int, public val signed: Boolean = true) {
    StartAddrsOffset(0),
    EndAddrsOffset(1),
    StartLoopAddrsOffset(2),
    EndLoopAddrsOffset(3),
    StartAddrsCoarseOffset(4),
    ModLfoToPitch(5),
    VibLfoToPitch(6),
    ModEnvToPitch(7),
    InitialFilterFc(8),
    InitialFilterQ(9),
    ModLfoToFilterFc(10),
    ModEnvToFilterFc(11),
    EndAddrsCoarseOffset(12),
    ModLfoToVolume(13),
    ChorusEffectsSend(15),
    ReverbEffectsSend(16),
    Pan(17),
    DelayModLfo(21),
    FreqModLfo(22),
    DelayVibLfo(23),
    FreqVibLfo(24),
    DelayModEnv(25),
    AttackModEnv(26),
    HoldModEnv(27),
    DecayModEnv(28),
    SustainModEnv(29),
    ReleaseModEnv(30),
    KeynumToModEnvHold(31),
    KeynumToModEnvDecay(32),
    DelayVolEnv(33),
    AttackVolEnv(34),
    HoldVolEnv(35),
    DecayVolEnv(36),
    SustainVolEnv(37),
    ReleaseVolEnv(38),
    KeynumToVolEnvHold(39),
    KeynumToVolEnvDecay(40),
    Instrument(41, signed = false),
    KeyRange(43, signed = false),
    VelRange(44, signed = false),
    StartLoopAddrsCoarseOffset(45),
    Keynum(46),
    Velocity(47),
    InitialAttenuation(48),
    EndLoopAddrsCoarseOffset(50),
    CoarseTune(51),
    FineTune(52),
    SampleId(53, signed = false),
    SampleModes(54),
    ScaleTuning(56),
    ExclusiveClass(57),
    OverridingRootKey(58),
    ;

    /**
     * Reads a raw 16-bit amount as this generator means it.
     *
     * The format stores one 16-bit field whose meaning depends entirely on the operator, and for
     * all but a handful it is a **signed** short. The specification's own min and max columns
     * describe the value a voice ends up with, not what the field may hold: a preset's generator
     * is an offset added to the instrument's, so it has to be able to subtract. Reading
     * attenuation as unsigned turns "this layer is 6 dB louder" into "this layer is silent".
     *
     * The exceptions are the two range operators, which are a pair of bytes rather than a
     * number, and the two that point at another record.
     */
    public fun read(raw: Int): Int =
        if (signed && raw >= 0x8000) raw - 0x10000 else raw

    public companion object {
        private val byId: Map<Int, Generator> = entries.associateBy { it.id }

        /** The generator with this operator number, or null when it is one we do not name. */
        public fun of(id: Int): Generator? = byId[id]
    }
}

/**
 * The generators that belong to one zone, kept by operator number.
 *
 * A map rather than sixty fields, because a zone usually sets three or four of them and
 * inheritance works by asking "did this zone say anything about X".
 */
public class GeneratorSet(private val values: Map<Int, Int> = emptyMap()) {

    public val size: Int get() = values.size

    /** The amount, read as this generator means it, or null when the zone says nothing. */
    public operator fun get(generator: Generator): Int? =
        values[generator.id]?.let { generator.read(it) }

    /** The amount, or [fallback] when this zone says nothing about it. */
    public fun get(generator: Generator, fallback: Int): Int = get(generator) ?: fallback

    /** The raw sixteen bits, before any signed reading. Ranges need this. */
    public fun raw(generator: Generator): Int? = values[generator.id]

    /** True when this zone sets it at all. */
    public operator fun contains(generator: Generator): Boolean = generator.id in values

    /**
     * This set laid over [other].
     *
     * Instrument zones sit under preset zones, so the preset wins where both speak. The
     * specification calls this an offset for most generators, but for the ones this synthesiser
     * reads a straight override is the behaviour every real font expects.
     */
    public fun over(other: GeneratorSet): GeneratorSet = GeneratorSet(other.values + values)

    /** Everything this set says, for tests and for writing a font back out. */
    public fun all(): Map<Int, Int> = values

    override fun toString(): String = "GeneratorSet(${values.size} set)"
}

/** The lowest and highest key or velocity a zone answers to. */
public data class Span(val low: Int, val high: Int) {
    public operator fun contains(value: Int): Boolean = value in low..high

    public companion object {
        /** Everything, which is what a zone means when it says nothing. */
        public val Full: Span = Span(0, 127)

        /** Unpacks the two bytes SoundFont packs a range into. */
        public fun ofPacked(amount: Int): Span = Span(amount and 0xFF, (amount shr 8) and 0xFF)
    }
}

/** How a sample loops. */
public enum class LoopMode {
    /** Play once and stop. */
    NoLoop,

    /** Loop for as long as the key is held. */
    Continuous,

    /** Loop while held, then play the rest of the sample through the release. */
    UntilRelease,
    ;

    public companion object {
        /** Reads the SampleModes generator. 2 is undefined in the specification and means no loop. */
        public fun of(amount: Int): LoopMode = when (amount and 0x3) {
            1 -> Continuous
            3 -> UntilRelease
            else -> NoLoop
        }
    }
}

/** One sample in the font: its own frames, and the pitch it was recorded at. */
public class SampleHeader(
    public val name: String,
    /** This sample's frames, 16-bit signed, mono. Nothing else shares them. */
    public val data: ShortArray,
    /** Loop points in frames, counted from the first frame of [data]. */
    public val loopStart: Int,
    public val loopEnd: Int,
    public val sampleRate: Int,
    /** The MIDI key this recording sounds at. */
    public val originalKey: Int,
    /** A tweak in cents, so a slightly sharp recording can be pulled back. */
    public val correctionCents: Int,
    public val link: Int,
    public val type: Int,
    /** An SF3 sample's Ogg Vorbis stream while it is still compressed. [data] stays empty until [SoundFont.decoded] fills it. */
    public val compressed: ByteArray? = null,
) {
    public val frameCount: Int get() = data.size

    /** True when the loop points are usable. A font with a broken loop is common. */
    public val hasUsableLoop: Boolean
        get() = loopEnd > loopStart && loopStart >= 0 && loopEnd <= data.size

    override fun toString(): String =
        compressed?.let { "SampleHeader($name, ${it.size} compressed bytes at ${sampleRate}Hz)" }
            ?: "SampleHeader($name, ${frameCount}f at ${sampleRate}Hz)"
}

/**
 * One modulator record, exactly as the file stores it: a source, a destination and an amount.
 *
 * Kept, not evaluated. What a modulator does to a voice is the synthesiser's business.
 */
public class Modulator(
    /** sfModSrcOper: controller in bits 0 to 6, CC flag bit 7, direction bit 8, polarity bit 9, curve in 10 to 15. */
    public val source: Int,
    /** The destination. With bit 15 set it feeds another modulator: the rest is that one's place in this zone's list. */
    public val destinationId: Int,
    /** Signed: how far the destination moves at full source. */
    public val amount: Int,
    public val amountSource: Int,
    public val transform: Int,
) {
    /** The generator this adds to, or null when the destination is not a named generator. */
    public val destination: Generator?
        get() = if ((destinationId and 0x8000) != 0) null else Generator.of(destinationId)

    /** Two modulators are the same modulator when everything but the amount matches. */
    public fun sameAs(other: Modulator): Boolean =
        source == other.source && destinationId == other.destinationId &&
            amountSource == other.amountSource && transform == other.transform

    override fun toString(): String = "Modulator($source to $destinationId, amount $amount)"
}

/** One zone of an instrument: a set of generators, and the sample they point at. */
public class InstrumentZone(
    public val generators: GeneratorSet,
    public val keys: Span,
    public val velocities: Span,
    /** Index into the font's samples, or null for a global zone. */
    public val sampleId: Int?,
    /** This zone's modulators, with its instrument's global ones already laid underneath. */
    public val modulators: List<Modulator> = emptyList(),
)

/** A named collection of zones. */
public class Instrument(public val name: String, public val zones: List<InstrumentZone>) {
    override fun toString(): String = "Instrument($name, ${zones.size} zones)"
}

/** One zone of a preset: generators, and the instrument it points at. */
public class PresetZone(
    public val generators: GeneratorSet,
    public val keys: Span,
    public val velocities: Span,
    /** Index into the font's instruments, or null for a global zone. */
    public val instrumentId: Int?,
    /** This zone's modulators, with its preset's global ones already laid underneath. */
    public val modulators: List<Modulator> = emptyList(),
)

/** A sound a person can choose: a bank number, a program number, and its zones. */
public class Preset(
    public val name: String,
    public val program: Int,
    public val bank: Int,
    public val zones: List<PresetZone>,
) {
    override fun toString(): String = "Preset($name, bank $bank program $program)"
}

/**
 * A loaded SoundFont.
 *
 * Every sample carries its own frames, so two fonts merge by joining lists, and a voice reads
 * straight out of its sample without copying anything.
 */
public class SoundFont(
    public val name: String,
    public val presets: List<Preset>,
    public val instruments: List<Instrument>,
    public val samples: List<SampleHeader>,
    /** The INFO list's text chunks by their four-letter id, so a writer can put them back. */
    public val info: Map<String, String> = emptyMap(),
) {
    /** Finds a sound by bank and program, which is what a MIDI program change asks for. */
    public fun preset(bank: Int, program: Int): Preset? =
        presets.firstOrNull { it.bank == bank && it.program == program }

    /** The first preset, for a font whose banks nobody has looked at yet. */
    public val defaultPreset: Preset? get() = presets.minByOrNull { it.bank * 1000 + it.program }

    /**
     * This font with every compressed sample decoded by [decoder], which turns one sample's Ogg
     * Vorbis bytes into its 16-bit mono frames. A font with nothing compressed comes back as it is.
     */
    public fun decoded(decoder: (ByteArray) -> ShortArray): SoundFont {
        if (samples.none { it.compressed != null }) return this
        val plain = samples.map { sample ->
            val stream = sample.compressed ?: return@map sample
            val frames = decoder(stream)
            SampleHeader(
                name = sample.name,
                data = frames,
                loopStart = sample.loopStart.coerceIn(0, frames.size),
                loopEnd = sample.loopEnd.coerceIn(0, frames.size),
                sampleRate = sample.sampleRate,
                originalKey = sample.originalKey,
                correctionCents = sample.correctionCents,
                link = sample.link,
                type = sample.type and SAMPLE_COMPRESSED.inv(),
            )
        }
        return SoundFont(name, presets, instruments, plain, info)
    }

    override fun toString(): String =
        "SoundFont($name, ${presets.size} presets, ${samples.size} samples, " +
            "${samples.sumOf { it.frameCount }} frames)"
}

/**
 * This font plus [other], as one font. Where both have a preset at one bank and program, this
 * font's wins. No frames are copied. The result is [pruned], so indexes into either font change.
 */
public fun SoundFont.merged(other: SoundFont, name: String = this.name): SoundFont {
    // Pruned first: a zone pointing past this font's own lists would land on the other font's sounds.
    val base = pruned()
    val taken = base.presets.mapTo(HashSet()) { it.bank to it.program }
    val instrumentBase = base.instruments.size
    val sampleBase = base.samples.size
    fun movedSample(id: Int?): Int? = id?.takeIf { it in other.samples.indices }?.plus(sampleBase)
    fun movedInstrument(id: Int?): Int? = id?.takeIf { it in other.instruments.indices }?.plus(instrumentBase)

    val movedSamples = other.samples.map { sample ->
        if (sample.isLinked) (movedSample(sample.link)?.let { sample.withLink(it) } ?: sample.asMono()) else sample
    }
    val movedInstruments = other.instruments.map { instrument ->
        Instrument(instrument.name, instrument.zones.map { it.pointingAt(movedSample(it.sampleId)) })
    }
    val movedPresets = other.presets.filter { (it.bank to it.program) !in taken }.map { preset ->
        Preset(preset.name, preset.program, preset.bank, preset.zones.map { it.pointingAt(movedInstrument(it.instrumentId)) })
    }
    return SoundFont(
        name, base.presets + movedPresets, base.instruments + movedInstruments, base.samples + movedSamples, base.info,
    ).pruned()
}

/** Drops every instrument no preset uses and every sample no instrument uses, renumbering the rest. */
public fun SoundFont.pruned(): SoundFont {
    val usedInstruments = BooleanArray(instruments.size)
    for (preset in presets) for (zone in preset.zones) {
        zone.instrumentId?.takeIf { it in instruments.indices }?.let { usedInstruments[it] = true }
    }
    val usedSamples = BooleanArray(samples.size)
    for (index in instruments.indices) if (usedInstruments[index]) for (zone in instruments[index].zones) {
        zone.sampleId?.takeIf { it in samples.indices }?.let { usedSamples[it] = true }
    }
    // A stereo pair, or a longer linked chain, stays whole even when a zone names only one of it.
    var grew = true
    while (grew) {
        grew = false
        for (index in samples.indices) {
            val partner = samples[index].link
            if (usedSamples[index] && samples[index].isLinked && partner in samples.indices && !usedSamples[partner]) {
                usedSamples[partner] = true
                grew = true
            }
        }
    }
    val instrumentMap = renumbering(usedInstruments)
    val sampleMap = renumbering(usedSamples)
    fun remap(map: IntArray, id: Int?): Int? = id?.takeIf { it in map.indices }?.let { map[it] }?.takeIf { it >= 0 }

    return SoundFont(
        name = name,
        presets = presets.map { preset ->
            Preset(preset.name, preset.program, preset.bank, preset.zones.mapNotNull { zone ->
                remap(instrumentMap, zone.instrumentId)?.let { zone.pointingAt(it) }
            })
        },
        instruments = instruments.filterIndexed { index, _ -> usedInstruments[index] }.map { instrument ->
            Instrument(instrument.name, instrument.zones.mapNotNull { zone ->
                remap(sampleMap, zone.sampleId)?.let { zone.pointingAt(it) }
            })
        },
        samples = samples.filterIndexed { index, _ -> usedSamples[index] }.map { sample ->
            if (sample.isLinked) (remap(sampleMap, sample.link)?.let { sample.withLink(it) } ?: sample.asMono()) else sample
        },
        info = info,
    )
}

/** Old index to new, or -1 for one that goes. */
private fun renumbering(kept: BooleanArray): IntArray {
    var next = 0
    return IntArray(kept.size) { if (kept[it]) next++ else -1 }
}

/** Right, left and linked samples name their partner in [SampleHeader.link]; mono ones do not. */
private val SampleHeader.isLinked: Boolean get() = (type and 0x0E) != 0

private fun SampleHeader.withLink(link: Int): SampleHeader =
    SampleHeader(name, data, loopStart, loopEnd, sampleRate, originalKey, correctionCents, link, type, compressed)

/** A sample whose partner is gone plays on its own. The ROM flag stays. */
private fun SampleHeader.asMono(): SampleHeader =
    SampleHeader(name, data, loopStart, loopEnd, sampleRate, originalKey, correctionCents, 0, (type and 0x8000) or 1, compressed)

private fun InstrumentZone.pointingAt(sampleId: Int?): InstrumentZone =
    InstrumentZone(generators.withPointer(Generator.SampleId, sampleId), keys, velocities, sampleId, modulators)

private fun PresetZone.pointingAt(instrumentId: Int?): PresetZone =
    PresetZone(generators.withPointer(Generator.Instrument, instrumentId), keys, velocities, instrumentId, modulators)

/** The pointer generator has to agree with the zone's own pointer after a renumbering. */
private fun GeneratorSet.withPointer(generator: Generator, id: Int?): GeneratorSet {
    val values = LinkedHashMap(all())
    if (id == null) values.remove(generator.id) else values[generator.id] = id
    return GeneratorSet(values)
}

/** The sample type bit that marks an SF3 sample: an Ogg Vorbis stream, not 16-bit frames. */
internal const val SAMPLE_COMPRESSED: Int = 0x10
