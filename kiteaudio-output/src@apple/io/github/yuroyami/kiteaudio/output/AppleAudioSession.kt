package io.github.yuroyami.kiteaudio.output

/** A notice from the system audio session that ends a running stream. */
internal enum class SessionNotice(val reason: String, val invalidatesQueue: Boolean) {
    InterruptionBegan("an audio session interruption began", false),
    OldDeviceUnavailable("the audio route lost its output device", false),
    MediaServicesLost("the system media services were lost", true),
    MediaServicesReset("the system media services were reset", true),
}

/**
 * Sends each session notice that ends a stream to [AppleAudioOutput.onSessionNotice].
 * Returns the function that stops watching. On macOS there is no audio session, so it watches nothing.
 */
internal expect fun watchAudioSession(output: AppleAudioOutput): () -> Unit
