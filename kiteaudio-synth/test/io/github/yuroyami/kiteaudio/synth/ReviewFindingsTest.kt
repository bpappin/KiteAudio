package io.github.yuroyami.kiteaudio.synth

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The bugs an adversarial review found in the synthesiser, each with the case that shows it.
 *
 * Grouped together on purpose. Every one of these passed the tests that existed at the time, and
 * keeping them in one place makes it obvious what those tests were not looking at.
 */
class ReviewFindingsTest {

    private val rate = 48_000

    private fun flatFont(frames: Int = 48_000, zones: List<Sf2Builder.ZoneSpec>? = null): SoundFont =
        KiteSynth.readFont(
            Sf2Builder().build(
                sampleFrames = ShortArray(frames) { 20000 },
                samples = listOf(Sf2Builder.SampleSpec("flat", 0, frames, 0, frames - 100, rate, 60)),
                instrumentZones = zones ?: listOf(
                    Sf2Builder.ZoneSpec(sampleId = 0, extras = listOf(Generator.SampleModes.id to 1)),
                ),
            ),
        )

    private fun loudness(out: FloatArray, frames: Int): Float {
        var sum = 0f
        for (i in 0 until frames * 2) sum += abs(out[i])
        return sum / (frames * 2)
    }

    private fun peak(out: FloatArray, from: Int, to: Int): Float {
        var top = 0f
        for (i in from * 2 until to * 2) top = maxOf(top, abs(out[i]))
        return top
    }

    // ------------------------------------------------------------------ event order

    @Test
    fun eventsStampedOnTheSameFrameKeepTheOrderTheyCameIn() {
        // MIDI order inside one timestamp is load-bearing: a note-off has to land before the
        // note-on that follows it, or the note-on is released the instant it starts. Two events
        // could not show this, which is why every earlier test passed.
        val synth = Synth(flatFont(), rate)
        synth.noteOn(0, 60, 100, atFrame = 0)
        synth.noteOff(1, 62, atFrame = 0)
        synth.noteOn(1, 62, 100, atFrame = 0)

        synth.render(FloatArray(480 * 2), 480)
        assertEquals(2, synth.activeVoices, "the note-off released the note-on that came after it")
    }

    @Test
    fun fourEventsOnOneFrameAlsoKeepTheirOrder() {
        val synth = Synth(flatFont(), rate)
        synth.noteOn(0, 60, 100, atFrame = 0)
        synth.noteOn(0, 64, 100, atFrame = 0)
        synth.noteOff(1, 67, atFrame = 0)
        synth.noteOn(1, 67, 100, atFrame = 0)

        synth.render(FloatArray(480 * 2), 480)
        assertTrue(67 in synth.soundingKeys, "key 67 was lost: ${synth.soundingKeys}")
    }

    // ------------------------------------------------------------------ exclusive classes

    @Test
    fun twoLayersOfOneDrumDoNotCutEachOther() {
        // A hi-hat written as a tick layer plus a body layer puts both zones in the same
        // exclusive class. Cutting the class while starting the note kills the layer the same
        // note-on started a moment earlier.
        val layered = flatFont(
            zones = listOf(
                Sf2Builder.ZoneSpec(
                    sampleId = 0,
                    extras = listOf(Generator.SampleModes.id to 1, Generator.ExclusiveClass.id to 1),
                ),
                Sf2Builder.ZoneSpec(
                    sampleId = 0,
                    extras = listOf(Generator.SampleModes.id to 1, Generator.ExclusiveClass.id to 1),
                ),
            ),
        )
        val synth = Synth(layered, rate)
        synth.noteOn(0, 60, 100)
        synth.render(FloatArray(480 * 2), 480)

        assertEquals(2, synth.activeVoices, "one layer cut the other")
    }

    @Test
    fun aLaterNoteStillCutsTheExclusiveClass() {
        // The point of the class is that a closed hi-hat silences an open one. Fixing the layer
        // case must not throw that away.
        val exclusive = flatFont(
            zones = listOf(
                Sf2Builder.ZoneSpec(
                    sampleId = 0,
                    extras = listOf(Generator.SampleModes.id to 1, Generator.ExclusiveClass.id to 1),
                ),
            ),
        )
        val synth = Synth(exclusive, rate)
        synth.noteOn(0, 60, 100)
        synth.render(FloatArray(480 * 2), 480)
        synth.noteOn(0, 62, 100)
        synth.render(FloatArray(480 * 2), 480)

        assertEquals(1, synth.activeVoices, "the second note should have cut the first")
    }

    // ------------------------------------------------------------------ stealing

    @Test
    fun theNoteYouJustPlayedIsNotTheFirstThingStolen() {
        // A voice reports the gain of its last rendered block, and a voice that has not rendered
        // yet reported zero. That made every freshly started note look like silence and put it
        // at the front of the queue to be stolen by the next note in the same block.
        val synth = Synth(flatFont(), rate, maxVoices = 2)
        synth.noteOn(0, 60, 100)
        synth.noteOn(0, 62, 100)
        synth.render(FloatArray(480 * 2), 480)

        synth.noteOn(0, 64, 100, atFrame = 0)
        synth.noteOn(0, 65, 100, atFrame = 0)
        synth.render(FloatArray(480 * 2), 480)

        assertEquals(listOf(64, 65), synth.soundingKeys.sorted(), "a new note was stolen back")
    }

    @Test
    fun aQuietVoiceIsStillTheOneStolenFirst() {
        // The fix must not turn into "never steal anything recent", or a held chord becomes
        // unplayable over.
        val synth = Synth(flatFont(), rate, maxVoices = 2)
        synth.noteOn(0, 60, 100)
        synth.noteOn(0, 62, 100)
        synth.render(FloatArray(480 * 2), 480)
        synth.noteOff(0, 60)
        synth.render(FloatArray(480 * 2), 480)

        synth.noteOn(0, 64, 100)
        synth.render(FloatArray(480 * 2), 480)
        assertTrue(62 in synth.soundingKeys, "the held note was stolen instead of the fading one")
    }

    // ------------------------------------------------------------------ the end of a sample

    @Test
    fun aReleasedLoopStopsAtTheEndOfItsOwnSample() {
        // SampleModes 3 loops until the key comes up, then plays on to the end. A released loop
        // once walked past its own last frame into the next sample, and must still stop there.
        val poolFrames = 4046
        val pool = ShortArray(poolFrames) { index ->
            when {
                index < 2000 -> 1000                  // sample A, quiet
                index < 2046 -> 0                     // a guard band of silence
                else -> 30000                         // sample B, thirty times louder
            }
        }
        val font = KiteSynth.readFont(
            Sf2Builder().build(
                sampleFrames = pool,
                samples = listOf(
                    Sf2Builder.SampleSpec("a", 0, 2000, 500, 1500, rate, 60),
                    Sf2Builder.SampleSpec("b", 2046, 4046, 2100, 3900, rate, 60),
                ),
                instrumentZones = listOf(
                    Sf2Builder.ZoneSpec(
                        sampleId = 0,
                        extras = listOf(
                            Generator.SampleModes.id to 3,
                            Generator.ReleaseVolEnv.id to 0,      // one second
                        ),
                    ),
                ),
            ),
        )

        val synth = Synth(font, rate)
        synth.reverb.wet = 0.0
        synth.reverb.dry = 1.0
        synth.noteOn(0, 60, 127)
        synth.render(FloatArray(1000 * 2), 1000)
        synth.noteOff(0, 60)

        val after = FloatArray(4000 * 2)
        synth.render(after, 4000)

        // Sample A at this level peaks around 0.03. Anything near 0.4 is sample B being played.
        assertTrue(peak(after, 2000, 4000) < 0.1f, "it played the next sample in the pool")
        assertEquals(0, synth.activeVoices, "the released loop never ended")
    }

    @Test
    fun aVoiceThatDecaysToSilenceGivesUpItsSlot() {
        // A percussive font decays to nothing and then holds its slot until the key comes up.
        // On a font like that, a fast passage runs out of voices while sounding nothing at all.
        val percussive = flatFont(
            zones = listOf(
                Sf2Builder.ZoneSpec(
                    sampleId = 0,
                    extras = listOf(
                        Generator.SampleModes.id to 1,
                        Generator.SustainVolEnv.id to 1000,       // past silence
                        Generator.DecayVolEnv.id to -3986,        // about 100 ms
                    ),
                ),
            ),
        )
        val synth = Synth(percussive, rate)
        synth.noteOn(0, 60, 127)

        val out = FloatArray(4800 * 2)
        repeat(10) { synth.render(out, 4800) }        // a second, with the key still down

        assertEquals(0, synth.activeVoices, "a silent voice held its slot")
    }

    // ------------------------------------------------------------------ the channel's volume

    @Test
    fun channelVolumeIsActuallyApplied() {
        // CC7 was stored on the channel and read by nothing. A file that fades a part out plays
        // it at full volume instead.
        val synth = Synth(flatFont(), rate)
        synth.reverb.wet = 0.0
        synth.reverb.dry = 1.0

        val full = FloatArray(480 * 2)
        synth.noteOn(0, 60, 100)
        synth.render(full, 480)

        val quiet = Synth(flatFont(), rate).apply {
            reverb.wet = 0.0
            reverb.dry = 1.0
            controlChange(0, 7, 32)
            noteOn(0, 60, 100)
        }
        val quietOut = FloatArray(480 * 2)
        quiet.render(quietOut, 480)

        assertTrue(
            loudness(quietOut, 480) < loudness(full, 480) * 0.5f,
            "quarter volume made no difference: ${loudness(quietOut, 480)} against ${loudness(full, 480)}",
        )
    }

    @Test
    fun channelVolumeOfZeroIsSilence() {
        val synth = Synth(flatFont(), rate)
        synth.reverb.wet = 0.0
        synth.reverb.dry = 1.0
        synth.controlChange(0, 7, 0)
        synth.noteOn(0, 60, 100)

        val out = FloatArray(480 * 2)
        synth.render(out, 480)
        assertEquals(0.0f, loudness(out, 480), 1e-6f, "MIDI said silent")
    }

    @Test
    fun expressionIsAppliedOnTopOfVolume() {
        val synth = Synth(flatFont(), rate)
        synth.reverb.wet = 0.0
        synth.reverb.dry = 1.0
        synth.controlChange(0, 11, 0)
        synth.noteOn(0, 60, 100)

        val out = FloatArray(480 * 2)
        synth.render(out, 480)
        assertEquals(0.0f, loudness(out, 480), 1e-6f)
    }

    @Test
    fun aChannelStartsAtFullVolume() {
        // MIDI's own default for CC7 is 100 of 127, but an instrument nobody has sent a volume
        // to must not be quieter than one somebody set to maximum.
        val untouched = Synth(flatFont(), rate).apply { reverb.wet = 0.0; reverb.dry = 1.0 }
        val maximum = Synth(flatFont(), rate).apply {
            reverb.wet = 0.0
            reverb.dry = 1.0
            controlChange(0, 7, 127)
            controlChange(0, 11, 127)
        }
        untouched.noteOn(0, 60, 100)
        maximum.noteOn(0, 60, 100)

        val a = FloatArray(480 * 2)
        val b = FloatArray(480 * 2)
        untouched.render(a, 480)
        maximum.render(b, 480)
        assertEquals(loudness(b, 480), loudness(a, 480), 1e-6f)
    }

    // ------------------------------------------------------------------ the room

    @Test
    fun theRoomSizeFollowsFreeverbsOwnNumbers() {
        // Freeverb is feedback = 0.7 + roomSize * 0.28. Swapping the scale and the offset makes
        // every room far too short, and only a room size of one lands on the right number by
        // accident.
        fun tailOf(size: Double): Float {
            val reverb = Reverb(rate)
            reverb.wet = 1.0
            reverb.dry = 0.0
            reverb.damping = 0.0
            reverb.roomSize = size

            val hit = FloatArray(64 * 2) { 0.9f }
            reverb.process(hit, 64)

            // How loud the room still is a second after the sound stopped.
            val silence = FloatArray(4800 * 2)
            repeat(9) { silence.fill(0f); reverb.process(silence, 4800) }
            silence.fill(0f)
            reverb.process(silence, 4800)
            return peak(silence, 0, 4800)
        }

        val small = tailOf(0.0)
        val large = tailOf(1.0)
        assertTrue(large > small, "a large room must ring longer than a small one")
        assertTrue(
            small > 1e-7f,
            "a room size of zero rang for less than a second, so the scale is far too small",
        )
    }

    @Test
    fun turningTheRoomOffAlsoEmptiesIt() {
        // Bypassing returns early, so the delay lines keep whatever was in them. Turning the
        // room back on then plays a sound that ended minutes ago.
        val reverb = Reverb.room(rate)
        val loud = FloatArray(4800 * 2) { 0.9f }
        reverb.process(loud, 4800)

        reverb.wet = 0.0
        reverb.dry = 1.0
        val skipped = FloatArray(4800 * 2)
        repeat(20) { skipped.fill(0f); reverb.process(skipped, 4800) }

        reverb.wet = 0.22
        reverb.dry = 0.84
        val out = FloatArray(4800 * 2)
        reverb.process(out, 4800)

        assertTrue(peak(out, 0, 4800) < 1e-4f, "the old tail came back: ${peak(out, 0, 4800)}")
    }

    // ------------------------------------------------------------------ reading the file

    @Test
    fun aPresetCanMakeALayerLouderAndNotOnlyQuieter() {
        // The preset level is an offset added to the instrument level, so it has to be able to
        // subtract. Read as unsigned, an offset of -60 centibels becomes 65476, the sum lands far
        // past silence, and a preset that says "this layer is louder" silences it completely.
        val font = KiteSynth.readFont(
            Sf2Builder().build(
                sampleFrames = ShortArray(48_000) { 20000 },
                samples = listOf(Sf2Builder.SampleSpec("flat", 0, 48_000, 0, 47_900, rate, 60)),
                instrumentZones = listOf(
                    Sf2Builder.ZoneSpec(
                        sampleId = 0,
                        extras = listOf(
                            Generator.SampleModes.id to 1,
                            Generator.InitialAttenuation.id to 200,
                        ),
                    ),
                ),
                presetZones = listOf(0 to listOf(Generator.InitialAttenuation.id to 0xFFC4)),
            ),
        )

        val plan = font.voicesFor(0, 0, 60, 100).single()
        assertEquals(140, plan.attenuationCb, "the preset's -60 was read as 65476")

        val synth = Synth(font, rate)
        synth.reverb.wet = 0.0
        synth.reverb.dry = 1.0
        synth.noteOn(0, 60, 127)
        val out = FloatArray(480 * 2)
        synth.render(out, 480)
        assertTrue(peak(out, 0, 480) > 0.01f, "the note was silent")
    }

    @Test
    fun aPresetOffsetCannotPushAValuePastWhatTheFormatAllows() {
        // Even a font that asks for something illegal has to end up somewhere sensible. Silencing
        // the note, or sending its pitch to two to the fifty-fourth, is not sensible.
        val font = KiteSynth.readFont(
            Sf2Builder().build(
                sampleFrames = ShortArray(48_000) { 20000 },
                samples = listOf(Sf2Builder.SampleSpec("flat", 0, 48_000, 0, 47_900, rate, 60)),
                instrumentZones = listOf(
                    Sf2Builder.ZoneSpec(
                        sampleId = 0,
                        extras = listOf(
                            Generator.SampleModes.id to 1,
                            Generator.InitialAttenuation.id to 100,
                        ),
                    ),
                ),
                presetZones = listOf(0 to listOf(Generator.InitialAttenuation.id to 0x8000)),
            ),
        )
        val plan = font.voicesFor(0, 0, 60, 100).single()
        assertTrue(plan.attenuationCb in 0..1440, "attenuation landed at ${plan.attenuationCb}")
    }

    @Test
    fun aPresetCanTuneAScaleDownAsWellAsUp() {
        val font = KiteSynth.readFont(
            Sf2Builder().build(
                sampleFrames = ShortArray(48_000) { 20000 },
                samples = listOf(Sf2Builder.SampleSpec("flat", 0, 48_000, 0, 47_900, rate, 60)),
                instrumentZones = listOf(
                    Sf2Builder.ZoneSpec(sampleId = 0, extras = listOf(Generator.SampleModes.id to 1)),
                ),
                presetZones = listOf(0 to listOf(Generator.ScaleTuning.id to 0xFF9C)),
            ),
        )
        // The default scale tuning is 100. A preset offset of -100 makes a drum kit, where every
        // key plays at the same pitch.
        assertEquals(0, font.voicesFor(0, 0, 60, 100).single().scaleTuning)
    }

    @Test
    fun aGeneratorlessZoneAfterTheFirstDoesNotReplaceTheGlobalZone() {
        // Only the first zone of a run can be global. A later zone with an empty generator range
        // was being promoted over it, throwing away the settings the global zone existed to
        // supply.
        val font = KiteSynth.readFont(
            Sf2Builder().build(
                sampleFrames = ShortArray(48_000) { 20000 },
                samples = listOf(Sf2Builder.SampleSpec("flat", 0, 48_000, 0, 47_900, rate, 60)),
                instrumentZones = listOf(
                    // A zone with no generators at all and no sample: malformed, and the reader
                    // used to promote it over the global zone that came before it.
                    Sf2Builder.ZoneSpec(sampleId = -1),
                    Sf2Builder.ZoneSpec(sampleId = 0, extras = listOf(Generator.SampleModes.id to 1)),
                ),
                instrumentGlobal = listOf(Generator.InitialAttenuation.id to 120),
            ),
        )
        assertEquals(120, font.voicesFor(0, 0, 60, 100).single().attenuationCb)
    }

    @Test
    fun aChunkClaimingMoreBytesThanCanBeCountedIsRejectedAsABadFont() {
        // The bounds check added start to length and overflowed, so a chunk claiming two billion
        // bytes came back as a plain argument error instead of "this file is not a soundfont".
        val bytes = Sf2Builder().build(
            sampleFrames = ShortArray(64) { 1000 },
            samples = listOf(Sf2Builder.SampleSpec("s", 0, 64, 8, 56, rate, 60)),
        )
        // Break the sdta chunk's length to a number close to the top of a signed integer.
        val broken = bytes.copyOf()
        val at = findChunkLength(broken, "smpl")
        if (at >= 0) {
            broken[at] = 0xFF.toByte()
            broken[at + 1] = 0xFF.toByte()
            broken[at + 2] = 0xFF.toByte()
            broken[at + 3] = 0x7F.toByte()
        }

        val thrown = runCatching { KiteSynth.readFont(broken) }.exceptionOrNull()
        assertTrue(
            thrown is SoundFontFormatException,
            "a broken font threw ${thrown?.let { it::class.simpleName }} instead of a format error",
        )
    }

    private fun findChunkLength(bytes: ByteArray, tag: String): Int {
        val needle = tag.map { it.code.toByte() }
        outer@ for (i in 0..bytes.size - 8) {
            for (j in needle.indices) if (bytes[i + j] != needle[j]) continue@outer
            return i + 4
        }
        return -1
    }
}
