package io.github.yuroyami.kiteaudio.synth.internal

import io.github.yuroyami.kiteaudio.synth.Generator
import io.github.yuroyami.kiteaudio.synth.GeneratorSet
import io.github.yuroyami.kiteaudio.synth.Instrument
import io.github.yuroyami.kiteaudio.synth.InstrumentZone
import io.github.yuroyami.kiteaudio.synth.LINK_FLAG
import io.github.yuroyami.kiteaudio.synth.Modulator
import io.github.yuroyami.kiteaudio.synth.NO_LINK_TARGET
import io.github.yuroyami.kiteaudio.synth.Preset
import io.github.yuroyami.kiteaudio.synth.PresetZone
import io.github.yuroyami.kiteaudio.synth.SAMPLE_COMPRESSED
import io.github.yuroyami.kiteaudio.synth.SampleHeader
import io.github.yuroyami.kiteaudio.synth.SoundFont
import io.github.yuroyami.kiteaudio.synth.SoundFontFormatException
import io.github.yuroyami.kiteaudio.synth.Span

/**
 * Reads a SoundFont 2 file, or an SF3 file when its compressed samples are kept.
 *
 * The layout, from the 2.04 specification:
 *
 * ```
 * RIFF sfbk
 *   LIST INFO      ifil (version), INAM (name), and other text
 *   LIST sdta      smpl (16-bit sample frames, or Ogg Vorbis streams in an SF3)
 *                  sm24 (optional: the low byte of each frame of a 24-bit font)
 *   LIST pdta      phdr pbag pmod pgen inst ibag imod igen shdr
 * ```
 *
 * Two things in the pdta lists catch people out, and both are handled here.
 *
 * The first is that every list ends with a **terminal record** (named EOP, EOI, EOS). It is not
 * a preset or an instrument or a sample; it exists to mark where the last real one's zones end.
 * Treating it as real gives a font one phantom preset with nonsense in it.
 *
 * The second is that a **zone with no sample or instrument at the front of the list is global**:
 * its generators apply to every zone after it. Reading it as an ordinary zone silently loses
 * settings that a font meant to apply everywhere.
 */
internal fun readSoundFont(bytes: ByteArray, keepCompressed: Boolean, maxSampleBytes: Long): SoundFont {
    require(maxSampleBytes >= 0) { "sample payload budget must not be negative" }
    val reader = RiffReader(bytes)

    val riff = reader.readChunkHeader()
    if (riff.id != "RIFF") {
        throw SoundFontFormatException("this is not a RIFF file: it starts with \"${riff.id}\"", 0)
    }
    val form = reader.fourCC()
    if (form != "sfbk") {
        throw SoundFontFormatException(
            "this is a RIFF file but not a SoundFont: expected form sfbk, found \"$form\"",
            8,
        )
    }
    // RIFF's own size field counts everything after it, so the real end is eight bytes later.
    val fileEnd = minOf(riff.end, bytes.size)

    var info = InfoList(emptyMap(), null)
    var sampleChunks = SampleChunks(null, null)
    var pdta: Chunk? = null

    while (reader.position + 8 <= fileEnd) {
        val list = reader.readChunkHeader()
        if (list.id != "LIST") {
            reader.skipChunkBody(list)
            continue
        }
        when (val kind = reader.fourCC()) {
            "INFO" -> info = readInfo(reader, list)
            "sdta" -> findSampleChunk(reader, list).let { if (it.smpl != null) sampleChunks = it }
            "pdta" -> pdta = Chunk(kind, reader.position, list.end - reader.position)
            else -> Unit
        }
        reader.position = list.end
        if (list.size % 2 == 1 && reader.position < bytes.size) reader.position++
    }

    val hunks = pdta ?: throw SoundFontFormatException("the font has no pdta list, so it has no sounds")
    val name = info.text["INAM"]?.takeIf { it.isNotBlank() } ?: "Untitled"
    val smpl = sampleChunks.smpl
    val sm24 = usableLowBytes(smpl, sampleChunks.sm24, info.version, bytes.size)
    return readPdta(reader, hunks, name, smpl, sm24, info.text, keepCompressed, maxSampleBytes)
}

/** The INFO text chunks, and the ifil version as major times 65536 plus minor, or null when the font does not state it. */
private class InfoList(val text: Map<String, String>, val version: Int?)

/** The version check, and every text chunk kept by its id so a writer can put it back. */
private fun readInfo(reader: RiffReader, list: Chunk): InfoList {
    val found = LinkedHashMap<String, String>()
    var version: Int? = null
    while (reader.position + 8 <= list.end) {
        val chunk = reader.readChunkHeader()
        if (chunk.id == "ifil" && chunk.size >= 2) {
            val cursor = RiffReader(reader.slice(chunk.start, minOf(chunk.size, 4)))
            val major = cursor.u16()
            if (major > 3) {
                throw SoundFontFormatException("this font says it is version $major, which is newer than 3")
            }
            // The minor version decides if sm24 counts, so a two-byte ifil states no usable version.
            version = if (chunk.size >= 4) (major shl 16) or cursor.u16() else null
        } else if (chunk.id != "iver" && chunk.size > 0) {
            // iver is the ROM's version, a pair of numbers. Everything else in INFO is text.
            found[chunk.id] = RiffReader(reader.slice(chunk.start, chunk.size)).name(chunk.size)
        }
        reader.skipChunkBody(chunk)
    }
    return InfoList(found, version)
}

/** The first smpl chunk of one sdta list, and the first sm24 chunk of the same list. */
private class SampleChunks(val smpl: Chunk?, val sm24: Chunk?)

/**
 * Where the 16-bit frames are, and the `sm24` chunk with the low byte of each frame of a 24-bit
 * font. Nothing is decoded here; each sample reads its own frames later. [usableLowBytes] decides
 * if the `sm24` chunk counts.
 */
private fun findSampleChunk(reader: RiffReader, list: Chunk): SampleChunks {
    var found: Chunk? = null
    var low: Chunk? = null
    while (reader.position + 8 <= list.end) {
        val chunk = reader.readChunkHeader()
        if (chunk.id == "smpl" && found == null) {
            // Checked before anything is sized from it: a corrupt font claiming two billion
            // bytes must fail here, not take the app down allocating for it.
            if (chunk.size > reader.remaining) {
                throw SoundFontFormatException("a chunk claims ${chunk.size} bytes at ${chunk.start}, past the end", chunk.start)
            }
            found = chunk
        } else if (chunk.id == "sm24" && low == null) {
            low = chunk
        }
        reader.skipChunkBody(chunk)
    }
    return SampleChunks(found, low)
}

/**
 * The [sm24] chunk when the SoundFont 2.04 rules let it count, or null when they say to ignore it.
 *
 * Section 6.2 of the specification ignores `sm24` when there is no `smpl` chunk, when the ifil
 * version is below 2.04, and when its size is not half the size of `smpl`. Half the size gets one
 * more byte when it is odd, for the even length of a RIFF chunk (section 6). A chunk that states
 * the odd half itself and leaves the pad byte out of its size counts too, because RIFF writers
 * often do not count the pad byte. An odd `smpl` size has no exact half, so its `sm24` never counts.
 */
private fun usableLowBytes(smpl: Chunk?, sm24: Chunk?, version: Int?, fileSize: Int): Chunk? {
    if (smpl == null || sm24 == null) return null
    if (version == null || version < ((2 shl 16) or 4)) return null
    if (smpl.size % 2 != 0) return null
    val half = smpl.size / 2
    if (sm24.size != half && sm24.size != half + half % 2) return null
    // A chunk that counts must be whole: a font is never returned half-read.
    if (sm24.size > fileSize - sm24.start) {
        throw SoundFontFormatException("a chunk claims ${sm24.size} bytes at ${sm24.start}, past the end", sm24.start)
    }
    return sm24
}

private class RawZone(val generatorIndex: Int, val modulatorIndex: Int)

private fun readPdta(
    reader: RiffReader,
    pdta: Chunk,
    name: String,
    smpl: Chunk?,
    sm24: Chunk?,
    info: Map<String, String>,
    keepCompressed: Boolean,
    maxSampleBytes: Long,
): SoundFont {
    val chunks = HashMap<String, Chunk>()
    reader.position = pdta.start
    while (reader.position + 8 <= pdta.end) {
        val chunk = reader.readChunkHeader()
        chunks[chunk.id] = chunk
        reader.skipChunkBody(chunk)
    }

    fun need(id: String): Chunk = chunks[id]
        ?: throw SoundFontFormatException("the font is missing its $id chunk")

    val presetHeaders = readPresetHeaders(reader, need("phdr"))
    val presetZones = readZones(reader, need("pbag"))
    val presetGenerators = readGenerators(reader, need("pgen"))
    val presetModulators = readModulators(reader, chunks["pmod"])
    val instrumentHeaders = readInstrumentHeaders(reader, need("inst"))
    val instrumentZones = readZones(reader, need("ibag"))
    val instrumentGenerators = readGenerators(reader, need("igen"))
    val instrumentModulators = readModulators(reader, chunks["imod"])
    validateHeaderIndexes(instrumentHeaders.map { it.second }, instrumentZones.size, "inst", need("inst").start)
    validateHeaderIndexes(presetHeaders.map { it.zoneIndex }, presetZones.size, "phdr", need("phdr").start)
    val samples = readSampleHeaders(reader, need("shdr"), smpl, sm24, keepCompressed, maxSampleBytes)

    val instruments = instrumentHeaders.dropLast(1).mapIndexed { index, header ->
        val from = header.second
        val to = instrumentHeaders[index + 1].second
        Instrument(
            name = header.first,
            zones = buildInstrumentZones(instrumentZones, instrumentGenerators, instrumentModulators, from, to),
        )
    }

    val presets = presetHeaders.dropLast(1).mapIndexed { index, header ->
        val from = header.zoneIndex
        val to = presetHeaders[index + 1].zoneIndex
        Preset(
            name = header.name,
            program = header.program,
            bank = header.bank,
            zones = buildPresetZones(presetZones, presetGenerators, presetModulators, from, to),
        )
    }

    return SoundFont(name, presets, instruments, samples, info)
}

/** Header indexes must describe ordered ranges ending before the terminal bag record. */
private fun validateHeaderIndexes(indexes: List<Int>, bags: Int, table: String, offset: Int) {
    var previous = 0
    for (index in indexes) {
        if (index < previous || index >= bags) {
            throw SoundFontFormatException("$table bag indexes must be ordered and within the bag table", offset)
        }
        previous = index
    }
}

private class PresetHeader(
    val name: String,
    val program: Int,
    val bank: Int,
    val zoneIndex: Int,
)

private fun readPresetHeaders(reader: RiffReader, chunk: Chunk): List<PresetHeader> {
    val recordSize = 38
    if (chunk.size < recordSize * 2) {
        // One real preset plus the terminal record is the smallest a font can be.
        throw SoundFontFormatException("phdr is ${chunk.size} bytes, too small to hold a preset", chunk.start)
    }
    val cursor = RiffReader(reader.slice(chunk.start, chunk.size))
    return (0 until chunk.size / recordSize).map {
        val name = cursor.name(20)
        val program = cursor.u16()
        val bank = cursor.u16()
        val zoneIndex = cursor.u16()
        cursor.skip(12)                       // library, genre and morphology, unused by anyone
        PresetHeader(name, program, bank, zoneIndex)
    }
}

private fun readInstrumentHeaders(reader: RiffReader, chunk: Chunk): List<Pair<String, Int>> {
    val recordSize = 22
    if (chunk.size < recordSize * 2) {
        throw SoundFontFormatException("inst is ${chunk.size} bytes, too small to hold an instrument", chunk.start)
    }
    val cursor = RiffReader(reader.slice(chunk.start, chunk.size))
    return (0 until chunk.size / recordSize).map { cursor.name(20) to cursor.u16() }
}

private fun readZones(reader: RiffReader, chunk: Chunk): List<RawZone> {
    val cursor = RiffReader(reader.slice(chunk.start, chunk.size))
    return (0 until chunk.size / 4).map { RawZone(cursor.u16(), cursor.u16()) }
}

private fun readGenerators(reader: RiffReader, chunk: Chunk): List<Pair<Int, Int>> {
    val cursor = RiffReader(reader.slice(chunk.start, chunk.size))
    return (0 until chunk.size / 4).map { cursor.u16() to cursor.u16() }
}

/** Every modulator record, the terminal included, as zones index them. A missing chunk is none. */
private fun readModulators(reader: RiffReader, chunk: Chunk?): List<Modulator> {
    if (chunk == null) return emptyList()
    val cursor = RiffReader(reader.slice(chunk.start, chunk.size))
    return (0 until chunk.size / 10).map {
        val source = cursor.u16()
        val destination = cursor.u16()
        val amount = cursor.s16()
        val amountSource = cursor.u16()
        val transform = cursor.u16()
        Modulator(source, destination, amount, amountSource, transform)
    }
}

private fun readSampleHeaders(
    reader: RiffReader,
    chunk: Chunk,
    smpl: Chunk?,
    sm24: Chunk?,
    keepCompressed: Boolean,
    maxSampleBytes: Long,
): List<SampleHeader> {
    val recordSize = 46
    val cursor = RiffReader(reader.slice(chunk.start, chunk.size))
    val count = chunk.size / recordSize
    // A header a few frames too long is common in the wild. It is cut to the frames that exist.
    val available = ((smpl?.size ?: 0) / 2).toLong()
    val base = smpl?.start ?: 0
    // Preflight the cumulative retained payload, not just the input pool. Many headers can
    // name the same range, and each public SampleHeader owns a separate mutable array.
    var retainedBytes = 0L
    repeat((count - 1).coerceAtLeast(0)) {
        cursor.skip(20)
        val rawStart = cursor.u32()
        val rawEnd = cursor.u32()
        cursor.skip(8)
        val rate = cursor.u32()
        if (rate == 0L || rate > Int.MAX_VALUE.toLong()) {
            throw SoundFontFormatException("sample rate $rate must be positive and fit a signed 32-bit rate", chunk.start + cursor.position - 4)
        }
        cursor.skip(4)
        val compressed = (cursor.u16() and SAMPLE_COMPRESSED) != 0
        if (compressed && !keepCompressed) {
            throw SoundFontFormatException("this is an SF3 font; read it with keepCompressed, or convert it to SF2 first", chunk.start)
        }
        val limit = if (compressed) (smpl?.size ?: 0).toLong() else available
        val start = rawStart.coerceAtMost(limit)
        val end = rawEnd.coerceIn(start, limit)
        // A 24-bit frame keeps one more byte, from sm24.
        val bytes = (end - start) * if (compressed) 1L else if (sm24 != null) 3L else 2L
        if (bytes > maxSampleBytes - retainedBytes) {
            throw SoundFontFormatException("sample payload exceeds the $maxSampleBytes byte load budget", chunk.start)
        }
        retainedBytes += bytes
    }
    cursor.position = 0
    // The last record is the terminal EOS marker, not a sample.
    return (0 until (count - 1).coerceAtLeast(0)).map {
        val name = cursor.name(20)
        val rawStart = cursor.u32()
        val rawEnd = cursor.u32()
        val loopStart = cursor.u32()
        val loopEnd = cursor.u32()
        val sampleRate = cursor.u32().toInt()
        val originalKey = cursor.u8()
        val correction = cursor.s8()
        val link = cursor.u16()
        val type = cursor.u16()
        if ((type and SAMPLE_COMPRESSED) != 0) {
            // Compressed frames would read as noise. Saying what the font is beats playing it badly.
            if (!keepCompressed) {
                throw SoundFontFormatException("sample \"$name\" is compressed, so this is an SF3 font; read it with keepCompressed, or convert it to SF2 first")
            }
            // An SF3 sample is an Ogg Vorbis stream: its start and end count bytes, its loop points decoded frames.
            val bytesAvailable = (smpl?.size ?: 0).toLong()
            val from = rawStart.coerceAtMost(bytesAvailable)
            val until = rawEnd.coerceIn(from, bytesAvailable)
            return@map SampleHeader(
                name = name,
                data = ShortArray(0),
                loopStart = loopStart.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                loopEnd = loopEnd.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                sampleRate = sampleRate,
                originalKey = originalKey,
                correctionCents = correction,
                link = link,
                type = type,
                compressed = reader.slice(base + from.toInt(), (until - from).toInt()),
            )
        }
        val start = rawStart.coerceAtMost(available)
        val end = rawEnd.coerceIn(start, available)
        val frames = end - start
        SampleHeader(
            name = name,
            data = reader.shorts(base + start.toInt() * 2, frames.toInt()),
            loopStart = (loopStart - start).coerceIn(0, frames).toInt(),
            loopEnd = (loopEnd - start).coerceIn(0, frames).toInt(),
            sampleRate = sampleRate,
            originalKey = originalKey,
            correctionCents = correction,
            link = link,
            type = type,
            // Byte n of sm24 is the low byte of frame n of smpl.
            lowBytes = sm24?.let { reader.slice(it.start + start.toInt(), frames.toInt()) },
        )
    }
}

/**
 * Turns a run of raw zones into instrument zones.
 *
 * The global-zone rule lives here: a first zone with no SampleId sets defaults for the rest.
 */
private fun buildInstrumentZones(
    zones: List<RawZone>,
    generators: List<Pair<Int, Int>>,
    modulators: List<Modulator>,
    from: Int,
    to: Int,
): List<InstrumentZone> {
    val built = ArrayList<InstrumentZone>(to - from)
    var global = GeneratorSet()
    var globalModulators = emptyList<Modulator>()

    for (index in from until minOf(to, zones.size)) {
        val set = generatorsOf(zones, generators, index)
        val sampleId = set[Generator.SampleId]

        if (sampleId == null) {
            // No sample: this is a global zone, and its settings apply to the zones after it.
            // Only the FIRST zone of the run can be one. A later zone missing its terminal
            // generator is a malformed zone to skip, not a second global zone to promote over
            // the real one, which throws away the settings the global zone existed to supply.
            if (index == from) {
                global = set
                globalModulators = layered(emptyList(), modulatorsOf(zones, modulators, index))
            }
            continue
        }
        val merged = set.over(global)
        built.add(
            InstrumentZone(
                generators = merged,
                keys = merged.raw(Generator.KeyRange)?.let { Span.ofPacked(it) } ?: Span.Full,
                velocities = merged.raw(Generator.VelRange)?.let { Span.ofPacked(it) } ?: Span.Full,
                sampleId = sampleId,
                modulators = layered(globalModulators, modulatorsOf(zones, modulators, index)),
            ),
        )
    }
    return built
}

/** The same shape for presets, where the pointer is an Instrument rather than a SampleId. */
private fun buildPresetZones(
    zones: List<RawZone>,
    generators: List<Pair<Int, Int>>,
    modulators: List<Modulator>,
    from: Int,
    to: Int,
): List<PresetZone> {
    val built = ArrayList<PresetZone>(to - from)
    var global = GeneratorSet()
    var globalModulators = emptyList<Modulator>()

    for (index in from until minOf(to, zones.size)) {
        val set = generatorsOf(zones, generators, index)
        val instrumentId = set[Generator.Instrument]

        if (instrumentId == null) {
            // As above: only the first zone of the run is the global one.
            if (index == from) {
                global = set
                globalModulators = layered(emptyList(), modulatorsOf(zones, modulators, index))
            }
            continue
        }
        val merged = set.over(global)
        built.add(
            PresetZone(
                generators = merged,
                keys = merged.raw(Generator.KeyRange)?.let { Span.ofPacked(it) } ?: Span.Full,
                velocities = merged.raw(Generator.VelRange)?.let { Span.ofPacked(it) } ?: Span.Full,
                instrumentId = instrumentId,
                modulators = layered(globalModulators, modulatorsOf(zones, modulators, index)),
            ),
        )
    }
    return built
}

/** The generators belonging to one zone: from its own index up to the next zone's. */
private fun generatorsOf(
    zones: List<RawZone>,
    generators: List<Pair<Int, Int>>,
    index: Int,
): GeneratorSet {
    val start = zones[index].generatorIndex
    val end = if (index + 1 < zones.size) zones[index + 1].generatorIndex else generators.size
    if (start >= end || start >= generators.size) return GeneratorSet()

    val values = LinkedHashMap<Int, Int>()
    for (i in start until minOf(end, generators.size)) {
        val (operator, amount) = generators[i]
        // A later generator of the same kind in one zone wins, which is what a map gives.
        values[operator] = amount
    }
    return GeneratorSet(values)
}

/** One zone's modulator records, as the file lists them. */
private fun modulatorsOf(zones: List<RawZone>, modulators: List<Modulator>, index: Int): List<Modulator> {
    val start = zones[index].modulatorIndex
    val end = if (index + 1 < zones.size) zones[index + 1].modulatorIndex else modulators.size
    if (start >= end || start >= modulators.size) return emptyList()
    return modulators.subList(start, minOf(end, modulators.size))
}

/**
 * [raw] laid over [base]: a modulator replaces the one it matches, the rest follow in order.
 * A link (destination bit 15) counts places in [raw]; it is renumbered to count places in the
 * result, so the list a zone keeps is complete on its own. A link past the end of [raw] becomes
 * [NO_LINK_TARGET], so it stays past the end of the result.
 */
private fun layered(base: List<Modulator>, raw: List<Modulator>): List<Modulator> {
    if (raw.isEmpty()) return base
    val merged = ArrayList(base)
    val placedAt = IntArray(raw.size)
    raw.forEachIndexed { index, mod ->
        val at = merged.indexOfFirst { it.sameAs(mod) }
        placedAt[index] = if (at >= 0) at.also { merged[it] = mod } else merged.size.also { merged.add(mod) }
    }
    raw.forEachIndexed { index, mod ->
        // Only a modulator still in its slot: a later identical one may have taken the place.
        if ((mod.destinationId and LINK_FLAG) == 0 || merged[placedAt[index]] !== mod) return@forEachIndexed
        val link = mod.destinationId and NO_LINK_TARGET
        val target = if (link < raw.size) placedAt[link].coerceAtMost(NO_LINK_TARGET) else NO_LINK_TARGET
        if ((LINK_FLAG or target) != mod.destinationId) {
            merged[placedAt[index]] = Modulator(mod.source, LINK_FLAG or target, mod.amount, mod.amountSource, mod.transform)
        }
    }
    return merged
}
