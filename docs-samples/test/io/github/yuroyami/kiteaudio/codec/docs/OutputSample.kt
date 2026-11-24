package io.github.yuroyami.kiteaudio.codec.docs

// --8<-- [start:imports]
import io.github.yuroyami.kiteaudio.codec.AudioKodec
import io.github.yuroyami.kiteaudio.output.AudioOutputEvent
import io.github.yuroyami.kiteaudio.output.openAudioOutput
import java.util.concurrent.atomic.AtomicBoolean
// --8<-- [end:imports]
import io.github.yuroyami.kiteaudio.codec.EncodeTarget
import io.github.yuroyami.kiteaudio.codec.Signals
import io.github.yuroyami.kiteaudio.core.AudioFormat
import io.github.yuroyami.kiteaudio.output.audioOutputAvailable
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

// --8<-- [start:play]
/** Decodes [bytes] and plays them on the default audio output. Returns after the last frame has played. */
fun play(bytes: ByteArray) {
    val audio = AudioKodec.decode(bytes)
    val channels = audio.format.channels
    var next = 0  // the next frame to hand over; only the render callback uses it
    val finished = AtomicBoolean(false)

    // The decoder and the output use the same AudioFormat type, so the format needs no conversion.
    val output = openAudioOutput(audio.format) { event ->
        // A failure or a lost device ends the stream, so stop waiting.
        if (event is AudioOutputEvent.Failed || event is AudioOutputEvent.DeviceLost) finished.set(true)
    }
    output.start { out, frames ->
        val count = minOf(frames, audio.frames - next)
        audio.samples.copyInto(out, 0, next * channels, (next + count) * channels)
        next += count
        if (next == audio.frames) finished.set(true)
        count  // fewer than `frames` means that the rest of the block is silence
    }
    while (!finished.get()) Thread.sleep(10)
    // The output still holds the last blocks. Give them time to play, then close.
    Thread.sleep(output.format.framesToMicros(output.latency.frames) / 1000 + 50)
    output.close()
}
// --8<-- [end:play]

class OutputSampleTest {
    /** A quarter second of a quiet 440 Hz tone, as a 16-bit WAV file. */
    private val wav = AudioKodec.encode(
        Signals.sine(Fixtures.RATE / 4, Fixtures.CHANNELS, Fixtures.RATE, 440.0, amplitude = 0.05f),
        Fixtures.format,
        EncodeTarget.wav(16),
    )

    @Test(timeout = 20_000)
    fun playADecodedFile() {
        assumeTrue("this machine has no audio output", audioOutputAvailable())
        play(wav)
    }

    @Test
    fun theOutputTakesTheDecodedFormatAsItIs() {
        val format: AudioFormat = AudioKodec.decode(wav).format
        assertEquals(Fixtures.format, format)
        // The output refuses more than two channels before it opens a device, so this needs no device.
        assertFailsWith<IllegalArgumentException> { openAudioOutput(AudioFormat(48_000, 6)) }
    }
}
