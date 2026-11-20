package io.github.yuroyami.kiteaudio.synth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VoicePlanTest {

    private fun twoZoneFont(): SoundFont = KiteSynth.readFont(
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

    @Test
    fun aKeyPicksTheZoneThatCoversIt() {
        val font = twoZoneFont()
        assertEquals("low", font.voicesFor(0, 0, key = 40, velocity = 90).single().sample.name)
        assertEquals("high", font.voicesFor(0, 0, key = 80, velocity = 90).single().sample.name)
    }

    @Test
    fun theBoundaryKeysBelongToTheZoneThatNamesThem() {
        val font = twoZoneFont()
        assertEquals("low", font.voicesFor(0, 0, key = 59, velocity = 90).single().sample.name)
        assertEquals("high", font.voicesFor(0, 0, key = 60, velocity = 90).single().sample.name)
    }

    @Test
    fun aKeyNoZoneCoversStartsNoVoiceRatherThanTheWrongOne() {
        val font = KiteSynth.readFont(
            Sf2Builder().build(
                instrumentZones = listOf(Sf2Builder.ZoneSpec(sampleId = 0, keyLow = 60, keyHigh = 72)),
            ),
        )
        assertTrue(font.voicesFor(0, 0, key = 30, velocity = 90).isEmpty())
        assertTrue(font.voicesFor(0, 0, key = 100, velocity = 90).isEmpty())
    }

    @Test
    fun velocityPicksBetweenLayersTheSameWay() {
        val font = KiteSynth.readFont(
            Sf2Builder().build(
                sampleFrames = ShortArray(128),
                samples = listOf(
                    Sf2Builder.SampleSpec("soft", 0, 64, 8, 56, 44100, 60),
                    Sf2Builder.SampleSpec("hard", 64, 128, 72, 120, 44100, 60),
                ),
                instrumentZones = listOf(
                    Sf2Builder.ZoneSpec(sampleId = 0, velLow = 0, velHigh = 63),
                    Sf2Builder.ZoneSpec(sampleId = 1, velLow = 64, velHigh = 127),
                ),
            ),
        )
        assertEquals("soft", font.voicesFor(0, 0, key = 60, velocity = 30).single().sample.name)
        assertEquals("hard", font.voicesFor(0, 0, key = 60, velocity = 100).single().sample.name)
    }

    @Test
    fun twoZonesCoveringTheSameKeyBothStartAVoice() {
        // That is what a layered sound is. Picking only the first one loses half of it.
        val font = KiteSynth.readFont(
            Sf2Builder().build(
                sampleFrames = ShortArray(128),
                samples = listOf(
                    Sf2Builder.SampleSpec("a", 0, 64, 8, 56, 44100, 60),
                    Sf2Builder.SampleSpec("b", 64, 128, 72, 120, 44100, 60),
                ),
                instrumentZones = listOf(
                    Sf2Builder.ZoneSpec(sampleId = 0),
                    Sf2Builder.ZoneSpec(sampleId = 1),
                ),
            ),
        )
        assertEquals(2, font.voicesFor(0, 0, key = 60, velocity = 90).size)
    }

    @Test
    fun aPresetGeneratorAddsToTheInstrumentsRatherThanReplacingIt() {
        // The specification says a preset generator is an offset. A preset that says "quieter by
        // 60" means sixty quieter than whatever the instrument chose, not exactly sixty.
        val font = KiteSynth.readFont(
            Sf2Builder().build(
                instrumentZones = listOf(
                    Sf2Builder.ZoneSpec(
                        sampleId = 0,
                        extras = listOf(Generator.InitialAttenuation.id to 100),
                    ),
                ),
                presetZones = listOf(0 to listOf(Generator.InitialAttenuation.id to 60)),
            ),
        )
        assertEquals(160, font.voicesFor(0, 0, key = 60, velocity = 90).single().attenuationCb)
    }

    @Test
    fun anInstrumentValueStandsWhenThePresetSaysNothing() {
        val font = KiteSynth.readFont(
            Sf2Builder().build(
                instrumentZones = listOf(
                    Sf2Builder.ZoneSpec(
                        sampleId = 0,
                        extras = listOf(Generator.InitialAttenuation.id to 100),
                    ),
                ),
            ),
        )
        assertEquals(100, font.voicesFor(0, 0, key = 60, velocity = 90).single().attenuationCb)
    }

    @Test
    fun theRootKeyComesFromTheSampleUnlessTheZoneOverridesIt() {
        val plain = KiteSynth.readFont(Sf2Builder().build())
        assertEquals(60, plain.voicesFor(0, 0, key = 64, velocity = 90).single().rootKey)

        val overridden = KiteSynth.readFont(
            Sf2Builder().build(
                instrumentZones = listOf(
                    Sf2Builder.ZoneSpec(
                        sampleId = 0,
                        extras = listOf(Generator.OverridingRootKey.id to 69),
                    ),
                ),
            ),
        )
        assertEquals(69, overridden.voicesFor(0, 0, key = 64, velocity = 90).single().rootKey)
    }

    @Test
    fun coarseAndFineTuneAddUpIntoOneNumberOfCents() {
        val font = KiteSynth.readFont(
            Sf2Builder().build(
                instrumentZones = listOf(
                    Sf2Builder.ZoneSpec(
                        sampleId = 0,
                        extras = listOf(
                            Generator.CoarseTune.id to 2,      // two semitones
                            Generator.FineTune.id to 30,       // thirty cents
                        ),
                    ),
                ),
            ),
        )
        assertEquals(230, font.voicesFor(0, 0, key = 60, velocity = 90).single().tuneCents)
    }

    @Test
    fun theSamplesOwnCorrectionIsIncluded() {
        val font = KiteSynth.readFont(
            Sf2Builder().build(
                samples = listOf(Sf2Builder.SampleSpec("s", 0, 64, 8, 56, 44100, 60, correction = -14)),
            ),
        )
        assertEquals(-14, font.voicesFor(0, 0, key = 60, velocity = 90).single().tuneCents)
    }

    @Test
    fun aMissingEnvelopeIsInstantAndNotASecondLong() {
        // -12000 timecents is one millisecond, the shortest the format can express. A default of
        // zero would mean one whole second of attack on every note of every simple font.
        val plan = KiteSynth.readFont(Sf2Builder().build())
            .voicesFor(0, 0, key = 60, velocity = 90).single()
        assertEquals(-12000, plan.volumeEnvelope.attackTimecents)
        assertEquals(-12000, plan.volumeEnvelope.releaseTimecents)
        assertEquals(0, plan.volumeEnvelope.sustainCb, "sustain of zero means no drop at all")
    }

    @Test
    fun anEnvelopeTheFontSetsIsRead() {
        val font = KiteSynth.readFont(
            Sf2Builder().build(
                instrumentZones = listOf(
                    Sf2Builder.ZoneSpec(
                        sampleId = 0,
                        extras = listOf(
                            Generator.AttackVolEnv.id to (-2000 and 0xFFFF),
                            Generator.ReleaseVolEnv.id to (-1000 and 0xFFFF),
                            Generator.SustainVolEnv.id to 200,
                        ),
                    ),
                ),
            ),
        )
        val envelope = font.voicesFor(0, 0, key = 60, velocity = 90).single().volumeEnvelope
        assertEquals(-2000, envelope.attackTimecents)
        assertEquals(-1000, envelope.releaseTimecents)
        assertEquals(200, envelope.sustainCb)
    }

    @Test
    fun scaleTuningDefaultsToOneHundredCentsPerKey() {
        val plan = KiteSynth.readFont(Sf2Builder().build())
            .voicesFor(0, 0, key = 60, velocity = 90).single()
        assertEquals(100, plan.scaleTuning, "a missing scale tuning must not make every key one pitch")
    }

    @Test
    fun panIsHeldInsideTheRangeTheFormatAllows() {
        val font = KiteSynth.readFont(
            Sf2Builder().build(
                instrumentZones = listOf(
                    Sf2Builder.ZoneSpec(sampleId = 0, extras = listOf(Generator.Pan.id to 9000)),
                ),
            ),
        )
        assertTrue(font.voicesFor(0, 0, key = 60, velocity = 90).single().pan in -500..500)
    }

    @Test
    fun theSampleAddressesCountFromTheSamplesOwnFirstFrame() {
        val font = twoZoneFont()
        val high = font.voicesFor(0, 0, key = 80, velocity = 90).single()
        assertEquals(0, high.startFrame)
        assertEquals(64, high.endFrame)
        assertEquals(8, high.loopStartFrame)
        assertEquals(56, high.loopEndFrame)
    }

    @Test
    fun anUnknownBankFallsBackToADefaultRatherThanSilence() {
        // A file asking for a bank the font does not have should still make a sound.
        val font = twoZoneFont()
        assertTrue(font.voicesFor(bank = 7, program = 3, key = 60, velocity = 90).isNotEmpty())
    }

    @Test
    fun aNegativeGeneratorComesBackNegative() {
        // The format stores one 16-bit field whose meaning depends on the operator. Reading a
        // signed one as unsigned turns a pan of -250 into 65286 and an attack of -2000 into
        // about sixty-three thousand timecents, which is an attack of roughly nine hours.
        val font = KiteSynth.readFont(
            Sf2Builder().build(
                instrumentZones = listOf(
                    Sf2Builder.ZoneSpec(
                        sampleId = 0,
                        extras = listOf(
                            Generator.Pan.id to (-250 and 0xFFFF),
                            Generator.CoarseTune.id to (-12 and 0xFFFF),
                            Generator.FineTune.id to (-40 and 0xFFFF),
                        ),
                    ),
                ),
            ),
        )
        val plan = font.voicesFor(0, 0, key = 60, velocity = 90).single()
        assertEquals(-250, plan.pan)
        assertEquals(-1240, plan.tuneCents, "minus twelve semitones and forty cents")
    }

    @Test
    fun anUnsignedGeneratorIsNotAccidentallyMadeNegative() {
        // Attenuation runs 0 to 1440 and is never negative. Sign-extending it would make a quiet
        // zone extremely loud.
        val font = KiteSynth.readFont(
            Sf2Builder().build(
                instrumentZones = listOf(
                    Sf2Builder.ZoneSpec(
                        sampleId = 0,
                        extras = listOf(Generator.InitialAttenuation.id to 1440),
                    ),
                ),
            ),
        )
        assertEquals(1440, font.voicesFor(0, 0, key = 60, velocity = 90).single().attenuationCb)
    }

    @Test
    fun aKeyRangeReachingTheTopOfTheKeyboardIsNotReadAsNegative() {
        // A range packs two bytes. Reading it as one signed number breaks every zone whose top
        // key is 128 or above in the packed form.
        val font = KiteSynth.readFont(
            Sf2Builder().build(
                instrumentZones = listOf(Sf2Builder.ZoneSpec(sampleId = 0, keyLow = 21, keyHigh = 127)),
            ),
        )
        assertEquals(Span(21, 127), font.instruments.single().zones.single().keys)
    }

    @Test
    fun voicesForResolvesTheWayASynthChannelDoes() {
        val font = gmFont()
        assertEquals("rhodes", font.voicesFor(7, 5, 60, 100).single().sample.name, "a missing bank tries bank 0")
        assertEquals("kick", font.voicesFor(128, 99, 36, 100).single().sample.name, "a missing kit is kit 0")
        assertTrue(KiteSynth.readFont(Sf2Builder().build()).voicesFor(128, 0, 36, 100).isEmpty(), "no kit, no sound")
    }
}
