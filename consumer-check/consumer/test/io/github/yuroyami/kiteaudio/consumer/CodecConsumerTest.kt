package io.github.yuroyami.kiteaudio.consumer

import io.github.yuroyami.kiteaudio.codec.AudioContainer
import io.github.yuroyami.kiteaudio.codec.AudioKodec
import io.github.yuroyami.kiteaudio.codec.EncodeTarget
import io.github.yuroyami.kiteaudio.codec.io.readAudioSource
import io.github.yuroyami.kiteaudio.core.AudioFormat
import io.github.yuroyami.kiteaudio.core.ChannelLayout
import kotlinx.io.Buffer
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals

class CodecConsumerTest {

    @Test
    fun aFlacFileDecodesFromAKotlinxIoBuffer() {
        val format = AudioFormat(sampleRate = 8_000, channels = 1)
        val samples = FloatArray(800) { i -> (sin(2 * PI * i / 80) * 0.5).toFloat() }
        val flac = AudioKodec.encode(samples, format, EncodeTarget.flac(bitDepth = 16))

        assertEquals(AudioContainer.FLAC, AudioKodec.probe(flac).container)
        val decoded = AudioKodec.decode(Buffer().apply { write(flac) }.readAudioSource())
        assertEquals(format, decoded.format)
        assertEquals(ChannelLayout.MONO, decoded.format.channelLayout)
        assertEquals(samples.size, decoded.samples.size)
        for (i in samples.indices) assertEquals(samples[i], decoded.samples[i], 1f / 32_768)
    }
}
