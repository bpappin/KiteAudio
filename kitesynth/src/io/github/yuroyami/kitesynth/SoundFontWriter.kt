package io.github.yuroyami.kitesynth

/**
 * Writes a [SoundFont] as SoundFont 2 bytes that [KiteSynth.readFont] reads back unchanged, or as an
 * SF3 file when some of its samples are still compressed.
 *
 * Generators every zone of a preset or instrument shares, and the modulators every zone starts
 * with, are written once in a global zone, the way fonts are written by hand. Zones that point at
 * nothing are left out, as the synthesiser ignores them.
 */
public object SoundFontWriter {

    /** @throws IllegalArgumentException when the font has more records than the format can index. */
    public fun write(font: SoundFont): ByteArray {
        require(font.presets.isNotEmpty()) { "the font has no preset, and a SoundFont file needs at least one" }
        require(font.instruments.isNotEmpty()) { "the font has no instrument, and a SoundFont file needs at least one" }
        val presetZones = font.presets.map { preset ->
            layout(preset.zones.mapNotNull { zone ->
                zone.instrumentId?.let { ZoneOut(zone.generators, zone.keys, zone.velocities, zone.modulators, Generator.Instrument.id, it) }
            })
        }
        val instrumentZones = font.instruments.map { instrument ->
            layout(instrument.zones.mapNotNull { zone ->
                zone.sampleId?.let { ZoneOut(zone.generators, zone.keys, zone.velocities, zone.modulators, Generator.SampleId.id, it) }
            })
        }
        indexable("instruments", font.instruments.size)
        indexable("samples", font.samples.size)
        for (lists in listOf(presetZones, instrumentZones)) {
            indexable("zones in one list", lists.sumOf { it.size })
            indexable("generators in one list", lists.sumOf { records -> records.sumOf { it.generators.size } })
            indexable("modulators in one list", lists.sumOf { records -> records.sumOf { it.modulators.size } })
        }

        val sampleBytes = font.samples.sumOf { sample -> sample.compressed?.let { it.size + 1L } ?: ((sample.frameCount + SAMPLE_PAD) * 2L) }
        // A font that still holds compressed samples is an SF3 file, which says so with version 3.
        val sf3 = font.samples.any { it.compressed != null }
        val recordBytes = (presetZones + instrumentZones).sumOf { records ->
            records.sumOf { 4 + 4 * it.generators.size + 10 * it.modulators.size }
        }
        require(sampleBytes + recordBytes < Int.MAX_VALUE - 1_000_000) { "the font is too large for one SoundFont file" }
        val headerBytes = 64 * (font.presets.size + font.instruments.size + font.samples.size) +
            font.info.values.sumOf { it.length + 10 } + 4096
        val sink = ByteSink((sampleBytes + recordBytes).toInt() + headerBytes)

        sink.chunk("RIFF") {
            fourCC("sfbk")
            list("INFO") {
                chunk("ifil") { if (sf3) { u16(3); u16(0) } else { u16(2); u16(1) } }
                text("isng", font.info["isng"] ?: "EMU8000")
                text("INAM", font.name)
                for ((id, value) in font.info) {
                    if (id.length == 4 && id != "isng" && id != "INAM" && id != "ifil") text(id, value)
                }
            }
            val starts = IntArray(font.samples.size)
            list("sdta") {
                chunk("smpl") {
                    var byteAt = 0
                    font.samples.forEachIndexed { index, sample ->
                        val stream = sample.compressed
                        if (stream != null) {
                            // An SF3 sample's start and end count bytes, and a stream needs no silent frames after it.
                            starts[index] = byteAt
                            bytes(stream)
                            byteAt += stream.size
                            if (stream.size % 2 == 1) {
                                zeros(1)
                                byteAt += 1
                            }
                        } else {
                            starts[index] = byteAt / 2
                            shorts(sample.data)
                            // The format asks for 46 silent frames after every sample, for interpolation.
                            zeros(SAMPLE_PAD * 2)
                            byteAt += (sample.frameCount + SAMPLE_PAD) * 2
                        }
                    }
                }
            }
            list("pdta") {
                chunk("phdr") {
                    var bag = 0
                    font.presets.forEachIndexed { index, preset ->
                        name(preset.name); u16(preset.program); u16(preset.bank); u16(bag); u32(0); u32(0); u32(0)
                        bag += presetZones[index].size
                    }
                    name("EOP"); u16(0); u16(0); u16(bag); u32(0); u32(0); u32(0)
                }
                bags("pbag", presetZones)
                modulators("pmod", presetZones)
                generators("pgen", presetZones)
                chunk("inst") {
                    var bag = 0
                    font.instruments.forEachIndexed { index, instrument ->
                        name(instrument.name); u16(bag)
                        bag += instrumentZones[index].size
                    }
                    name("EOI"); u16(bag)
                }
                bags("ibag", instrumentZones)
                modulators("imod", instrumentZones)
                generators("igen", instrumentZones)
                chunk("shdr") {
                    font.samples.forEachIndexed { index, sample ->
                        val start = starts[index]
                        name(sample.name)
                        val stream = sample.compressed
                        if (stream != null) {
                            // Loop points of a compressed sample count its decoded frames, not the file's bytes.
                            u32(start); u32(start + stream.size)
                            u32(sample.loopStart); u32(sample.loopEnd)
                        } else {
                            u32(start); u32(start + sample.frameCount)
                            u32(start + sample.loopStart); u32(start + sample.loopEnd)
                        }
                        u32(sample.sampleRate); u8(sample.originalKey); u8(sample.correctionCents)
                        u16(sample.link); u16(sample.type)
                    }
                    name("EOS"); zeros(26)
                }
            }
        }
        return sink.toByteArray()
    }

    private const val SAMPLE_PAD = 46

    private fun indexable(what: String, count: Int) {
        require(count <= 0xFFFF) { "the font has $count $what, and a SoundFont file can index 65535" }
    }
}

/** A zone on its way out: everything the reader merged, plus the record it points at. */
private class ZoneOut(
    val generators: GeneratorSet,
    val keys: Span,
    val velocities: Span,
    val modulators: List<Modulator>,
    val pointerId: Int,
    val pointer: Int,
)

/** One zone as the file stores it: generators in file order, and its modulators. */
private class ZoneRecord(val generators: List<Pair<Int, Int>>, val modulators: List<Modulator>)

private val RANGES = setOf(Generator.KeyRange.id, Generator.VelRange.id)
private val POINTERS = setOf(Generator.Instrument.id, Generator.SampleId.id)

/** A global zone for what every zone shares, then each zone: ranges first, the pointer last. */
private fun layout(zones: List<ZoneOut>): List<ZoneRecord> {
    val common: Map<Int, Int> = if (zones.size < 2) emptyMap() else {
        zones[0].generators.all().filter { (id, value) ->
            id !in RANGES && id !in POINTERS && zones.all { it.generators.all()[id] == value }
        }
    }
    val shared = sharedModulators(zones)
    val records = ArrayList<ZoneRecord>(zones.size + 1)
    if (common.isNotEmpty() || shared.isNotEmpty()) {
        records += ZoneRecord(common.entries.sortedBy { it.key }.map { it.key to it.value }, shared)
    }
    for (zone in zones) {
        val all = zone.generators.all()
        val generators = ArrayList<Pair<Int, Int>>(all.size + 3)
        // The zone's own ranges are what plays, so they are what gets written.
        if (Generator.KeyRange.id in all || zone.keys != Span.Full) generators += Generator.KeyRange.id to packed(zone.keys)
        if (Generator.VelRange.id in all || zone.velocities != Span.Full) generators += Generator.VelRange.id to packed(zone.velocities)
        all.entries.filter { it.key !in RANGES && it.key !in POINTERS && it.key !in common }
            .sortedBy { it.key }
            .forEach { generators += it.key to it.value }
        generators += zone.pointerId to zone.pointer
        records += ZoneRecord(generators, zone.modulators.subList(shared.size, zone.modulators.size))
    }
    return records
}

/**
 * The modulators every zone starts with, in the same order. The reader lays a global zone's
 * modulators in front of each zone's own, so they go back there. A link counts places within one
 * list, so an instrument with a linked modulator keeps each zone's whole list instead.
 */
private fun sharedModulators(zones: List<ZoneOut>): List<Modulator> {
    if (zones.size < 2) return emptyList()
    if (zones.any { zone -> zone.modulators.any { (it.destinationId and 0x8000) != 0 } }) return emptyList()
    val first = zones[0].modulators
    var length = 0
    while (length < first.size && zones.all { zone ->
            length < zone.modulators.size && zone.modulators[length].let { it.sameAs(first[length]) && it.amount == first[length].amount }
        }
    ) length++
    return first.subList(0, length)
}

private fun packed(span: Span): Int = (span.low and 0xFF) or ((span.high and 0xFF) shl 8)

private fun ByteSink.bags(id: String, lists: List<List<ZoneRecord>>) = chunk(id) {
    var generator = 0
    var modulator = 0
    for (records in lists) for (record in records) {
        u16(generator); u16(modulator)
        generator += record.generators.size
        modulator += record.modulators.size
    }
    u16(generator); u16(modulator)
}

private fun ByteSink.modulators(id: String, lists: List<List<ZoneRecord>>) = chunk(id) {
    for (records in lists) for (record in records) for (mod in record.modulators) {
        u16(mod.source); u16(mod.destinationId); u16(mod.amount); u16(mod.amountSource); u16(mod.transform)
    }
    zeros(10)
}

private fun ByteSink.generators(id: String, lists: List<List<ZoneRecord>>) = chunk(id) {
    for (records in lists) for (record in records) for ((operator, amount) in record.generators) {
        u16(operator); u16(amount)
    }
    zeros(4)
}

/** A zero-terminated INFO string, padded to the even length the format asks for. */
private fun ByteSink.text(id: String, value: String) = chunk(id) {
    latin1(value)
    zeros(if (value.length % 2 == 0) 2 else 1)
}

private inline fun ByteSink.chunk(id: String, body: ByteSink.() -> Unit) {
    fourCC(id)
    val at = size
    u32(0)
    val start = size
    body()
    val length = size - start
    patch32(at, length)
    // RIFF pads an odd chunk to an even boundary, and the pad is not counted in its size.
    if (length % 2 == 1) u8(0)
}

private inline fun ByteSink.list(kind: String, body: ByteSink.() -> Unit) = chunk("LIST") {
    fourCC(kind)
    body()
}

/** A growing byte array. A list of boxed bytes costs sixteen times the file in heap. */
private class ByteSink(capacity: Int) {
    private var bytes = ByteArray(capacity.coerceAtLeast(64))
    var size: Int = 0
        private set

    private fun room(extra: Int) {
        val needed = size.toLong() + extra
        if (needed <= bytes.size) return
        require(needed <= MAX_ARRAY) { "the font is too large to write as one array" }
        // Doubling in Long: past a gigabyte, an Int doubling turns negative and every write copies.
        bytes = bytes.copyOf(maxOf(needed, minOf(bytes.size * 2L, MAX_ARRAY)).toInt())
    }

    private companion object {
        const val MAX_ARRAY: Long = Int.MAX_VALUE - 8L
    }

    fun u8(value: Int) {
        room(1)
        bytes[size++] = value.toByte()
    }

    fun u16(value: Int) {
        room(2)
        bytes[size++] = value.toByte()
        bytes[size++] = (value shr 8).toByte()
    }

    fun u32(value: Int) {
        u16(value)
        u16(value shr 16)
    }

    /** New space in a Kotlin array is already zero, so skipping ahead writes zeros. */
    fun zeros(count: Int) {
        room(count)
        size += count
    }

    fun fourCC(id: String) = id.forEach { u8(it.code) }

    fun latin1(text: String) = text.forEach { u8(if (it.code <= 0xFF) it.code else '?'.code) }

    /** Twenty bytes. A name of exactly twenty characters has no terminator, which readers expect. */
    fun name(text: String) {
        val cut = text.take(20)
        latin1(cut)
        zeros(20 - cut.length)
    }

    fun bytes(data: ByteArray) {
        for (value in data) u8(value.toInt())
    }

    fun shorts(data: ShortArray) {
        room(data.size * 2)
        for (value in data) {
            bytes[size++] = value.toByte()
            bytes[size++] = (value.toInt() shr 8).toByte()
        }
    }

    fun patch32(at: Int, value: Int) {
        bytes[at] = value.toByte()
        bytes[at + 1] = (value shr 8).toByte()
        bytes[at + 2] = (value shr 16).toByte()
        bytes[at + 3] = (value shr 24).toByte()
    }

    fun toByteArray(): ByteArray = if (size == bytes.size) bytes else bytes.copyOf(size)
}
