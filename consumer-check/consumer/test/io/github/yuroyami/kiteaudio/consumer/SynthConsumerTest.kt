package io.github.yuroyami.kiteaudio.consumer

import io.github.yuroyami.kiteaudio.synth.GeneratorSet
import io.github.yuroyami.kiteaudio.synth.Instrument
import io.github.yuroyami.kiteaudio.synth.InstrumentZone
import io.github.yuroyami.kiteaudio.synth.KiteSynth
import io.github.yuroyami.kiteaudio.synth.Preset
import io.github.yuroyami.kiteaudio.synth.PresetZone
import io.github.yuroyami.kiteaudio.synth.SampleHeader
import io.github.yuroyami.kiteaudio.synth.SoundFont
import io.github.yuroyami.kiteaudio.synth.SoundFontFormatException
import io.github.yuroyami.kiteaudio.synth.SoundFontWriter
import io.github.yuroyami.kiteaudio.synth.Span
import io.github.yuroyami.kiteaudio.synth.Synth
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SynthConsumerTest {

    /** A font with one preset that plays a 480 Hz sine. */
    private fun sineFont(): SoundFont {
        val frames = ShortArray(4_800) { i -> (sin(2 * PI * i / 100) * 20_000).toInt().toShort() }
        val sample = SampleHeader(
            name = "sine", data = frames, loopStart = 0, loopEnd = frames.size, sampleRate = 48_000,
            originalKey = 69, correctionCents = 0, link = 0, type = 1,
        )
        val instrument = Instrument("sine", listOf(InstrumentZone(GeneratorSet(), Span.Full, Span.Full, sampleId = 0)))
        val preset = Preset("sine", program = 0, bank = 0, zones = listOf(PresetZone(GeneratorSet(), Span.Full, Span.Full, instrumentId = 0)))
        return SoundFont("consumer", listOf(preset), listOf(instrument), listOf(sample))
    }

    @Test
    fun aWrittenFontReadsBackAndPlays() {
        val font = KiteSynth.readFont(SoundFontWriter.write(sineFont()))
        assertEquals("sine", font.defaultPreset?.name)

        val synth = Synth(font)
        synth.noteOn(channel = 0, key = 69, velocity = 127)
        val out = FloatArray(2 * 512)
        synth.render(out, 512)
        assertTrue(out.any { it != 0f }, "the note made no sound")
    }

    @Test
    fun bytesThatAreNotAFontAreRefused() {
        assertFailsWith<SoundFontFormatException> { KiteSynth.readFont(byteArrayOf(1, 2, 3)) }
    }
}
