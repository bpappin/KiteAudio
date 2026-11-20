package io.github.yuroyami.kiteaudio.synth

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SoundFontWriterTest {

    @Test
    fun readWriteReadIsTheSameFont() {
        val original = KiteSynth.readFont(
            Sf2Builder().build(
                sampleFrames = ShortArray(128) { (it * 250 - 16000).toShort() },
                samples = listOf(
                    Sf2Builder.SampleSpec("a", 0, 64, 8, 56, 44100, 60),
                    Sf2Builder.SampleSpec("b", 64, 128, 70, 120, 22050, 48, correction = -7, type = 2, link = 0),
                ),
                instrumentGlobal = listOf(Generator.ReleaseVolEnv.id to 1200),
                instrumentGlobalModulators = listOf(Sf2Builder.ModSpec(0x0502, 48, 960)),
                instrumentZones = listOf(
                    Sf2Builder.ZoneSpec(sampleId = 0, keyHigh = 63),
                    Sf2Builder.ZoneSpec(
                        sampleId = 1, keyLow = 64, velLow = 20,
                        extras = listOf(Generator.Pan.id to (-200 and 0xFFFF)),
                        modulators = listOf(Sf2Builder.ModSpec(0x0081, 6, -40)),
                    ),
                ),
                presetGlobal = listOf(Generator.InitialAttenuation.id to 30),
                presetZones = listOf(0 to listOf(Generator.CoarseTune.id to 12)),
                engineer = "Somebody",
                comment = "A note",
            ),
        )
        val again = KiteSynth.readFont(SoundFontWriter.write(original))
        assertFontsEqual(original, again)
        assertEquals("EMU8000", again.info["isng"], "the format's required sound engine line is there")
    }

    @Test
    fun manyPresetsInstrumentsAndAKitRoundTrip() {
        val original = KiteSynth.readFont(
            Sf2Builder().buildFont(
                fontName = "Bank",
                sampleFrames = ShortArray(192) { (it * 100).toShort() },
                samples = listOf(
                    Sf2Builder.SampleSpec("piano", 0, 64, 8, 56, 44100, 60),
                    Sf2Builder.SampleSpec("kick", 64, 128, 0, 0, 44100, 36),
                    Sf2Builder.SampleSpec("snare", 128, 192, 0, 0, 44100, 38),
                ),
                instruments = listOf(
                    Sf2Builder.InstrumentSpec("Piano inst", listOf(Sf2Builder.ZoneSpec(sampleId = 0))),
                    Sf2Builder.InstrumentSpec(
                        name = "Kit inst",
                        zones = listOf(
                            Sf2Builder.ZoneSpec(sampleId = 1, keyLow = 36, keyHigh = 36, extras = listOf(Generator.ExclusiveClass.id to 1)),
                            Sf2Builder.ZoneSpec(sampleId = 2, keyLow = 38, keyHigh = 38, extras = listOf(Generator.ExclusiveClass.id to 1)),
                        ),
                        global = listOf(Generator.ScaleTuning.id to 0),
                    ),
                ),
                presets = listOf(
                    Sf2Builder.PresetDef("Piano", 0, 0, listOf(Sf2Builder.PresetZoneSpec(0))),
                    Sf2Builder.PresetDef("Bright", 1, 0, listOf(Sf2Builder.PresetZoneSpec(0, extras = listOf(Generator.FineTune.id to 5)))),
                    Sf2Builder.PresetDef("Standard", 128, 0, listOf(Sf2Builder.PresetZoneSpec(1))),
                ),
            ),
        )
        val again = KiteSynth.readFont(SoundFontWriter.write(original))
        assertFontsEqual(original, again)
        assertEquals("Standard", again.preset(128, 0)!!.name)
    }

    @Test
    fun theWrittenFontPlaysTheSameAsTheOriginal() {
        val original = KiteSynth.readFont(Sf2Builder().build(sampleFrames = ShortArray(4800) { (it % 100 * 300).toShort() },
            samples = listOf(Sf2Builder.SampleSpec("ramp", 0, 4800, 100, 4700, 48_000, 60)),
            instrumentZones = listOf(Sf2Builder.ZoneSpec(sampleId = 0, extras = listOf(Generator.SampleModes.id to 1)))))
        val again = KiteSynth.readFont(SoundFontWriter.write(original))
        fun play(font: SoundFont): FloatArray {
            val synth = Synth(font, 48_000, 8).apply { reverb.wet = 0.0 }
            synth.noteOn(0, 64, 100)
            return FloatArray(2048).also { synth.render(it, 1024) }
        }
        val expected = play(original)
        assertTrue(expected.any { it != 0f }, "the original font is silent")
        assertContentEquals(expected, play(again))
    }

    private fun assertFontsEqual(expected: SoundFont, actual: SoundFont) {
        assertEquals(expected.name, actual.name)
        assertEquals(expected.info - "isng", actual.info - "isng")
        assertEquals(expected.presets.map { it.describe() }, actual.presets.map { it.describe() })
        assertEquals(expected.instruments.map { it.describe() }, actual.instruments.map { it.describe() })
        assertEquals(expected.samples.size, actual.samples.size)
        expected.samples.zip(actual.samples).forEach { (e, a) ->
            assertEquals(e.describe(), a.describe())
            assertContentEquals(e.data, a.data, "frames of ${e.name}")
        }
    }

    private fun GeneratorSet.describe() = all().entries.sortedBy { it.key }.map { it.key to it.value }

    private fun List<Modulator>.describe() = map { listOf(it.source, it.destinationId, it.amount, it.amountSource, it.transform) }

    private fun Preset.describe() = listOf(name, bank, program, zones.map { listOf(it.generators.describe(), it.keys, it.velocities, it.instrumentId, it.modulators.describe()) })

    private fun Instrument.describe() = listOf(name, zones.map { listOf(it.generators.describe(), it.keys, it.velocities, it.sampleId, it.modulators.describe()) })

    private fun SampleHeader.describe() = listOf(name, loopStart, loopEnd, sampleRate, originalKey, correctionCents, link, type)

    @Test
    fun aFontWithNothingToPlayIsRefusedByName() {
        val failure = assertFailsWith<IllegalArgumentException> {
            SoundFontWriter.write(SoundFont("empty", emptyList(), emptyList(), emptyList()))
        }
        assertTrue(failure.message!!.contains("preset"), failure.message)
    }

    /** Two zones over one global modulator, each with its own list. */
    private fun twoZoneModulatorFont(first: List<Sf2Builder.ModSpec>, second: List<Sf2Builder.ModSpec>): SoundFont =
        KiteSynth.readFont(
            Sf2Builder().build(
                sampleFrames = ShortArray(128) { 900 },
                samples = listOf(Sf2Builder.SampleSpec("a", 0, 64, 8, 56, 44100, 60), Sf2Builder.SampleSpec("b", 64, 128, 72, 120, 44100, 72)),
                instrumentGlobalModulators = listOf(Sf2Builder.ModSpec(0x0502, 48, 960)),
                instrumentZones = listOf(
                    Sf2Builder.ZoneSpec(sampleId = 0, keyHigh = 63, modulators = first),
                    Sf2Builder.ZoneSpec(sampleId = 1, keyLow = 64, modulators = second),
                ),
            ),
        )

    @Test
    fun sharedModulatorsGoBackIntoTheGlobalZone() {
        val original = twoZoneModulatorFont(listOf(Sf2Builder.ModSpec(0x0081, 6, 50)), listOf(Sf2Builder.ModSpec(0x0102, 8, -2400)))
        val bytes = SoundFontWriter.write(original)
        assertEquals(3, recordsIn(bytes, "imod", 10), "one global modulator, then one in each zone")
        assertFontsEqual(original, KiteSynth.readFont(bytes))
    }

    @Test
    fun aLinkedModulatorPointsAtItsTargetInTheZonesOwnList() {
        // The first feeds the second: bit 15 marks a link, the rest counts places in the zone's list.
        val linked = listOf(Sf2Builder.ModSpec(0x0081, 0x8001, 50), Sf2Builder.ModSpec(0x0102, 8, -2400))
        val original = twoZoneModulatorFont(linked, linked)
        val zone = original.instruments.single().zones.first()
        assertEquals(0x8002, zone.modulators[1].destinationId, "the global modulator sits in front, so the target is now third")
        val bytes = SoundFontWriter.write(original)
        assertEquals(6, recordsIn(bytes, "imod", 10), "a zone with a link keeps its whole list")
        assertFontsEqual(original, KiteSynth.readFont(bytes))
    }

    @Test
    fun aHandBuiltZoneKeepsItsKeyRange() {
        val sample = SampleHeader("one", ShortArray(64) { 1000 }, 8, 56, 44100, 36, 0, 0, 1)
        val zone = InstrumentZone(GeneratorSet(mapOf(Generator.SampleId.id to 0)), Span(36, 36), Span.Full, 0)
        val presetZone = PresetZone(GeneratorSet(mapOf(Generator.Instrument.id to 0)), Span.Full, Span.Full, 0)
        val font = SoundFont("hand", listOf(Preset("Kick", 0, 128, listOf(presetZone))), listOf(Instrument("Kick", listOf(zone))), listOf(sample))
        val again = KiteSynth.readFont(SoundFontWriter.write(font))
        assertEquals(Span(36, 36), again.instruments.single().zones.single().keys)
    }

    /** How many records a pdta list holds, not counting its terminal record. */
    private fun recordsIn(bytes: ByteArray, id: String, recordSize: Int): Int {
        val tag = id.encodeToByteArray()
        for (i in 0 until bytes.size - 8) {
            if (bytes[i] == tag[0] && bytes[i + 1] == tag[1] && bytes[i + 2] == tag[2] && bytes[i + 3] == tag[3]) {
                val length = (bytes[i + 4].toInt() and 0xFF) or ((bytes[i + 5].toInt() and 0xFF) shl 8) or
                    ((bytes[i + 6].toInt() and 0xFF) shl 16) or ((bytes[i + 7].toInt() and 0xFF) shl 24)
                return length / recordSize - 1
            }
        }
        error("no $id chunk")
    }
}
