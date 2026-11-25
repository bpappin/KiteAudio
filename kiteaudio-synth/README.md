# KiteSynth

<p align="center">
  A sample-based synthesiser written in common Kotlin: read SoundFont banks, send MIDI
  messages and render stereo Float32 PCM from <code>commonMain</code>.
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

KiteSynth brings its own synthesis engine. It depends only on `kotlin-stdlib`, with no JNI,
platform synthesiser or bundled SoundFont. Your app supplies the font bytes and the audio output;
[KiteRT](https://github.com/yuroyami/KiteAudio/tree/main/kiteaudio-output) is the companion library for sending PCM to a device,
and [KiteMIDI](https://github.com/yuroyami/KiteAudio/tree/main/kiteaudio-midi) handles MIDI files and connections.

| Task | What KiteSynth provides |
| --- | --- |
| Read | SF2 presets, instruments, zones, generators, modulators, INFO text, 16-bit sample frames and the `sm24` low bytes of 24-bit samples. SF3 compressed samples can be retained for an external decoder. |
| Play | Layered sample voices, velocity and key ranges, loop modes, exclusive classes, a fixed voice pool and voice stealing. |
| Shape | Volume and modulation envelopes, two LFOs, a resonant low-pass filter, linear or cubic interpolation, stereo reverb and chorus. |
| Control | 16 channels, programs, bank selection, percussion channels, sustain and sostenuto, volume, expression, pan, pressure and pitch bend. |
| Tune | RPN pitch-bend range, fine and coarse channel tuning, an A4 reference and a per-key temperament table. |
| Keep and combine | `SoundFontWriter.write`, `merged` and `pruned`, preserving sample data (24-bit samples included) and renumbering references. |

Here is the whole path from a font to an audio block:

```kotlin
import io.github.yuroyami.kiteaudio.synth.KiteSynth
import io.github.yuroyami.kiteaudio.synth.Synth

fun renderFirstNote(sf2Bytes: ByteArray): FloatArray {
    val font = KiteSynth.readFont(sf2Bytes)
    val synth = Synth(font, sampleRate = 48_000, maxVoices = 64)
    synth.noteOn(channel = 0, key = 60, velocity = 100)
    return FloatArray(512 * 2).also { buffer ->
        synth.render(buffer, frames = 512)
    }
}
```

The result is 512 **frames**, each containing a left and a right sample. Creating the synth loads
and prepares its regions; a callback should reuse that synth and its output buffer.

> [!NOTE]
> The source version is `0.3.0`. The API is still evolving; [CHANGELOG.md](../CHANGELOG.md) lists the
> breaking changes from `0.1`. As of 29 September 2026, this repository has no GitHub releases
> and the `io.github.yuroyami:kiteaudio-synth` Maven Central metadata endpoint returns 404. The source
> version is not a verified Maven Central release.

## Install from source

KiteSynth builds with the [Kotlin Toolchain](https://github.com/JetBrains/kotlin-toolchain), the
`./kotlin` script in the repository root. Publish the checkout to your local Maven repository:

```sh
./kotlin publish mavenLocal -m kiteaudio-synth
```

The publish signs the artifacts, so it needs a signing key in the `KOTLIN_TOOLCHAIN_SIGNING_KEY`
and `KOTLIN_TOOLCHAIN_SIGNING_KEY_PASSPHRASE` environment variables. Add `mavenLocal()` to the
repositories of the consuming project, then add the dependency to your shared code:

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("io.github.yuroyami:kiteaudio-synth:0.3.0")
        }
    }
}
```

For a plain Android or JVM application, put the same dependency in its usual `dependencies { }`
block. With the Kotlin Toolchain, add it to `module.yaml`:

```yaml
dependencies:
  - io.github.yuroyami:kiteaudio-synth:0.3.0
```

The coordinates are `io.github.yuroyami:kiteaudio-synth:0.3.0`. The version is `publishing.version` in
`library.module-template.yaml`.

## A quick tour

### Load and choose a sound

```kotlin
import io.github.yuroyami.kiteaudio.synth.KiteSynth
import io.github.yuroyami.kiteaudio.synth.SoundFont

fun inspectFont(bytes: ByteArray): SoundFont {
    val font = KiteSynth.readFont(bytes)
    for (preset in font.presets) {
        println("${preset.name}: bank ${preset.bank}, program ${preset.program}")
    }
    return font
}
```

`readFont` is synchronous. For malformed input it is intended to throw
`SoundFontFormatException`, whose `offset` identifies the byte position when available. Loading
is eager: every sample gets its own array, even when file ranges overlap. Keep file loading,
SF3 decoding and font preparation away from the audio callback.

Programs, channels and MIDI keys use zero-based numeric MIDI values. A melodic channel reads its
bank from CC 0; CC 32 is stored but does not contribute to bank lookup. If a bank lacks the requested
program, lookup tries bank 0 and then the lowest bank/program preset.

```kotlin
synth.controlChange(channel = 0, controller = 0, value = 1)
synth.programChange(channel = 0, program = 5)
synth.noteOn(channel = 0, key = 60, velocity = 100)
```

These messages become effective during the next render. `presetOn`, `programOn`, `bankOn` and
`controller` report the state that has already been applied.

### Place a note within a block

```kotlin
val blockFrames = 512
val buffer = FloatArray(blockFrames * 2)

synth.noteOn(channel = 0, key = 60, velocity = 100, atFrame = 128)
synth.noteOff(channel = 0, key = 60, atFrame = 384)
synth.render(buffer, frames = blockFrames)
```

`atFrame` is relative to the next render, not an absolute song position or a timestamp.
Negative offsets clamp to zero; offsets beyond the block carry into later renders. Messages at
the same frame keep their arrival order. The queue holds 1,024 events. `tryEnqueue(...)` returns
`false` when full; `enqueue` and the convenience note/control methods throw `IllegalStateException`.
Rejection increments `rejectedEvents` and leaves accepted events and voices unchanged. Use
`tryEnqueue` on a deadline-sensitive path, render regularly, and handle a rejected note-off
explicitly (for example, with the host's panic policy). No event is applied early to make space.

For a message from a MIDI parser or input connection, pass the status and data bytes directly:

```kotlin
synth.enqueue(status = 0x90, data1 = 60, data2 = 100, atFrame = 0)
synth.enqueue(status = 0xD0, data1 = 64, data2 = 0, atFrame = 0) // channel pressure
```

KiteSynth accepts channel voice messages; it does not parse MIDI byte streams, play an SMF timeline
or interpret SysEx/UMP messages. Those responsibilities belong to the caller or a MIDI library.

### Use pedals, drums and tuning

```kotlin
synth.controlChange(channel = 0, controller = 64, value = 127) // sustain down
synth.controlChange(channel = 0, controller = 64, value = 0)   // sustain up
synth.controlChange(channel = 0, controller = 66, value = 127) // capture held keys with sostenuto
synth.pitchBend(channel = 0, value = 4096)                     // halfway up; range defaults to 2 semitones

synth.setPercussion(channel = 8, percussion = true)
synth.programChange(channel = 8, program = 0)
synth.noteOn(channel = 8, key = 36, velocity = 100)

synth.referenceA4 = 432.0
synth.tuningCents[60] = -50.0
```

Channel 9 starts as percussion. Percussion channels use bank 128; a missing kit falls back to kit
0, and a font without a kit produces silence on those channels. The A4 reference and temperament
table affect subsequently started melodic notes. Held notes keep that tuning; pitch bend and RPN
channel tuning still move them.

`allNotesOff(channel)` releases notes and clears pedals. `allSoundOff()` immediately stops every
voice, clears the shared effects and empties the event queue. `allSoundOff(channel)` stops that
channel's voices; the effects are shared, so it does not remove only that channel's existing tail.

### Adjust the output

```kotlin
import io.github.yuroyami.kiteaudio.synth.Interpolation

synth.interpolation = Interpolation.Linear
synth.reverb.wet = 0.0
synth.reverb.dry = 1.0
synth.chorus.level = 0.0
```

Cubic interpolation is the default. Setting the reverb's wet level to zero does not reset its dry
level: use `dry = 1.0` too when you want the unattenuated dry mix. Voices are mixed into Float32 PCM;
many loud voices can exceed the nominal `-1f..1f` range. The host owns any limiter, master gain or
conversion to an integer device format.

### Write, merge and prune fonts

```kotlin
import io.github.yuroyami.kiteaudio.synth.SoundFontWriter
import io.github.yuroyami.kiteaudio.synth.merged
import io.github.yuroyami.kiteaudio.synth.pruned

val bank = piano.merged(generalMidi)
val compact = bank.pruned()
val sf2Bytes = SoundFontWriter.write(compact)
```

The first font wins a bank/program collision. Merge and prune renumber instruments, samples and
links, and merge shares frame arrays with the inputs. The writer removes zones without a target
and extracts shared zone values into global zones. It rejects record counts that cannot fit the
format's 16-bit indexes.

### Decode SF3 samples

```kotlin
import io.github.yuroyami.kiteaudio.synth.KiteSynth
import io.github.yuroyami.kiteaudio.synth.SoundFont

fun decodeSf3(
    bytes: ByteArray,
    decodeVorbis: (ByteArray) -> ShortArray,
): SoundFont = KiteSynth.readFont(bytes, keepCompressed = true).decoded(decodeVorbis)
```

The callback receives one compressed sample's Ogg Vorbis bytes and returns decoded **mono 16-bit
frames**. It is synchronous and called once per compressed sample. KiteSynth supplies no Vorbis
decoder; the decoder and its resource limits are the caller's responsibility. Without
`keepCompressed`, the reader rejects compressed samples, and `Synth` refuses a still-compressed
font. `SoundFontWriter` can preserve a font loaded with its compressed samples intact.

## Platforms

All production code is common Kotlin in `kiteaudio-synth/src`. These are the targets declared by the current build:

| Target | Details |
| --- | --- |
| Android | Android KMP library, minSdk 23 and compileSdk 36. The host provides audio and MIDI access. |
| JVM | Java 21 toolchain; desktop applications on macOS, Linux and Windows use the JVM artifact. |
| iOS devices | `iosArm64`; a dynamic framework named `KiteSynth` is configured. |
| iOS simulators | `iosSimulatorArm64` and `iosX64`; both configure the same framework name. |
| macOS Native | `macosArm64`. |
| Web | `js` and `wasmJs`. Rendering is the same common code; sending the samples to Web Audio is the app's job. |
| Other Native | Linux Native, Windows Native, macOS x64, tvOS, watchOS and Android Native are not declared. |

A declared target establishes build intent. It does not establish device latency, callback safety,
audio fidelity or runtime parity. `readFont`, `Synth` construction and `replaceFont` export their
expected errors through `@Throws`, so Swift can use `do`/`try`/`catch`. Swift reads a synth's font
through `currentFont()` and replaces it through `try replaceFont(newFont:)`; the Kotlin `font`
property is hidden from Objective-C because a property setter cannot export a throwing contract.
Kotlin and JVM callers retain ordinary property access. This changes the earlier Swift property
surface; regenerate and rebuild the framework and its consumers together.

## Buffers, threads and ownership

Use one owner thread for a `Synth`, or serialize access outside the callback. The event arrays,
voices, channels and effect buffers are mutable and are not protected for concurrent producers.
In particular, calling `enqueue` on a MIDI-input thread while another thread runs `render` is not
a supported concurrent queue. Kotlin/Native's memory manager does not add synchronization.

- Construct or replace a font outside the callback, with rendering stopped during replacement.
  Preparation allocates; successful replacement stops the current performance. A rejected
  replacement leaves the current font and performance intact.
- Reuse a `FloatArray` holding at least `frames * 2` values and pass a non-negative frame count.
  `render` overwrites that prefix; any unused suffix stays untouched.
- Rendering and normal event queueing use preallocated state. `soundingKeys`, font transforms,
  construction, decoding and `voicesFor` allocate and should stay off the callback.
- Treat a font's lists, generator maps and sample arrays as read-only while any synth uses them.
  Public arrays remain mutable, and merged fonts share sample frames.
- KiteSynth opens no file, audio device, coroutine or worker thread. There is no `close` operation;
  the host manages its own device, scheduling and lifetime.

## Known limits

| Topic | Current behaviour |
| --- | --- |
| SoundFont precision | Playback uses the 16-bit `smpl` frames only. The reader keeps a valid `sm24` chunk in `SampleHeader.lowBytes`, and the writer writes it back, so a read and write keeps every bit of a 24-bit font. `frame24` gives the full signed value of one frame. The low bytes cost one more byte for each frame of a 24-bit font, and nothing for a 16-bit font. |
| Modulators | Ordinary generator destinations are evaluated. Linked modulator destinations are preserved for writing but are not evaluated. |
| Stereo sample links | Merge and prune preserve links. Playback prepares zones independently and does not enforce the right sample's pitch as a shared stereo-pair clock. |
| Interpolation | Linear and cubic only; there is no band-limited resampler. |
| Envelope timing | Envelopes and LFOs update every 16 output frames. Envelope times are capped near 100 seconds. |
| MIDI scope | Channel voice messages only. Bank selection uses CC 0, and there is no portamento/legato mode, NRPN synthesis control, MPE manager or MIDI 2.0 interface. |
| Resource bounds | Reading defaults to 64 MiB of cumulative retained sample payload, counting overlapping ranges separately and the low bytes of 24-bit samples. Preparation defaults to 4,194,304 examined zone pairs, 16,384 regions and 1,048,576 retained modulator entries. Explicit overloads allow larger trusted fonts; limits are checked before expansion. SF3 decoding still needs a separate host-owned bound. |
| Output and integration | No bundled sound bank, audio output backend, file I/O, Compose UI or timeline player. |
| Verification | Tests use synthetic fonts. The repository has no CI workflow and no differential suite against a reference synthesiser or physical-device audio tests. |

For a larger trusted bank, supply explicit budgets:

```kotlin
val font = KiteSynth.readFont(bytes, maxSampleBytes = 128L * 1024 * 1024)
val synth = Synth(font, sampleRate = 48_000, maxVoices = 256,
    maxPreparedRegions = 32_768, maxPreparedModulators = 2_097_152,
    maxPreparedPairs = 8_388_608L)
```

The reader rejects zero sample rates and unsigned rates that do not fit a positive Kotlin `Int`.
Valid high rates such as 96 kHz remain supported. Quiet releases retain their authored envelope
and sample lifetime; release-heavy passages can therefore use more CPU than the former early
pruning behavior. Effect sends use SoundFont units directly, which corrects the previous overly
wet mix.

Java callers now see declared checked exceptions on applicable `@Throws` entry points and
may need to catch or declare them. Existing JVM method descriptors are retained; Kotlin callers
keep Kotlin's ordinary exception-handling rules.

## Build and test

Use the `./kotlin` script in the repository root (`kotlin.bat` on Windows). It downloads the JDK
and Kotlin/Native that it needs. Android builds need an Android SDK with API level 37.2. Apple builds
and native tests need macOS and a compatible Xcode installation.

```sh
./kotlin test -p jvm
./kotlin test -p macosArm64
./kotlin build -p iosSimulatorArm64
python3 .github/scripts/apple_frameworks.py --platforms iosSimulatorArm64 --build-type debug --no-xcframework
./kotlin do dokka
```

The Kotlin Toolchain does not link Apple frameworks. The script links `KiteSynth.framework` from the
klibs that `./kotlin build` writes. `./kotlin check abi klibAbi` compares the public API with the
dumps in `kiteaudio-synth/api`. Build every platform first, because the klib check reads the built klibs.

Common tests construct SoundFont bytes deliberately, then check parsing, round trips, channels,
voice selection, envelopes, filtering and effects. JVM tests also write a short demonstration WAV
under the system temporary directory and measure a 96-voice render workload. The benchmark's
machine-specific assertions are disabled when `CI` is set; its numbers are not a device guarantee.

Known defects, open proposals and platform limits are tracked as
[GitHub issues](https://github.com/yuroyami/KiteAudio/issues).

## Contribute

Open an [issue](https://github.com/yuroyami/KiteAudio/issues) with the smallest font or MIDI
sequence that shows the problem, the sample rate and render block size, target and toolchain,
and the observed and expected behaviour. A generated fixture is welcome when a real SoundFont
cannot be redistributed. For an audio mismatch, a short output WAV and a reference render help.

Keep shared synthesis and format logic in `kiteaudio-synth/src`; add a focused regression test for the
public path that changes. Public API snapshots live under [`kiteaudio-synth/api`](api), and
API changes should include an entry in [CHANGELOG.md](../CHANGELOG.md).

## License

Apache-2.0. See [LICENSE](../LICENSE). KiteSynth bundles no SoundFont bank; fonts and decoders supplied
by an application have their own licenses.

Part of the Kite family: [KiteMIDI](https://github.com/yuroyami/KiteAudio/tree/main/kiteaudio-midi),
[KiteRT](https://github.com/yuroyami/KiteAudio/tree/main/kiteaudio-output), [KitePDF](https://github.com/yuroyami/KitePDF).
