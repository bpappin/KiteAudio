package io.github.yuroyami.kiteaudio.synth

import kotlin.math.PI
import kotlin.math.sin

/** A General MIDI sized load for the render benchmarks: one zone with everything a busy zone uses. */
internal object BusyFont {

    /** 100 ms of a 440 Hz tone at [rate], looped end to end. */
    fun font(rate: Int): SoundFont {
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

    /** A synth at [rate] with 96 held notes over 8 channels, warmed up. */
    fun loaded(rate: Int, interpolation: Interpolation, block: Int): Synth {
        val synth = Synth(font(rate), rate, maxVoices = 128)
        synth.interpolation = interpolation
        for (channel in 0 until 8) {
            synth.controlChange(channel, 1, 64)
            synth.controlChange(channel, 4, 80)
            for (k in 0 until 12) synth.noteOn(channel, 40 + k * 3 + channel, 100)
        }
        val out = FloatArray(block * 2)
        repeat(50) { synth.render(out, block) }
        return synth
    }
}
