package io.github.yuroyami.kitesynth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SoundFontTest {

    private fun font(presetName: String, bank: Int, program: Int, sampleName: String): SoundFont =
        KiteSynth.readFont(Sf2Builder().build(presets = listOf(Sf2Builder.PresetSpec(presetName, bank, program, sampleName))))

    @Test
    fun theFirstFontWinsACollisionAndTheOtherPresetsComeAlong() {
        val piano = font("Piano", 0, 0, "piano")
        val gm = KiteSynth.readFont(
            Sf2Builder().build(
                fontName = "GM",
                presets = listOf(
                    Sf2Builder.PresetSpec("GM Piano", 0, 0, "gm piano"),
                    Sf2Builder.PresetSpec("Rhodes", 0, 5, "rhodes"),
                ),
            ),
        )
        val merged = piano.merged(gm)
        assertEquals("Piano", merged.preset(0, 0)!!.name)
        assertEquals("Rhodes", merged.preset(0, 5)!!.name)
        val rhodesZone = merged.instruments[merged.preset(0, 5)!!.zones.single().instrumentId!!].zones.single()
        assertEquals("rhodes", merged.samples[rhodesZone.sampleId!!].name)
        assertTrue(merged.samples.none { it.name == "gm piano" }, "the losing preset's sample is pruned")
        assertEquals(2, merged.instruments.size)
        assertSame(piano.samples[0].data, merged.samples[0].data, "merging copies no frames")
        assertSame(gm.samples[1].data, merged.samples[1].data)
    }

    @Test
    fun mergingKeepsBank128Kits() {
        val merged = font("Piano", 0, 0, "piano").merged(font("Standard", 128, 0, "kick"))
        assertEquals("Standard", merged.preset(128, 0)!!.name)
        assertEquals("kick", merged.voicesFor(merged.preset(128, 0)!!, 36, 100).single().sample.name)
    }

    @Test
    fun pruningDropsWhatNoPresetReachesAndRenumbersTheRest() {
        val font = KiteSynth.readFont(
            Sf2Builder().buildFont(
                sampleFrames = ShortArray(192) { 1000 },
                samples = listOf(
                    Sf2Builder.SampleSpec("orphan", 0, 64, 8, 56, 44100, 60),
                    Sf2Builder.SampleSpec("used", 64, 128, 72, 120, 44100, 60),
                    Sf2Builder.SampleSpec("unreached", 128, 192, 136, 184, 44100, 60),
                ),
                instruments = listOf(
                    Sf2Builder.InstrumentSpec("Nobody", listOf(Sf2Builder.ZoneSpec(sampleId = 2))),
                    Sf2Builder.InstrumentSpec("Somebody", listOf(Sf2Builder.ZoneSpec(sampleId = 1))),
                ),
                presets = listOf(Sf2Builder.PresetDef("Only", 0, 0, listOf(Sf2Builder.PresetZoneSpec(1)))),
            ),
        )
        val pruned = font.pruned()
        assertEquals(listOf("Somebody"), pruned.instruments.map { it.name })
        assertEquals(listOf("used"), pruned.samples.map { it.name })
        val presetZone = pruned.presets.single().zones.single()
        assertEquals(0, presetZone.instrumentId)
        assertEquals(0, presetZone.generators[Generator.Instrument], "the pointer generator follows the renumbering")
        assertEquals(0, pruned.instruments.single().zones.single().sampleId)
        assertEquals("used", pruned.voicesFor(0, 0, 60, 100).single().sample.name)
    }

    @Test
    fun aStereoPairKeepsItsLinksWhenMovedAndWhenPruned() {
        val piano = font("Piano", 0, 0, "piano")
        val stereo = KiteSynth.readFont(
            Sf2Builder().buildFont(
                sampleFrames = ShortArray(128) { 500 },
                samples = listOf(
                    Sf2Builder.SampleSpec("left", 0, 64, 8, 56, 44100, 60, type = 4, link = 1),
                    Sf2Builder.SampleSpec("right", 64, 128, 72, 120, 44100, 60, type = 2, link = 0),
                ),
                // Only the left side is named by a zone. Pruning must still keep the right side.
                instruments = listOf(Sf2Builder.InstrumentSpec("Pad inst", listOf(Sf2Builder.ZoneSpec(sampleId = 0)))),
                presets = listOf(Sf2Builder.PresetDef("Pad", 0, 1, listOf(Sf2Builder.PresetZoneSpec(0)))),
            ),
        )
        val merged = piano.merged(stereo)
        assertEquals(listOf("piano", "left", "right"), merged.samples.map { it.name })
        assertEquals(2, merged.samples[1].link)
        assertEquals(1, merged.samples[2].link)
    }

    @Test
    fun aMergedFontWritesAndReadsBack() {
        val merged = font("Piano", 0, 0, "piano").merged(font("Standard", 128, 0, "kick"))
        val again = KiteSynth.readFont(SoundFontWriter.write(merged))
        assertEquals(listOf("Piano", "Standard"), again.presets.map { it.name })
        assertNotNull(again.preset(128, 0))
        assertEquals(merged.samples.map { it.name }, again.samples.map { it.name })
    }

    @Test
    fun theFirstFontsBrokenPointerIsDroppedNotPointedAtTheSecondFont() {
        // Preset zone two names instrument 2, which this font does not have.
        val broken = KiteSynth.readFont(Sf2Builder().build(presetZones = listOf(0 to emptyList(), 2 to emptyList())))
        val other = KiteSynth.readFont(
            Sf2Builder().build(presets = listOf(Sf2Builder.PresetSpec("A", 0, 1, "a"), Sf2Builder.PresetSpec("B", 0, 2, "b"))),
        )
        val merged = broken.merged(other)
        assertEquals(1, merged.preset(0, 0)!!.zones.size, "the broken zone borrowed another font's instrument")
    }

    @Test
    fun aStereoChainOfThreeStaysWhole() {
        // A plays, A links to B, B to C, C back to A. All three are one sound.
        val font = KiteSynth.readFont(
            Sf2Builder().buildFont(
                sampleFrames = ShortArray(192) { 700 },
                samples = listOf(
                    Sf2Builder.SampleSpec("b", 0, 64, 8, 56, 44100, 60, type = 8, link = 1),
                    Sf2Builder.SampleSpec("c", 64, 128, 72, 120, 44100, 60, type = 8, link = 2),
                    Sf2Builder.SampleSpec("a", 128, 192, 136, 184, 44100, 60, type = 8, link = 0),
                ),
                instruments = listOf(Sf2Builder.InstrumentSpec("Chain", listOf(Sf2Builder.ZoneSpec(sampleId = 2)))),
                presets = listOf(Sf2Builder.PresetDef("Chain", 0, 0, listOf(Sf2Builder.PresetZoneSpec(0)))),
            ),
        )
        val pruned = font.pruned()
        assertEquals(listOf("b", "c", "a"), pruned.samples.map { it.name })
        assertEquals(listOf(1, 2, 0), pruned.samples.map { it.link })
    }

    @Test
    fun aBrokenStereoLinkBecomesAMonoSample() {
        val font = KiteSynth.readFont(
            Sf2Builder().build(samples = listOf(Sf2Builder.SampleSpec("lonely", 0, 64, 8, 56, 44100, 60, type = 4, link = 99))),
        )
        val sample = font.pruned().samples.single()
        assertEquals(1, sample.type, "a partner that does not exist cannot be paired")
        assertEquals(0, sample.link)
    }
}
