package io.github.yuroyami.kiteaudio.output

// macOS has no AVAudioSession. The running-state listener of the queue covers device loss there.
internal actual fun watchAudioSession(output: AppleAudioOutput): () -> Unit = {}
