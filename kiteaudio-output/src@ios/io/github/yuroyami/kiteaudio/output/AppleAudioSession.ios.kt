package io.github.yuroyami.kiteaudio.output

import platform.AVFAudio.AVAudioSessionInterruptionNotification
import platform.AVFAudio.AVAudioSessionInterruptionTypeBegan
import platform.AVFAudio.AVAudioSessionInterruptionTypeKey
import platform.AVFAudio.AVAudioSessionMediaServicesWereLostNotification
import platform.AVFAudio.AVAudioSessionMediaServicesWereResetNotification
import platform.AVFAudio.AVAudioSessionRouteChangeNotification
import platform.AVFAudio.AVAudioSessionRouteChangeReasonKey
import platform.AVFAudio.AVAudioSessionRouteChangeReasonOldDeviceUnavailable
import platform.Foundation.NSNotification
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSNumber

// The observers only read the notice and hand it on. The host app owns the session itself,
// so nothing here sets its category or activates it.
internal actual fun watchAudioSession(output: AppleAudioOutput): () -> Unit {
    val center = NSNotificationCenter.defaultCenter
    // A nil queue runs each block on the posting thread. onSessionNotice only hops to the control queue.
    val tokens = listOf(
        center.addObserverForName(AVAudioSessionInterruptionNotification, null, null) { note ->
            if (note.unsignedValue(AVAudioSessionInterruptionTypeKey) == AVAudioSessionInterruptionTypeBegan) {
                output.onSessionNotice(SessionNotice.InterruptionBegan)
            }
        },
        center.addObserverForName(AVAudioSessionRouteChangeNotification, null, null) { note ->
            if (note.unsignedValue(AVAudioSessionRouteChangeReasonKey) == AVAudioSessionRouteChangeReasonOldDeviceUnavailable) {
                output.onSessionNotice(SessionNotice.OldDeviceUnavailable)
            }
        },
        center.addObserverForName(AVAudioSessionMediaServicesWereLostNotification, null, null) {
            output.onSessionNotice(SessionNotice.MediaServicesLost)
        },
        center.addObserverForName(AVAudioSessionMediaServicesWereResetNotification, null, null) {
            output.onSessionNotice(SessionNotice.MediaServicesReset)
        },
    )
    return { tokens.forEach { center.removeObserver(it) } }
}

private fun NSNotification?.unsignedValue(key: String?): ULong? = when (val value = this?.userInfo?.get(key)) {
    is NSNumber -> value.unsignedLongValue
    is Number -> value.toLong().toULong()
    else -> null
}
