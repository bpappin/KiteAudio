package io.github.yuroyami.kiteaudio.synth

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** 24-bit fonts: the sm24 chunk, as sections 6 and 6.2 of the SoundFont 2.04 specification define it. */
class SoundFont24BitTest {

    /** A low byte for each position of a pool, different at every position near it. */
    private fun lowBytes(count: Int, seed: Int = 0): ByteArray = ByteArray(count) { (it * 37 + seed * 11 + 5).toByte() }

    @Test
    fun twoFontsWithTheSameSmplAndDifferentSm24AreDifferent() {
        val first = KiteSynth.readFont(Sf2Builder().build(sm24 = lowBytes(64, seed = 1))).samples.single()
        val second = KiteSynth.readFont(Sf2Builder().build(sm24 = lowBytes(64, seed = 2))).samples.single()
        assertContentEquals(first.data, second.data, "the 16-bit frames are the same")
        assertContentEquals(lowBytes(64, seed = 1), first.lowBytes)
        assertContentEquals(lowBytes(64, seed = 2), second.lowBytes)
        assertFalse(first.lowBytes!!.contentEquals(second.lowBytes!!), "the model tells the fonts apart")
        assertTrue((0 until 64).any { first.frame24(it) != second.frame24(it) })
    }

    @Test
    fun aMetadataEditKeepsEverySampleBit() {
        // Laid out the way the writer lays a pool: each sample, then 46 silent frames.
        val a = ShortArray(65) { (it * 500 - 16000).toShort() }
        val b = ShortArray(32) { (9000 - it * 700).toShort() }
        val pool = a + ShortArray(46) + b + ShortArray(46)
        val lowA = lowBytes(65, seed = 3)
        val lowB = lowBytes(32, seed = 4)
        // 189 words in smpl is an odd half, so sm24 gets one more byte (section 6.2).
        val sm24 = lowA + ByteArray(46) + lowB + ByteArray(46) + ByteArray(1)
        val input = Sf2Builder().build(
            sampleFrames = pool,
            samples = listOf(
                Sf2Builder.SampleSpec("a", 0, 65, 8, 60, 44100, 60),
                Sf2Builder.SampleSpec("b", 111, 143, 115, 140, 22050, 48),
            ),
            instrumentZones = listOf(Sf2Builder.ZoneSpec(sampleId = 0, keyHigh = 63), Sf2Builder.ZoneSpec(sampleId = 1, keyLow = 64)),
            sm24 = sm24,
        )
        val original = KiteSynth.readFont(input)
        val edited = SoundFont(
            name = "Renamed",
            presets = original.presets.map { Preset("Renamed preset", it.program, it.bank, it.zones) },
            instruments = original.instruments,
            samples = original.samples,
            info = original.info + ("ICMT" to "edited"),
        )
        val output = SoundFontWriter.write(edited)
        val again = KiteSynth.readFont(output)

        assertEquals("Renamed preset", again.presets.single().name)
        assertEquals(original.samples.size, again.samples.size)
        original.samples.zip(again.samples).forEach { (before, after) ->
            assertContentEquals(before.data, after.data, "16-bit frames of ${before.name}")
            assertContentEquals(before.lowBytes, after.lowBytes, "low bytes of ${before.name}")
        }
        assertContentEquals(lowA, again.samples[0].lowBytes)
        assertContentEquals(lowB, again.samples[1].lowBytes)
        assertContentEquals(subChunk(input, "smpl"), subChunk(output, "smpl"), "smpl bytes")
        assertContentEquals(subChunk(input, "sm24"), subChunk(output, "sm24"), "sm24 bytes")
        assertEquals(190, subChunk(output, "sm24")!!.size)
    }

    @Test
    fun anSm24ThatTheSpecificationIgnoresReadsAsSixteenBit() {
        val plain = KiteSynth.readFont(Sf2Builder().build()).samples.single()
        // The default pool is 64 frames, so the valid sm24 size is 64.
        val ignored = mapOf(
            "version 2.01" to Sf2Builder().build(sm24 = lowBytes(64), minorVersion = 1),
            "version 2.03" to Sf2Builder().build(sm24 = lowBytes(64), minorVersion = 3),
            "version 1.10" to Sf2Builder().build(sm24 = lowBytes(64), version = 1, minorVersion = 10),
            "one byte short" to Sf2Builder().build(sm24 = lowBytes(63)),
            "one byte long" to Sf2Builder().build(sm24 = lowBytes(65)),
            "two bytes long" to Sf2Builder().build(sm24 = lowBytes(66)),
        )
        for ((case, bytes) in ignored) {
            val sample = KiteSynth.readFont(bytes).samples.single()
            assertNull(sample.lowBytes, case)
            assertContentEquals(plain.data, sample.data, case)
        }

        // 65 frames is an odd half. A size of 66 counts the pad byte and a size of 65 leaves it out. Both count.
        val oddPool = ShortArray(65) { (it * 400).toShort() }
        for (size in listOf(65, 66)) {
            val sample = KiteSynth.readFont(Sf2Builder().build(sampleFrames = oddPool, sm24 = lowBytes(size))).samples.single()
            assertContentEquals(lowBytes(size).copyOf(sample.frameCount), sample.lowBytes, "an sm24 of $size bytes")
        }
        assertNull(KiteSynth.readFont(Sf2Builder().build(sampleFrames = oddPool, sm24 = lowBytes(64))).samples.single().lowBytes)
        assertNull(KiteSynth.readFont(Sf2Builder().build(sampleFrames = oddPool, sm24 = lowBytes(67))).samples.single().lowBytes)

        // Without smpl there are no frames, and sm24 does not count either.
        val noSmpl = KiteSynth.readFont(Sf2Builder().build(sm24 = ByteArray(0), writeSmpl = false)).samples.single()
        assertNull(noSmpl.lowBytes)
        assertEquals(0, noSmpl.frameCount)

        // The controls: the same chunk counts at 2.04 and at 3.00.
        assertContentEquals(lowBytes(64), KiteSynth.readFont(Sf2Builder().build(sm24 = lowBytes(64))).samples.single().lowBytes)
        assertContentEquals(
            lowBytes(64),
            KiteSynth.readFont(Sf2Builder().build(sm24 = lowBytes(64), version = 3, minorVersion = 0)).samples.single().lowBytes,
        )
    }

    /** One preset whose instrument plays each sample in its own key range. */
    private fun fontOf(vararg samples: SampleHeader): SoundFont {
        val zones = samples.indices.map { index ->
            InstrumentZone(GeneratorSet(mapOf(Generator.SampleId.id to index)), Span(index * 10, index * 10 + 9), Span.Full, index)
        }
        val presetZone = PresetZone(GeneratorSet(mapOf(Generator.Instrument.id to 0)), Span.Full, Span.Full, 0)
        return SoundFont("hand", listOf(Preset("Mixed", 0, 0, listOf(presetZone))), listOf(Instrument("Mixed", zones)), samples.toList())
    }

    @Test
    fun aFontWithTwentyFourAndSixteenBitSamplesWritesAndReadsBack() {
        val wide = SampleHeader("wide", ShortArray(40) { (it * 800 - 16000).toShort() }, 4, 36, 44100, 60, 0, 0, 1, lowBytes = lowBytes(40, seed = 7))
        val narrow = SampleHeader("narrow", ShortArray(31) { (it * -900 + 12000).toShort() }, 2, 28, 22050, 72, 0, 0, 1)
        val bytes = SoundFontWriter.write(fontOf(wide, narrow))
        val again = KiteSynth.readFont(bytes)

        val (wideAgain, narrowAgain) = again.samples
        assertContentEquals(wide.data, wideAgain.data)
        assertContentEquals(wide.lowBytes, wideAgain.lowBytes)
        assertContentEquals(narrow.data, narrowAgain.data)
        // The file has one sm24 pool, so the 16-bit sample reads back with zero low bytes.
        assertContentEquals(ByteArray(31), narrowAgain.lowBytes)
        for (i in 0 until 31) assertEquals(narrow.frame24(i), narrowAgain.frame24(i))
        assertEquals(listOf(4, 36, 2, 28), listOf(wideAgain.loopStart, wideAgain.loopEnd, narrowAgain.loopStart, narrowAgain.loopEnd))

        // Section 6.2 ignores sm24 below version 2.04, so the file must state 2.04.
        assertContentEquals(byteArrayOf(2, 0, 4, 0), subChunk(bytes, "ifil"))
        // (40 + 46) + (31 + 46) = 163 words in smpl, an odd half, so sm24 is 164 bytes.
        assertEquals(326, subChunk(bytes, "smpl")!!.size)
        assertEquals(164, subChunk(bytes, "sm24")!!.size)

        // A font with no low bytes stays a 16-bit 2.01 file with no sm24 chunk.
        val sixteen = SoundFontWriter.write(fontOf(narrow))
        assertNull(subChunk(sixteen, "sm24"))
        assertContentEquals(byteArrayOf(2, 0, 1, 0), subChunk(sixteen, "ifil"))
        assertNull(KiteSynth.readFont(sixteen).samples.single().lowBytes)
    }

    @Test
    fun anSf3FontKeepsItsStreamsAndItsTwentyFourBitSamples() {
        val stream = ByteArray(7) { (it + 1).toByte() }
        val packed = SampleHeader("packed", ShortArray(0), 0, 0, 44100, 60, 0, 0, 1 or SAMPLE_COMPRESSED, compressed = stream)
        val wide = SampleHeader("wide", ShortArray(20) { (it * 1000).toShort() }, 2, 18, 44100, 64, 0, 0, 1, lowBytes = lowBytes(20, seed = 9))
        val again = KiteSynth.readFont(SoundFontWriter.write(fontOf(packed, wide)), keepCompressed = true)
        assertContentEquals(stream, again.samples[0].compressed)
        assertNull(again.samples[0].lowBytes, "a compressed sample has no low bytes")
        assertContentEquals(wide.data, again.samples[1].data)
        assertContentEquals(wide.lowBytes, again.samples[1].lowBytes)
    }

    @Test
    fun decodingKeepsTheLowBytesOfSamplesThatWereNotCompressed() {
        val packed = SampleHeader("packed", ShortArray(0), 0, 0, 44100, 60, 0, 0, 1 or SAMPLE_COMPRESSED, compressed = ByteArray(4))
        val wide = SampleHeader("wide", ShortArray(20), 2, 18, 44100, 64, 0, 0, 1, lowBytes = lowBytes(20))
        val decoded = fontOf(packed, wide).decoded { ShortArray(10) }
        assertNull(decoded.samples[0].lowBytes)
        assertSame(wide.lowBytes, decoded.samples[1].lowBytes)
    }

    /**
     * Four 64-frame samples: a right and left pair, one sample no zone uses, and a right sample
     * whose partner does not exist. Zones play the first and the last.
     */
    private fun linkedFont(): SoundFont = KiteSynth.readFont(
        Sf2Builder().build(
            sampleFrames = ShortArray(256) { (it * 100 - 12000).toShort() },
            samples = listOf(
                Sf2Builder.SampleSpec("right", 0, 64, 8, 56, 44100, 60, type = 2, link = 1),
                Sf2Builder.SampleSpec("left", 64, 128, 72, 120, 44100, 60, type = 4, link = 0),
                Sf2Builder.SampleSpec("unused", 128, 192, 136, 184, 44100, 60),
                Sf2Builder.SampleSpec("orphan", 192, 256, 200, 248, 44100, 60, type = 2, link = 99),
            ),
            instrumentZones = listOf(Sf2Builder.ZoneSpec(sampleId = 0, keyHigh = 63), Sf2Builder.ZoneSpec(sampleId = 3, keyLow = 64)),
            sm24 = lowBytes(256, seed = 5),
        ),
    )

    private fun lowBytesAt(start: Int): ByteArray = lowBytes(256, seed = 5).copyOfRange(start, start + 64)

    @Test
    fun prunedKeepsTheLowBytes() {
        val pruned = linkedFont().pruned()
        assertEquals(listOf("right", "left", "orphan"), pruned.samples.map { it.name })
        assertContentEquals(lowBytesAt(0), pruned.samples[0].lowBytes, "a renumbered linked sample")
        assertContentEquals(lowBytesAt(64), pruned.samples[1].lowBytes, "its partner")
        assertContentEquals(lowBytesAt(192), pruned.samples[2].lowBytes, "a sample that became mono")
        assertEquals(1, pruned.samples[2].type, "the orphan plays as mono")
    }

    @Test
    fun mergedKeepsTheLowBytesOfBothFonts() {
        val rhodes = KiteSynth.readFont(
            Sf2Builder().build(presets = listOf(Sf2Builder.PresetSpec("Rhodes", 0, 5, "rhodes")), sm24 = lowBytes(64, seed = 6)),
        )
        val merged = rhodes.merged(linkedFont())
        val byName = merged.samples.associateBy { it.name }
        assertEquals(setOf("rhodes", "right", "left", "orphan"), byName.keys)
        assertContentEquals(lowBytes(64, seed = 6), byName.getValue("rhodes").lowBytes)
        assertContentEquals(lowBytesAt(0), byName.getValue("right").lowBytes)
        assertContentEquals(lowBytesAt(64), byName.getValue("left").lowBytes)
        assertContentEquals(lowBytesAt(192), byName.getValue("orphan").lowBytes)
    }

    @Test
    fun frame24JoinsTheHighAndLowPartsAsASignedValue() {
        val data = shortArrayOf(0x1234, -1, Short.MIN_VALUE, Short.MAX_VALUE, 0, -2)
        val low = byteArrayOf(0x56, 0xFF.toByte(), 0, 0xFF.toByte(), 0x80.toByte(), 0x01)
        val sample = SampleHeader("s", data, 0, 0, 44100, 60, 0, 0, 1, lowBytes = low)
        assertEquals(listOf(0x123456, -1, -8_388_608, 8_388_607, 128, -511), data.indices.map { sample.frame24(it) })

        val sixteen = SampleHeader("s", data, 0, 0, 44100, 60, 0, 0, 1)
        assertEquals(listOf(0x123400, -256, -8_388_608, 8_388_352, 0, -512), data.indices.map { sixteen.frame24(it) })
    }

    @Test
    fun lowBytesOfTheWrongSizeAreRefused() {
        val failure = assertFailsWith<IllegalArgumentException> {
            SampleHeader("s", ShortArray(4), 0, 0, 44100, 60, 0, 0, 1, lowBytes = ByteArray(3))
        }
        assertTrue(failure.message!!.contains("lowBytes"), failure.message)
        assertFailsWith<IllegalArgumentException> {
            SampleHeader("s", ShortArray(4), 0, 0, 44100, 60, 0, 0, 1, lowBytes = ByteArray(5))
        }
    }

    @Test
    fun aNotePlaysTheSameWithAndWithoutLowBytes() {
        val wide = KiteSynth.readFont(
            Sf2Builder().build(
                sampleFrames = ShortArray(4800) { (it % 100 * 300).toShort() },
                samples = listOf(Sf2Builder.SampleSpec("ramp", 0, 4800, 100, 4700, 48_000, 60)),
                instrumentZones = listOf(Sf2Builder.ZoneSpec(sampleId = 0, extras = listOf(Generator.SampleModes.id to 1))),
                sm24 = lowBytes(4800, seed = 8),
            ),
        )
        assertTrue(wide.samples.single().lowBytes!!.any { it != 0.toByte() }, "the font has low bytes to ignore")
        val narrow = SoundFont(
            wide.name, wide.presets, wide.instruments,
            wide.samples.map { SampleHeader(it.name, it.data, it.loopStart, it.loopEnd, it.sampleRate, it.originalKey, it.correctionCents, it.link, it.type) },
            wide.info,
        )
        fun play(font: SoundFont): FloatArray {
            val synth = Synth(font, 48_000, 8).apply { reverb.wet = 0.0 }
            synth.noteOn(0, 64, 100)
            return FloatArray(2048).also { synth.render(it, 1024) }
        }
        val expected = play(narrow)
        assertTrue(expected.any { it != 0f }, "the font is silent")
        assertContentEquals(expected, play(wide))
    }

    @Test
    fun theLoadBudgetCountsTheLowBytes() {
        // 64 frames keep 128 bytes in data and 64 in lowBytes.
        val bytes = Sf2Builder().build(sm24 = lowBytes(64))
        assertFailsWith<SoundFontFormatException> { KiteSynth.readFont(bytes, maxSampleBytes = 191) }
        assertEquals(64, KiteSynth.readFont(bytes, maxSampleBytes = 192).samples.single().lowBytes!!.size)
        assertNull(KiteSynth.readFont(Sf2Builder().build(), maxSampleBytes = 128).samples.single().lowBytes)
    }

    /** The body of the first sub-chunk [id] inside any LIST of the file, by its size field, or null. */
    private fun subChunk(bytes: ByteArray, id: String): ByteArray? {
        fun u32(at: Int) = (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8) or
            ((bytes[at + 2].toInt() and 0xFF) shl 16) or ((bytes[at + 3].toInt() and 0xFF) shl 24)
        fun tag(at: Int) = bytes.decodeToString(at, at + 4)
        var list = 12
        while (list + 8 <= bytes.size) {
            val listEnd = list + 8 + u32(list + 4)
            var at = list + 12
            while (tag(list) == "LIST" && at + 8 <= listEnd) {
                val size = u32(at + 4)
                if (tag(at) == id) return bytes.copyOfRange(at + 8, at + 8 + size)
                at += 8 + size + size % 2
            }
            list = listEnd + (listEnd - list) % 2
        }
        return null
    }
}
