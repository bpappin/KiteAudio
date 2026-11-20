package io.github.yuroyami.kiteaudio.synth

import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A render budget for a General MIDI sized load: 96 held notes over 8 channels, every voice with a
 * filter, both LFOs, a modulation envelope and eight modulators of its own.
 *
 * A local number, not a contract. It asserts on a developer machine and only prints on CI.
 */
class RenderBenchmarkTest {

    private val rate = 48_000
    private val block = 512

    /** 100 ms of a 440 Hz tone looped end to end, with everything a busy General MIDI zone uses. */
    private fun busyFont(): SoundFont {
        val frames = rate / 10
        val data = ShortArray(frames) { (sin(2 * PI * 440.0 * it / rate) * 12_000).toInt().toShort() }
        val zone = Sf2Builder.ZoneSpec(
            sampleId = 0,
            extras = listOf(
                Generator.SampleModes.id to 1,
                Generator.InitialFilterFc.id to 9_000,
                Generator.InitialFilterQ.id to 60,
                Generator.ModLfoToPitch.id to 20,
                Generator.VibLfoToPitch.id to 15,
                Generator.ModLfoToFilterFc.id to 600,
                Generator.ModEnvToFilterFc.id to 2_400,
                Generator.ModLfoToVolume.id to 30,
                Generator.FreqModLfo.id to 200,
                Generator.FreqVibLfo.id to 300,
                Generator.DelayModLfo.id to -4_000,
                Generator.AttackModEnv.id to -2_400,
                Generator.SustainModEnv.id to 500,
                Generator.ReleaseVolEnv.id to -1_200,
                Generator.ReverbEffectsSend.id to 300,
                Generator.ChorusEffectsSend.id to 300,
            ),
            modulators = listOf(
                Sf2Builder.ModSpec(0x0081, Generator.ModLfoToPitch.id, 50),       // CC 1 to the other LFO
                Sf2Builder.ModSpec(0x0082, Generator.InitialFilterFc.id, -1_200), // CC 2 closes the filter
                Sf2Builder.ModSpec(0x0284, Generator.Pan.id, 200),                // CC 4, bipolar, to pan
                Sf2Builder.ModSpec(0x008B, Generator.ModLfoToVolume.id, 20),      // CC 11 to tremolo
                Sf2Builder.ModSpec(0x0003, Generator.InitialFilterFc.id, 1_200),  // key tracking
                Sf2Builder.ModSpec(0x0002, Generator.ModEnvToFilterFc.id, 1_200), // velocity opens the sweep
                Sf2Builder.ModSpec(0x000D, Generator.ModLfoToFilterFc.id, 600),   // pressure to the filter LFO
                Sf2Builder.ModSpec(0x020E, Generator.FineTune.id, 50),            // the wheel to fine tune
            ),
        )
        return KiteSynth.readFont(
            Sf2Builder().buildFont(
                fontName = "Busy",
                sampleFrames = data,
                samples = listOf(Sf2Builder.SampleSpec("tone", 0, frames, 0, frames, rate, 69)),
                instruments = listOf(Sf2Builder.InstrumentSpec("Busy inst", listOf(zone))),
                presets = listOf(Sf2Builder.PresetDef("Busy", 0, 0, listOf(Sf2Builder.PresetZoneSpec(0)))),
            ),
        )
    }

    @Test
    fun aGeneralMidiLoadFitsInABlockWithRoomToSpare() {
        val synth = Synth(busyFont(), rate, maxVoices = 128)
        for (channel in 0 until 8) {
            synth.controlChange(channel, 1, 64)
            synth.controlChange(channel, 4, 80)
            for (k in 0 until 12) synth.noteOn(channel, 40 + k * 3 + channel, 100)
        }
        val out = FloatArray(block * 2)
        repeat(50) { synth.render(out, block) }
        assertEquals(96, synth.activeVoices, "every note should still sound after the warm-up")

        val times = LongArray(400)
        for (i in times.indices) {
            // The mod wheel moves now and then, so the modulators are recomputed inside the timing.
            if (i % 20 == 0) for (channel in 0 until 8) synth.controlChange(channel, 1, 40 + i % 60)
            val start = System.nanoTime()
            synth.render(out, block)
            times[i] = System.nanoTime() - start
        }
        val meanMs = times.average() / 1e6
        val worstMs = times.max() / 1e6
        println("Render budget: mean %.3f ms, worst %.3f ms for %d frames (%.1f ms of audio)"
            .format(meanMs, worstMs, block, block * 1000.0 / rate))
        assertEquals(96, synth.activeVoices, "every note should still sound after the timing")

        if (System.getenv("CI") != null) return  // a shared runner is too noisy for a budget
        assertTrue(meanMs < 1.6, "the mean block took %.3f ms, the budget is 1.6".format(meanMs))
        assertTrue(worstMs < 5.0, "the worst block took %.3f ms, the ceiling is 5".format(worstMs))
    }
}
