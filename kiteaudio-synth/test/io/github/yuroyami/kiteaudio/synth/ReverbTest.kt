package io.github.yuroyami.kiteaudio.synth

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReverbTest {

    private val rate = 48_000

    /** One loud frame then silence: whatever comes after the click is the room. */
    private fun impulse(frames: Int): FloatArray = FloatArray(frames * 2).also {
        it[0] = 1f
        it[1] = 1f
    }

    private fun energyAfter(out: FloatArray, fromFrame: Int, frames: Int): Float {
        var sum = 0f
        for (i in fromFrame until frames) sum += abs(out[i * 2]) + abs(out[i * 2 + 1])
        return sum
    }

    @Test
    fun aRoomKeepsRingingAfterTheSoundStops() {
        val reverb = Reverb.room(rate)
        val out = impulse(24_000)
        reverb.process(out, 24_000)
        assertTrue(
            energyAfter(out, 1_000, 24_000) > 0.01f,
            "nothing came back after the impulse, so there is no room",
        )
    }

    @Test
    fun silenceInIsSilenceOut() {
        val reverb = Reverb.room(rate)
        val out = FloatArray(9600)
        reverb.process(out, 4800)
        assertTrue(out.all { it == 0f }, "the reverb invented sound from silence")
    }

    @Test
    fun noWetAndFullDryLeavesTheSoundExactlyAsItWas() {
        val reverb = Reverb(rate).apply { wet = 0.0; dry = 1.0 }
        val original = FloatArray(960) { (it % 100) / 100f }
        val out = original.copyOf()
        reverb.process(out, 480)
        assertTrue(out.contentEquals(original), "a bypassed reverb changed the sound")
    }

    @Test
    fun aBiggerRoomRingsForLongerThanASmallOne() {
        fun tailOf(reverb: Reverb): Float {
            val out = impulse(48_000)
            reverb.process(out, 48_000)
            return energyAfter(out, 24_000, 48_000)
        }
        assertTrue(
            tailOf(Reverb.hall(rate)) > tailOf(Reverb.dryRoom(rate)),
            "the hall does not ring longer than the dry room",
        )
    }

    @Test
    fun moreDampingMeansTheTailDiesSooner() {
        fun tailOf(damping: Double): Float {
            val reverb = Reverb(rate).apply {
                wet = 0.5
                roomSize = 0.7
                this.damping = damping
            }
            val out = impulse(48_000)
            reverb.process(out, 48_000)
            return energyAfter(out, 12_000, 48_000)
        }
        assertTrue(tailOf(0.9) < tailOf(0.1), "damping did nothing")
    }

    @Test
    fun theTailDecaysRatherThanGrowing() {
        // Eight comb filters feeding back is a runaway if the gains are wrong, and a runaway
        // reverb is a scream.
        val reverb = Reverb.hall(rate)
        val out = impulse(96_000)
        reverb.process(out, 96_000)

        val early = energyAfter(out, 4_000, 12_000)
        val late = energyAfter(out, 84_000, 92_000)
        assertTrue(late < early, "the room grew instead of decaying: $early then $late")
    }

    @Test
    fun aLoudPassageThroughTheBiggestRoomNeverClips() {
        // A sine rather than a constant. Music has no direct current, and feeding some into a
        // bank of comb filters measures something no instrument ever produces.
        val reverb = Reverb.hall(rate)
        val out = FloatArray(96_000 * 2)
        for (i in 0 until 96_000) {
            val value = (kotlin.math.sin(2.0 * kotlin.math.PI * 220.0 * i / rate) * 0.8).toFloat()
            out[i * 2] = value
            out[i * 2 + 1] = value
        }
        reverb.process(out, 96_000)
        val peak = out.maxOf { abs(it) }
        assertTrue(peak <= 1.0f, "a loud passage through the hall clips at $peak")
    }

    @Test
    fun turningTheRoomUpDoesNotTurnTheWholeOutputUp() {
        // Choosing a bigger room should change the sound, not the volume. Otherwise a hall makes
        // everything louder and the first thing somebody does is turn it back off.
        fun peakWith(reverb: Reverb): Float {
            val out = FloatArray(48_000 * 2)
            for (i in 0 until 48_000) {
                val value = (kotlin.math.sin(2.0 * kotlin.math.PI * 220.0 * i / rate) * 0.8).toFloat()
                out[i * 2] = value
                out[i * 2 + 1] = value
            }
            reverb.process(out, 48_000)
            return out.maxOf { abs(it) }
        }
        val dryPeak = peakWith(Reverb.dryRoom(rate))
        val hallPeak = peakWith(Reverb.hall(rate))
        assertTrue(
            abs(hallPeak - dryPeak) < 0.25f,
            "the hall is $hallPeak against the dry room's $dryPeak, which is a volume change",
        )
    }

    @Test
    fun clearingEmptiesTheRoom() {
        // Without this the last note of one song leaks into the first note of the next.
        val reverb = Reverb.hall(rate)
        val loud = impulse(9600)
        reverb.process(loud, 4800)

        reverb.clear()
        val quiet = FloatArray(9600)
        reverb.process(quiet, 4800)
        assertTrue(quiet.all { abs(it) < 1e-6f }, "the room still had the last sound in it")
    }

    @Test
    fun aWidthOfZeroMakesBothSidesTheSame() {
        val reverb = Reverb.room(rate).apply { width = 0.0 }
        val out = impulse(9600)
        reverb.process(out, 4800)
        for (i in 100 until 4800) {
            assertEquals(out[i * 2], out[i * 2 + 1], 1e-5f, "the sides differ at frame $i")
        }
    }

    @Test
    fun settingsAreHeldInsideTheirRange() {
        val reverb = Reverb(rate)
        reverb.wet = 9.0
        reverb.dry = -3.0
        reverb.roomSize = 42.0
        reverb.damping = -1.0
        assertEquals(1.0, reverb.wet)
        assertEquals(0.0, reverb.dry)
        assertEquals(1.0, reverb.roomSize)
        assertEquals(0.0, reverb.damping)
    }

    @Test
    fun theSameInputTwiceGivesTheSameOutput() {
        fun once(): FloatArray {
            val reverb = Reverb.room(rate)
            val out = impulse(9600)
            reverb.process(out, 4800)
            return out
        }
        assertTrue(once().contentEquals(once()), "the reverb is not deterministic")
    }

    @Test
    fun theRoomIsBuiltForWhateverRateItIsGiven() {
        // Freeverb's delay lengths are in samples at 44,100 Hz. Using them untouched at 48 kHz
        // makes a slightly smaller, brighter room than the one asked for.
        val out44 = impulse(44_100)
        Reverb.hall(44_100).process(out44, 44_100)
        val out48 = impulse(48_000)
        Reverb.hall(48_000).process(out48, 48_000)

        // Measured over the same amount of TIME, not the same number of frames.
        val tail44 = energyAfter(out44, 22_050, 44_100)
        val tail48 = energyAfter(out48, 24_000, 48_000)
        assertTrue(
            abs(tail44 - tail48) < maxOf(tail44, tail48) * 0.5f,
            "the same room sounds very different at the two rates: $tail44 against $tail48",
        )
    }

    @Test
    fun aRoomWhoseTailHasDiedIsExactlySilent() {
        // Once its tail is under -120 dB the room is emptied, leftovers and all, so silence in is
        // exact silence out rather than a trickle of subnormal numbers.
        val reverb = Reverb.room(rate)
        reverb.process(impulse(512), 512)
        val block = FloatArray(512 * 2)
        repeat(20 * rate / 512) {
            block.fill(0f)
            reverb.process(block, 512)
        }
        val stray = block.firstOrNull { it != 0f }
        assertEquals(null, stray, "the room still gave out $stray")
    }

    @Test
    fun anEmptiedRoomRingsExactlyLikeANewOne() {
        val used = Reverb.room(rate)
        used.process(impulse(512), 512)
        val block = FloatArray(512 * 2)
        repeat(20 * rate / 512) {
            block.fill(0f)
            used.process(block, 512)
        }
        val again = impulse(4_800).also { used.process(it, 4_800) }
        val fresh = impulse(4_800).also { Reverb.room(rate).process(it, 4_800) }
        assertTrue(again.contentEquals(fresh), "the old note leaked into the new one")
    }

    @Test
    fun anIdleRoomStillTurnsTheDrySoundDown() {
        // The room hears only its send, and the send is silent. The dry sound still comes down.
        val reverb = Reverb(rate).apply { wet = 0.3; dry = 0.5 }
        val send = FloatArray(960)
        val out = FloatArray(960) { 0.8f }
        reverb.process(send, out, 0, 480)
        assertTrue(out.all { abs(it - 0.4f) < 1e-6f }, "dry came out at ${out.first()}")
    }

    @Test
    fun anInvalidSampleNeverRingsInTheRoom() {
        val reverb = Reverb.room(rate)
        val poisoned = impulse(4_800).also {
            it[200] = Float.NaN
            it[401] = Float.POSITIVE_INFINITY
        }
        reverb.process(poisoned, 4_800)
        assertTrue(poisoned.all { it - it == 0f }, "a NaN or an infinity came out")
        val next = impulse(48_000)
        reverb.process(next, 48_000)
        assertTrue(next.all { it - it == 0f }, "the room kept a NaN")
        assertTrue(energyAfter(next, 1_000, 48_000) > 0.01f, "the room stopped ringing")
    }

    @Test
    fun aValueTooLargeForTheRoomNeverComesOutAsNoise() {
        // Large enough to overflow inside the allpass chain on its first echo.
        val reverb = Reverb.hall(rate)
        val huge = FloatArray(9_600).also { it[0] = 3e38f }
        reverb.process(huge, 4_800)
        assertTrue(huge.all { it - it == 0f }, "an overflow came out")
        val next = impulse(48_000)
        reverb.process(next, 48_000)
        assertTrue(next.all { it - it == 0f }, "the room kept an overflow")
        assertTrue(energyAfter(next, 1_000, 48_000) > 0.01f, "the room stopped ringing")
    }
}
