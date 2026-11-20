package io.github.yuroyami.kitesynth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SoundFontReaderTest {

    private fun font(builder: Sf2Builder.() -> ByteArray = { build() }): SoundFont =
        KiteSynth.readFont(Sf2Builder().builder())

    @Test
    fun aFontReadsBackItsNameAndItsOnePreset() {
        val loaded = font { build(fontName = "Test Font") }
        assertEquals("Test Font", loaded.name)
        assertEquals(1, loaded.presets.size)
        assertEquals("Piano", loaded.presets[0].name)
        assertEquals(0, loaded.presets[0].bank)
        assertEquals(0, loaded.presets[0].program)
    }

    @Test
    fun theTerminalRecordsAreNotMistakenForRealOnes() {
        // Every pdta list ends with a marker named EOP, EOI or EOS. Reading one as real gives
        // the font a phantom preset with nonsense in it.
        val loaded = font()
        assertTrue(loaded.presets.none { it.name.startsWith("EOP") }, "the EOP marker became a preset")
        assertTrue(loaded.instruments.none { it.name.startsWith("EOI") }, "the EOI marker became an instrument")
        assertTrue(loaded.samples.none { it.name.startsWith("EOS") }, "the EOS marker became a sample")
        assertEquals(1, loaded.samples.size)
    }

    @Test
    fun theSampleHeaderComesBackWithItsRealNumbers() {
        val loaded = font()
        val sample = loaded.samples.single()
        assertEquals("saw", sample.name)
        assertEquals(64, sample.data.size)
        assertEquals(8, sample.loopStart)
        assertEquals(56, sample.loopEnd)
        assertEquals(44100, sample.sampleRate)
        assertEquals(60, sample.originalKey)
        assertEquals(64, sample.frameCount)
        assertTrue(sample.hasUsableLoop)
    }

    @Test
    fun sampleFramesAreReadAsSignedSixteenBit() {
        val frames = shortArrayOf(0, 1000, -1000, 32767, -32768, 0, 0, 0)
        val loaded = KiteSynth.readFont(
            Sf2Builder().build(
                sampleFrames = frames,
                samples = listOf(Sf2Builder.SampleSpec("s", 0, 8, 2, 6, 44100, 60)),
            ),
        )
        assertEquals(frames.toList(), loaded.samples.single().data.toList())
    }

    @Test
    fun eachSampleOwnsExactlyItsOwnFrames() {
        val pool = ShortArray(200) { (it * 3).toShort() }
        val loaded = KiteSynth.readFont(
            Sf2Builder().build(
                sampleFrames = pool,
                samples = listOf(
                    Sf2Builder.SampleSpec("first", 0, 80, 10, 70, 44100, 60),
                    Sf2Builder.SampleSpec("second", 100, 200, 120, 190, 22050, 48),
                ),
                instrumentZones = listOf(
                    Sf2Builder.ZoneSpec(sampleId = 0, keyHigh = 63),
                    Sf2Builder.ZoneSpec(sampleId = 1, keyLow = 64),
                ),
            ),
        )
        val (first, second) = loaded.samples
        assertEquals(80, first.data.size)
        assertEquals(100, second.data.size)
        assertEquals(pool[100], second.data[0])
        assertEquals(pool[199], second.data[99])
        assertEquals(20, second.loopStart, "loop points count from the sample's own first frame")
        assertEquals(90, second.loopEnd)
    }

    @Test
    fun aSampleThatClaimsFramesPastTheDataIsCutWhereTheDataEnds() {
        // A header a few frames too long is common in the wild. It loads, cut to what is there.
        val loaded = KiteSynth.readFont(
            Sf2Builder().build(
                sampleFrames = ShortArray(100) { 7 },
                samples = listOf(Sf2Builder.SampleSpec("long", 40, 500, 50, 400, 44100, 60)),
            ),
        )
        val sample = loaded.samples.single()
        assertEquals(60, sample.data.size)
        assertEquals(10, sample.loopStart)
        assertEquals(60, sample.loopEnd)
    }

    @Test
    fun anInstrumentZoneKnowsItsSampleAndItsKeyRange() {
        val loaded = KiteSynth.readFont(
            Sf2Builder().build(
                instrumentZones = listOf(Sf2Builder.ZoneSpec(sampleId = 0, keyLow = 36, keyHigh = 59)),
            ),
        )
        val zone = loaded.instruments.single().zones.single()
        assertEquals(0, zone.sampleId)
        assertEquals(Span(36, 59), zone.keys)
        assertEquals(Span.Full, zone.velocities, "a zone that says nothing about velocity takes all of it")
    }

    @Test
    fun aVelocityRangeIsReadAsWell() {
        val loaded = KiteSynth.readFont(
            Sf2Builder().build(
                instrumentZones = listOf(Sf2Builder.ZoneSpec(sampleId = 0, velLow = 0, velHigh = 63)),
            ),
        )
        assertEquals(Span(0, 63), loaded.instruments.single().zones.single().velocities)
    }

    @Test
    fun severalZonesSplitTheKeyboardBetweenThem() {
        val loaded = KiteSynth.readFont(
            Sf2Builder().build(
                sampleFrames = ShortArray(128),
                samples = listOf(
                    Sf2Builder.SampleSpec("low", 0, 64, 8, 56, 44100, 48),
                    Sf2Builder.SampleSpec("high", 64, 128, 72, 120, 44100, 72),
                ),
                instrumentZones = listOf(
                    Sf2Builder.ZoneSpec(sampleId = 0, keyLow = 0, keyHigh = 59),
                    Sf2Builder.ZoneSpec(sampleId = 1, keyLow = 60, keyHigh = 127),
                ),
            ),
        )
        val zones = loaded.instruments.single().zones
        assertEquals(2, zones.size)
        assertEquals(0, zones[0].sampleId)
        assertEquals(1, zones[1].sampleId)
        assertEquals(Span(60, 127), zones[1].keys)
    }

    @Test
    fun aGlobalZoneSetsDefaultsForEveryZoneAfterIt() {
        // A zone with no SampleId at the front of an instrument is global. Reading it as an
        // ordinary zone silently loses settings the font meant to apply everywhere.
        val loaded = KiteSynth.readFont(
            Sf2Builder().build(
                instrumentGlobal = listOf(Generator.InitialAttenuation.id to 120),
                instrumentZones = listOf(Sf2Builder.ZoneSpec(sampleId = 0)),
            ),
        )
        val zones = loaded.instruments.single().zones
        assertEquals(1, zones.size, "the global zone was counted as a playable zone")
        assertEquals(120, zones[0].generators[Generator.InitialAttenuation])
    }

    @Test
    fun aZoneOfItsOwnBeatsTheGlobalOne() {
        val loaded = KiteSynth.readFont(
            Sf2Builder().build(
                instrumentGlobal = listOf(Generator.InitialAttenuation.id to 120),
                instrumentZones = listOf(
                    Sf2Builder.ZoneSpec(
                        sampleId = 0,
                        extras = listOf(Generator.InitialAttenuation.id to 30),
                    ),
                ),
            ),
        )
        assertEquals(30, loaded.instruments.single().zones[0].generators[Generator.InitialAttenuation])
    }

    @Test
    fun aPresetZonePointsAtAnInstrument() {
        val loaded = font()
        val zone = loaded.presets.single().zones.single()
        assertEquals(0, zone.instrumentId)
    }

    @Test
    fun aPresetLevelGlobalZoneWorksTheSameWay() {
        val loaded = KiteSynth.readFont(
            Sf2Builder().build(presetGlobal = listOf(Generator.ReverbEffectsSend.id to 200)),
        )
        val zones = loaded.presets.single().zones
        assertEquals(1, zones.size)
        assertEquals(200, zones[0].generators[Generator.ReverbEffectsSend])
    }

    @Test
    fun aGeneratorNobodyNamedIsStillKept() {
        // A font may carry generators this synthesiser does not read. Dropping them would make a
        // written copy differ from what was read.
        val loaded = KiteSynth.readFont(
            Sf2Builder().build(
                instrumentZones = listOf(Sf2Builder.ZoneSpec(sampleId = 0, extras = listOf(14 to 77))),
            ),
        )
        assertEquals(77, loaded.instruments.single().zones[0].generators.all()[14])
        assertNull(Generator.of(14), "generator 14 is unused in the specification")
    }

    @Test
    fun aFileThatIsNotRiffIsRejectedWithASentenceSayingSo() {
        val problem = assertFailsWith<SoundFontFormatException> {
            KiteSynth.readFont("this is not a soundfont at all".encodeToByteArray())
        }
        assertTrue(problem.message!!.contains("RIFF"), "the message does not say what was wrong: ${problem.message}")
    }

    @Test
    fun aRiffFileThatIsNotASoundFontIsRejected() {
        val wave = "RIFF".encodeToByteArray() + byteArrayOf(4, 0, 0, 0) + "WAVE".encodeToByteArray()
        val problem = assertFailsWith<SoundFontFormatException> { KiteSynth.readFont(wave) }
        assertTrue(problem.message!!.contains("sfbk"))
    }

    @Test
    fun aTruncatedFontIsRejectedRatherThanHalfRead() {
        val whole = Sf2Builder().build()
        for (fraction in listOf(2, 3, 4, 8)) {
            val cut = whole.copyOf(whole.size / fraction)
            assertFailsWith<SoundFontFormatException>("a font cut to 1/$fraction was accepted") {
                KiteSynth.readFont(cut)
            }
        }
    }

    @Test
    fun anEmptyFileIsRejected() {
        assertFailsWith<SoundFontFormatException> { KiteSynth.readFont(ByteArray(0)) }
    }

    @Test
    fun aVersionFromTheFutureIsRefused() {
        val problem = assertFailsWith<SoundFontFormatException> {
            KiteSynth.readFont(Sf2Builder().build(version = 4))
        }
        assertTrue(problem.message!!.contains("4"))
    }

    @Test
    fun theFontFindsAPresetByBankAndProgram() {
        val loaded = font()
        assertNotNull(loaded.preset(bank = 0, program = 0))
        assertNull(loaded.preset(bank = 1, program = 0))
        assertEquals("Piano", loaded.defaultPreset?.name)
    }

    @Test
    fun aSpanContainsWhatItShould() {
        val span = Span(36, 59)
        assertTrue(36 in span)
        assertTrue(59 in span)
        assertTrue(35 !in span)
        assertTrue(60 !in span)
    }

    @Test
    fun aPackedRangeUnpacksLowByteFirst() {
        // The specification packs the low key in the low byte. Getting this backwards makes
        // every zone answer to the wrong half of the keyboard.
        assertEquals(Span(36, 59), Span.ofPacked((59 shl 8) or 36))
    }

    @Test
    fun loopModesAreReadFromTheirTwoBits() {
        assertEquals(LoopMode.NoLoop, LoopMode.of(0))
        assertEquals(LoopMode.Continuous, LoopMode.of(1))
        assertEquals(LoopMode.NoLoop, LoopMode.of(2), "2 is undefined and means no loop")
        assertEquals(LoopMode.UntilRelease, LoopMode.of(3))
    }

    // ------------------------------------------------------------------ modulators, INFO, SF3

    @Test
    fun instrumentZoneModulatorsAreKept() {
        val loaded = font {
            build(instrumentZones = listOf(Sf2Builder.ZoneSpec(sampleId = 0, modulators = listOf(Sf2Builder.ModSpec(0x0502, 48, 960)))))
        }
        val mod = loaded.instruments[0].zones[0].modulators.single()
        assertEquals(0x0502, mod.source)
        assertEquals(Generator.InitialAttenuation, mod.destination)
        assertEquals(960, mod.amount)
    }

    @Test
    fun aLocalModulatorReplacesTheGlobalOneItMatches() {
        val loaded = font {
            build(
                instrumentGlobalModulators = listOf(Sf2Builder.ModSpec(0x0081, 6, 50), Sf2Builder.ModSpec(0x0102, 8, -2400)),
                instrumentZones = listOf(Sf2Builder.ZoneSpec(sampleId = 0, modulators = listOf(Sf2Builder.ModSpec(0x0081, 6, 100)))),
            )
        }
        val zone = loaded.instruments[0].zones.single()
        assertEquals(2, zone.modulators.size)
        assertEquals(100, zone.modulators.first { it.source == 0x0081 }.amount)
        assertEquals(-2400, zone.modulators.first { it.source == 0x0102 }.amount, "amounts are signed")
    }

    @Test
    fun presetGlobalModulatorsReachEveryPresetZone() {
        val loaded = font { build(presetGlobalModulators = listOf(Sf2Builder.ModSpec(0x0081, 6, 25))) }
        assertEquals(25, loaded.presets.single().zones.single().modulators.single().amount)
    }

    @Test
    fun infoChunksAreKept() {
        val loaded = font { build(fontName = "Named", engineer = "Somebody", comment = "A note") }
        assertEquals("Somebody", loaded.info["IENG"])
        assertEquals("A note", loaded.info["ICMT"])
        assertEquals("Named", loaded.info["INAM"])
    }

    @Test
    fun aCompressedSampleIsRefusedWithAPlainMessage() {
        val failure = assertFailsWith<SoundFontFormatException> {
            font { build(samples = listOf(Sf2Builder.SampleSpec("ogg", 0, 64, 8, 56, 44100, 60, type = 0x11))) }
        }
        assertTrue(failure.message!!.contains("SF3"), failure.message)
    }
}
