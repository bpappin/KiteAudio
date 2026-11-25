package io.github.yuroyami.kiteaudio.synth

import io.github.yuroyami.kiteaudio.synth.Sf2Builder.ModSpec
import io.github.yuroyami.kiteaudio.synth.StereoFonts.RIGHT
import io.github.yuroyami.kiteaudio.synth.StereoFonts.Side
import io.github.yuroyami.kiteaudio.synth.StereoFonts.crossings
import io.github.yuroyami.kiteaudio.synth.StereoFonts.frequency
import io.github.yuroyami.kiteaudio.synth.StereoFonts.joined
import io.github.yuroyami.kiteaudio.synth.StereoFonts.left
import io.github.yuroyami.kiteaudio.synth.StereoFonts.take
import kotlin.math.abs
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A modulator can feed another modulator instead of a generator (SoundFont 2.04, section 9.5.4).
 * The feeder's output is the target's primary source, so the target adds
 * `target amount * (feeder amount * feeder source * feeder amount source) * target amount source`.
 *
 * Every font here plays a flat, looped sample at full velocity, so the default velocity modulators
 * read zero and every sum is a whole number of centibels. That makes most results exact, on every platform.
 */
class LinkedModulatorTest {

    private val rate = 48_000
    private val frames = 4_800
    private val attenuation = Generator.InitialAttenuation.id

    /** The link source: the output of the modulators that link to this one. */
    private val link = 0x007F

    /** CC 1, 2, 4 and 5 as unipolar linear sources. Only CC 1 has a default modulator, and it moves the vibrato. */
    private val cc1 = 0x0081
    private val cc2 = 0x0082
    private val cc4 = 0x0084
    private val cc5 = 0x0085

    private fun to(index: Int) = 0x8000 or index

    /** One preset over one instrument zone with a flat looped sample, so the level shows in every frame. */
    private fun font(
        zone: List<ModSpec>,
        instrumentGlobal: List<ModSpec> = emptyList(),
        preset: List<ModSpec> = emptyList(),
        presetGlobal: List<ModSpec> = emptyList(),
        extras: List<Pair<Int, Int>> = emptyList(),
    ): SoundFont = KiteSynth.readFont(
        Sf2Builder().buildFont(
            fontName = "Linked",
            sampleFrames = ShortArray(frames) { 16_000 },
            samples = listOf(Sf2Builder.SampleSpec("flat", 0, frames, 100, frames - 100, rate, 60)),
            instruments = listOf(
                Sf2Builder.InstrumentSpec(
                    "Flat inst",
                    listOf(Sf2Builder.ZoneSpec(sampleId = 0, extras = listOf(Generator.SampleModes.id to 1) + extras, modulators = zone)),
                    globalModulators = instrumentGlobal,
                ),
            ),
            presets = listOf(
                Sf2Builder.PresetDef("Flat", 0, 0, listOf(Sf2Builder.PresetZoneSpec(0, modulators = preset)), globalModulators = presetGlobal),
            ),
        ),
    )

    /** Sets [controllers] on channel 0, then plays key 60 at full velocity for [length] frames. */
    private fun play(font: SoundFont, controllers: Map<Int, Int>, length: Int = 2_400): FloatArray {
        val synth = Synth(font, rate).also { it.reverb.wet = 0.0 }
        for ((controller, value) in controllers) synth.controlChange(0, controller, value)
        synth.noteOn(0, 60, 127)
        val out = FloatArray(length * 2)
        synth.render(out, length)
        return out
    }

    /** The font of the writer's link test, with a looped sample: a global modulator, then a feeder and its target. */
    private fun writerTestFont(target: ModSpec = ModSpec(link, attenuation, 12)): SoundFont = font(
        zone = listOf(ModSpec(cc1, to(1), 50), target),
        instrumentGlobal = listOf(ModSpec(0x0502, attenuation, 960)),
    )

    private fun List<Modulator>.describe() = map { listOf(it.source, it.destinationId, it.amount, it.amountSource, it.transform) }

    private fun SoundFont.zoneModulators() = instruments.single().zones.single().modulators

    @Test
    fun aLinkedSourceMovesTheTargetsGeneratorByTheProductOfBothAmounts() {
        // CC 1 at full scale: the feeder gives 50 * 1, and the target adds 12 * 50 = 600 centibels.
        val linked = writerTestFont()
        assertEquals(0x8002, linked.zoneModulators()[1].destinationId, "the global modulator sits in front, so the target is third")
        val full = play(linked, mapOf(1 to 127))
        val reference = play(font(emptyList(), extras = listOf(attenuation to 600)), mapOf(1 to 127))
        assertTrue(full.any { it != 0f })
        assertContentEquals(reference, full, "the linked route did not add exactly 600 centibels")

        // CC 1 at 64: 600 * 64 / 127 centibels, about 302.36. The sample is flat, so the vibrato cannot change the level.
        val rest = play(linked, mapOf(1 to 0))
        val half = play(linked, mapOf(1 to 64))
        val expected = Units.gainOf(600.0 * 64 / 127)
        for (frame in 100 until 2_400) {
            val ratio = half[frame * 2].toDouble() / rest[frame * 2]
            assertEquals(expected, ratio, 1e-6, "frame $frame")
        }
    }

    @Test
    fun aLinkedRouteFollowsTheControllerWhileTheNoteSounds() {
        // The linked route and a plain CC 1 modulator of 600 must play the same performance, bit for bit.
        fun perform(font: SoundFont): FloatArray {
            val synth = Synth(font, rate).also { it.reverb.wet = 0.0 }
            val parts = ArrayList<FloatArray>()
            synth.noteOn(0, 60, 127)
            take(synth, 1_200, parts)
            synth.controlChange(0, 1, 127)
            take(synth, 1_200, parts)
            synth.controlChange(0, 1, 30)
            take(synth, 1_200, parts)
            return joined(parts)
        }
        val linked = perform(writerTestFont())
        val plain = perform(font(listOf(ModSpec(cc1, attenuation, 600)), instrumentGlobal = listOf(ModSpec(0x0502, attenuation, 960))))
        assertContentEquals(plain, linked)
        assertTrue(abs(linked[1_000 * 2]) > 2 * abs(linked[2_000 * 2]), "the controller did not move the level")
    }

    @Test
    fun aLinkedSourceAtRestSoundsLikeTheFontWithoutTheLink() {
        // A target whose feeder gives zero adds nothing, as a link source with no feeder adds nothing.
        val atRest = play(writerTestFont(), mapOf(1 to 0))
        val noFeeder = play(font(listOf(ModSpec(link, attenuation, 12)), instrumentGlobal = listOf(ModSpec(0x0502, attenuation, 960))), mapOf(1 to 0))
        val neither = play(font(emptyList(), instrumentGlobal = listOf(ModSpec(0x0502, attenuation, 960))), mapOf(1 to 0))
        assertTrue(atRest.any { it != 0f })
        assertContentEquals(noFeeder, atRest)
        assertContentEquals(neither, atRest)
    }

    @Test
    fun aChainOfThreeRunsInOrderWhereverTheListPutsIt() {
        // CC 1 feeds the middle with 2, the middle feeds the last with 3 * 2, and the last adds 50 * 6 = 300 centibels.
        val reference = play(font(emptyList(), extras = listOf(attenuation to 300)), mapOf(1 to 127))
        val orders = mapOf(
            "feeder first" to listOf(ModSpec(cc1, to(1), 2), ModSpec(link, to(2), 3), ModSpec(link, attenuation, 50)),
            "target first" to listOf(ModSpec(link, attenuation, 50), ModSpec(link, to(0), 3), ModSpec(cc1, to(1), 2)),
            "middle first" to listOf(ModSpec(link, to(2), 3), ModSpec(cc1, to(0), 2), ModSpec(link, attenuation, 50)),
        )
        for ((name, list) in orders) {
            assertContentEquals(reference, play(font(list), mapOf(1 to 127)), name)
        }
    }

    @Test
    fun invalidLinksContributeNothingAndStayInTheFont() {
        val plain = ModSpec(cc1, attenuation, 100)
        val invalid = listOf(
            plain,
            ModSpec(link, to(2), 7),          // 1 and 2 link to each other
            ModSpec(link, to(1), 7),
            ModSpec(cc1, to(1), 5),           // feeds the cycle
            ModSpec(link, to(4), 9),          // links to itself
            ModSpec(cc1, to(4), 5),           // feeds the modulator that links to itself
            ModSpec(cc1, to(40), 5),          // past the end of the list
            ModSpec(cc1, attenuation, 100, amountSource = link), // the link is not an amount source
        )
        val font = font(invalid)
        val played = play(font, mapOf(1 to 127))
        val reference = play(font(listOf(plain)), mapOf(1 to 127))
        assertTrue(played.any { it != 0f })
        assertContentEquals(reference, played, "an invalid link changed the sound")

        // Every modulator stays. The link past the end reads 0x7FFF, which is past the end of every list.
        val kept = font.zoneModulators()
        assertEquals(invalid.size, kept.size)
        assertEquals(0xFFFF, kept[6].destinationId)
        val again = KiteSynth.readFont(SoundFontWriter.write(font))
        assertEquals(kept.describe(), again.zoneModulators().describe())
        assertContentEquals(played, play(again, mapOf(1 to 127)))
    }

    @Test
    fun aLinkIntoAModulatorThatReadsAControllerIsIgnored() {
        // The target's source is CC 1, not the link source. It keeps its own source, and the feeder adds nothing.
        val target = ModSpec(cc1, attenuation, 100)
        val played = play(font(listOf(ModSpec(cc2, to(1), 5), target)), mapOf(1 to 127, 2 to 127))
        assertContentEquals(play(font(listOf(target)), mapOf(1 to 127, 2 to 127)), played)
    }

    @Test
    fun linkedPairsInGlobalLocalAndPresetZonesKeepTheirTargetsAndTakeEffect() {
        // The local and preset targets take the absolute value, which changes nothing here. It only keeps
        // them different from the global targets, so the reader does not let one replace the other.
        val font = font(
            instrumentGlobal = listOf(ModSpec(cc1, to(1), 2), ModSpec(link, attenuation, 100)),
            zone = listOf(ModSpec(cc2, to(1), 3), ModSpec(link, attenuation, 20, transform = 2)),
            presetGlobal = listOf(ModSpec(cc4, to(1), 1), ModSpec(link, attenuation, 10, transform = 2)),
            preset = listOf(ModSpec(cc5, to(1), 5), ModSpec(link, attenuation, 8)),
        )
        val instrumentZone = font.zoneModulators()
        val presetZone = font.presets.single().zones.single().modulators
        assertEquals(listOf(0x8001, attenuation, 0x8003, attenuation), instrumentZone.map { it.destinationId })
        assertEquals(listOf(0x8001, attenuation, 0x8003, attenuation), presetZone.map { it.destinationId })

        // The plan puts the nine defaults first, then the instrument's list, then the preset's.
        val plan = font.voicesFor(0, 0, 60, 127).single()
        assertEquals(listOf(0x8000 or 10, attenuation, 0x8000 or 12, attenuation, 0x8000 or 14, attenuation, 0x8000 or 16, attenuation),
            plan.modulators.drop(9).map { it.destinationId })

        // 100 * 2 + 20 * 3 + 10 * 1 + 8 * 5 = 310 centibels.
        val controllers = mapOf(1 to 127, 2 to 127, 4 to 127, 5 to 127)
        val played = play(font, controllers)
        assertContentEquals(play(font(emptyList(), extras = listOf(attenuation to 310)), controllers), played)

        val again = KiteSynth.readFont(SoundFontWriter.write(font))
        assertEquals(instrumentZone.describe(), again.zoneModulators().describe())
        assertEquals(presetZone.describe(), again.presets.single().zones.single().modulators.describe())
        assertContentEquals(played, play(again, controllers))
    }

    @Test
    fun aLinkPastTheEndOfAGlobalZoneStaysPastTheEndOfTheZoneBelowIt() {
        // Index 5 is past the end of the global zone's one modulator. In the zone's own list, index 5 is a link target.
        val local = listOf(
            ModSpec(0x0083, attenuation, 0),
            ModSpec(0x0084, attenuation, 0),
            ModSpec(0x0085, attenuation, 0),
            ModSpec(0x0086, attenuation, 0),
            ModSpec(link, attenuation, 100),
        )
        val font = font(local, instrumentGlobal = listOf(ModSpec(cc1, to(5), 3)))
        assertEquals(0xFFFF, font.zoneModulators()[0].destinationId)
        assertContentEquals(play(font(local), mapOf(1 to 127)), play(font, mapOf(1 to 127)))
    }

    @Test
    fun theRightSidesLinkedPitchModulatorAlsoMovesTheLeftSide() {
        // CC 2 feeds 2 * 1, and the target adds 50 * 2 = 100 cents of fine tune to the right side, so to the pair.
        val linked = listOf(ModSpec(cc2, to(1), 2), ModSpec(link, Generator.FineTune.id, 50))
        val plain = listOf(ModSpec(cc2, Generator.FineTune.id, 100))
        fun perform(modulators: List<ModSpec>): FloatArray {
            val synth = StereoFonts.synth(StereoFonts.font(left(), Side(type = RIGHT, link = 0, modulators = modulators)))
            val parts = ArrayList<FloatArray>()
            synth.noteOn(0, 60, 100)
            take(synth, 4_800, parts)
            synth.controlChange(0, 2, 127)
            take(synth, 9_600, parts)
            return joined(parts)
        }
        val played = perform(linked)
        assertContentEquals(perform(plain), played)
        assertEquals(1000.0, frequency(crossings(played, 0, 480, 4_800)), 0.5, "the left side before the controller")
        val up = 1000.0 * 2.0.pow(100.0 / 1200.0)
        assertEquals(up, frequency(crossings(played, 0, 5_280, 14_400)), 0.5, "the left side after the controller")
        assertEquals(up, frequency(crossings(played, 1, 5_280, 14_400)), 0.5, "the right side after the controller")
    }

    @Test
    fun theOrderLeavesOutEveryInvalidModulatorAndPutsFeedersFirst() {
        fun of(vararg modulators: Modulator) = assertNotNull(ModulatorLinks.of(arrayOf(*modulators)))
        fun mod(source: Int, destination: Int) = Modulator(source, destination, 1, 0, 0)
        val links = of(
            mod(cc1, to(1)),          // 0 feeds 1
            mod(link, attenuation),   // 1
            mod(link, to(3)),         // 2 and 3 form a cycle
            mod(link, to(2)),
            mod(cc2, to(2)),          // 4 feeds the cycle
            mod(link, to(5)),         // 5 links to itself
            mod(cc4, to(99)),         // 6 past the end
            mod(cc5, to(8)),          // 7 feeds a modulator that reads a controller
            mod(cc1, Generator.Pan.id), // 8
            mod(cc2, 70),             // 9 is not a generator
            mod(link, to(0x7FFF)),    // 10 has no target
            mod(cc1, -0x10000),       // 11 is a negative number, which names no generator
        )
        assertContentEquals(intArrayOf(0, 1, 8), links.order)
        assertEquals(1, links.target[0])
        assertEquals(-1, links.target[1])

        // A chain stored backwards: the farthest feeder comes first, and the generator last.
        assertContentEquals(intArrayOf(2, 1, 0), of(mod(link, attenuation), mod(link, to(0)), mod(cc1, to(1))).order)
    }

    @Test
    fun twoVoicesPlayingOnePlanWithLinksEachSoundAsTheyDoAlone() {
        // Channel 0 pans hard left and channel 1 hard right, so each output channel holds one voice.
        fun perform(channels: List<Int>): FloatArray {
            val synth = Synth(writerTestFont(), rate).also { it.reverb.wet = 0.0 }
            synth.controlChange(0, 10, 0)
            synth.controlChange(1, 10, 127)
            val parts = ArrayList<FloatArray>()
            for (channel in channels) synth.noteOn(channel, 60, 127)
            take(synth, 1_200, parts)
            for ((step, values) in listOf(127 to 64, 20 to 100).withIndex()) {
                if (0 in channels) synth.controlChange(0, 1, values.first, atFrame = step * 7)
                if (1 in channels) synth.controlChange(1, 1, values.second, atFrame = step * 7)
                take(synth, 1_200, parts)
            }
            return joined(parts)
        }
        val both = perform(listOf(0, 1))
        val first = perform(listOf(0))
        val second = perform(listOf(1))
        assertTrue(first.any { it != 0f } && second.any { it != 0f })
        for (frame in 0 until both.size / 2) {
            assertEquals(first[frame * 2], both[frame * 2], "channel 0 at frame $frame")
            assertEquals(second[frame * 2 + 1], both[frame * 2 + 1], "channel 1 at frame $frame")
        }
        assertTrue((0 until both.size / 2).any { both[it * 2] != both[it * 2 + 1] }, "the two controllers made no difference")
    }

    @Test
    fun aSynthMakesRoomForLinksWheneverItPreparesAFont() {
        // The defaults less the one the global modulator replaces, then that modulator, the feeder and the target.
        assertEquals(11, Synth(writerTestFont(), rate).linkRoom)
        val synth = Synth(font(listOf(ModSpec(cc1, attenuation, 100))), rate)
        assertEquals(0, synth.linkRoom, "a font without links needs no room")
        synth.replaceFont(writerTestFont())
        assertEquals(11, synth.linkRoom)
        synth.font = font(listOf(ModSpec(cc1, to(1), 2), ModSpec(link, to(2), 3), ModSpec(link, attenuation, 50)))
        assertEquals(12, synth.linkRoom)
        synth.replaceFont(font(emptyList()))
        assertEquals(12, synth.linkRoom, "the room only grows")
    }

    @Test
    fun aVoiceDrivenByHandGrowsItsRoomOnceAndPlaysTheLinks() {
        // No controller reads as 1, so the feeder gives 2 and the target adds 300 * 2 = 600 centibels.
        val plan = font(listOf(ModSpec(0x0000, to(1), 2), ModSpec(link, attenuation, 300))).voicesFor(0, 0, 60, 127).single()
        val reference = font(emptyList(), extras = listOf(attenuation to 600)).voicesFor(0, 0, 60, 127).single()
        fun render(plan: VoicePlan, voice: Voice = Voice()): FloatArray {
            voice.start(plan, rate, channel = 0, age = 0)
            return FloatArray(2_400 * 2).also { voice.render(it, 0, 2_400) }
        }
        val voice = Voice()
        assertEquals(0, voice.linkRoom)
        val played = render(plan, voice)
        assertEquals(plan.modulators.size, voice.linkRoom)
        assertTrue(played.any { it != 0f })
        assertContentEquals(render(reference), played)
        assertContentEquals(played, render(plan, voice), "the second start played differently")
        assertEquals(plan.modulators.size, voice.linkRoom)
    }

    @Test
    fun aFontWithoutLinksKeepsThePlainWalk() {
        val plan = font(listOf(ModSpec(cc1, attenuation, 100))).voicesFor(0, 0, 60, 127).single()
        assertNull(plan.links)
        assertFalse(plan.modulators.any { (it.destinationId and 0x8000) != 0 })
    }
}
