<h1 align="center">KiteRT</h1>

<p align="center">
  Audio output for Kotlin Multiplatform: fill interleaved floating-point samples in
  <code>commonMain</code>, and send them to the platform's audio backend.
</p>

<p align="center">
  <sub>AI tools wrote much of the code, tests and documentation in this project, under the direction
  and review of its author.</sub>
</p>

<p align="center">
  <a href="https://kotlinlang.org"><img src="https://img.shields.io/badge/Kotlin-2.4.21-7F52FF?logo=kotlin&logoColor=white" alt="Kotlin 2.4.21"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-Apache--2.0-blue" alt="License: Apache-2.0"></a>
</p>

## What you get

KiteRT provides one small output API backed by Java Sound on the JVM, `AudioTrack` on
Android, and AudioQueue on Apple. Your renderer supplies mono or stereo `FloatArray`
samples; each backend clamps them and converts them to signed 16-bit PCM.

| Part | What it does |
| --- | --- |
| `AudioSink` | Opens, starts, stops and releases a platform output stream. |
| `AudioRenderCallback` | Fills the requested frames; a short render is padded with silence. |
| `AudioFormat` and `ChannelLayout` | Describe sample rate and mono or stereo interleaving. |
| `Latency` and `LatencyQuality` | Report a delay value and distinguish estimates from measurements. All current backends report estimates. |
| `AudioSinkEvent` | Reports startup, deliberate stops, short renders, slow renders and some failures. Device-loss detection differs by backend. |

There is no Compose dependency, UI, audio decoder, synthesizer or input stream. Pair it
with [KiteSynth](https://github.com/yuroyami/KiteAudio/tree/main/kiteaudio-synth) to generate sound and
[KiteMIDI](https://github.com/yuroyami/KiteAudio/tree/main/kiteaudio-midi) to read MIDI messages.

> [!NOTE]
> The configured project version is `0.1.0`. The API is early and can change. A Maven Central
> release is not available at the time of this README update; use a source checkout.

## Install from source

KiteRT builds with the [Kotlin Toolchain](https://github.com/JetBrains/kotlin-toolchain), the
`./kotlin` script in the repository root. Clone this repository, then publish it to your local
Maven repository:

```sh
./kotlin publish mavenLocal -m kiteaudio-output
```

The publish signs the artifacts, so it needs a signing key in the `KOTLIN_TOOLCHAIN_SIGNING_KEY`
and `KOTLIN_TOOLCHAIN_SIGNING_KEY_PASSPHRASE` environment variables. Add `mavenLocal()` to the
repositories of your application, then add the dependency in a Kotlin Multiplatform application:

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("io.github.yuroyami:kiteaudio-output:0.1.0")
        }
    }
}
```

For a plain JVM or Android project, use the same dependency in your usual `dependencies { }`
block. With the Kotlin Toolchain, add it to `module.yaml`:

```yaml
dependencies:
  - io.github.yuroyami:kiteaudio-output:0.1.0
```

## A quick tour

### Render sound

This renderer makes a quiet mono sine wave and writes it to every configured channel.
Create and start it from your application's lifecycle code, and keep the returned sink
until playback ends.

```kotlin
import io.github.yuroyami.kiteaudio.output.AudioFormat
import io.github.yuroyami.kiteaudio.output.AudioSink
import io.github.yuroyami.kiteaudio.output.openAudioSink
import kotlin.math.PI
import kotlin.math.sin

fun startTone(): AudioSink {
    val sink = openAudioSink(
        preferredFormat = AudioFormat(sampleRate = 48_000), // stereo by default
        bufferFrames = 512,
    )
    val format = sink.format
    val step = 2.0 * PI * 440.0 / format.sampleRate
    var phase = 0.0

    try {
        sink.start { out, frames ->
            for (frame in 0 until frames) {
                val value = (sin(phase) * 0.03).toFloat()
                for (channel in 0 until format.channels) {
                    out[frame * format.channels + channel] = value
                }
                phase += step
                if (phase >= 2.0 * PI) phase -= 2.0 * PI
            }
            frames
        }
    } catch (failure: Exception) {
        sink.close()
        throw failure
    }
    return sink
}

val sink = startTone()
// Later, from the application's control/lifecycle thread:
sink.stop()
sink.close()
```

For mono, pass `AudioFormat(48_000, ChannelLayout.Mono)`. The callback receives
`bufferFrames * sink.format.channels` samples. Fill samples in channel order for each
frame and return a count in `0..frames`; returning `0` asks for silence. The array is
reused, so do not retain it or pass it to another thread.

### Inspect timing and events

```kotlin
import io.github.yuroyami.kiteaudio.output.AudioFormat
import io.github.yuroyami.kiteaudio.output.AudioSinkEvent
import io.github.yuroyami.kiteaudio.output.openAudioSink

val sink = openAudioSink(AudioFormat(48_000), bufferFrames = 512) { event ->
    when (event) {
        is AudioSinkEvent.Started -> Unit   // format and latency are available here
        is AudioSinkEvent.Underrun -> Unit  // event.frames were replaced with silence
        is AudioSinkEvent.Overrun -> Unit   // the render took event.tookMicros, past event.deadlineMicros
        is AudioSinkEvent.DeviceLost -> Unit
        is AudioSinkEvent.Failed -> Unit
        AudioSinkEvent.Stopped -> Unit
    }
}

val estimatedMicros = sink.format.framesToMicros(sink.latency.frames)
val quality = sink.latency.quality
sink.close()
```

An `Underrun` event means the renderer returned fewer frames. An `Overrun` event means a
render took longer than the block it filled lasts, even when it returned every frame.
Neither one measures hardware underruns. An invalid frame count or render exception stops the stream and emits
`Failed`. Event-handler exceptions are contained so they cannot escape a worker or native callback.

## Platforms

| Target | Backend | Integration requirements and current coverage |
| --- | --- | --- |
| JVM | `javax.sound.sampled.SourceDataLine` | JDK 21 toolchain; an installed Java Sound output provider supporting signed 16-bit PCM. The repository includes live audio tests. Linux and Windows use this JVM backend; there are no native Linux or Windows targets. |
| Android | Streaming `AudioTrack` | minSdk 23, compileSdk 36. Requests low-latency performance mode on API 26 and above; the system may choose a different performance path. An offline source-level fake-backend harness is included; it is not a device test. |
| iOS arm64 | AudioQueue | Built as a dynamic `KiteRT` framework. The host app owns its audio session, activation, interruptions and background-audio policy. No iOS test sources are included. |
| iOS simulator arm64 and x64 | AudioQueue | Framework targets are configured. Simulator support does not establish physical-device behavior. |
| macOS arm64 | AudioQueue | Uses the system AudioToolbox platform bindings. The repository includes live audio tests. |

macOS x64, tvOS, watchOS, native Linux and Windows, JS and Wasm are not configured.
`audioOutputAvailable()` checks Java Sound support for one default format on the JVM;
on Android and Apple it always returns `true`. It does not prove that a stream can open,
that a route is available, or that sound reaches a speaker.

Apple uses Kotlin/Native's system AudioToolbox bindings and a block callback on a private serial
dispatch queue. A separate control queue owns setup and teardown; callback ownership survives
until native disposal succeeds. There is no custom cinterop definition or bundled C source.
The shared heap does not synchronize application renderer state: UI, MIDI and audio work still
need an explicit handoff.

## Resource and thread contracts

- Allocate renderer state before `start()`. Keep the render path bounded: no file access,
  logging, waits or locks. Reuse buffers and keep allocation out of the callback.
- JVM and Android render on a dedicated writer thread. Apple primes and subsequently renders
  on one serial callback queue; dispatch may use different OS threads over time.
- Event handlers must be fast. Transfer UI work to the UI thread outside the callback.
  `stop()` and `close()` are safe from a render or event callback; they never wait for themselves.
- `stop()` cancels the current generation. JVM/Android unblock output and wait up to 500 ms;
  Apple schedules teardown. In-progress work may finish after return. Restart is rejected until
  rendering and terminal event delivery have unwound, including the `Stopped` observer itself.
- `close()` makes the sink permanently closed and releases platform output. Always call it,
  including after startup failure. A failed native disposal retains callback ownership and
  blocks restart; a later `stop()` or `close()` retries disposal.
- Use a positive, modest `bufferFrames` value. Bounds and multiplication are validated before
  allocation; return a frame count in `0..frames`. Partial writes are completed before the
  next block is rendered, and repeated zero-progress writes stop with `DeviceLost`.

## Good to know

| Topic | Current limit |
| --- | --- |
| Real-time guarantees | JVM thread priority and Android's audio priority are scheduling requests. Kotlin/Native garbage collection and native callback execution also affect deadlines. No backend promises a hard deadline. |
| Latency | JVM and Android report the platform buffer capacity; Apple reports its three-buffer capacity. All are `Estimated`: capacity is not current queue occupancy or measured acoustic delay and excludes other device/route delays. |
| Formats | Mono and stereo only; no multichannel layout, float-device output, resampler or format-negotiation API. Unsupported sample rates can fail to open. |
| Routes and recovery | No device-selection API, automatic recovery, audio-focus integration or audio-session management. Native creation, allocation, enqueue, start, stop and disposal statuses are checked. The host app still manages its platform lifecycle. |
| Precision | Float samples are clamped to `[-1, 1]` and quantized to 16-bit PCM without dithering. |
| Apple latency | AudioQueue uses three queued buffers. A render-callback AudioUnit backend is not implemented. |
| Input | Recording and full-duplex streams are not implemented. |

See the [issue tracker](https://github.com/yuroyami/KiteAudio/issues) for defects and proposed
improvements. This README describes the current implementation; it does not certify audio
quality, route recovery or device coverage.

Java callers now see declared checked exceptions on applicable `@Throws` entry points and
may need to catch or declare them. Existing JVM method descriptors are retained; Kotlin callers
keep Kotlin's ordinary exception-handling rules.

## Build and test

Use the `./kotlin` script in the repository root (`kotlin.bat` on Windows). It downloads the
JDK and Kotlin/Native that it needs. Android compilation needs an Android SDK with API level 37.2.
Apple compilation and linking need macOS and an appropriate Xcode SDK.

```sh
./kotlin build -p jvm -p android
./kotlin build -p macosArm64 -p iosSimulatorArm64
```

The Kotlin Toolchain does not link Apple frameworks. After a build of an iOS platform,
`python3 .github/scripts/apple_frameworks.py --platforms iosSimulatorArm64 --build-type debug --no-xcframework`
links `KiteRT.framework` from the klibs.

Deterministic lifecycle and transfer regressions use fake JVM/native drivers without opening audio:

```sh
./kotlin test -p jvm --include-classes='*RegressionTest'
./kotlin test -p macosArm64 --include-classes='*RegressionTest'
python3 kiteaudio-output/verification/android/run.py
```

The Android harness compiles the production source against small API fakes using cached Kotlin
compiler artifacts. Its README documents setup and limits. The unfiltered JVM and macOS suites
also include live tests that open output and play a quiet tone:

```sh
./kotlin test -p jvm
./kotlin test -p macosArm64
```

JVM audio tests return early if the default Java Sound format is unsupported; that is
not proof of successful playback. macOS audio tests require a working output. Neither
suite establishes Android or physical iOS behavior. The repository currently has no GitHub
Actions workflow. Fake-driver results establish tested control flow, not hardware playback.

Known defects, open proposals and platform limits are tracked as
[GitHub issues](https://github.com/yuroyami/KiteAudio/issues).

## Contribute

File one root cause per [GitHub issue](https://github.com/yuroyami/KiteAudio/issues/new).
Include your OS and device, target, sample rate, channel layout, buffer size, callback
behavior and the relevant events. A bounded reproduction using a fake backend is useful
for lifecycle or concurrency failures; distinguish it from an observed hardware failure.
For device reports, include the route and whether the failure occurs on startup,
interruption, stop, restart or removal.

## License

[Apache-2.0](../LICENSE).

Part of the Kite family: [KiteSynth](https://github.com/yuroyami/KiteAudio/tree/main/kiteaudio-synth),
[KiteMIDI](https://github.com/yuroyami/KiteAudio/tree/main/kiteaudio-midi),
[KitePDF](https://github.com/yuroyami/KitePDF).
