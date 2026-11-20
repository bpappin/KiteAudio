package io.github.yuroyami.kiteaudio.synth

import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Renders real audio to a file.
 *
 * Every other test asserts on numbers, which is the only way to check a synthesiser
 * automatically. A number can be right while the sound is wrong, so this writes a WAV somebody
 * can actually listen to. It still asserts what it can: the file is the right length, it is not
 * silent, and it does not clip.
 */
class RenderToWavTest {

    private val rate = 48_000

    /**
     * A plucked tone: a few harmonics with a decay, built in code.
     *
     * Not a piano, but close enough that a wrong pitch or a broken envelope is obvious to an ear
     * rather than only to a test.
     */
    private fun pluckFont(): SoundFont {
        val frequency = 261.626                       // middle C
        val cycleFrames = (rate / frequency).toInt()
        val frames = cycleFrames * 200

        val data = ShortArray(frames) { i ->
            val t = i.toDouble() / rate
            val phase = 2.0 * PI * frequency * t
            // A handful of harmonics, each quieter and each decaying faster than the last, which
            // is roughly what a struck string does.
            val value = sin(phase) * exp(-1.2 * t) +
                0.5 * sin(2 * phase) * exp(-2.0 * t) +
                0.25 * sin(3 * phase) * exp(-3.0 * t) +
                0.12 * sin(4 * phase) * exp(-4.5 * t)
            (value * 9000).toInt().coerceIn(-32768, 32767).toShort()
        }

        return KiteSynth.readFont(
            Sf2Builder().build(
                fontName = "Pluck",
                sampleFrames = data,
                samples = listOf(
                    Sf2Builder.SampleSpec("pluck", 0, frames, 0, 0, rate, 60),
                ),
                instrumentZones = listOf(
                    Sf2Builder.ZoneSpec(
                        sampleId = 0,
                        extras = listOf(
                            // About 400 ms of release, so letting go sounds like letting go.
                            Generator.ReleaseVolEnv.id to (-1585 and 0xFFFF),
                        ),
                    ),
                ),
            ),
        )
    }

    @Test
    fun aScaleAndAChordRenderToSomethingYouCanListenTo() {
        val font = pluckFont()
        val synth = Synth(font, rate, maxVoices = 64)

        val seconds = 9
        val frames = rate * seconds
        val out = FloatArray(frames * 2)

        // A C major scale up, one note every 300 ms, then a C major chord, then the same chord
        // with the pedal down so the release is obvious.
        val scale = intArrayOf(60, 62, 64, 65, 67, 69, 71, 72)
        val block = 512
        var frame = 0
        var noteIndex = 0
        var stage = 0

        while (frame < frames) {
            val chunk = minOf(block, frames - frame)
            val secondsIn = frame.toDouble() / rate

            // Scale, from 0 to 2.4 seconds.
            if (stage == 0 && noteIndex < scale.size && secondsIn >= noteIndex * 0.3) {
                if (noteIndex > 0) synth.noteOff(0, scale[noteIndex - 1])
                synth.noteOn(0, scale[noteIndex], 100)
                noteIndex++
                if (noteIndex == scale.size) stage = 1
            }
            if (stage == 1 && secondsIn >= 3.0) {
                synth.noteOff(0, scale.last())
                listOf(60, 64, 67, 72).forEach { synth.noteOn(0, it, 95) }
                stage = 2
            }
            if (stage == 2 && secondsIn >= 5.0) {
                listOf(60, 64, 67, 72).forEach { synth.noteOff(0, it) }
                stage = 3
            }
            if (stage == 3 && secondsIn >= 6.0) {
                synth.controlChange(0, 64, 127)                 // pedal down
                listOf(48, 55, 60, 64, 67).forEach { synth.noteOn(0, it, 85) }
                stage = 4
            }
            if (stage == 4 && secondsIn >= 7.0) {
                listOf(48, 55, 60, 64, 67).forEach { synth.noteOff(0, it) }
                stage = 5
            }

            val slice = FloatArray(chunk * 2)
            synth.render(slice, chunk)
            slice.copyInto(out, frame * 2)
            frame += chunk
        }

        val peak = out.maxOf { abs(it) }
        val loudness = out.sumOf { abs(it).toDouble() } / out.size

        assertTrue(loudness > 0.001, "the render is silent: average level $loudness")
        assertTrue(peak <= 1.0f, "the render clips: peak $peak")
        assertTrue(peak > 0.05f, "the render is far too quiet to hear: peak $peak")

        val target = File(System.getProperty("java.io.tmpdir"), "kiteaudio-synth-demo.wav")
        writeWav(target, out, frames, rate)
        println("KITESYNTH DEMO WAV: ${target.absolutePath} (${target.length()} bytes, peak $peak)")
        assertTrue(target.length() > frames * 2L, "the file is smaller than the audio in it")
    }

    /** A plain 16-bit stereo WAV. Nothing here is clever; it just has to open in a player. */
    private fun writeWav(file: File, samples: FloatArray, frames: Int, rate: Int) {
        val dataBytes = frames * 2 * 2
        val out = java.io.ByteArrayOutputStream(dataBytes + 44)

        fun ascii(text: String) = out.write(text.encodeToByteArray())
        fun u32(value: Int) {
            out.write(value and 0xFF); out.write((value shr 8) and 0xFF)
            out.write((value shr 16) and 0xFF); out.write((value shr 24) and 0xFF)
        }
        fun u16(value: Int) {
            out.write(value and 0xFF); out.write((value shr 8) and 0xFF)
        }

        ascii("RIFF"); u32(36 + dataBytes); ascii("WAVE")
        ascii("fmt "); u32(16); u16(1); u16(2); u32(rate)
        u32(rate * 4); u16(4); u16(16)
        ascii("data"); u32(dataBytes)

        for (i in 0 until frames * 2) {
            val clamped = samples[i].coerceIn(-1f, 1f)
            u16((clamped * 32767).toInt() and 0xFFFF)
        }
        file.writeBytes(out.toByteArray())
    }
}
