package io.github.yuroyami.kiteaudio.consumer

import io.github.yuroyami.kiteaudio.output.AudioRenderCallback
import io.github.yuroyami.kiteaudio.output.Latency
import io.github.yuroyami.kiteaudio.output.LatencyQuality
import io.github.yuroyami.kiteaudio.output.audioOutputAvailable
import kotlin.test.Test
import kotlin.test.assertEquals

class OutputConsumerTest {

    @Test
    fun theOutputTypesLinkWithoutADevice() {
        // A runner has no sound device, so the test opens none. It only asks whether one exists.
        audioOutputAvailable()
        assertEquals(LatencyQuality.Unknown, Latency.Unknown.quality)

        val silence = AudioRenderCallback { out, frames -> out.fill(0f, 0, frames * 2); frames }
        val buffer = FloatArray(8) { 1f }
        assertEquals(4, silence.render(buffer, 4))
        assertEquals(0f, buffer.sum())
    }
}
