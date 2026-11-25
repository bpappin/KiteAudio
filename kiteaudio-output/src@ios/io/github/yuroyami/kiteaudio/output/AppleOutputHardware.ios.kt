package io.github.yuroyami.kiteaudio.output

import io.github.yuroyami.kiteaudio.core.AudioFormat
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionPortDescription
import platform.AVFAudio.currentRoute
import platform.AVFAudio.outputNumberOfChannels
import platform.AVFAudio.sampleRate

internal actual fun platformOutputHardware(): AppleOutputHardware = IosOutputHardware

/**
 * The shared audio session. Reading it changes nothing: the host app owns its category and its
 * activation, and the system chooses the route.
 */
internal object IosOutputHardware : AppleOutputHardware {
    override val setsQueueDevice: Boolean get() = false

    // One entry for the current route. A route can hold several outputs, so the id joins their UIDs.
    override fun devices(): List<AudioOutputDevice> {
        val outputs = AVAudioSession.sharedInstance().currentRoute.outputs.filterIsInstance<AVAudioSessionPortDescription>()
        val id = outputs.joinToString(",") { it.UID }.ifEmpty { IOS_EMPTY_ROUTE_ID }
        val name = outputs.joinToString(" + ") { it.portName }.ifEmpty { "No output" }
        return listOf(AudioOutputDevice(id, name, isDefault = true, sampleRates = emptyList(), channelCounts = emptyList()))
    }

    // The session answers for the current route whether or not a stream runs.
    override fun deviceFormat(id: String?): AudioFormat? {
        val session = AVAudioSession.sharedInstance()
        val rate = session.sampleRate.toInt()
        val channels = session.outputNumberOfChannels.toInt()
        return if (rate > 0 && channels > 0) AudioFormat(rate, channels) else null
    }
}

/** The id of the route entry when the route has no output. */
internal const val IOS_EMPTY_ROUTE_ID: String = "no-output"
