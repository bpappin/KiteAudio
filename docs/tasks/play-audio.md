# Play decoded audio

**Depends on** `io.github.yuroyami:kiteaudio-codec:0.3.0`. The first section also needs
`io.github.yuroyami:kiteaudio-output:0.3.0`. See [Install](../index.md#install).

AudioKodec decodes and does not play. `kiteaudio-output` plays on the JVM, Android, iOS and
macOS, and in a browser from Kotlin/JS and Kotlin/Wasm. On the other platforms, or to keep
your own playback code, give the samples to the audio output of the platform yourself. Each
platform wants the samples in its own shape: interleaved 16-bit integers, interleaved
floats, or one float array per channel. AudioKodec decodes to interleaved Float32, and `Pcm`
converts to the others. The code on this page handles mono and stereo; for more channels,
match `format.channelLayout` to the order your platform expects.

## JVM, Android, iOS, macOS and the browser: kiteaudio-output

`kiteaudio-output` opens the default audio device and pulls the samples through a callback.
The decoder and the output use the same `AudioFormat` type, from `kiteaudio-core`, so the
decoded format goes to `openAudioOutput` as it is:

```kotlin
--8<-- "OutputSample.kt:imports"

--8<-- "OutputSample.kt:play"
```

The callback runs on an audio thread of the platform, or on the main thread of the page in
a browser, so it must not allocate, lock or block. The output plays one or two channels,
and a layout of more channels throws `IllegalArgumentException`. This sample decodes the
whole file first, which suits clips and songs. It waits with `Thread.sleep`, so it is code
for the JVM and Android. The `openAudioOutput` call and the callback are the same on iOS,
on macOS and in a browser. A browser usually lets a page play sound only after a click or
a key press, so start the output from such an event there. The
[README of kiteaudio-output](https://github.com/yuroyami/KiteAudio/blob/main/kiteaudio-output/README.md)
describes the events and the latency.

To play on another device, pass an entry of `audioOutputDevices()` as the `device` of
`openAudioOutput`. The open uses `AudioFormatPolicy.Exact` by default, so it fails when the
device rejects the decoded format. With `AudioFormatPolicy.Nearest` the output can open
another rate or channel count. `output.format` then differs from `audio.format`, and the
samples need a conversion first: see [Change the sample rate](../resample.md). The
[README of kiteaudio-output](https://github.com/yuroyami/KiteAudio/blob/main/kiteaudio-output/README.md#choose-a-device-and-a-format)
lists what each platform reports.

## JVM: Java Sound

A `SourceDataLine` takes 16-bit little-endian bytes:

```kotlin
--8<-- "PlaybackSample.kt:imports-jvm"

--8<-- "PlaybackSample.kt:jvm"
```

`line.write` blocks while the line's buffer is full, so call `playOnJvm` from a background
thread. On a machine with no audio output the line is not supported and `playOnJvm`
returns false. The documentation tests run it either way, and check `writePcm16`'s bytes.

## Android: AudioTrack

[`AudioTrack`](https://developer.android.com/reference/android/media/AudioTrack) built with
`AudioFormat.ENCODING_PCM_FLOAT` takes interleaved floats as they are, through
`write(FloatArray, offset, size, AudioTrack.WRITE_BLOCKING)`. Built with
`ENCODING_PCM_16BIT`, it takes shorts through `write(ShortArray, offset, size)`, and this
converts each block:

```kotlin
--8<-- "PlaybackSample.kt:android"
```

Set the track's channel mask to `CHANNEL_OUT_MONO` or `CHANNEL_OUT_STEREO` to match
`decoder.format.channels`, and its sample rate to `decoder.format.sampleRate`.

## Apple platforms: AVAudioEngine

An [`AVAudioPCMBuffer`](https://developer.apple.com/documentation/avfaudio/avaudiopcmbuffer)
in the standard format, from `AVAudioFormat(standardFormatWithSampleRate:channels:)`, holds
one float array per channel in `floatChannelData`. Split each block with `Pcm.deinterleave`,
copy plane `c` into `floatChannelData[c]`, set `frameLength`, and schedule the buffer on an
`AVAudioPlayerNode` attached to an `AVAudioEngine`:

```kotlin
--8<-- "PlaybackSample.kt:planar"
```

## Browser: Web Audio

An [`AudioBuffer`](https://developer.mozilla.org/en-US/docs/Web/API/AudioBuffer) also keeps
one array per channel, and `copyToChannel` fills one from a `Float32Array`, which is what a
Kotlin/JS `FloatArray` is:

```kotlin
--8<-- "WebAudioSample.kt:imports"

--8<-- "WebAudioSample.kt:web-audio"
```

Play the result with `context.createBufferSource()`: set its `buffer`, connect it to
`context.destination` and call `start()`. This sample is compiled for Kotlin/JS and runs in
the library's JavaScript tests, against a stand-in for the `AudioContext` because Node.js
has no Web Audio.

## Long files

The Java Sound, Android and Apple code converts one block at a time, so a long file can go
through [a streaming decoder](stream-and-seek.md) instead of being decoded whole. The Web
Audio sample copies a whole decoded file into one buffer, which suits clips and songs. To
play at a rate the device prefers, see [Change the sample rate](../resample.md).
