package io.github.yuroyami.kiteaudio.synth

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * [Interpolation.BandLimited] against the analytic spectrum of a resampled sine.
 *
 * A sine at [phi] times the sample's Nyquist limit, read at a step of `s` frames, comes out at `s * phi`
 * times the output's Nyquist limit. Above 1 that frequency does not exist in the output, and a reader
 * without a low-pass folds it back to `2 - s * phi`. A band-limited reader leaves nothing there.
 *
 * Levels are in dB against the same sine read at a step of 1 with [Interpolation.Linear], which returns
 * the stored frames exactly. Every font plays key 60, its root, so the sample rate alone sets the step.
 */
class BandLimitedTest {

    private val rate = 48_000
    private val amplitude = 30_000.0
    private val skip = 512
    private val count = 2048

    // The bounds this test holds the kernel to. The README states the same numbers.
    /** Aliases that land below this fraction of the output's Nyquist limit are fully attenuated. */
    private val transitionEdge = 0.8
    private val aliasBoundDb = -96.0
    private val passbandToleranceDb = 0.01

    private fun font(data: ShortArray, sampleRate: Int, loop: IntRange? = null, sampleMode: Int = 0, extras: List<Pair<Int, Int>> = emptyList()): SoundFont =
        KiteSynth.readFont(
            Sf2Builder().buildFont(
                sampleFrames = ShortArray(data.size + 46) { if (it < data.size) data[it] else 0 },
                samples = listOf(
                    Sf2Builder.SampleSpec("s", 0, data.size, loop?.first ?: 0, loop?.let { it.last + 1 } ?: 0, sampleRate, 60),
                ),
                instruments = listOf(
                    Sf2Builder.InstrumentSpec("i", listOf(Sf2Builder.ZoneSpec(0, extras = listOf(Generator.SampleModes.id to sampleMode) + extras))),
                ),
                presets = listOf(Sf2Builder.PresetDef("p", 0, 0, listOf(Sf2Builder.PresetZoneSpec(0)))),
            ),
        )

    private fun sine(phi: Double, frames: Int): ShortArray = ShortArray(frames) { (amplitude * sin(PI * phi * it)).roundToInt().toShort() }

    /** The sample rate that makes key 60 read [step] frames per output frame. */
    private fun rateFor(step: Double): Int = (step * rate).roundToInt()

    /** The left channel of one voice, with no reverb and no chorus. */
    private fun render(
        font: SoundFont,
        interpolation: Interpolation,
        frames: Int,
        boundary: LoopBoundary = LoopBoundary.GuardFrames,
        events: (Synth) -> Unit = {},
    ): DoubleArray {
        val synth = Synth(font, rate, maxVoices = 1).apply {
            reverb.wet = 0.0
            reverb.dry = 1.0
            chorus.level = 0.0
            this.interpolation = interpolation
            loopBoundary = boundary
        }
        synth.noteOn(0, 60, 127)
        events(synth)
        val out = FloatArray(frames * 2)
        synth.render(out, frames)
        return DoubleArray(frames) { out[it * 2].toDouble() }
    }

    /** The amplitude at [cycles] per frame over the analysis window, from a direct DFT with a Hann window. */
    private fun level(y: DoubleArray, cycles: Double): Double {
        var re = 0.0
        var im = 0.0
        var weights = 0.0
        for (m in 0 until count) {
            val w = 0.5 - 0.5 * cos(2 * PI * m / (count - 1))
            val v = w * y[skip + m]
            re += v * cos(2 * PI * cycles * m)
            im -= v * sin(2 * PI * cycles * m)
            weights += w
        }
        return 2 * sqrt(re * re + im * im) / weights
    }

    /** The level of the stored sine, read at a step of 1. */
    private val fullScale: Double by lazy {
        val y = render(font(sine(0.1, 4000), rate), Interpolation.Linear, skip + count)
        level(y, 0.05)
    }

    private fun db(value: Double): Double = 20 * log10(max(value, 1e-12) / fullScale)

    /** The level of the alias of a sine at [phi] read at [step], in dB. */
    private fun aliasDb(interpolation: Interpolation, step: Double, phi: Double): Double {
        val frames = ((skip + count + 64) * step + 200).toInt()
        val y = render(font(sine(phi, frames), rateFor(step)), interpolation, skip + count)
        return db(level(y, (2 - step * phi) / 2))
    }

    /** The level of a sine at [phi] read at [step], at the frequency it should come out at, in dB. */
    private fun toneDb(interpolation: Interpolation, step: Double, phi: Double): Double {
        val frames = ((skip + count + 64) * step + 200).toInt()
        val y = render(font(sine(phi, frames), rateFor(step)), interpolation, skip + count)
        return db(level(y, step * phi / 2))
    }

    @Test
    fun theBandLimitedReadKeepsAliasesBelowTheBoundAtFixedSteps() {
        for (step in listOf(1.25, 1.5, 2.0, 3.0, 4.0)) {
            for (landing in listOf(0.2, 0.5, 0.78)) {
                val phi = (2 - landing) / step
                if (phi >= 0.99) continue
                val band = aliasDb(Interpolation.BandLimited, step, phi)
                val cubic = aliasDb(Interpolation.Cubic, step, phi)
                println("Alias at step $step, landing at $landing of Nyquist: BandLimited ${tenths(band)} dB, Cubic ${tenths(cubic)} dB")
                assertTrue(band < aliasBoundDb, "step $step, landing $landing: the alias is at $band dB")
                // The same signal through cubic shows the alias, so the measurement can see one.
                assertTrue(cubic > -40.0, "step $step, landing $landing: cubic left only $cubic dB, the test cannot see aliases")
            }
        }
    }

    @Test
    fun aSlowSweepPastAStepOfOneLeavesNoAlias() {
        // A sine at 0.45 of the sample's Nyquist, looped over a whole number of cycles. The modulation
        // envelope raises the pitch by two octaves over half a second, so the step runs from 1 to 4.
        val phi = 0.45
        val data = sine(phi, 1500)
        val extras = listOf(Generator.ModEnvToPitch.id to 2400, Generator.AttackModEnv.id to -1200)
        val font = font(data, rate, loop = 1000 until 1400, sampleMode = 1, extras = extras)
        // From a step of 1.2 / phi on, the alias lands below the transition edge. That step comes at
        // 0.354 s. From 0.5 s on, the step stays at 4.
        val silent = 17_500 until 30_000
        fun aliasRms(interpolation: Interpolation): Double {
            val y = render(font, interpolation, silent.last + 1)
            val power = silent.sumOf { y[it] * y[it] } / (silent.last - silent.first + 1)
            return db(sqrt(2 * power))
        }
        val band = aliasRms(Interpolation.BandLimited)
        val cubic = aliasRms(Interpolation.Cubic)
        println("Sweep from a step of 1 to 4: BandLimited ${tenths(band)} dB, Cubic ${tenths(cubic)} dB")
        // The whole output counts here, so the 16-bit noise of the source sets a floor near -100 dB.
        assertTrue(band < -90.0, "the sweep left $band dB where the output should be silent")
        assertTrue(cubic > -20.0, "cubic left only $cubic dB, the test cannot see aliases")
    }

    @Test
    fun aStepBelowOneRemovesTheImagesOfTheSample() {
        // Read at half a frame per output frame, a sine at 0.7 has an image at (2 - 0.7) / 2 = 0.65.
        val step = 0.5
        val phi = 0.7
        fun imageDb(interpolation: Interpolation): Double {
            val frames = ((skip + count + 64) * step + 200).toInt()
            val y = render(font(sine(phi, frames), rateFor(step)), interpolation, skip + count)
            return db(level(y, step * (2 - phi) / 2))
        }
        val band = imageDb(Interpolation.BandLimited)
        val cubic = imageDb(Interpolation.Cubic)
        println("Image at step 0.5: BandLimited ${tenths(band)} dB, Cubic ${tenths(cubic)} dB")
        assertTrue(band < aliasBoundDb, "the image is at $band dB")
        assertTrue(cubic > -60.0, "cubic left only $cubic dB, the test cannot see images")
    }

    @Test
    fun aToneBelowTheCutoffKeepsItsLevel() {
        // Step and sine, chosen so the tone comes out at most at the transition edge.
        val cases = listOf(0.5 to 0.7, 0.75 to 0.8, 1.5 to 0.5, 2.0 to 0.39, 4.0 to 0.19)
        for ((step, phi) in cases) {
            val band = toneDb(Interpolation.BandLimited, step, phi)
            println("Tone at step $step, phi $phi: BandLimited ${(band * 1e4).roundToInt() / 1e4} dB")
            assertTrue(abs(band) < passbandToleranceDb, "step $step, phi $phi: the tone moved by $band dB")
        }
    }

    @Test
    fun aboveTheLargestStepTheCutoffStopsFalling() {
        // At a step of 4 this alias is gone. At 8 the cutoff stays where 4 put it, so the same alias returns.
        assertTrue(aliasDb(Interpolation.BandLimited, 4.0, 0.36) < aliasBoundDb)
        val past = aliasDb(Interpolation.BandLimited, 8.0, 0.18)
        assertTrue(past > -3.0, "at a step of 8 the alias is at $past dB, but the kernel should stop growing at 4")
    }

    // ------------------------------------------------------------------ loops and edges

    /** A pattern of [length] frames that repeats, with energy at every frequency. */
    private fun pattern(length: Int): ShortArray {
        var seed = 0x2545F491L + length
        return ShortArray(length) {
            seed = seed * 6364136223846793005L + 1442695040888963407L
            ((seed ushr 33).toInt() % 20_000).toShort()
        }
    }

    /** Frames that are not the loop's content, so a read of one shows in the output. */
    private fun other(frame: Int): Short = (if (frame % 2 == 0) 25_000 else -25_000).toShort()

    private val loopStart = 200

    /**
     * A loop of [loopLength] frames against the same content stored without a loop. The frames after the
     * loop end are other content, and so are the frames before the loop start when [garbageBefore] is set.
     */
    private fun loopAgainstReference(loopLength: Int, step: Double, boundary: LoopBoundary, garbageBefore: Boolean): Double {
        val content = pattern(loopLength)
        val loopEnd = loopStart + loopLength
        fun periodic(frame: Int) = content[(frame - loopStart).mod(loopLength)]
        val looped = ShortArray(loopEnd + 300) {
            when {
                it >= loopEnd -> other(it)
                it < loopStart && garbageBefore -> other(it)
                else -> periodic(it)
            }
        }
        val frames = 3000
        val stored = ShortArray((frames * step).toInt() + 500) { periodic(it) }
        val withLoop = render(font(looped, rateFor(step), loop = loopStart until loopEnd, sampleMode = 1), Interpolation.BandLimited, frames, boundary)
        val withoutLoop = render(font(stored, rateFor(step)), Interpolation.BandLimited, frames, boundary)
        // Before the first wrap, the frames before the loop start are the attack, and other content can be there.
        val from = if (garbageBefore) (loopEnd / step).toInt() + 2 else 0
        var most = 0.0
        for (frame in from until frames) most = max(most, abs(withLoop[frame] - withoutLoop[frame]))
        assertTrue(withLoop.sumOf { abs(it) } > 10.0, "the looped voice is silent")
        return most
    }

    @Test
    fun aLoopOfContinuousContentReadsAsIfItHadNoLoop() {
        // 64 frames is longer than the kernel at a step of 1, 8 and 3 are shorter than it at any step,
        // and 2 is shorter than a step of 4. The kernel wraps around such a loop many times.
        for (loopLength in listOf(64, 8, 3, 2)) {
            for (step in listOf(0.75, 2.5, 4.0, 5.0)) {
                val guards = loopAgainstReference(loopLength, step, LoopBoundary.GuardFrames, garbageBefore = false)
                assertTrue(guards < 1e-5, "GuardFrames, loop $loopLength, step $step: the loop is off by $guards")
                val wrapped = loopAgainstReference(loopLength, step, LoopBoundary.Wrapped, garbageBefore = true)
                assertTrue(wrapped < 1e-5, "Wrapped, loop $loopLength, step $step: the loop is off by $wrapped")
            }
        }
    }

    @Test
    fun aReleasedLoopReadsOnIntoATailThatContinuesIt() {
        // The tail continues the loop's content, so the release itself must not click. A ten-second
        // release keeps both voices at the same level for the whole render.
        val loopLength = 48
        val content = pattern(loopLength)
        fun periodic(frame: Int) = content[(frame - loopStart).mod(loopLength)]
        val loopEnd = loopStart + loopLength
        val extras = listOf(Generator.ReleaseVolEnv.id to 4000)
        for (step in listOf(0.75, 3.0)) {
            for (boundary in LoopBoundary.entries) {
                val frames = 2500
                val release = 1500
                // The tail is long enough that the taps never reach its end during the render.
                val looped = ShortArray(loopEnd + (frames * step).toInt() + 200) { periodic(it) }
                val stored = ShortArray((frames * step).toInt() + 500) { periodic(it) }
                val withLoop = render(
                    font(looped, rateFor(step), loop = loopStart until loopEnd, sampleMode = 3, extras = extras),
                    Interpolation.BandLimited, frames, boundary,
                ) { it.noteOff(0, 60, atFrame = release) }
                val withoutLoop = render(font(stored, rateFor(step), extras = extras), Interpolation.BandLimited, frames, boundary) {
                    it.noteOff(0, 60, atFrame = release)
                }
                var most = 0.0
                for (frame in 0 until frames) most = max(most, abs(withLoop[frame] - withoutLoop[frame]))
                assertTrue(most < 1e-5, "$boundary, step $step: the released loop is off by $most")
            }
        }
    }

    @Test
    fun aOneShotSampleEndsOnTheFrameCubicEndsOn() {
        // A loud sample that stops at full level. The taps past its last frame read the edge, and the
        // voice ends when its position passes the end, as with cubic.
        for (step in listOf(0.6, 1.0, 3.7)) {
            val data = ShortArray(700) { if (it < 350) 30_000 else -30_000 }
            val font = font(data, rateFor(step))
            val band = render(font, Interpolation.BandLimited, 2000)
            val cubic = render(font, Interpolation.Cubic, 2000)
            assertTrue(band.all { it.isFinite() }, "step $step")
            val bandEnd = band.indexOfLast { it != 0.0 }
            val cubicEnd = cubic.indexOfLast { it != 0.0 }
            assertTrue(bandEnd == cubicEnd, "step $step: band-limited ends at $bandEnd, cubic at $cubicEnd")
            assertTrue(abs(band[bandEnd]) > 0.1, "step $step: the last frame is not the edge of the sample")
        }
    }

    // ------------------------------------------------------------------ a moving step

    @Test
    fun aStepChangeInTheMiddleOfANoteDoesNotClick() {
        // A slow sine, which cubic reads almost exactly, through bends that take the step from 0.83 up
        // past 1 to 3.3 and down to 0.21. The kernel changes width at each bend, so a click would show
        // as a difference from cubic.
        val phi = 0.02
        val data = sine(phi, 2100)
        val font = font(data, 40_000, loop = 1000 until 2000, sampleMode = 1)
        fun bent(interpolation: Interpolation): DoubleArray = render(font, interpolation, 6000) { synth ->
            synth.controlChange(0, 101, 0)
            synth.controlChange(0, 100, 0)
            synth.controlChange(0, 6, 24)
            synth.pitchBend(0, 8191, atFrame = 1003)
            synth.pitchBend(0, -8192, atFrame = 2511)
            synth.pitchBend(0, 4000, atFrame = 4007)
        }
        val band = bent(Interpolation.BandLimited)
        val cubic = bent(Interpolation.Cubic)
        var most = 0.0
        for (frame in 100 until band.size) most = max(most, abs(band[frame] - cubic[frame]))
        val peak = band.maxOf { abs(it) }
        assertTrue(peak > 0.1, "the note is silent")
        assertTrue(most < 1e-4, "band-limited and cubic differ by $most at a bend")
    }

    private fun tenths(value: Double): Double = (value * 10).roundToInt() / 10.0
}
