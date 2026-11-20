package io.github.yuroyami.kitesynth

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SynthChannelsTest {

    private val out = FloatArray(4096)

    private fun gmSynth() = Synth(gmFont(), 48_000, 8).apply { reverb.wet = 0.0 }

    /** Applies whatever is queued, which is when a program or bank change takes effect. */
    private fun Synth.settle() = render(out, 1)

    @Test
    fun channelNineIsPercussionAndPlaysTheKit() {
        val synth = gmSynth()
        synth.noteOn(9, 36, 100)
        synth.render(out, 64)
        assertTrue(synth.isPercussion(9))
        assertFalse(synth.isPercussion(0))
        assertEquals("Standard Kit", synth.presetOn(9)!!.name)
        assertEquals(1, synth.activeVoices)
    }

    @Test
    fun aProgramChangeOnAPercussionChannelPicksAKitAndFallsBackToTheStandardOne() {
        val synth = gmSynth()
        synth.programChange(9, 8)
        synth.settle()
        assertEquals("Room Kit", synth.presetOn(9)!!.name)
        synth.programChange(9, 99)
        synth.settle()
        assertEquals("Standard Kit", synth.presetOn(9)!!.name)
    }

    @Test
    fun aMissingBankFallsBackToBankZeroThenToTheDefault() {
        val synth = gmSynth()
        synth.controlChange(0, 0, 1)
        synth.programChange(0, 5)
        synth.settle()
        assertEquals(1, synth.bankOn(0))
        assertEquals(5, synth.programOn(0))
        assertEquals("Rhodes Bright", synth.presetOn(0)!!.name)
        synth.controlChange(0, 0, 7)
        synth.settle()
        assertEquals("Rhodes", synth.presetOn(0)!!.name)
        synth.programChange(0, 77)
        synth.settle()
        assertEquals("Piano", synth.presetOn(0)!!.name)
    }

    @Test
    fun anyChannelCanBecomePercussionAndGoBack() {
        val synth = gmSynth()
        synth.setPercussion(8, true)
        synth.noteOn(8, 38, 100)
        synth.render(out, 64)
        assertEquals("Standard Kit", synth.presetOn(8)!!.name)
        assertEquals(listOf("snare"), synth.soundingSamples(8))

        synth.setPercussion(9, false)
        synth.programChange(9, 5)
        synth.settle()
        assertEquals("Rhodes", synth.presetOn(9)!!.name)
    }

    @Test
    fun aPercussionChannelWithNoKitIsSilent() {
        val synth = Synth(KiteSynth.readFont(Sf2Builder().build()), 48_000, 8)
        synth.noteOn(9, 36, 100)
        synth.render(out, 64)
        assertNull(synth.presetOn(9))
        assertEquals(0, synth.activeVoices, "a drum note played the piano")
    }

    @Test
    fun theTuningTableNeverReachesPercussion() {
        val synth = gmSynth()
        synth.tuningCents[36] = -50.0
        synth.noteOn(9, 36, 100)
        synth.render(out, 512)
        val detuned = out.copyOf(1024)
        synth.allSoundOff()
        synth.tuningCents[36] = 0.0
        synth.noteOn(9, 36, 100)
        synth.render(out, 512)
        assertContentEquals(detuned, out.copyOf(1024))
    }

    @Test
    fun theTuningTableStillReachesMelodicChannels() {
        val synth = gmSynth()
        synth.tuningCents[60] = -50.0
        synth.noteOn(0, 60, 100)
        synth.render(out, 512)
        val detuned = out.copyOf(1024)
        synth.allSoundOff()
        synth.tuningCents[60] = 0.0
        synth.noteOn(0, 60, 100)
        synth.render(out, 512)
        assertFalse(detuned.contentEquals(out.copyOf(1024)), "the quarter tone did not move the piano")
    }

    /** The samples a channel's sounding keys play, found through the public preset lookup. */
    private fun Synth.soundingSamples(channel: Int): List<String> =
        soundingKeys.flatMap { key -> font.voicesFor(presetOn(channel)!!, key, 100).map { it.sample.name } }

    // ------------------------------------------------------------------ controllers

    /** One note, rendered alone, with nothing else changed. */
    private fun Synth.play(channel: Int, key: Int, frames: Int = 1024): FloatArray {
        noteOn(channel, key, 100)
        val buffer = FloatArray(frames * 2)
        render(buffer, frames)
        return buffer
    }

    private fun Synth.selectRpn(channel: Int, number: Int) {
        controlChange(channel, 101, number shr 7)
        controlChange(channel, 100, number and 0x7F)
    }

    @Test
    fun bendRangeFollowsTheRpn() {
        val synth = gmSynth()
        synth.selectRpn(0, 0)
        synth.controlChange(0, 6, 12)
        synth.controlChange(0, 38, 50)
        synth.settle()
        assertEquals(12.5, synth.bendRangeSemitones(0))
    }

    @Test
    fun aHalfBendOverTwentyFourSemitonesIsExactlyTheOctaveAbove() {
        val bent = gmSynth()
        bent.selectRpn(0, 0)
        bent.controlChange(0, 6, 24)
        bent.pitchBend(0, 4096)
        assertContentEquals(gmSynth().play(0, 72), bent.play(0, 60))
    }

    @Test
    fun dataEntryAfterAnNrpnGoesNowhere() {
        val synth = gmSynth()
        synth.controlChange(0, 99, 1)
        synth.controlChange(0, 98, 2)
        synth.controlChange(0, 6, 24)
        synth.settle()
        assertEquals(2.0, synth.bendRangeSemitones(0))
    }

    @Test
    fun coarseTuneMovesAMelodicChannelByWholeSemitones() {
        val tuned = gmSynth()
        tuned.selectRpn(0, 2)
        tuned.controlChange(0, 6, 65)
        assertContentEquals(gmSynth().play(0, 61), tuned.play(0, 60))
    }

    @Test
    fun sostenutoHoldsOnlyTheKeysDownAtTheEdge() {
        val synth = gmSynth()
        synth.noteOn(0, 60, 100)
        synth.controlChange(0, 66, 127)
        synth.noteOn(0, 64, 100)
        synth.render(out, 64)
        synth.noteOff(0, 60)
        synth.noteOff(0, 64)
        synth.render(out, 256)
        assertEquals(listOf(60), synth.soundingKeys, "60 was down at the edge, 64 was not")
        synth.controlChange(0, 66, 0)
        synth.render(out, 256)
        assertEquals(emptyList(), synth.soundingKeys)
    }

    @Test
    fun sostenutoAndTheSustainPedalWorkTogether() {
        val synth = gmSynth()
        synth.noteOn(0, 60, 100)
        synth.controlChange(0, 66, 127)
        synth.controlChange(0, 64, 127)
        synth.noteOff(0, 60)
        synth.controlChange(0, 66, 0)
        synth.render(out, 256)
        assertEquals(listOf(60), synth.soundingKeys, "the sustain pedal took over when sostenuto let go")
        synth.controlChange(0, 64, 0)
        synth.render(out, 256)
        assertEquals(emptyList(), synth.soundingKeys)
    }

    @Test
    fun resetAllControllersLeavesVolumePanBankAndProgramAlone() {
        val synth = gmSynth()
        synth.controlChange(0, 7, 40)
        synth.controlChange(0, 10, 20)
        synth.controlChange(0, 0, 1)
        synth.programChange(0, 5)
        synth.controlChange(0, 1, 90)
        synth.controlChange(0, 11, 60)
        synth.controlChange(0, 65, 127)
        synth.controlChange(0, 121, 0)
        synth.settle()
        assertEquals(40, synth.controller(0, 7))
        assertEquals(20, synth.controller(0, 10))
        assertEquals(1, synth.bankOn(0))
        assertEquals(5, synth.programOn(0))
        assertEquals(0, synth.controller(0, 1))
        assertEquals(127, synth.controller(0, 11))
        assertEquals(0, synth.controller(0, 65), "portamento is one of the four pedals it resets")
    }

    @Test
    fun everyControllerIsRememberedAndBankLsbPicksNothing() {
        val synth = gmSynth()
        synth.controlChange(0, 74, 50)
        synth.controlChange(0, 32, 3)
        synth.enqueue(0xD0, 77, 0, 0)
        synth.enqueue(0xA0, 60, 90, 0)
        synth.settle()
        assertEquals(50, synth.controller(0, 74))
        assertEquals(3, synth.controller(0, 32))
        assertEquals(0, synth.bankOn(0))
        assertEquals(77, synth.channelPressure(0))
        assertEquals(90, synth.polyPressure(0, 60))
        assertEquals(64, synth.controller(1, 10), "pan starts in the middle")
        assertEquals(127, synth.controller(1, 7), "volume starts full")
    }

    @Test
    fun panFromControllerTenPlacesNewNotes() {
        fun sides(pan: Int): Pair<Float, Float> {
            val synth = gmSynth()
            synth.controlChange(0, 10, pan)
            val buffer = synth.play(0, 60)
            var left = 0f
            var right = 0f
            for (frame in 0 until 1024) {
                left += kotlin.math.abs(buffer[frame * 2])
                right += kotlin.math.abs(buffer[frame * 2 + 1])
            }
            return left to right
        }
        val (hardLeft, quietRight) = sides(0)
        assertTrue(hardLeft > 0f && quietRight == 0f, "pan 0 is hard left")
        val (quietLeft, hardRight) = sides(127)
        assertTrue(hardRight > 0f && quietLeft == 0f, "pan 127 is hard right")
    }

    @Test
    fun referenceA4MovesEveryMelodicNoteAndNoDrum() {
        val semitoneUp = 440.0 * 1.0594630943592953   // 440 times the twelfth root of two
        val sharp = gmSynth().apply { referenceA4 = semitoneUp }.play(0, 60)
        val plain = gmSynth().play(0, 61)
        val worst = sharp.indices.maxOf { kotlin.math.abs(sharp[it] - plain[it]) }
        assertTrue(worst < 1e-4f, "key 60 at A = 466 Hz should be key 61 at A = 440 Hz, off by $worst")
        assertContentEquals(gmSynth().play(9, 36), gmSynth().apply { referenceA4 = semitoneUp }.play(9, 36))
    }

    @Test
    fun aHeldNoteKeepsTheTuningItStartedWith() {
        fun held(change: Synth.() -> Unit): FloatArray {
            val synth = gmSynth()
            synth.noteOn(0, 60, 100)
            synth.render(out, 256)
            synth.change()
            synth.pitchBend(0, 0)
            synth.render(out, 512)
            return out.copyOf(1024)
        }
        val untouched = held {}
        val retuned = held { referenceA4 = 432.0; tuningCents[60] = -50.0 }
        assertContentEquals(untouched, retuned, "a bend pulled the new A4 and table into a note already sounding")
    }

    @Test
    fun allNotesOffAndAllSoundOffLeaveNoPedalBehindInTheControllers() {
        val synth = gmSynth()
        synth.controlChange(0, 64, 127)
        synth.controlChange(0, 66, 127)
        synth.controlChange(0, 123, 0)
        synth.settle()
        assertEquals(0, synth.controller(0, 64))
        assertEquals(0, synth.controller(0, 66))
        synth.controlChange(1, 64, 127)
        synth.settle()
        synth.allSoundOff()
        assertEquals(0, synth.controller(1, 64))
    }
}
