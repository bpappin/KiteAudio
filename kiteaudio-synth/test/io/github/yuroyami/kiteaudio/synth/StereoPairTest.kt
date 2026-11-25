package io.github.yuroyami.kiteaudio.synth

import io.github.yuroyami.kiteaudio.synth.StereoFonts.FRAMES
import io.github.yuroyami.kiteaudio.synth.StereoFonts.LEFT
import io.github.yuroyami.kiteaudio.synth.StereoFonts.MONO
import io.github.yuroyami.kiteaudio.synth.StereoFonts.RIGHT
import io.github.yuroyami.kiteaudio.synth.StereoFonts.Side
import io.github.yuroyami.kiteaudio.synth.StereoFonts.crossings
import io.github.yuroyami.kiteaudio.synth.StereoFonts.font
import io.github.yuroyami.kiteaudio.synth.StereoFonts.frequency
import io.github.yuroyami.kiteaudio.synth.StereoFonts.joined
import io.github.yuroyami.kiteaudio.synth.StereoFonts.left
import io.github.yuroyami.kiteaudio.synth.StereoFonts.leftPitch
import io.github.yuroyami.kiteaudio.synth.StereoFonts.leftTune
import io.github.yuroyami.kiteaudio.synth.StereoFonts.mono
import io.github.yuroyami.kiteaudio.synth.StereoFonts.perform
import io.github.yuroyami.kiteaudio.synth.StereoFonts.right
import io.github.yuroyami.kiteaudio.synth.StereoFonts.rightPitch
import io.github.yuroyami.kiteaudio.synth.StereoFonts.rightTune
import io.github.yuroyami.kiteaudio.synth.StereoFonts.synth
import io.github.yuroyami.kiteaudio.synth.StereoFonts.take
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A left and a right sample that link to each other play as one pair: both at the pitch of the
 * right side, in step, each with its own pan and level (SoundFont 2.04, section 7.10).
 *
 * Every pitch here is measured from the output, from the zero crossings of the sine each sample holds.
 */
class StereoPairTest {

    /** Two semitones up from the samples' 1000 Hz: the pitch the right zone's coarse tune asks for. */
    private val rightHz = 1000.0 * 2.0.pow(2.0 / 12.0)
    private val rightUp = listOf(Generator.CoarseTune.id to 2)

    /** Plays one held note for [frames] frames. */
    private fun hold(font: SoundFont, frames: Int = 9_600, velocity: Int = 100): FloatArray {
        val synth = synth(font)
        val parts = ArrayList<FloatArray>()
        synth.noteOn(0, 60, velocity)
        take(synth, frames, parts)
        return joined(parts)
    }

    private fun assertInStep(out: FloatArray, from: Int, to: Int, what: String, tolerance: Double = 1e-6) {
        val left = crossings(out, 0, from, to)
        val right = crossings(out, 1, from, to)
        assertTrue(right.size > 20, "$what: the right channel played only ${right.size} cycles")
        assertEquals(right.size, left.size, "$what: the channels played different numbers of cycles")
        for (i in right.indices) {
            assertEquals(right[i], left[i], tolerance, "$what: crossing $i is at ${left[i]} on the left and ${right[i]} on the right")
        }
    }

    private fun rms(out: FloatArray, channel: Int, from: Int, to: Int): Double {
        var sum = 0.0
        for (frame in from until to) sum += out[frame * 2 + channel].toDouble().pow(2)
        return sqrt(sum / (to - from))
    }

    @Test
    fun bothSidesOfAPairPlayAtThePitchOfTheRightSide() {
        // On its own, the left zone would play 1170 cents above the samples' pitch, at about 1964 Hz.
        val out = hold(font(left(leftTune), right(rightUp)))
        assertEquals(rightHz, frequency(crossings(out, 1, 960, 9_600)), 0.5, "the right side")
        assertEquals(rightHz, frequency(crossings(out, 0, 960, 9_600)), 0.5, "the left side kept its own pitch")
    }

    @Test
    fun theSidesStayTogetherThroughABendModulationAndTheRelease() {
        // StereoFonts.perform: the note, then a bend and the mod wheel at 4800, then the release at 9600.
        val out = perform(font(left(leftPitch), right(rightPitch)))
        assertInStep(out, 480, 4_800, "held")
        assertInStep(out, 4_800, 9_600, "bent and modulated")
        assertInStep(out, 9_600, 19_200, "released")

        // The right side's modulation envelope really moves the pitch: its attack raises it while the key is held.
        val held = crossings(out, 1, 480, 4_800)
        val firstPeriod = held[1] - held[0]
        val lastPeriod = held[held.size - 1] - held[held.size - 2]
        assertTrue(lastPeriod < firstPeriod * 0.97, "the pitch did not rise: $firstPeriod then $lastPeriod frames")
    }

    @Test
    fun theLeftSidePlaysExactlyAsIfItHadTheRightSidesPitchValues() {
        // The left side's own level, cutoff and release stay its own. Only the pitch values come from the right.
        val leftOwn = listOf(
            Generator.InitialAttenuation.id to 60,
            Generator.InitialFilterFc.id to 9_000,
            Generator.ReleaseVolEnv.id to -2_400,
        )
        val paired = perform(font(left(leftTune + leftOwn), right(rightPitch)))
        val reference = perform(font(Side(rightTune + leftOwn, MONO, 0), right(rightPitch).mono()))
        assertTrue(paired.any { it != 0f })
        assertTrue(paired.contentEquals(reference), "the pair did not play as the left side with the right side's pitch")
    }

    @Test
    fun theSidesStartOnTheSameFrameAndStayInStepOverALongNote() {
        // A quieter left side, so the channels differ in level and must still agree in phase.
        val font = font(left(leftPitch + (Generator.InitialAttenuation.id to 60)), right(rightPitch))
        val synth = synth(font)
        synth.noteOn(0, 60, 100, atFrame = 100)
        val start = FloatArray(960 * 2)
        synth.render(start, 960)
        fun firstSound(channel: Int): Int = (0 until 960).first { start[it * 2 + channel] != 0f }
        assertEquals(firstSound(1), firstSound(0), "the sides started on different frames")

        val block = FloatArray(4_800 * 2)
        repeat(200) { synth.render(block, 4_800) }   // twenty seconds
        assertInStep(block, 0, 4_800, "after twenty seconds", tolerance = 1e-4)
    }

    @Test
    fun eachSideKeepsItsOwnPanAndItsOwnVolume() {
        // The pans are the other way round from the sides' names, and the left side is 6 dB quieter.
        val font = font(
            Side(leftTune + (Generator.InitialAttenuation.id to 60), LEFT, 1, pan = 500),
            Side(rightTune, RIGHT, 0, pan = -500),
        )
        val out = hold(font, velocity = 127)
        val leftSide = rms(out, 1, 960, 9_600)
        val rightSide = rms(out, 0, 960, 9_600)
        assertEquals(Units.gainOf(60.0), leftSide / rightSide, 0.002, "the left side lost its own level or pan")
        assertInStep(out, 960, 9_600, "panned the other way", tolerance = 1e-4)
    }

    @Test
    fun aPairWithUnequalLengthsStillPlaysAtThePitchOfTheRightSide() {
        val out = hold(font(Side(leftTune, LEFT, 1, frames = FRAMES / 2), right(rightUp)))
        assertEquals(rightHz, frequency(crossings(out, 0, 960, 9_600)), 0.5)
    }

    @Test
    fun theRightSidesSampleRateSetsTheStepOfBothSides() {
        // On its own, a left sample recorded at half the rate would play an octave lower.
        val out = hold(font(Side(emptyList(), LEFT, 1, rate = 24_000), right(rightUp)))
        assertEquals(rightHz, frequency(crossings(out, 0, 960, 9_600)), 0.5)
    }

    @Test
    fun aBrokenOrUnplayablePairPlaysEachZoneAsItsOwnVoice() {
        for ((name, case) in StereoFonts.fallbacks) {
            val (left, right, split) = case
            val played = perform(font(left, right, split))
            val alone = perform(font(left.mono(), right.mono(), split))
            assertTrue(played.any { it != 0f }, "$name made no sound")
            assertTrue(played.contentEquals(alone), "$name did not play as separate voices")
        }
    }

    @Test
    fun aLeftSideWithItsOwnPitchStillSoundsAtItsOwnPitchWhenThePairIsBroken() {
        // A one-way link: the left zone keeps its own 1170 cents, as it always did. No vibrato, so the pitch is steady.
        val ownTune = listOf(Generator.CoarseTune.id to 7, Generator.FineTune.id to -30, Generator.OverridingRootKey.id to 55)
        val out = hold(font(left(ownTune), right(rightUp, link = 2)))
        assertEquals(1000.0 * 2.0.pow(1170.0 / 1200.0), frequency(crossings(out, 0, 960, 9_600)), 1.0)
        assertEquals(rightHz, frequency(crossings(out, 1, 960, 9_600)), 0.5)
    }

    @Test
    fun theSurvivorOfAStolenSideKeepsThePitchOfThePair() {
        // Two voices only, and a quieter right side, so the note on key 72 steals the right side.
        val quiet = Generator.InitialAttenuation.id to 200
        fun play(font: SoundFont): Pair<FloatArray, Synth> {
            val synth = synth(font, maxVoices = 2)
            val parts = ArrayList<FloatArray>()
            synth.noteOn(0, 60, 100)
            take(synth, 2_400, parts)
            synth.noteOn(0, 72, 100)
            take(synth, 2_400, parts)
            assertEquals(listOf(60, 72), synth.soundingKeys.sorted(), "the pair lost both sides, or none")
            synth.pitchBend(0, 3_000)
            synth.controlChange(0, 1, 90)
            take(synth, 4_800, parts)
            synth.noteOff(0, 60)
            take(synth, 9_600, parts)
            return joined(parts) to synth
        }
        val (paired, _) = play(font(Side(leftPitch, LEFT, 1, keyHigh = 71), Side(rightPitch + quiet, RIGHT, 0, keyHigh = 71), extraKey = 72))
        // The same left side with the right side's pitch values, and no pair to follow.
        val (reference, _) = play(
            font(Side(rightPitch, MONO, 0, keyHigh = 71), Side(rightPitch + quiet, MONO, 0, keyHigh = 71), extraKey = 72),
        )
        val survivor = (2_400 until 4_800).sumOf { abs(paired[it * 2].toDouble()) }
        assertTrue(survivor > 1.0, "the left side did not survive the steal")
        assertTrue(paired.contentEquals(reference), "the survivor left the pitch of the pair")
    }
}
