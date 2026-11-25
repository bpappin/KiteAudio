package io.github.yuroyami.kiteaudio.synth

/**
 * Builds a real SoundFont 2 file, byte for byte.
 *
 * Tests need a font, and no font can be committed to a repository without a licence question.
 * This writes one from scratch to the 2.04 layout, which has the useful side effect that the
 * reader is tested against bytes somebody wrote on purpose rather than against a fixture nobody
 * can inspect.
 */
internal class Sf2Builder {

    private val out = ArrayList<Byte>(4096)

    private fun u8(value: Int) {
        out.add((value and 0xFF).toByte())
    }

    private fun u16(value: Int) {
        u8(value)
        u8(value shr 8)
    }

    private fun u32(value: Int) {
        u16(value)
        u16(value shr 16)
    }

    private fun ascii(text: String) = text.forEach { u8(it.code) }

    private fun name(text: String, width: Int) {
        val cut = text.take(width - 1)
        ascii(cut)
        repeat(width - cut.length) { u8(0) }
    }

    /** A zero-terminated INFO text chunk, padded to an even length. */
    private fun text(id: String, value: String) {
        val size = value.length + 1 + (value.length + 1) % 2
        ascii(id); u32(size); ascii(value)
        repeat(size - value.length) { u8(0) }
    }

    private fun placeholderSize(): Int {
        val at = out.size
        u32(0)
        return at
    }

    private fun patchSize(at: Int, size: Int) {
        var value = size
        for (i in 0 until 4) {
            out[at + i] = (value and 0xFF).toByte()
            value = value shr 8
        }
    }

    private fun modulator(mod: ModSpec) {
        u16(mod.source); u16(mod.destination); u16(mod.amount); u16(mod.amountSource); u16(mod.transform)
    }

    /**
     * One preset and one instrument, the shape most tests want.
     *
     * @param sampleFrames the whole sample pool, as the file stores it
     * @param instrumentZones one per zone: sampleId, key and velocity range, extra generators
     * @param presetGlobal generators for a preset-level global zone, or null for none
     * @param sm24 the body of an sm24 chunk, written as given, or null for a 16-bit font
     */
    fun build(
        fontName: String = "Test Font",
        sampleFrames: ShortArray = ShortArray(64) { (it * 400).toShort() },
        samples: List<SampleSpec> = listOf(SampleSpec("saw", 0, 64, 8, 56, 44100, 60)),
        instrumentZones: List<ZoneSpec> = listOf(ZoneSpec(sampleId = 0)),
        instrumentGlobal: List<Pair<Int, Int>> = emptyList(),
        presetZones: List<Pair<Int, List<Pair<Int, Int>>>> = listOf(0 to emptyList()),
        presetGlobal: List<Pair<Int, Int>>? = null,
        version: Int = 2,
        instrumentGlobalModulators: List<ModSpec> = emptyList(),
        presetGlobalModulators: List<ModSpec> = emptyList(),
        engineer: String? = null,
        comment: String? = null,
        presets: List<PresetSpec>? = null,
        sm24: ByteArray? = null,
        minorVersion: Int = 4,
        writeSmpl: Boolean = true,
    ): ByteArray {
        val info = buildMap {
            if (engineer != null) put("IENG", engineer)
            if (comment != null) put("ICMT", comment)
        }
        if (presets != null) {
            // One preset per spec, each with its own instrument over its own 64-frame sample.
            val frames = ShortArray(64 * presets.size) { ((it % 64) * 400).toShort() }
            return buildFont(
                fontName = fontName,
                sampleFrames = frames,
                samples = presets.mapIndexed { index, spec ->
                    SampleSpec(spec.sampleName, index * 64, index * 64 + 64, index * 64 + 8, index * 64 + 56, 44100, 60)
                },
                instruments = presets.mapIndexed { index, spec ->
                    InstrumentSpec("${spec.name} inst", listOf(ZoneSpec(sampleId = index, extras = spec.instrumentExtras)))
                },
                presets = presets.mapIndexed { index, spec ->
                    PresetDef(spec.name, spec.bank, spec.program, listOf(PresetZoneSpec(index)))
                },
                version = version,
                info = info,
                sm24 = sm24,
                minorVersion = minorVersion,
                writeSmpl = writeSmpl,
            )
        }
        return buildFont(
            fontName = fontName,
            sampleFrames = sampleFrames,
            samples = samples,
            instruments = listOf(InstrumentSpec("Piano inst", instrumentZones, instrumentGlobal, instrumentGlobalModulators)),
            presets = listOf(
                PresetDef(
                    name = "Piano", bank = 0, program = 0,
                    zones = presetZones.map { (instrumentId, extras) -> PresetZoneSpec(instrumentId, extras) },
                    global = presetGlobal,
                    globalModulators = presetGlobalModulators,
                ),
            ),
            version = version,
            info = info,
            sm24 = sm24,
            minorVersion = minorVersion,
            writeSmpl = writeSmpl,
        )
    }

    /**
     * Any number of presets and instruments. Every list gets its terminal record.
     *
     * [sm24] is the body of an sm24 chunk after smpl. Its size field is its length, so a test can
     * build a size the format rejects. [writeSmpl] false leaves the smpl chunk out.
     */
    fun buildFont(
        fontName: String = "Test Font",
        sampleFrames: ShortArray,
        samples: List<SampleSpec>,
        instruments: List<InstrumentSpec>,
        presets: List<PresetDef>,
        version: Int = 2,
        info: Map<String, String> = emptyMap(),
        sm24: ByteArray? = null,
        minorVersion: Int = 4,
        writeSmpl: Boolean = true,
    ): ByteArray {
        ascii("RIFF")
        val riffSize = placeholderSize()
        ascii("sfbk")

        // ---- LIST INFO ----
        ascii("LIST")
        val infoSize = placeholderSize()
        val infoStart = out.size
        ascii("INFO")
        ascii("ifil"); u32(4); u16(version); u16(minorVersion)
        val paddedName = if (fontName.length % 2 == 0) fontName else "$fontName "
        ascii("INAM"); u32(paddedName.length + 2); ascii(paddedName); u16(0)
        info.forEach { (id, value) -> text(id, value) }
        patchSize(infoSize, out.size - infoStart)

        // ---- LIST sdta ----
        ascii("LIST")
        val sdtaSize = placeholderSize()
        val sdtaStart = out.size
        ascii("sdta")
        if (writeSmpl) {
            ascii("smpl"); u32(sampleFrames.size * 2)
            sampleFrames.forEach { u16(it.toInt()) }
        }
        if (sm24 != null) {
            ascii("sm24"); u32(sm24.size)
            sm24.forEach { u8(it.toInt()) }
            if (sm24.size % 2 == 1) u8(0)                 // RIFF's pad byte, not counted in the size
        }
        patchSize(sdtaSize, out.size - sdtaStart)

        // ---- LIST pdta ----
        ascii("LIST")
        val pdtaSize = placeholderSize()
        val pdtaStart = out.size
        ascii("pdta")

        // phdr: the presets, then the terminal record.
        val presetBags = presets.sumOf { it.bagCount }
        ascii("phdr"); u32(38 * (presets.size + 1))
        var bagCursor = 0
        presets.forEach { preset ->
            name(preset.name, 20); u16(preset.program); u16(preset.bank); u16(bagCursor); u32(0); u32(0); u32(0)
            bagCursor += preset.bagCount
        }
        name("EOP", 20); u16(0); u16(0); u16(presetBags); u32(0); u32(0); u32(0)

        // pbag: every zone of every preset, then the terminal.
        ascii("pbag"); u32(4 * (presetBags + 1))
        var genCursor = 0
        var modCursor = 0
        presets.forEach { preset ->
            if (preset.hasGlobal) {
                u16(genCursor); u16(modCursor)
                genCursor += preset.global.orEmpty().size
                modCursor += preset.globalModulators.size
            }
            preset.zones.forEach { zone ->
                u16(genCursor); u16(modCursor)
                genCursor += zone.generatorCount()
                modCursor += zone.modulators.size
            }
        }
        u16(genCursor); u16(modCursor)

        ascii("pmod"); u32(10 * (modCursor + 1))
        presets.forEach { preset ->
            preset.globalModulators.forEach(::modulator)
            preset.zones.forEach { zone -> zone.modulators.forEach(::modulator) }
        }
        u16(0); u16(0); u16(0); u16(0); u16(0)

        ascii("pgen"); u32(4 * (genCursor + 1))
        presets.forEach { preset ->
            preset.global?.forEach { (operator, amount) -> u16(operator); u16(amount) }
            preset.zones.forEach { zone ->
                if (zone.keyLow != 0 || zone.keyHigh != 127) {
                    u16(Generator.KeyRange.id); u16((zone.keyHigh shl 8) or zone.keyLow)
                }
                zone.extras.forEach { (operator, amount) -> u16(operator); u16(amount) }
                u16(Generator.Instrument.id); u16(zone.instrumentId)
            }
        }
        u16(0); u16(0)

        // inst: the instruments, then the terminal.
        val instrumentBags = instruments.sumOf { it.bagCount }
        ascii("inst"); u32(22 * (instruments.size + 1))
        bagCursor = 0
        instruments.forEach { instrument ->
            name(instrument.name, 20); u16(bagCursor)
            bagCursor += instrument.bagCount
        }
        name("EOI", 20); u16(instrumentBags)

        ascii("ibag"); u32(4 * (instrumentBags + 1))
        genCursor = 0
        modCursor = 0
        instruments.forEach { instrument ->
            if (instrument.hasGlobal) {
                u16(genCursor); u16(modCursor)
                genCursor += instrument.global.size
                modCursor += instrument.globalModulators.size
            }
            instrument.zones.forEach { zone ->
                u16(genCursor); u16(modCursor)
                genCursor += zone.generatorCount()
                modCursor += zone.modulators.size
            }
        }
        u16(genCursor); u16(modCursor)

        ascii("imod"); u32(10 * (modCursor + 1))
        instruments.forEach { instrument ->
            instrument.globalModulators.forEach(::modulator)
            instrument.zones.forEach { zone -> zone.modulators.forEach(::modulator) }
        }
        u16(0); u16(0); u16(0); u16(0); u16(0)

        ascii("igen"); u32(4 * (genCursor + 1))
        instruments.forEach { instrument ->
            instrument.global.forEach { (operator, amount) -> u16(operator); u16(amount) }
            instrument.zones.forEach { zone ->
                if (zone.keyLow != 0 || zone.keyHigh != 127) {
                    u16(Generator.KeyRange.id); u16((zone.keyHigh shl 8) or zone.keyLow)
                }
                if (zone.velLow != 0 || zone.velHigh != 127) {
                    u16(Generator.VelRange.id); u16((zone.velHigh shl 8) or zone.velLow)
                }
                zone.extras.forEach { (operator, amount) -> u16(operator); u16(amount) }
                // SampleId must be last: the specification says so, and readers rely on it. A
                // negative id writes none at all, which is how a malformed zone is built for a test.
                if (zone.sampleId >= 0) {
                    u16(Generator.SampleId.id); u16(zone.sampleId)
                }
            }
        }
        u16(0); u16(0)

        // shdr: the samples, then the terminal.
        ascii("shdr"); u32(46 * (samples.size + 1))
        samples.forEach { spec ->
            name(spec.name, 20)
            u32(spec.start); u32(spec.end); u32(spec.loopStart); u32(spec.loopEnd)
            u32(spec.rate); u8(spec.rootKey); u8(spec.correction); u16(spec.link); u16(spec.type)
        }
        name("EOS", 20); u32(0); u32(0); u32(0); u32(0); u32(0); u8(0); u8(0); u16(0); u16(0)

        patchSize(pdtaSize, out.size - pdtaStart)
        patchSize(riffSize, out.size - 8)
        return out.toByteArray()
    }

    internal class SampleSpec(
        val name: String,
        val start: Int,
        val end: Int,
        val loopStart: Int,
        val loopEnd: Int,
        val rate: Int,
        val rootKey: Int,
        val correction: Int = 0,
        /** 1 is mono. 0x10 marks a compressed (SF3) sample. */
        val type: Int = 1,
        val link: Int = 0,
    )

    /** One modulator record: source, destination generator id, amount, amount source, transform. */
    internal class ModSpec(
        val source: Int,
        val destination: Int,
        val amount: Int,
        val amountSource: Int = 0,
        val transform: Int = 0,
    )

    internal class ZoneSpec(
        val sampleId: Int,
        val keyLow: Int = 0,
        val keyHigh: Int = 127,
        val velLow: Int = 0,
        val velHigh: Int = 127,
        val extras: List<Pair<Int, Int>> = emptyList(),
        val modulators: List<ModSpec> = emptyList(),
    ) {
        fun generatorCount(): Int {
            var count = if (sampleId >= 0) 1 else 0         // SampleId
            if (keyLow != 0 || keyHigh != 127) count++
            if (velLow != 0 || velHigh != 127) count++
            return count + extras.size
        }
    }

    internal class InstrumentSpec(
        val name: String,
        val zones: List<ZoneSpec>,
        val global: List<Pair<Int, Int>> = emptyList(),
        val globalModulators: List<ModSpec> = emptyList(),
    ) {
        val hasGlobal: Boolean get() = global.isNotEmpty() || globalModulators.isNotEmpty()
        val bagCount: Int get() = zones.size + if (hasGlobal) 1 else 0
    }

    internal class PresetZoneSpec(
        val instrumentId: Int,
        val extras: List<Pair<Int, Int>> = emptyList(),
        val keyLow: Int = 0,
        val keyHigh: Int = 127,
        val modulators: List<ModSpec> = emptyList(),
    ) {
        fun generatorCount(): Int = extras.size + 1 + if (keyLow != 0 || keyHigh != 127) 1 else 0
    }

    internal class PresetDef(
        val name: String,
        val bank: Int,
        val program: Int,
        val zones: List<PresetZoneSpec>,
        /** Null means no global zone; an empty list is a global zone with no generators. */
        val global: List<Pair<Int, Int>>? = null,
        val globalModulators: List<ModSpec> = emptyList(),
    ) {
        val hasGlobal: Boolean get() = global != null || globalModulators.isNotEmpty()
        val bagCount: Int get() = zones.size + if (hasGlobal) 1 else 0
    }

    /** The short form: a preset with its own instrument and its own 64-frame sample. */
    internal class PresetSpec(
        val name: String,
        val bank: Int,
        val program: Int,
        val sampleName: String = name.lowercase(),
        val instrumentExtras: List<Pair<Int, Int>> = emptyList(),
    )
}
