# Module kiteaudio-codec

The pure-Kotlin audio codec module. Container parsing, codecs, the Float32 PCM model
and the hostile-input guards. It depends on `kotlin-stdlib` and `kiteaudio-core` only.
The audio format types, such as [io.github.yuroyami.kiteaudio.core.AudioFormat],
come from `kiteaudio-core`.

Start at [io.github.yuroyami.kiteaudio.codec.AudioKodec], which is the entry point for
sniffing, probing, decoding and encoding.
