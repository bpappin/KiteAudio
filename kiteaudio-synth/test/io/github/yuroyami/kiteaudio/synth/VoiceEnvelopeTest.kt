package io.github.yuroyami.kiteaudio.synth

import kotlin.math.abs
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VoiceEnvelopeTest {
    private val rate = 48_000

    private fun near(wanted: Double, got: Double, slack: Double, what: String = "") =
        assertTrue(abs(wanted - got) <= slack, "$what wanted $wanted, got $got")

    /** A voice over a flat, looped sample, so its level is the envelope and nothing else. */
    private fun voiceWith(
        key: Int = 60,
        attackTimecents: Int = -12000,
        holdTimecents: Int = -12000,
        decayTimecents: Int = -12000,
        sustainCb: Int = 0,
        releaseTimecents: Int = -12000,
        keynumToVolEnvHold: Int = 0,
        keynumToVolEnvDecay: Int = 0,
        sampleLevel: Short = 16000,
    ): Voice {
        val font = KiteSynth.readFont(
            Sf2Builder().build(
                sampleFrames = ShortArray(4800) { sampleLevel },
                samples = listOf(Sf2Builder.SampleSpec("flat", 0, 4800, 100, 4700, rate, 60)),
                instrumentZones = listOf(
                    Sf2Builder.ZoneSpec(
                        sampleId = 0,
                        extras = listOf(
                            Generator.SampleModes.id to 1,
                            Generator.AttackVolEnv.id to (attackTimecents and 0xFFFF),
                            Generator.HoldVolEnv.id to (holdTimecents and 0xFFFF),
                            Generator.DecayVolEnv.id to (decayTimecents and 0xFFFF),
                            Generator.SustainVolEnv.id to sustainCb,
                            Generator.ReleaseVolEnv.id to (releaseTimecents and 0xFFFF),
                            Generator.KeynumToVolEnvHold.id to (keynumToVolEnvHold and 0xFFFF),
                            Generator.KeynumToVolEnvDecay.id to (keynumToVolEnvDecay and 0xFFFF),
                        ),
                    ),
                ),
            ),
        )
        return Voice().also { it.start(font.voicesFor(0, 0, key, 127).single(), rate, channel = 0, age = 0) }
    }

    /** Renders in 512-frame blocks, the way a callback does. */
    private fun render(voice: Voice, frames: Int) {
        val block = FloatArray(512 * 2)
        var left = frames
        while (left > 0) {
            val n = minOf(512, left)
            voice.render(block, 0, n)
            left -= n
        }
    }

    @Test
    fun theGainTableIsTheFormatsCentibels() {
        near(1.0, Units.gainOf(0.0), 1e-9)
        near(10.0.pow(-480 / 200.0), Units.gainOf(480.0), 1e-6)
        near((Units.gainOf(100.0) + Units.gainOf(101.0)) / 2, Units.gainOf(100.5), 1e-6, "between entries it interpolates")
        assertEquals(0.0, Units.gainOf(960.0))
        assertEquals(0.0, Units.gainOf(2000.0))
    }

    @Test
    fun decayIsLinearInDecibels() {
        // A one second decay to silence: after half a second the level is -48 dB, not half the amplitude.
        val voice = voiceWith(decayTimecents = 0, sustainCb = 1440)
        render(voice, 24_000)
        near(Units.gainOf(480.0), voice.currentGain, 0.002)
    }

    @Test
    fun releaseFallsAtTheSameRateFromWhereverItIs() {
        // A one second release falls 96 dB a second. Measured on the envelope, because the voice
        // itself ends early once it is too quiet to hear.
        val fromFull = voiceWith(releaseTimecents = 0, sustainCb = 0)
        render(fromFull, 2400); fromFull.release()
        render(fromFull, 24_000)
        near(Units.gainOf(480.0), fromFull.currentGain, 0.001, "half a second down from full")
        val fromHalf = voiceWith(releaseTimecents = 0, sustainCb = 480)
        render(fromHalf, 2400); fromHalf.release()
        render(fromHalf, 12_000)
        near(Units.gainOf(720.0), fromHalf.currentGain, 0.0005, "a quarter second down from half way")
        render(fromFull, 24_480); assertFalse(fromFull.active, "gone by one second")
        render(fromHalf, 12_480); assertFalse(fromHalf.active, "half the distance takes half the time at most")
    }

    @Test
    fun aQuietReleaseKeepsItsFullEnvelopeLifetime() {
        // A quiet window cannot prove that later samples or controller changes remain quiet.
        val quiet = voiceWith(releaseTimecents = 2786, sustainCb = 0, sampleLevel = 328)
        render(quiet, 2400); quiet.release()
        render(quiet, 48_000 * 24 / 10); assertTrue(quiet.active, "ended while it could still be heard")
        render(quiet, 48_000 * 4 / 10); assertTrue(quiet.active, "quietness prematurely ended the envelope")
        render(quiet, 48_000 * 3); assertFalse(quiet.active, "the completed envelope kept running")
    }

    @Test
    fun aLoudReleaseIsNotCutShort() {
        // At full scale the sound stays over -90 dB for 90 of the release's 96 dB.
        val loud = voiceWith(releaseTimecents = 0, sustainCb = 0, sampleLevel = 32_767)
        render(loud, 2400); loud.release()
        render(loud, 48_000 * 9 / 10); assertTrue(loud.active, "a loud release ended early")
    }

    @Test
    fun holdAndDecayScaleWithTheKey() {
        val at60 = voiceWith(key = 60, decayTimecents = 0, sustainCb = 1440, keynumToVolEnvDecay = 100)
        val at48 = voiceWith(key = 48, decayTimecents = 0, sustainCb = 1440, keynumToVolEnvDecay = 100)
        render(at60, 24_000); render(at48, 24_000)
        near(Units.gainOf(480.0), at60.currentGain, 0.002, "half of a one second decay")
        near(Units.gainOf(240.0), at48.currentGain, 0.004, "twelve keys lower is twelve hundred timecents longer, so half as far")
    }

    @Test
    fun aLongReleaseIsNotCappedAtTenSeconds() {
        val voice = voiceWith(releaseTimecents = 5186, sustainCb = 0)
        render(voice, 480); voice.release()
        render(voice, 48_000 * 12)
        assertTrue(voice.active, "twelve seconds into a twenty second release")
    }

    @Test
    fun aSustainAtSilenceEndsTheNoteWhenTheDecayGetsThere() {
        val voice = voiceWith(decayTimecents = 0, sustainCb = 1000)
        render(voice, 48_000 + 960)
        assertFalse(voice.active, "a note decaying into silence does not wait for the key")
    }

    @Test
    fun theAttackRisesInAmplitudeWithoutSteps() {
        val voice = voiceWith(attackTimecents = 0, sustainCb = 0)
        render(voice, 24_000)
        near(0.5, voice.currentGain, 0.01, "half way through a one second attack")
    }
}
