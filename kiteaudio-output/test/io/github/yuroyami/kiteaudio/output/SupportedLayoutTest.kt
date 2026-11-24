package io.github.yuroyami.kiteaudio.output

import io.github.yuroyami.kiteaudio.core.AudioFormat
import io.github.yuroyami.kiteaudio.core.ChannelLayout
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The layout check that every platform runs before it opens a device. */
class SupportedLayoutTest {

    @Test
    fun monoAndStereoPassWithoutSpeakerPositions() {
        requireSupportedLayout(AudioFormat(48_000, ChannelLayout.unknown(1)))
        requireSupportedLayout(AudioFormat(48_000, ChannelLayout.unknown(2)))
    }

    @Test
    fun monoAndStereoPassWithSpeakerPositions() {
        requireSupportedLayout(AudioFormat(48_000, ChannelLayout.MONO))
        requireSupportedLayout(AudioFormat(48_000, ChannelLayout.STEREO))
    }

    @Test
    fun aLayoutOfThreeChannelsFailsAndNamesTheCount() {
        val problem = assertFailsWith<IllegalArgumentException> {
            requireSupportedLayout(AudioFormat(48_000, ChannelLayout.unknown(3)))
        }
        assertTrue("3" in problem.message.orEmpty(), "the message does not name the count: ${problem.message}")
    }
}
