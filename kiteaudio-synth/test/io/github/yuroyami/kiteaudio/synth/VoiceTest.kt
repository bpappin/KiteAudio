package io.github.yuroyami.kiteaudio.synth

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VoiceTest {

    private val rate = 48_000

    /** A sine at [frequency], long enough to measure, with a clean loop over whole cycles. */
    private fun sineFont(frequency: Double = 440.0, rootKey: Int = 69, cycles: Int = 100): SoundFont {
        val framesPerCycle = (rate / frequency).toInt()
        val frames = framesPerCycle * cycles
        val data = ShortArray(frames) { i ->
            (sin(2.0 * PI * i / framesPerCycle) * 20000).toInt().toShort()
        }
        return KiteSynth.readFont(
            Sf2Builder().build(
                sampleFrames = data,
                samples = listOf(
                    Sf2Builder.SampleSpec("sine", 0, frames, 0, framesPerCycle * (cycles - 1), rate, rootKey),
                ),
                instrumentZones = listOf(
                    Sf2Builder.ZoneSpec(
                        sampleId = 0,
                        extras = listOf(Generator.SampleModes.id to 1),   // loop while held
                    ),
                ),
            ),
        )
    }

    private fun render(voice: Voice, frames: Int): FloatArray {
        val out = FloatArray(frames * 2)
        voice.render(out, 0, frames)
        return out
    }

    /** Counts upward zero crossings, which gives the frequency of anything periodic. */
    private fun frequencyOf(buffer: FloatArray, frames: Int): Double {
        var crossings = 0
        var previous = buffer[0]
        for (i in 1 until frames) {
            val value = buffer[i * 2]
            if (previous <= 0f && value > 0f) crossings++
            previous = value
        }
        return crossings * rate.toDouble() / frames
    }

    @Test
    fun aVoicePlayingItsOwnRootKeyComesOutAtTheRecordedPitch() {
        val font = sineFont(frequency = 440.0, rootKey = 69)
        val plan = font.voicesFor(0, 0, key = 69, velocity = 127).single()
        val voice = Voice()
        voice.start(plan, rate, channel = 0, age = 0)

        val frames = rate / 2
        val out = render(voice, frames)
        assertEquals(440.0, frequencyOf(out, frames), 3.0)
    }

    @Test
    fun anOctaveUpDoublesTheFrequency() {
        val font = sineFont(frequency = 440.0, rootKey = 69)
        val plan = font.voicesFor(0, 0, key = 81, velocity = 127).single()
        val voice = Voice()
        voice.start(plan, rate, channel = 0, age = 0)

        val frames = rate / 2
        assertEquals(880.0, frequencyOf(render(voice, frames), frames), 6.0)
    }

    @Test
    fun anOctaveDownHalvesIt() {
        val font = sineFont(frequency = 440.0, rootKey = 69)
        val plan = font.voicesFor(0, 0, key = 57, velocity = 127).single()
        val voice = Voice()
        voice.start(plan, rate, channel = 0, age = 0)

        val frames = rate / 2
        assertEquals(220.0, frequencyOf(render(voice, frames), frames), 3.0)
    }

    @Test
    fun aSemitoneUpIsTheTwelfthRootOfTwo() {
        val font = sineFont(frequency = 440.0, rootKey = 69)
        val plan = font.voicesFor(0, 0, key = 70, velocity = 127).single()
        val voice = Voice()
        voice.start(plan, rate, channel = 0, age = 0)

        val frames = rate / 2
        // A4 to A#4 is 440 * 2^(1/12) = 466.16 Hz.
        assertEquals(466.16, frequencyOf(render(voice, frames), frames), 4.0)
    }

    @Test
    fun aScaleTuningOfZeroPlaysEveryKeyAtOnePitch() {
        // A drum kit does this: every key is a different sound, not a different pitch.
        val font = KiteSynth.readFont(
            Sf2Builder().build(
                sampleFrames = ShortArray(4800) { i -> (sin(2.0 * PI * i / 109.0) * 20000).toInt().toShort() },
                samples = listOf(Sf2Builder.SampleSpec("s", 0, 4800, 0, 4360, rate, 60)),
                instrumentZones = listOf(
                    Sf2Builder.ZoneSpec(
                        sampleId = 0,
                        extras = listOf(Generator.ScaleTuning.id to 0, Generator.SampleModes.id to 1),
                    ),
                ),
            ),
        )
        fun pitchOf(key: Int): Double {
            val voice = Voice()
            voice.start(font.voicesFor(0, 0, key, 127).single(), rate, 0, 0)
            return frequencyOf(render(voice, rate / 4), rate / 4)
        }
        assertEquals(pitchOf(48), pitchOf(72), 4.0, "scale tuning of zero still changed the pitch")
    }

    @Test
    fun aQuietVoiceIsQuieterThanALoudOne() {
        val font = sineFont()
        fun peakAt(velocity: Int): Float {
            val voice = Voice()
            voice.start(font.voicesFor(0, 0, 69, velocity).single(), rate, 0, 0)
            return render(voice, 4800).maxOf { abs(it) }
        }
        assertTrue(peakAt(30) < peakAt(100), "velocity did not change the loudness")
        assertTrue(peakAt(100) < peakAt(127))
    }

    @Test
    fun attenuationMakesAVoiceQuieter() {
        fun peakWith(attenuationCb: Int): Float {
            val font = KiteSynth.readFont(
                Sf2Builder().build(
                    sampleFrames = ShortArray(4800) { 20000 },
                    samples = listOf(Sf2Builder.SampleSpec("flat", 0, 4800, 0, 4700, rate, 60)),
                    instrumentZones = listOf(
                        Sf2Builder.ZoneSpec(
                            sampleId = 0,
                            extras = listOf(Generator.InitialAttenuation.id to attenuationCb),
                        ),
                    ),
                ),
            )
            val voice = Voice()
            voice.start(font.voicesFor(0, 0, 60, 127).single(), rate, 0, 0)
            return render(voice, 2400).maxOf { abs(it) }
        }
        assertTrue(peakWith(0) > peakWith(200), "attenuation did nothing")
        assertTrue(peakWith(200) > peakWith(600))
    }

    @Test
    fun aVoiceThatIsNotLoopingStopsAtTheEndOfItsSample() {
        val short = KiteSynth.readFont(
            Sf2Builder().build(
                sampleFrames = ShortArray(480) { 10000 },
                samples = listOf(Sf2Builder.SampleSpec("blip", 0, 480, 0, 0, rate, 60)),
                instrumentZones = listOf(Sf2Builder.ZoneSpec(sampleId = 0)),
            ),
        )
        val voice = Voice()
        voice.start(short.voicesFor(0, 0, 60, 127).single(), rate, 0, 0)

        render(voice, 480)
        render(voice, 480)
        assertTrue(!voice.active, "a one-shot sample kept playing past its end")
    }

    @Test
    fun aLoopingVoiceKeepsGoingWellPastTheSampleLength() {
        val font = sineFont(cycles = 20)
        val voice = Voice()
        voice.start(font.voicesFor(0, 0, 69, 127).single(), rate, 0, 0)
        // Ten times the sample's own length.
        repeat(10) { render(voice, font.samples.single().frameCount) }
        assertTrue(voice.active, "the loop ran out")
    }

    @Test
    fun aLoopOfNoLengthPlaysOnceRatherThanBuzzing() {
        // A font with a broken loop is common, and looping over one frame is a tone at the
        // sample rate, which is the worst sound a synthesiser can make.
        val font = KiteSynth.readFont(
            Sf2Builder().build(
                sampleFrames = ShortArray(480) { 10000 },
                samples = listOf(Sf2Builder.SampleSpec("broken", 0, 480, 100, 100, rate, 60)),
                instrumentZones = listOf(
                    Sf2Builder.ZoneSpec(sampleId = 0, extras = listOf(Generator.SampleModes.id to 1)),
                ),
            ),
        )
        val voice = Voice()
        voice.start(font.voicesFor(0, 0, 60, 127).single(), rate, 0, 0)
        repeat(3) { render(voice, 480) }
        assertTrue(!voice.active, "a zero-length loop kept the voice alive")
    }

    @Test
    fun releasingAVoiceFadesItRatherThanCuttingIt() {
        val font = KiteSynth.readFont(
            Sf2Builder().build(
                sampleFrames = ShortArray(48000) { 20000 },
                samples = listOf(Sf2Builder.SampleSpec("flat", 0, 48000, 0, 47000, rate, 60)),
                instrumentZones = listOf(
                    Sf2Builder.ZoneSpec(
                        sampleId = 0,
                        extras = listOf(
                            Generator.SampleModes.id to 1,
                            // About 100 ms of release.
                            Generator.ReleaseVolEnv.id to (-3986 and 0xFFFF),
                        ),
                    ),
                ),
            ),
        )
        val voice = Voice()
        voice.start(font.voicesFor(0, 0, 60, 127).single(), rate, 0, 0)
        render(voice, 2400)

        voice.release()
        val fading = render(voice, 1200)
        val early = abs(fading[0])
        val late = abs(fading[fading.size - 2])
        assertTrue(late < early, "the release did not get quieter: $early then $late")
        assertTrue(voice.active, "the note stopped instantly instead of fading")
    }

    @Test
    fun aReleasedVoiceEventuallyStops() {
        val font = sineFont()
        val voice = Voice()
        voice.start(font.voicesFor(0, 0, 69, 127).single(), rate, 0, 0)
        render(voice, 480)
        voice.release()
        repeat(20) { render(voice, 4800) }
        assertTrue(!voice.active, "a released voice never finished")
    }

    @Test
    fun killingAVoiceStopsItAtOnce() {
        val font = sineFont()
        val voice = Voice()
        voice.start(font.voicesFor(0, 0, 69, 127).single(), rate, 0, 0)
        voice.kill()
        assertTrue(!voice.active)
        val out = render(voice, 480)
        assertTrue(out.all { it == 0f }, "a killed voice still made sound")
    }

    @Test
    fun anAttackRampsUpRatherThanStartingAtFullVolume() {
        val font = KiteSynth.readFont(
            Sf2Builder().build(
                sampleFrames = ShortArray(48000) { 20000 },
                samples = listOf(Sf2Builder.SampleSpec("flat", 0, 48000, 0, 47000, rate, 60)),
                instrumentZones = listOf(
                    Sf2Builder.ZoneSpec(
                        sampleId = 0,
                        extras = listOf(
                            Generator.SampleModes.id to 1,
                            // About 200 ms of attack.
                            Generator.AttackVolEnv.id to (-2786 and 0xFFFF),
                        ),
                    ),
                ),
            ),
        )
        val voice = Voice()
        voice.start(font.voicesFor(0, 0, 60, 127).single(), rate, 0, 0)
        val out = render(voice, 4800)
        assertTrue(abs(out[0]) < abs(out[out.size - 2]), "the attack did not ramp")
        assertTrue(abs(out[0]) < 0.05f, "the note started at full volume")
    }

    @Test
    fun aVoiceWithNoEnvelopeStartsSoundingImmediately() {
        // Every generator unset means an instant attack, not a slow one.
        val font = sineFont()
        val voice = Voice()
        voice.start(font.voicesFor(0, 0, 69, 127).single(), rate, 0, 0)
        val out = render(voice, 480)
        assertTrue(out.any { abs(it) > 0.01f }, "nothing came out of the first ten milliseconds")
    }

    @Test
    fun panMovesTheSoundBetweenTheSpeakers() {
        fun sidesWith(pan: Int): Pair<Float, Float> {
            val font = KiteSynth.readFont(
                Sf2Builder().build(
                    sampleFrames = ShortArray(4800) { 20000 },
                    samples = listOf(Sf2Builder.SampleSpec("flat", 0, 4800, 0, 4700, rate, 60)),
                    instrumentZones = listOf(
                        Sf2Builder.ZoneSpec(sampleId = 0, extras = listOf(Generator.Pan.id to (pan and 0xFFFF))),
                    ),
                ),
            )
            val voice = Voice()
            voice.start(font.voicesFor(0, 0, 60, 127).single(), rate, 0, 0)
            val out = render(voice, 2400)
            var left = 0f
            var right = 0f
            for (i in 0 until 2400) {
                left += abs(out[i * 2])
                right += abs(out[i * 2 + 1])
            }
            return left to right
        }

        val (centreLeft, centreRight) = sidesWith(0)
        assertEquals(centreLeft, centreRight, centreLeft * 0.01f, "centre was not centred")

        val (hardLeftL, hardLeftR) = sidesWith(-500)
        assertTrue(hardLeftL > hardLeftR * 10, "panning left did not move it left")

        val (hardRightL, hardRightR) = sidesWith(500)
        assertTrue(hardRightR > hardRightL * 10, "panning right did not move it right")
    }

    @Test
    fun renderingAddsToTheBufferRatherThanReplacingIt() {
        // Every voice shares one buffer. Writing instead of adding means only the last voice is
        // heard, which sounds like a monophonic synthesiser rather than a broken one.
        val font = sineFont()
        val out = FloatArray(960)
        val one = Voice().also { it.start(font.voicesFor(0, 0, 69, 127).single(), rate, 0, 0) }
        val two = Voice().also { it.start(font.voicesFor(0, 0, 69, 127).single(), rate, 0, 1) }

        one.render(out, 0, 480)
        val single = out.copyOf()
        two.render(out, 0, 480)

        assertTrue(
            out.indices.any { abs(out[it]) > abs(single[it]) + 0.001f },
            "the second voice replaced the first instead of adding to it",
        )
    }

    @Test
    fun twoRendersOfTheSameNoteAreIdentical() {
        val font = sineFont()
        fun once(): FloatArray {
            val voice = Voice()
            voice.start(font.voicesFor(0, 0, 69, 100).single(), rate, 0, 0)
            return render(voice, 4800)
        }
        assertTrue(once().contentEquals(once()), "the same note rendered differently twice")
    }

    @Test
    fun aVoiceThatWasNeverStartedMakesNoSound() {
        val out = render(Voice(), 480)
        assertTrue(out.all { it == 0f })
    }

    @Test
    fun aLowCutoffDarkensABrightSample() {
        fun roughness(cutoff: Int?): Double {
            // A square wave is all harmonics, so a filter has plenty to take away.
            val frames = 4800
            val data = ShortArray(frames) { if ((it / 24) % 2 == 0) 16000 else -16000 }
            val extras = mutableListOf(Generator.SampleModes.id to 1)
            if (cutoff != null) extras += Generator.InitialFilterFc.id to cutoff
            val font = KiteSynth.readFont(
                Sf2Builder().build(
                    sampleFrames = data,
                    samples = listOf(Sf2Builder.SampleSpec("square", 0, frames, 0, frames, rate, 60)),
                    instrumentZones = listOf(Sf2Builder.ZoneSpec(sampleId = 0, extras = extras)),
                ),
            )
            val voice = Voice()
            voice.start(font.voicesFor(0, 0, 60, 127).single(), rate, 0, 0)
            val out = render(voice, 4800)
            var sum = 0.0
            for (i in 1 until 4800) {
                val d = out[i * 2] - out[(i - 1) * 2]
                sum += d * d
            }
            return sum
        }
        assertTrue(roughness(6000) < roughness(null) / 4, "a 260 Hz cutoff should take most of the edges away")
    }

    /** A font over [data], looping all of it, with [extras] on its one zone. */
    private fun fontWith(data: ShortArray, extras: List<Pair<Int, Int>>): SoundFont = KiteSynth.readFont(
        Sf2Builder().build(
            sampleFrames = data,
            samples = listOf(Sf2Builder.SampleSpec("tone", 0, data.size, 0, data.size, rate, 69)),
            instrumentZones = listOf(Sf2Builder.ZoneSpec(sampleId = 0, extras = listOf(Generator.SampleModes.id to 1) + extras)),
        ),
    )

    private fun crossings(buffer: FloatArray, from: Int, to: Int): Int {
        var count = 0
        for (i in from + 1 until to) if (buffer[(i - 1) * 2] <= 0f && buffer[i * 2] > 0f) count++
        return count
    }

    private fun roughness(buffer: FloatArray, from: Int, to: Int): Double {
        var sum = 0.0
        for (i in from + 1 until to) {
            val d = buffer[i * 2] - buffer[(i - 1) * 2]
            sum += d * d
        }
        return sum
    }

    @Test
    fun theVibratoLfoMovesThePitch() {
        // A 480 Hz sine, exactly one hundred frames a cycle, and a 5 Hz vibrato a semitone deep.
        val data = ShortArray(100 * 100) { (sin(2.0 * PI * it / 100) * 20000).toInt().toShort() }
        val fiveHertz = (1200 * kotlin.math.log2(5.0 / 8.176)).toInt()
        val font = fontWith(data, listOf(Generator.VibLfoToPitch.id to 100, Generator.FreqVibLfo.id to (fiveHertz and 0xFFFF)))
        val voice = Voice()
        voice.start(font.voicesFor(0, 0, 69, 127).single(), rate, 0, 0)
        val out = render(voice, 9600)
        val rising = crossings(out, 0, 4800).toDouble()
        val falling = crossings(out, 4800, 9600).toDouble()
        assertTrue(rising / falling > 1.03, "the half above sounded $rising cycles, the half below $falling")
    }

    @Test
    fun theModulationEnvelopeSweepsTheFilterShut() {
        val frames = 4800
        val square = ShortArray(frames) { if ((it / 24) % 2 == 0) 16000 else -16000 }
        val font = fontWith(
            square,
            listOf(
                Generator.InitialFilterFc.id to 5000,
                Generator.ModEnvToFilterFc.id to 3000,
                Generator.DecayModEnv.id to 0,
                Generator.SustainModEnv.id to 1000,
            ),
        )
        val voice = Voice()
        voice.start(font.voicesFor(0, 0, 69, 127).single(), rate, 0, 0)
        val out = render(voice, 48_000)
        assertTrue(roughness(out, 38_400, 40_800) < roughness(out, 2_400, 4_800) / 2, "the filter should close as the envelope falls")
    }
}
