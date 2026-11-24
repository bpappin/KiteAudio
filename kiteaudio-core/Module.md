# Module kiteaudio-core

The audio format types that the other KiteAudio modules share, with no
dependency beyond `kotlin-stdlib`.

[io.github.yuroyami.kiteaudio.core.AudioFormat] holds a sample rate and a
[io.github.yuroyami.kiteaudio.core.ChannelLayout], and converts between frames
and microseconds. [io.github.yuroyami.kiteaudio.core.PcmSource] is a pull-based
stream of interleaved Float32 samples.
