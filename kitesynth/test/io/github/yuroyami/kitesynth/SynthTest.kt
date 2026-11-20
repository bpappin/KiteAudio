package io.github.yuroyami.kitesynth

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SynthTest {

    private val rate = 48_000

    /** A loud, flat, looping sample: easy to see in the output. */
    private fun flatFont(frames: Int = 48_000): SoundFont = KiteSynth.readFont(
        Sf2Builder().build(
            sampleFrames = ShortArray(frames) { 20000 },
            samples = listOf(Sf2Builder.SampleSpec("flat", 0, frames, 0, frames - 100, rate, 60)),
            instrumentZones = listOf(
                Sf2Builder.ZoneSpec(sampleId = 0, extras = listOf(Generator.SampleModes.id to 1)),
            ),
        ),
    )

    private fun loudness(out: FloatArray, frames: Int): Float {
        var sum = 0f
        for (i in 0 until frames * 2) sum += abs(out[i])
        return sum / (frames * 2)
    }

    @Test
    fun aNoteMakesSoundAndSilenceMakesNone() {
        val synth = Synth(flatFont(), rate)
        val out = FloatArray(960)

        synth.render(out, 480)
        assertTrue(out.all { it == 0f }, "something came out before any note was played")

        synth.noteOn(0, 60, 100)
        synth.render(out, 480)
        assertTrue(loudness(out, 480) > 0.01f, "the note made no sound")
    }

    @Test
    fun renderingClearsTheBufferSoTheCallerDoesNotHaveTo() {
        val synth = Synth(flatFont(), rate)
        val out = FloatArray(960) { 0.5f }
        synth.render(out, 480)
        assertTrue(out.all { it == 0f }, "the old contents were left in the buffer")
    }

    @Test
    fun aNoteOffLetsTheVoiceGo() {
        val synth = Synth(flatFont(), rate)
        val out = FloatArray(960)
        synth.noteOn(0, 60, 100)
        synth.render(out, 480)
        assertEquals(1, synth.activeVoices)

        synth.noteOff(0, 60)
        repeat(40) { synth.render(out, 480) }
        assertEquals(0, synth.activeVoices, "the note never stopped")
    }

    @Test
    fun aNoteOnWithNoVelocityIsARelease() {
        val synth = Synth(flatFont(), rate)
        val out = FloatArray(960)
        synth.noteOn(0, 60, 100)
        synth.render(out, 480)
        synth.noteOn(0, 60, 0)
        repeat(40) { synth.render(out, 480) }
        assertEquals(0, synth.activeVoices)
    }

    @Test
    fun anEventIsAppliedAtItsFrameAndNotAtTheStartOfTheBlock() {
        // Quantising every note to the block size is 5 ms of slop at 256 frames, which a player
        // hears as sloppiness rather than as a bug.
        val synth = Synth(flatFont(), rate)
        val out = FloatArray(960)
        synth.noteOn(0, 60, 127, atFrame = 240)
        synth.render(out, 480)

        var firstHalf = 0f
        var secondHalf = 0f
        for (i in 0 until 240) firstHalf += abs(out[i * 2])
        for (i in 240 until 480) secondHalf += abs(out[i * 2])

        assertTrue(firstHalf < 0.001f, "the note sounded before its frame")
        assertTrue(secondHalf > 0.1f, "the note never sounded")
    }

    @Test
    fun anEventStampedPastTheBlockWaitsForTheNextOne() {
        val synth = Synth(flatFont(), rate)
        val out = FloatArray(960)
        synth.noteOn(0, 60, 127, atFrame = 600)

        synth.render(out, 480)
        assertTrue(loudness(out, 480) < 0.001f, "the note jumped forward into this block")

        synth.render(out, 480)
        assertTrue(loudness(out, 480) > 0.01f, "the note never arrived in the next block")
    }

    @Test
    fun severalNotesAtOnceAreLouderThanOne() {
        val synth = Synth(flatFont(), rate)
        val out = FloatArray(960)

        synth.noteOn(0, 60, 100)
        synth.render(out, 480)
        val one = loudness(out, 480)

        synth.noteOn(0, 64, 100)
        synth.noteOn(0, 67, 100)
        synth.render(out, 480)
        assertTrue(loudness(out, 480) > one, "adding notes did not add sound")
        assertEquals(3, synth.activeVoices)
    }

    @Test
    fun preparedRegionsPreserveVelocityBoundariesAndLayerOrder() {
        val font = KiteSynth.readFont(
            Sf2Builder().build(
                sampleFrames = ShortArray(192) { 20000 },
                samples = listOf(
                    Sf2Builder.SampleSpec("soft", 0, 64, 8, 56, rate, 60),
                    Sf2Builder.SampleSpec("hard-a", 64, 128, 72, 120, rate, 60),
                    Sf2Builder.SampleSpec("hard-b", 128, 192, 136, 184, rate, 60),
                ),
                instrumentZones = listOf(
                    Sf2Builder.ZoneSpec(sampleId = 0, velLow = 0, velHigh = 63),
                    Sf2Builder.ZoneSpec(sampleId = 1, velLow = 64, velHigh = 127),
                    Sf2Builder.ZoneSpec(sampleId = 2, velLow = 64, velHigh = 127),
                ),
            ),
        )
        val synth = Synth(font, rate)
        val out = FloatArray(2)

        synth.noteOn(0, 60, 63)
        synth.render(out, 1)
        assertEquals(1, synth.activeVoices, "the soft velocity selected the wrong regions")

        synth.allSoundOff()
        synth.noteOn(0, 60, 64)
        synth.render(out, 1)
        assertEquals(2, synth.activeVoices, "the hard boundary lost a prepared layer")
    }

    @Test
    fun theSustainPedalHoldsNotesAfterTheKeyComesUp() {
        val synth = Synth(flatFont(), rate)
        val out = FloatArray(960)

        synth.controlChange(0, 64, 127)     // pedal down
        synth.noteOn(0, 60, 100)
        synth.render(out, 480)
        synth.noteOff(0, 60)
        synth.render(out, 480)
        assertEquals(1, synth.activeVoices, "the pedal did not hold the note")

        synth.controlChange(0, 64, 0)       // pedal up
        repeat(40) { synth.render(out, 480) }
        assertEquals(0, synth.activeVoices, "lifting the pedal did not let the note go")
    }

    @Test
    fun liftingThePedalReleasesEveryHeldKeyFromPreallocatedState() {
        val synth = Synth(flatFont(), rate)
        val out = FloatArray(960)
        synth.controlChange(0, 64, 127)
        for (key in 60..64) synth.noteOn(0, key, 100)
        synth.render(out, 480)
        for (key in 60..64) synth.noteOff(0, key)
        synth.render(out, 480)
        assertEquals(5, synth.activeVoices)

        synth.controlChange(0, 64, 0)
        repeat(40) { synth.render(out, 480) }

        assertEquals(0, synth.activeVoices, "pedal release left a preallocated held key behind")
    }

    @Test
    fun aHalfPressedPedalCountsAsDown() {
        // MIDI's own rule: 64 and above is down.
        val synth = Synth(flatFont(), rate)
        val out = FloatArray(960)
        synth.controlChange(0, 64, 70)
        synth.noteOn(0, 60, 100)
        synth.render(out, 480)
        synth.noteOff(0, 60)
        synth.render(out, 480)
        assertEquals(1, synth.activeVoices)
    }

    @Test
    fun theSameKeyStruckTwiceDoesNotStack() {
        val synth = Synth(flatFont(), rate)
        val out = FloatArray(960)
        synth.noteOn(0, 60, 100)
        synth.render(out, 480)
        synth.noteOn(0, 60, 100)
        synth.render(out, 480)
        // The first is released and fading, the second is sounding. Never two full voices.
        assertTrue(synth.activeVoices <= 2)
        repeat(40) { synth.render(out, 480) }
        assertEquals(1, synth.activeVoices, "the repeated note left the first one ringing")
    }

    @Test
    fun theSameKeyOnTwoChannelsIsTwoNotes() {
        val synth = Synth(flatFont(), rate)
        val out = FloatArray(960)
        synth.noteOn(0, 60, 100)
        synth.noteOn(1, 60, 100)
        synth.render(out, 480)
        assertEquals(2, synth.activeVoices)

        synth.noteOff(0, 60)
        synth.render(out, 480)
        assertTrue(synth.activeVoices >= 1, "stopping one channel stopped the other")
    }

    @Test
    fun runningOutOfVoicesStealsRatherThanRefusing() {
        val synth = Synth(flatFont(), rate, maxVoices = 4)
        val out = FloatArray(960)
        for (key in 60..69) {
            synth.noteOn(0, key, 100)
            synth.render(out, 48)
        }
        assertEquals(4, synth.activeVoices, "the pool grew or shrank")
        assertTrue(loudness(out, 48) > 0.001f, "everything went silent under load")
    }

    @Test
    fun stealingTakesTheOldestQuietestVoiceAndNotTheNewestOne() {
        // Stealing the newest is what makes a synthesiser sound broken: the note you just
        // played is the one that disappears.
        val synth = Synth(flatFont(), rate, maxVoices = 2)
        val out = FloatArray(960)
        synth.noteOn(0, 60, 100)
        repeat(10) { synth.render(out, 480) }        // let the first note get old
        synth.noteOn(0, 62, 100)
        synth.render(out, 480)
        synth.noteOn(0, 64, 100)
        synth.render(out, 480)

        assertTrue(loudness(out, 480) > 0.001f, "the newest note was the one dropped")
        assertEquals(2, synth.activeVoices)
    }

    @Test
    fun allNotesOffLetsEverythingFade() {
        val synth = Synth(flatFont(), rate)
        val out = FloatArray(960)
        (60..67).forEach { synth.noteOn(0, it, 100) }
        synth.render(out, 480)
        assertEquals(8, synth.activeVoices)

        synth.controlChange(0, 123, 0)
        repeat(40) { synth.render(out, 480) }
        assertEquals(0, synth.activeVoices)
    }

    @Test
    fun allSoundOffStopsEverythingImmediately() {
        val synth = Synth(flatFont(), rate)
        val out = FloatArray(960)
        (60..67).forEach { synth.noteOn(0, it, 100) }
        synth.render(out, 480)

        synth.allSoundOff()
        assertEquals(0, synth.activeVoices, "all sound off left something ringing")
        synth.render(out, 480)
        assertTrue(out.all { it == 0f })
    }

    @Test
    fun changingTheFontStopsWhateverWasSounding() {
        // The old voices point into a sample pool that is no longer there.
        val synth = Synth(flatFont(), rate)
        val out = FloatArray(960)
        synth.noteOn(0, 60, 100)
        synth.render(out, 480)
        assertEquals(1, synth.activeVoices)

        synth.font = flatFont(frames = 24_000)
        assertEquals(0, synth.activeVoices, "voices survived a font change")
    }

    @Test
    fun aKeyOutsideMidiIsIgnoredRatherThanCrashing() {
        val synth = Synth(flatFont(), rate)
        val out = FloatArray(960)
        synth.noteOn(0, -5, 100)
        synth.noteOn(0, 200, 100)
        synth.render(out, 480)
        assertEquals(0, synth.activeVoices)
    }

    @Test
    fun aFontWithNoZoneForAKeyMakesNoVoiceAndNoTrouble() {
        val narrow = KiteSynth.readFont(
            Sf2Builder().build(
                instrumentZones = listOf(Sf2Builder.ZoneSpec(sampleId = 0, keyLow = 60, keyHigh = 64)),
            ),
        )
        val synth = Synth(narrow, rate)
        val out = FloatArray(960)
        synth.noteOn(0, 30, 100)
        synth.render(out, 480)
        assertEquals(0, synth.activeVoices)
    }

    @Test
    fun theTuningTableRetunesWhatStartsAfterIt() {
        val synth = Synth(flatFont(), rate)
        assertEquals(128, synth.tuningCents.size)
        assertTrue(synth.tuningCents.all { it == 0.0 }, "it does not start in equal temperament")
        synth.tuningCents[60] = 50.0
        assertEquals(50.0, synth.tuningCents[60])
    }

    @Test
    fun aBufferTooSmallForTheFramesAskedForIsRefused() {
        val synth = Synth(flatFont(), rate)
        val problem = runCatching { synth.render(FloatArray(100), 480) }.exceptionOrNull()
        assertTrue(problem is IllegalArgumentException, "a short buffer was accepted")
    }

    @Test
    fun twoRunsOfTheSameNotesProduceIdenticalSamples() {
        fun once(): FloatArray {
            val synth = Synth(flatFont(), rate)
            val out = FloatArray(2400)
            synth.noteOn(0, 60, 100, atFrame = 10)
            synth.noteOn(0, 64, 90, atFrame = 200)
            synth.render(out, 1200)
            return out
        }
        assertTrue(once().contentEquals(once()), "the synthesiser is not deterministic")
    }

    @Test
    fun aQueueThatOverflowsReportsRejectionWithoutApplyingEarly() {
        val synth = Synth(flatFont(), rate, maxVoices = 8)
        repeat(1024) { assertTrue(synth.tryEnqueue(0x90, 60 + (it % 8), 100, atFrame = 100)) }
        assertFalse(synth.tryEnqueue(0x90, 72, 100, atFrame = 0))
        assertEquals(1L, synth.rejectedEvents)
        assertEquals(0, synth.activeVoices)
        val out = FloatArray(960)
        synth.render(out, 480)
        assertTrue(synth.activeVoices > 0, "everything was dropped")
    }

    @Test
    fun aProgramChangeIsRememberedForTheNextNote() {
        val synth = Synth(flatFont(), rate)
        val out = FloatArray(960)
        synth.programChange(0, 5)
        synth.noteOn(0, 60, 100)
        synth.render(out, 480)
        // The test font has one preset, so an unknown program falls back to it and still sounds.
        assertEquals(1, synth.activeVoices)
    }

    @Test
    fun theSynthesiserPutsTheSoundInARoom() {
        val synth = Synth(flatFont(), rate)
        val out = FloatArray(960)
        synth.noteOn(0, 60, 100)
        synth.render(out, 480)

        // Stopping the note leaves the room ringing for a moment. Without a reverb the very next
        // block is exactly zero.
        synth.allSoundOff()
        synth.reverb.wet = 0.5
        synth.noteOn(0, 60, 100)
        synth.render(out, 480)
        synth.allSoundOff()

        assertTrue(synth.reverb.wet > 0.0, "the synthesiser has no room at all")
    }

    @Test
    fun turningTheRoomOffLeavesTheSamplesAlone() {
        val synth = Synth(flatFont(), rate)
        synth.reverb.wet = 0.0
        synth.reverb.dry = 1.0
        val out = FloatArray(960)
        synth.noteOn(0, 60, 100)
        synth.render(out, 480)
        assertTrue(loudness(out, 480) > 0.01f, "turning the room off silenced the note")
    }

    @Test
    fun allSoundOffEmptiesTheRoomAsWellAsTheVoices() {
        val synth = Synth(flatFont(), rate)
        synth.reverb.wet = 0.6
        val out = FloatArray(960)
        synth.noteOn(0, 60, 100)
        repeat(4) { synth.render(out, 480) }

        synth.allSoundOff()
        synth.render(out, 480)
        assertTrue(
            out.all { kotlin.math.abs(it) < 1e-6f },
            "the room still had the last song in it: peak ${out.maxOf { kotlin.math.abs(it) }}",
        )
    }

    private fun decibelsBelow(quiet: Float, loud: Float): Double = 20 * kotlin.math.log10(loud.toDouble() / quiet)

    /** A square wave at 1 kHz, looping, so a filter has plenty of edges to take away. */
    private fun squareFont(
        zoneMods: List<Sf2Builder.ModSpec> = emptyList(),
        presetMods: List<Sf2Builder.ModSpec> = emptyList(),
        cutoff: Int = 5000,
        flat: Boolean = false,
    ): SoundFont {
        val frames = 4800
        return KiteSynth.readFont(
            Sf2Builder().buildFont(
                sampleFrames = ShortArray(frames) { if (flat || (it / 24) % 2 == 0) 16000 else -16000 },
                samples = listOf(Sf2Builder.SampleSpec("square", 0, frames, 0, frames, rate, 60)),
                instruments = listOf(
                    Sf2Builder.InstrumentSpec(
                        "square",
                        listOf(
                            Sf2Builder.ZoneSpec(
                                sampleId = 0,
                                extras = listOf(Generator.SampleModes.id to 1, Generator.InitialFilterFc.id to cutoff),
                                modulators = zoneMods,
                            ),
                        ),
                    ),
                ),
                presets = listOf(Sf2Builder.PresetDef("square", 0, 0, listOf(Sf2Builder.PresetZoneSpec(0, modulators = presetMods)))),
            ),
        )
    }

    private fun roughness(out: FloatArray, frames: Int): Double {
        var sum = 0.0
        for (i in 1 until frames) {
            val d = out[i * 2] - out[(i - 1) * 2]
            sum += d * d
        }
        return sum
    }

    @Test
    fun halfVelocityIsTheFormatsConcaveCurveDown() {
        fun level(velocity: Int): Float {
            val synth = Synth(flatFont(), rate).apply { reverb.wet = 0.0 }
            synth.noteOn(0, 60, velocity)
            val out = FloatArray(960)
            synth.render(out, 480)
            synth.render(out, 480)
            return loudness(out, 480)
        }
        val drop = decibelsBelow(level(64), level(127))
        assertTrue(drop in 8.0..14.0, "velocity 64 is $drop dB down")
    }

    @Test
    fun volumeTurnsAHeldNoteDownWithoutRestartingIt() {
        val synth = Synth(flatFont(), rate).apply { reverb.wet = 0.0 }
        synth.noteOn(0, 60, 127)
        val before = FloatArray(960)
        synth.render(before, 480)
        synth.controlChange(0, 7, 64)
        val after = FloatArray(960)
        synth.render(after, 480)
        assertTrue(after.all { it != 0f }, "the note restarted: a silent frame appeared")
        synth.render(after, 480)
        val drop = decibelsBelow(loudness(after, 480), loudness(before, 480))
        assertTrue(drop in 8.0..14.0, "CC 7 at 64 is $drop dB down, the format's concave curve says about 12")
        assertEquals(1, synth.activeVoices)
    }

    @Test
    fun aFontModulatorOpensTheFilterOnAHeldNote() {
        val cc74 = Sf2Builder.ModSpec(source = 0x80 or 74, destination = Generator.InitialFilterFc.id, amount = 5000)
        val synth = Synth(squareFont(zoneMods = listOf(cc74)), rate).apply { reverb.wet = 0.0 }
        synth.noteOn(0, 60, 127)
        val out = FloatArray(960)
        synth.render(out, 480)
        synth.render(out, 480)
        val dark = roughness(out, 480)
        synth.controlChange(0, 74, 127)
        synth.render(out, 480)
        synth.render(out, 480)
        assertTrue(roughness(out, 480) > dark * 4, "CC 74 should open the filter")
    }

    @Test
    fun aPresetModulatorAddsToTheInstrumentsOwn() {
        val cc74 = Sf2Builder.ModSpec(source = 0x80 or 74, destination = Generator.InitialFilterFc.id, amount = 2500)
        val once = squareFont(zoneMods = listOf(cc74))
        val twice = squareFont(zoneMods = listOf(cc74), presetMods = listOf(cc74))
        assertEquals(2, twice.voicesFor(0, 0, 60, 127).single().modulators.count { it.sameAs(twice.voicesFor(0, 0, 60, 127).single().modulators.last()) })
        fun brightness(font: SoundFont): Double {
            val synth = Synth(font, rate).apply { reverb.wet = 0.0 }
            synth.controlChange(0, 74, 127)
            synth.noteOn(0, 60, 127)
            val out = FloatArray(960)
            synth.render(out, 480)
            synth.render(out, 480)
            return roughness(out, 480)
        }
        assertTrue(brightness(twice) > brightness(once) * 2, "the two add up to a brighter sound than one")
    }

    @Test
    fun anInstrumentModulatorReplacesTheDefaultItMatches() {
        val noTouch = Sf2Builder.ModSpec(source = 0x0502, destination = Generator.InitialAttenuation.id, amount = 0)
        fun level(velocity: Int): Float {
            // A flat sample: the default velocity-to-brightness modulator darkens a soft note, and a low-pass lets a flat signal through untouched.
            val synth = Synth(squareFont(zoneMods = listOf(noTouch), cutoff = 13500, flat = true), rate).apply { reverb.wet = 0.0 }
            synth.noteOn(0, 60, velocity)
            val out = FloatArray(960)
            synth.render(out, 480)
            synth.render(out, 480)
            return loudness(out, 480)
        }
        assertTrue(abs(decibelsBelow(level(40), level(127))) < 0.5, "a velocity curve of nothing makes every note the same")
    }

    /** A short burst, 96 frames and no loop, so what is heard after it is the room or the chorus. */
    private fun burstFont(extras: List<Pair<Int, Int>> = emptyList()): SoundFont = KiteSynth.readFont(
        Sf2Builder().build(
            sampleFrames = ShortArray(96) { if (it % 2 == 0) 20000 else -20000 },
            samples = listOf(Sf2Builder.SampleSpec("burst", 0, 96, 0, 0, rate, 60)),
            instrumentZones = listOf(Sf2Builder.ZoneSpec(sampleId = 0, extras = extras)),
        ),
    )

    private fun energy(out: FloatArray, from: Int, to: Int): Double = (from until to).sumOf { (out[it * 2] * out[it * 2] + out[it * 2 + 1] * out[it * 2 + 1]).toDouble() }

    @Test
    fun aZoneSendingToTheRoomRingsOnAfterIt() {
        fun tail(send: Int): Double {
            val synth = Synth(burstFont(listOf(Generator.ReverbEffectsSend.id to send)), rate).apply { reverb.wet = 0.5 }
            synth.controlChange(0, 91, 0)
            synth.noteOn(0, 60, 127)
            val out = FloatArray(9600 * 2)
            synth.render(out, 9600)
            return energy(out, 2400, 9600)
        }
        assertTrue(tail(1000) > tail(0) * 10, "a full send should leave a room behind the burst")
    }

    @Test
    fun theChorusSendBringsBackAnEchoAboutThirtyMillisecondsLater() {
        fun echo(send: Int): Double {
            val synth = Synth(burstFont(), rate).apply { reverb.wet = 0.0 }
            synth.controlChange(0, 91, 0)
            synth.controlChange(0, 93, send)
            synth.noteOn(0, 60, 127)
            val out = FloatArray(4800 * 2)
            synth.render(out, 4800)
            return energy(out, rate * 25 / 1000, rate * 40 / 1000)
        }
        assertTrue(echo(0) == 0.0, "no send, no echo")
        assertTrue(echo(127) > 0.0, "a full CC 93 sends the burst to the chorus")
    }

    @Test
    fun aChannelNobodyTouchedUsesTheMaximumDefaultControllerSend() {
        val synth = Synth(flatFont(), rate)
        assertEquals(127, synth.controller(0, 91), "the room is full until somebody turns it down")
        assertEquals(0, synth.controller(0, 93))
    }

    @Test
    fun cubicInterpolationStopsAPitchedUpSampleSoundingLikeSand() {
        // A 440 Hz sine recorded at 8 kHz, played at 48 kHz: six output frames for every recorded one.
        val recorded = 8000
        val data = ShortArray(recorded) { (kotlin.math.sin(2 * kotlin.math.PI * 440 * it / recorded) * 20000).toInt().toShort() }
        val font = KiteSynth.readFont(
            Sf2Builder().build(
                sampleFrames = data,
                samples = listOf(Sf2Builder.SampleSpec("sine", 0, recorded, 0, 0, recorded, 69)),
                instrumentZones = listOf(Sf2Builder.ZoneSpec(sampleId = 0)),
            ),
        )
        fun error(interpolation: Interpolation): Double {
            val synth = Synth(font, rate).apply { reverb.wet = 0.0; this.interpolation = interpolation }
            synth.noteOn(0, 69, 127)
            val out = FloatArray(4800 * 2)
            synth.render(out, 4800)
            val heard = DoubleArray(4320) { out[(480 + it) * 2].toDouble() }
            val ideal = DoubleArray(4320) { kotlin.math.sin(2 * kotlin.math.PI * 440 * (480 + it) / rate) }
            fun rms(x: DoubleArray) = kotlin.math.sqrt(x.sumOf { it * it } / x.size)
            val a = rms(heard)
            val b = rms(ideal)
            return kotlin.math.sqrt(heard.indices.sumOf { (heard[it] / a - ideal[it] / b).let { d -> d * d } } / heard.size)
        }
        val gain = 20 * kotlin.math.log10(error(Interpolation.Linear) / error(Interpolation.Cubic))
        assertTrue(gain >= 12.0, "cubic was only $gain dB closer to the true sine than linear")
    }
}
