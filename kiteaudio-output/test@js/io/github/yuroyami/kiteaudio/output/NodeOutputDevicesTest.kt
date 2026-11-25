package io.github.yuroyami.kiteaudio.output

import io.github.yuroyami.kiteaudio.core.AudioFormat
import io.github.yuroyami.kiteaudio.core.ChannelLayout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The real web bindings in Node.js, which has no `navigator.mediaDevices` and no Web Audio. */
class NodeOutputDevicesTest {

    @Test
    fun nodeListsNoOutputs() {
        assertEquals(emptyList(), runSuspending { audioOutputDevices() })
    }

    @Test
    fun anOpenWithADeviceFailsAsAnyOpenDoesInNode() {
        val device = AudioOutputDevice("abc", "Speakers", false, emptyList(), emptyList())
        for (policy in AudioFormatPolicy.entries) {
            assertFailsWith<UnsupportedOperationException> { openAudioOutput(AudioFormat(48_000, ChannelLayout.STEREO), 256, device, policy) }
        }
    }
}
