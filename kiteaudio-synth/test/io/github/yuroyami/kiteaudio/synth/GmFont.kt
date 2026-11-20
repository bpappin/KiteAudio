package io.github.yuroyami.kiteaudio.synth

private val GM_SAMPLES = listOf(
    "piano" to 60, "rhodes" to 60, "rhodes bright" to 60, "kick" to 36, "snare" to 38, "room kick" to 36,
)

/**
 * A small font shaped like a General MIDI bank: melodic presets on banks 0 and 1, two kits on
 * bank 128 and a mirror of one on bank 120. Every preset loops its own sample, so a test can tell
 * which one is playing.
 */
internal fun gmFont(): SoundFont {
    val length = 4800
    val pool = ShortArray(length * GM_SAMPLES.size) { index ->
        val sample = index / length
        ((index % length % (40 + 7 * sample)) * 400 - 8000).toShort()
    }
    val samples = GM_SAMPLES.mapIndexed { i, (name, root) ->
        Sf2Builder.SampleSpec(name, i * length, i * length + length, i * length + 100, i * length + length - 100, 48_000, root)
    }
    fun looped(sampleId: Int, key: Int? = null) = Sf2Builder.ZoneSpec(
        sampleId = sampleId, keyLow = key ?: 0, keyHigh = key ?: 127,
        extras = listOf(Generator.SampleModes.id to 1),
    )
    val drums = listOf(Generator.ScaleTuning.id to 0)
    return KiteSynth.readFont(
        Sf2Builder().buildFont(
            fontName = "GM",
            sampleFrames = pool,
            samples = samples,
            instruments = listOf(
                Sf2Builder.InstrumentSpec("Piano", listOf(looped(0))),
                Sf2Builder.InstrumentSpec("Rhodes", listOf(looped(1))),
                Sf2Builder.InstrumentSpec("Rhodes Bright", listOf(looped(2))),
                Sf2Builder.InstrumentSpec("Standard Kit", listOf(looped(3, 36), looped(4, 38)), global = drums),
                Sf2Builder.InstrumentSpec("Room Kit", listOf(looped(5, 36)), global = drums),
            ),
            presets = listOf(
                Sf2Builder.PresetDef("Piano", 0, 0, listOf(Sf2Builder.PresetZoneSpec(0))),
                Sf2Builder.PresetDef("Rhodes", 0, 5, listOf(Sf2Builder.PresetZoneSpec(1))),
                Sf2Builder.PresetDef("Rhodes Bright", 1, 5, listOf(Sf2Builder.PresetZoneSpec(2))),
                Sf2Builder.PresetDef("Standard Kit", 128, 0, listOf(Sf2Builder.PresetZoneSpec(3))),
                Sf2Builder.PresetDef("Room Kit", 128, 8, listOf(Sf2Builder.PresetZoneSpec(4))),
                Sf2Builder.PresetDef("Standard Kit copy", 120, 0, listOf(Sf2Builder.PresetZoneSpec(3))),
            ),
        ),
    )
}
