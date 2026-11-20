package io.github.yuroyami.kitesynth

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Public-contract regressions use padded samples, matching loop guards and mandatory INFO. */
class AuditRegressionTest {
    private fun bytes(
        extras: List<Pair<Int, Int>> = emptyList(),
        flat: Boolean = false,
        root: Int = 60,
        sampleCount: Int = 1,
        zones: Int = 1,
        presetZones: Int = 1,
    ): ByteArray = Sf2Builder().buildFont(
        sampleFrames = ShortArray(4846) { index ->
            if (index >= 4800) 0 else if (flat) 20000 else (20000 * sin(2 * PI * (index % 100) / 100)).toInt().toShort()
        },
        samples = List(sampleCount) { Sf2Builder.SampleSpec("sample", 0, 4800, 100, 4700, 48000, root) },
        instruments = listOf(Sf2Builder.InstrumentSpec("instrument", List(zones) {
            Sf2Builder.ZoneSpec(0, extras = listOf(Generator.SampleModes.id to 1) + extras)
        })),
        presets = listOf(Sf2Builder.PresetDef("preset", 0, 0, List(presetZones) { Sf2Builder.PresetZoneSpec(0) })),
        info = mapOf("isng" to "EMU8000"),
    )

    private fun font(extras: List<Pair<Int, Int>> = emptyList(), flat: Boolean = false, root: Int = 60): SoundFont =
        KiteSynth.readFont(bytes(extras, flat, root))

    private fun dry(font: SoundFont, voices: Int = 4): Synth = Synth(font, maxVoices = voices).apply {
        reverb.wet = 0.0
        chorus.level = 0.0
    }

    private fun pcm(font: SoundFont, key: Int = 60, velocity: Int = 127): FloatArray =
        FloatArray(4096).also { out -> dry(font).apply { noteOn(0, key, velocity); render(out, 2048) } }

    @Test
    fun loadBudgetCountsRepeatedPcmRangesAndKeepsIndependentOwnership() {
        val encoded = bytes(sampleCount = 3)
        assertFailsWith<SoundFontFormatException> { KiteSynth.readFont(encoded, maxSampleBytes = 28799) }
        val loaded = KiteSynth.readFont(encoded, maxSampleBytes = 28800)
        assertEquals(3, loaded.samples.size)
        loaded.samples[0].data[10] = 123
        assertTrue(loaded.samples[1].data[10] != 123.toShort(), "overlapping headers must keep separate mutable arrays")
        assertFailsWith<IllegalArgumentException> { KiteSynth.readFont(encoded, maxSampleBytes = -1) }
    }

    @Test
    fun loadBudgetCountsRetainedCompressedRangesInBytes() {
        // Compressed bytes are deliberately opaque: readFont retains them without a codec.
        val encoded = Sf2Builder().build(sampleFrames = ShortArray(8), samples = List(3) {
            Sf2Builder.SampleSpec("compressed", 2, 14, 0, 48, 48000, 60, type = 0x11)
        })
        assertFailsWith<SoundFontFormatException> { KiteSynth.readFont(encoded, keepCompressed = true, maxSampleBytes = 35) }
        assertEquals(36, KiteSynth.readFont(encoded, keepCompressed = true, maxSampleBytes = 36).samples.sumOf { it.compressed!!.size })
    }

    @Test
    fun regionBudgetIsCumulativeAndFailedReplacementLeavesTheOldFontPlaying() {
        val layered = KiteSynth.readFont(bytes(zones = 3, presetZones = 3))
        assertFailsWith<SoundFontFormatException> { Synth(layered, maxPreparedRegions = 8) }
        assertEquals(9, Synth(layered, maxPreparedRegions = 9).maxPreparedRegions)
        val original = font(flat = true)
        val synth = Synth(original, maxPreparedRegions = 8)
        synth.noteOn(0, 60, 127)
        synth.render(FloatArray(128), 64)
        assertFailsWith<SoundFontFormatException> { synth.font = layered }
        assertSame(original, synth.font)
        assertEquals(1, synth.activeVoices)
        assertFailsWith<SoundFontFormatException> { synth.replaceFont(layered) }
        assertSame(original, synth.currentFont())
        assertEquals(1, synth.activeVoices)
        val replacement = font()
        synth.replaceFont(replacement)
        assertSame(replacement, synth.font)
        assertSame(replacement, synth.currentFont())
        assertEquals(0, synth.activeVoices)
    }

    @Test
    fun candidatePairBudgetCountsDisjointZonesAcrossPresetsAndKeepsReplacementAtomic() {
        val original = font(flat = true)
        val instrument = Instrument("disjoint", List(3) {
            InstrumentZone(GeneratorSet(), Span(127, 127), Span.Full, 0)
        })
        fun disjointFont(vararg presetZoneCounts: Int): SoundFont = SoundFont(
            "disjoint", presetZoneCounts.mapIndexed { program, count ->
                Preset("preset $program", program, 0, List(count) {
                    PresetZone(GeneratorSet(), Span(0, 0), Span.Full, 0)
                })
            }, listOf(instrument), original.samples, original.info,
        )
        val disjoint = disjointFont(3)
        assertFailsWith<SoundFontFormatException> {
            Synth(disjoint, maxPreparedRegions = 0, maxPreparedPairs = 8)
        }
        val accepted = Synth(disjoint, maxPreparedRegions = 0, maxPreparedPairs = 9)
        assertEquals(9L, accepted.maxPreparedPairs)
        accepted.noteOn(0, 60, 127)
        accepted.render(FloatArray(128), 64)
        assertEquals(0, accepted.activeVoices, "nine candidate pairs produce no compatible region")
        assertFailsWith<SoundFontFormatException> {
            Synth(disjointFont(2, 1), maxPreparedRegions = 0, maxPreparedPairs = 8)
        }
        assertFailsWith<IllegalArgumentException> {
            Synth(original, maxPreparedRegions = 8, maxPreparedPairs = -1)
        }
        assertEquals(0L, Synth(disjointFont(), maxPreparedRegions = 0, maxPreparedPairs = 0).maxPreparedPairs)

        val synth = Synth(original, maxPreparedRegions = 8, maxPreparedPairs = 8)
        synth.noteOn(0, 60, 127)
        synth.render(FloatArray(128), 64)
        assertFailsWith<SoundFontFormatException> { synth.replaceFont(disjoint) }
        assertSame(original, synth.currentFont())
        assertEquals(1, synth.activeVoices)
    }

    @Test
    fun modulatorBudgetCountsExpansionAfterExactDefaultReplacement() {
        val encoded = Sf2Builder().buildFont(
            sampleFrames = ShortArray(4846) { if (it < 4800) 20000 else 0 },
            samples = listOf(Sf2Builder.SampleSpec("sample", 0, 4800, 100, 4700, 48000, 60)),
            instruments = listOf(Sf2Builder.InstrumentSpec("instrument", List(2) { Sf2Builder.ZoneSpec(0) }, globalModulators = listOf(
                Sf2Builder.ModSpec(0x0502, Generator.InitialAttenuation.id, 0),
                Sf2Builder.ModSpec(0x00E4, Generator.Pan.id, 10),
            ))),
            presets = listOf(Sf2Builder.PresetDef("preset", 0, 0, List(2) { Sf2Builder.PresetZoneSpec(0) }, globalModulators = listOf(
                Sf2Builder.ModSpec(0x00E5, Generator.Pan.id, 10),
            ))),
            info = mapOf("isng" to "EMU8000"),
        )
        val font = KiteSynth.readFont(encoded)
        // Four plans each retain eight defaults, two instrument routes and one preset route.
        assertEquals(44, font.voicesFor(0, 0, 60, 127).sumOf { it.modulators.size })
        assertFailsWith<SoundFontFormatException> { Synth(font, maxPreparedRegions = 4, maxPreparedModulators = 43) }
        assertEquals(44, Synth(font, maxPreparedRegions = 4, maxPreparedModulators = 44).maxPreparedModulators)
    }

    @Test
    fun overflowIsExplicitAndRetryKeepsTheReleaseBehindTheQueuedNote() {
        val synth = dry(font(flat = true))
        repeat(1023) { assertTrue(synth.tryEnqueue(0, 0, 0, 0)) }
        synth.noteOn(0, 60, 127, atFrame = 16)
        assertFailsWith<IllegalStateException> { synth.noteOff(0, 60, atFrame = 17) }
        assertEquals(1L, synth.rejectedEvents)
        assertEquals(0, synth.activeVoices)
        synth.render(FloatArray(32), 16)
        assertEquals(0, synth.activeVoices, "accepted future events must not execute early")
        assertTrue(synth.tryEnqueue(0x80, 60, 0, atFrame = 1))
        synth.render(FloatArray(128), 64)
        assertEquals(0, synth.activeVoices)
    }

    @Test
    fun invalidRenderSizesDoNotTouchTheBufferOrQueuedOffsets() {
        val synth = dry(font(flat = true))
        synth.noteOn(0, 60, 127, atFrame = 5)
        val out = FloatArray(12) { 17f }
        for (count in listOf(-1, Int.MIN_VALUE, 1 shl 30, Int.MAX_VALUE, 7)) {
            assertFailsWith<IllegalArgumentException> { synth.render(out, count) }
            assertTrue(out.all { it == 17f })
            assertEquals(0, synth.activeVoices)
        }
        synth.render(out, 6)
        assertEquals(1, synth.activeVoices)
        assertTrue(out.take(10).all { it == 0f })
    }

    @Test
    fun descendingAndOutOfBoundsBagIndexesAreFormatErrors() {
        for ((tag, field) in listOf("inst" to 20, "phdr" to 24)) for (index in listOf(2, 65535)) {
            val encoded = bytes()
            val chunk = (0..encoded.size - 4).first { at -> tag.indices.all { encoded[at + it] == tag[it].code.toByte() } }
            val at = chunk + 8 + field
            encoded[at] = index.toByte()
            encoded[at + 1] = (index shr 8).toByte()
            assertFailsWith<SoundFontFormatException> { KiteSynth.readFont(encoded) }
        }
    }

    @Test
    fun illegalOrUnrepresentableSampleRatesAreRejectedBeforePlayback() {
        for (rate in listOf(0L, 0x80000000L, 0xFFFFFFFFL, 96000L)) {
            val encoded = bytes()
            val tag = "shdr"
            val chunk = (0..encoded.size - 4).first { at -> tag.indices.all { encoded[at + it] == tag[it].code.toByte() } }
            val at = chunk + 8 + 36
            for (byte in 0 until 4) encoded[at + byte] = (rate shr (byte * 8)).toByte()
            if (rate == 96000L) {
                assertEquals(96000, KiteSynth.readFont(encoded).samples.single().sampleRate)
            } else {
                assertFailsWith<SoundFontFormatException> { KiteSynth.readFont(encoded) }
            }
        }
    }

    @Test
    fun fixedKeyAndVelocityApplyToPitchEnvelopesAndModulatorsWhileNoteOffUsesPressedKey() {
        val fixed = font(listOf(
            Generator.Keynum.id to 60,
            Generator.Velocity.id to 127,
            Generator.HoldVolEnv.id to -6000,
            Generator.KeynumToVolEnvHold.id to 100,
            Generator.KeynumToModEnvHold.id to 100,
        ))
        assertContentEquals(pcm(fixed, 60, 127), pcm(fixed, 72, 32))
        val synth = dry(fixed)
        synth.noteOn(0, 72, 32)
        synth.render(FloatArray(128), 64)
        assertEquals(listOf(72), synth.soundingKeys)
        synth.noteOff(0, 60)
        synth.render(FloatArray(128), 64)
        assertEquals(1, synth.activeVoices)
        synth.noteOff(0, 72)
        synth.render(FloatArray(128), 64)
        assertEquals(0, synth.activeVoices)
    }

    @Test
    fun undefinedHeaderRootsUseMiddleCWithoutChangingRawMetadata() {
        val reference = pcm(font())
        for (root in listOf(128, 200, 254, 255)) {
            val unpitched = font(root = root)
            assertEquals(root, unpitched.samples.single().originalKey)
            assertEquals(60, unpitched.voicesFor(0, 0, 60, 127).single().rootKey)
            assertContentEquals(reference, pcm(unpitched))
        }
        val overridden = font(listOf(Generator.OverridingRootKey.id to 72), root = 255)
        assertEquals(72, overridden.voicesFor(0, 0, 60, 127).single().rootKey)
    }

    @Test
    fun authoredQuarterSendsHaveOneQuarterTheFullWetSignal() {
        for (generator in listOf(Generator.ReverbEffectsSend, Generator.ChorusEffectsSend)) {
            fun wet(send: Int): FloatArray {
                val synth = Synth(font(listOf(generator.id to send), flat = true))
                synth.reverb.dry = 0.0
                synth.reverb.wet = if (generator == Generator.ReverbEffectsSend) 1.0 else 0.0
                synth.chorus.level = if (generator == Generator.ChorusEffectsSend) 1.0 else 0.0
                synth.controlChange(0, 91, 0)
                synth.controlChange(0, 93, 0)
                synth.noteOn(0, 60, 127)
                return FloatArray(9600).also { synth.render(it, 4800) }
            }
            val quarter = wet(250)
            val full = wet(1000)
            assertTrue(full.any { abs(it) > 1e-5f })
            assertTrue(full.indices.maxOf { abs(full[it] - quarter[it] * 4) } < 1e-5f, "$generator send units changed")
        }
    }

    @Test
    fun chorusRejectsNonfiniteSettingsAndContainsBadOrOverflowingSamples() {
        val chorus = Chorus()
        val original = chorus.level
        for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) chorus.level = value
        assertEquals(original, chorus.level)
        val input = FloatArray(6000)
        input[0] = Float.NaN
        input[1] = Float.POSITIVE_INFINITY
        input[2] = Float.MAX_VALUE
        input[3] = Float.MAX_VALUE
        input[4] = -Float.MAX_VALUE
        input[5] = -Float.MAX_VALUE
        val out = FloatArray(6000)
        chorus.process(input, out, 0, 3000)
        assertTrue(out.all { it.isFinite() })
        val impulse = FloatArray(6000).also { it[0] = 1f; it[1] = 1f }
        val recovered = FloatArray(6000)
        chorus.process(impulse, recovered, 0, 3000)
        assertTrue(recovered.all { it.isFinite() })
        assertTrue(recovered.any { abs(it) > 1e-5f })
    }

    @Test
    fun reapplyingReverbDefaultsDoesNotChangeItsImpulseResponse() {
        fun response(room: Reverb): FloatArray {
            val impulse = FloatArray(96000).also { it[0] = 1f; it[1] = 1f }
            return FloatArray(96000).also { room.process(impulse, it, 0, 48000) }
        }
        assertContentEquals(response(Reverb()), response(Reverb().apply {
            roomSize = roomSize
            damping = damping
            wet = wet
            dry = dry
            width = width
        }))
    }

    @Test
    fun mutingVolumeOrExpressionDoesNotEndAnUnfinishedRelease() {
        for (controller in listOf(7, 11)) {
            val synth = dry(font(listOf(Generator.ReleaseVolEnv.id to 0), flat = true))
            synth.noteOn(0, 60, 127)
            synth.render(FloatArray(128), 64)
            synth.noteOff(0, 60)
            synth.controlChange(0, controller, 0)
            synth.render(FloatArray(16384), 8192)
            assertEquals(1, synth.activeVoices)
            synth.controlChange(0, controller, 127)
            val restored = FloatArray(128)
            synth.render(restored, 64)
            assertTrue(restored.any { abs(it) > 1e-5f })
            synth.render(FloatArray(96000), 48000)
            assertEquals(0, synth.activeVoices)
        }
    }

    @Test
    fun killingAVoiceDropsItsFontDependentPlan() {
        val voice = Voice()
        voice.start(font().voicesFor(0, 0, 60, 127).single(), 48000, 0, 0)
        assertTrue(voice.watches(7))
        voice.kill()
        assertFalse(voice.watches(7))
        assertFalse(voice.watchesWheel)
        voice.start(font(flat = true).voicesFor(0, 0, 64, 127).single(), 48000, 0, 1)
        val out = FloatArray(128)
        voice.render(out, 0, 64)
        assertTrue(out.any { it != 0f })
    }

    @Test
    fun stealingPrefersQuietVelocityAndChannelGainAtTheSameEnvelopeStage() {
        val synth = dry(font(flat = true), voices = 2)
        synth.noteOn(0, 60, 127)
        synth.noteOn(0, 62, 8)
        synth.render(FloatArray(512), 256)
        synth.noteOn(0, 64, 127)
        synth.render(FloatArray(512), 256)
        assertEquals(listOf(60, 64), synth.soundingKeys.sorted())

        val channels = dry(font(flat = true), voices = 2)
        channels.noteOn(0, 60, 127)
        channels.noteOn(1, 62, 127)
        channels.render(FloatArray(512), 256)
        channels.controlChange(1, 7, 0)
        channels.noteOn(0, 64, 127)
        channels.render(FloatArray(512), 256)
        assertEquals(listOf(60, 64), channels.soundingKeys.sorted())
    }

    @Test
    fun stealingIncludesThePreparedSampleAmplitude() {
        val encoded = Sf2Builder().buildFont(
            sampleFrames = ShortArray(9692) { index ->
                val local = index % 4846
                if (local >= 4800) 0 else if (index < 4846) 20000 else 1000
            },
            samples = listOf(
                Sf2Builder.SampleSpec("loud", 0, 4800, 100, 4700, 48000, 60),
                Sf2Builder.SampleSpec("quiet", 4846, 9646, 4946, 9546, 48000, 60),
            ),
            instruments = listOf(Sf2Builder.InstrumentSpec("instrument", listOf(60, 62, 64).map { key ->
                Sf2Builder.ZoneSpec(if (key == 62) 1 else 0, keyLow = key, keyHigh = key,
                    extras = listOf(Generator.SampleModes.id to 1))
            })),
            presets = listOf(Sf2Builder.PresetDef("preset", 0, 0, listOf(Sf2Builder.PresetZoneSpec(0)))),
            info = mapOf("isng" to "EMU8000"),
        )
        val synth = dry(KiteSynth.readFont(encoded), voices = 2)
        synth.noteOn(0, 60, 127)
        synth.noteOn(0, 62, 127)
        synth.render(FloatArray(512), 256)
        synth.noteOn(0, 64, 127)
        synth.render(FloatArray(512), 256)
        assertEquals(listOf(60, 64), synth.soundingKeys.sorted())
    }
}
