<h1 align="center">KiteMIDI</h1>

<p align="center">
  MIDI for Kotlin Multiplatform: read and write Standard MIDI Files, work with typed
  MIDI 1.0 messages, and connect to instruments through the platform's MIDI service.
</p>

<p align="center">
  <sub>AI tools wrote much of the code, tests and documentation in this project, under the direction
  and review of its author.</sub>
</p>

<p align="center">
  <a href="https://kotlinlang.org"><img src="https://img.shields.io/badge/Kotlin-2.4.21-7F52FF?logo=kotlin&amp;logoColor=white" alt="Kotlin 2.4.21"></a>
  <a href="#platforms"><img src="https://img.shields.io/badge/targets-Android%20%7C%20iOS%20%7C%20macOS%20%7C%20JVM-1f6feb" alt="Targets: Android, iOS, macOS and JVM"></a>
  <a href="../LICENSE"><img src="https://img.shields.io/badge/license-Apache--2.0-blue" alt="License: Apache-2.0"></a>
</p>

<p align="center">
  <b>Documentation</b> · the README contains the current tour;
  the source KDoc describes individual APIs.
</p>

## What you get

The file and message engine lives in `commonMain` and depends only on Kotlin's standard
library. Device access is optional, in separate modules that use Android MIDI,
CoreMIDI or `javax.sound.midi`.

<table>
<tr>
<td width="33%" valign="top">

**Read and write**<br>
Standard MIDI Files with PPQ or SMPTE timing, channel messages, meta events and file-framed system exclusive chunks.

</td>
<td width="33%" valign="top">

**Understand the music**<br>
Merge tracks into absolute ticks, pair note presses and releases, and convert ticks to microseconds through a tempo map.

</td>
<td width="33%" valign="top">

**Connect**<br>
Enumerate device ports, receive typed messages and send messages through one `MidiDevices` interface.

</td>
</tr>
<tr>
<td width="33%" valign="top">

**Read a live stream**<br>
Reassemble messages split across reads, retain channel running status and deliver interleaved real-time bytes.

</td>
<td width="33%" valign="top">

**Keep unfamiliar data**<br>
Text meta events retain their original bytes. Unknown meta events and SMF system exclusive chunks retain their payloads.

</td>
<td width="33%" valign="top">

**Ask and tune**<br>
Build universal device inquiry, master volume, fine tuning and coarse tuning messages.

</td>
</tr>
</table>

```kotlin
import io.github.yuroyami.kiteaudio.midi.*

val file = KiteMidi.read(bytes)
val timeline = file.toTimeline()
val firstNote = timeline.notes.firstOrNull()

println("${file.tracks.size} tracks, ${timeline.notes.size} notes")
firstNote?.let {
    println("Key ${it.key} at ${timeline.tempoMap.tickToMicros(it.startTick)} microseconds")
}
```

KiteMIDI provides MIDI data and device I/O. Audio synthesis, playback scheduling and
Compose UI belong to the application or another library.

> [!NOTE]
> The source version is `0.3.0`. The API is still evolving and may change between minor versions.

## Install

This checkout declares three modules at `0.3.0`. Published Maven Central artifacts and
GitHub releases were not available when checked on 29 September 2026. Publish the source
checkout to your local Maven repository first, as [Build and test](#build-and-test) shows,
and add `mavenLocal()` to the repositories of your application.

Then add the modules you need:

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("io.github.yuroyami:kiteaudio-midi:0.3.0")
            // Optional: real device access. Also brings in kiteaudio-midi.
            implementation("io.github.yuroyami:kiteaudio-midi-devices:0.3.0")
        }
        jvmMain.dependencies {
            // Optional: CoreMIDI on the desktop JVM, for macOS.
            implementation("io.github.yuroyami:kiteaudio-midi-coremidi:0.3.0")
        }
    }
}
```

A plain JVM or Android app can put the applicable dependencies in its usual
`dependencies { }` block. With the Kotlin Toolchain, add them to `module.yaml`:

```yaml
dependencies:
  - io.github.yuroyami:kiteaudio-midi:0.3.0
  - io.github.yuroyami:kiteaudio-midi-devices:0.3.0
```

| Module | What it adds |
| --- | --- |
| `kiteaudio-midi` | Typed MIDI messages, SMF reader and writer, timelines, note pairing, tempo maps and universal SysEx helpers. |
| `kiteaudio-midi-devices` | `MidiDevices`, stream parsing and the platform device backends; depends on `kiteaudio-midi` and coroutines. |
| `kiteaudio-midi-coremidi` | A macOS desktop JVM backend through Java's foreign function API; depends on `kiteaudio-midi-devices`. |

## A quick tour

### Read a MIDI file

```kotlin
import io.github.yuroyami.kiteaudio.midi.KiteMidi
import io.github.yuroyami.kiteaudio.midi.toTimeline

val file = KiteMidi.read(bytes)
val timeline = file.toTimeline()

file.format                      // 0, 1 or 2
file.division                    // Division.Ppq or Division.Smpte
file.tracks                      // per-track events with delta times

timeline.events                  // merged events with absolute ticks and track indices
timeline.notes                   // paired notes, computed on first access
timeline.durationMicros          // time of the last event
```

`SmfFile` is useful for editing because it retains track structure and delta times.
`MidiTimeline` is useful for analysis and scheduling because it exposes absolute ticks.
`KiteMidi.readTimeline(bytes)` combines reading and conversion.

Format 2 tracks are independent sequences. The current `toTimeline()` merges them anyway;
use a separate timeline for each track when working with format 2 files. Note pairing
measures key-down time and does not apply sustain-pedal or other controller effects.

### Create a file

```kotlin
import io.github.yuroyami.kiteaudio.midi.*

val file = SmfFile(
    format = 0,
    division = Division.Ppq(96),
    tracks = listOf(
        SmfTrack(
            listOf(
                SmfEvent(0, SetTempo(500_000)),
                SmfEvent(0, TimeSignature(4, 4)),
                SmfEvent(0, NoteOn(channel = 0, key = 60, velocity = 100)),
                SmfEvent(96, NoteOff(channel = 0, key = 60, velocity = 64)),
                SmfEvent(0, EndOfTrack),
            ),
        ),
    ),
)
val bytes = KiteMidi.write(file)
```

Channels are numbered `0..15`; keys and velocities use `0..127`. Pitch bend is signed,
from `-8192` to `8191`. A velocity-zero `NoteOn` stays a `NoteOn` in the message model,
but timeline note pairing treats it as a release.

The writer emits full status bytes and adds `EndOfTrack` if the last event is not one.
Place `EndOfTrack` only at the end: readers stop there. SMF SysEx needs its own framing:
use `SysExChunk(SysExKind.F0, payloadIncludingFinalF7)` for a file event, and `SysEx` for
sending on a cable. Wire `SysEx` and `SystemReset` currently do not write back as valid
SMF events.

### Convert musical time

```kotlin
val map = timeline.tempoMap

map.tickToMicros(960)
map.microsToTick(2_000_000)
map.bpmAt(960)
map.microsPerQuarterAt(960)
```

PPQ files default to 500,000 microseconds per quarter note, or 120 BPM, until a tempo
event changes it. SMPTE timing ignores tempo events; the `29` frame-rate code uses the
30-drop-frame rate of `30000/1001` frames per second. Conversion currently walks tempo
entries for each lookup; avoid rebuilding a timeline or repeatedly looking up every
past tempo on a latency-sensitive callback thread.

### Open an instrument

Create one backend and keep it for the lifetime of the MIDI session:

```kotlin
// JVM: import io.github.yuroyami.kiteaudio.midi.devices.jvmMidiDevices
val devices = jvmMidiDevices(scope)

// Android: androidMidiDevices(context, scope)
// Apple Kotlin/Native: appleMidiDevices(scope)
```

`devices.ports` is a `StateFlow<List<MidiPort>>`. The JVM's first scan is asynchronous,
so an immediately read `ports.value` can still be empty. Observe the flow as devices
appear and disappear.

Directions always describe the app: `INPUT` is read by the app and `OUTPUT` is written
by the app. Android's instrument-side port names are translated by the backend.

Run the following example inside a coroutine or suspend function; opening a port suspends.

```kotlin
import io.github.yuroyami.kiteaudio.midi.NoteOn
import io.github.yuroyami.kiteaudio.midi.NoteOff
import io.github.yuroyami.kiteaudio.midi.devices.MidiDirection

// chosenPort is an OUTPUT selected from devices.ports by your application.
val output = devices.openOutput(chosenPort)
try {
    output.send(NoteOn(channel = 0, key = 60, velocity = 100))
    output.send(NoteOff(channel = 0, key = 60, velocity = 0))
} finally {
    output.close()
}
```

`openInput(inputPort) { timedMessage -> ... }` delivers `TimedMidiMessage` values with a
platform timestamp in nanoseconds. Keep that callback short, avoid blocking and handle
application errors inside it. Dispatch UI work to the UI thread. Native Apple and macOS JVM
FFM callback boundaries contain failures, log them and discard the affected packet; parser state
is reset before another packet is accepted.

Close each input and output, then call `devices.close()` when the session ends. The
Android backend closes on completion of the supplied scope's `Job`; cancellation of
that scope only stops polling or refresh work on the JVM and Apple backends. Explicit
closure is the portable ownership rule. Lifecycle adoption is synchronized: close racing an
open either owns and closes the new handle or rejects the open. CoreMIDI close disables new
delivery immediately and defers native teardown until callbacks return; an application callback
already in progress may still finish. Native disposal failures retain callback ownership rather
than freeing referenced memory.

### Use CoreMIDI from a desktop JVM

```kotlin
import io.github.yuroyami.kiteaudio.midi.coremidi.coreMidiAvailable
import io.github.yuroyami.kiteaudio.midi.coremidi.coreMidiDevices
import io.github.yuroyami.kiteaudio.midi.devices.jvmMidiDevices

val devices = if (coreMidiAvailable()) {
    coreMidiDevices(scope)
} else {
    jvmMidiDevices(scope)
}
```

This optional backend requires macOS and JDK 22 or later at runtime. Start the JVM with
`--enable-native-access=ALL-UNNAMED`. It watches CoreMIDI notifications through a private
run-loop thread. `coreMidiAvailable()` checks whether the bindings can load; actual
client or port creation can still fail.

### Ask an instrument and tune it

Send these messages through an output that is still open, within the session's `try`
block above:

```kotlin
import io.github.yuroyami.kiteaudio.midi.UniversalSysEx

output.send(UniversalSysEx.deviceInquiry())
output.send(UniversalSysEx.masterFineTune(cents = -31.77))
output.send(UniversalSysEx.masterCoarseTune(semitones = 2))
output.send(UniversalSysEx.masterVolume(level = 16_383))

val cents = UniversalSysEx.centsBetween(from = 440.0, to = 432.0)
// Pass an incoming SysEx to UniversalSysEx.parseDeviceInquiryReply(...).
```

Master fine tuning clamps to its 14-bit range. These messages request an action;
whether an instrument supports that action depends on its implementation.

## Platforms

These are the targets declared in the current build, rather than claims of physical
instrument testing.

| Module / backend | Platforms | Requirements and behavior |
| --- | --- | --- |
| `kiteaudio-midi` | Android, JVM, `js`, `wasmJs`, `iosArm64`, `iosSimulatorArm64`, `iosX64`, `macosArm64` | Android minSdk 23. Core file operations are synchronous computation over byte arrays. |
| `kiteaudio-midi-devices` Android | Android | minSdk 23; uses the byte-stream MIDI transport. Devices without MIDI support produce no ports. |
| `kiteaudio-midi-devices` JVM | JVM on macOS, Linux or Windows with an available Java MIDI provider | Uses `javax.sound.midi`; scans once per second. Physical transport is reported as `UNKNOWN`. Provider availability varies by runtime and OS. |
| `kiteaudio-midi-devices` Apple | iOS device and simulator targets above, native macOS arm64 | CoreMIDI platform bindings; callbacks arrive on CoreMIDI threads. |
| `kiteaudio-midi-coremidi` | macOS desktop JVM | Runtime JDK 22+ and native access enabled; the source build requests JDK 25. |

For a plain Swift application, the generated `KiteMIDIIO` framework exports the core message
types. Import that umbrella framework for device access and messages; a separate `KiteMIDI`
framework is unnecessary. Parsing, file writing, port opening and sending export their declared
expected exceptions. Use Swift `do`/`try`/`catch` (or the suspend completion error parameter).
The exported Kotlin model still uses Kotlin collections and byte-array wrappers.

Android USB MIDI uses the MIDI service. Bluetooth needs an extra connection step:
cast the backend to `AndroidMidiDevices`, call `openBluetoothMidiDevice(device)`, and
retain and close the returned `BluetoothMidiConnection`. Bluetooth discovery and the
applicable runtime permissions belong to the application. The library manifest declares
no features. To declare MIDI support, add
`<uses-feature android:name="android.software.midi" android:required="false" />` to the
app manifest. On Apple, system-connected Bluetooth devices appear through CoreMIDI.

The current build does not declare JS, Wasm, Linux Native, Windows Native, native
Intel macOS, tvOS or watchOS targets. JVM support on those desktop operating systems
is distinct from Kotlin/Native support.

## Good to know

<details>
<summary><b>Current limits and contracts</b></summary>
<br>

| Topic | What to expect |
| --- | --- |
| MIDI generation | MIDI 1.0 byte streams and SMF. MIDI 2.0, UMP and MIDI-CI are not implemented. |
| Playback | No sequencer, synthesis engine, scheduled-send API or playback clock. `send` asks the platform to send now. |
| Port identity | IDs come from the backend. Do not assume names are unique or IDs are portable across backends or reconnects. JVM IDs for identical devices use enumeration order. |
| Error reporting | Malformed file structure, including invalid divisions, reports `MidiFormatException`. Invalid application-generated writer input reports `IllegalArgumentException`. Some offsets are relative to a track or unavailable. Device opening uses `MidiPortException`. |
| Stream parsing | `parseMessage` expects one already-separated message. `MidiStreamParser` handles fragmentation, running status and interleaved real-time bytes. Its configurable SysEx payload bound defaults to 1 MiB; overflow discards the message. Give each port its own parser and do not feed it concurrently. |
| File validation | Declared track counts and complete chunk headers are enforced. Writers reject events after End-of-Track and reject wire-only system messages; represent SMF exclusive data explicitly as `SysExChunk`. Format 2 tracks are independent patterns, so choose one before timeline conversion. |
| Large sends | Native Apple and desktop FFM sends reject payloads above 65,520 bytes. Split larger SysEx transfers deliberately at the application layer; no packet length is silently truncated. |
| Note interpretation | Pairing uses channel and key, prefers a release on the same track, and closes remaining notes at that track's last event. Controller-driven sounding duration is not calculated. |
| Text | Original bytes survive. The convenience `text` property decodes as UTF-8, which may show replacement characters for other encodings. |
| Compose | No Compose module. Keep parsing and timeline construction out of repeated composition work and store owned device sessions outside transient UI calls. |

</details>

Java callers now see declared checked exceptions on applicable `@Throws` entry points and
may need to catch or declare them. Existing JVM method descriptors are retained; Kotlin callers
keep Kotlin's ordinary exception-handling rules.

## Build and test

The project builds with the [Kotlin Toolchain](https://github.com/JetBrains/kotlin-toolchain):
the `./kotlin` script in the repository root (`kotlin.bat` on Windows). Core and device
modules use JDK 21. The desktop CoreMIDI module compiles with JDK 25. The script downloads
both JDKs. Android builds also need an Android SDK with API level 37.2, and Apple
compilation and tests need macOS and the applicable Xcode toolchain.

```sh
./kotlin test -p jvm
./kotlin test -p macosArm64
./kotlin check abi klibAbi
./kotlin do dokka && ./kotlin do dokkaSite
```

The klib check reads the built klibs, so run `./kotlin build` for every platform first.
The Kotlin Toolchain does not link Apple frameworks.
`.github/scripts/apple_frameworks.py` links `KiteMIDI.framework` and `KiteMIDIIO.framework`
from the klibs.

To publish the modules to your local Maven repository:

```sh
./kotlin publish mavenLocal -m kiteaudio-midi -m kiteaudio-midi-devices -m kiteaudio-midi-coremidi
```

The publish signs the artifacts, so it needs a signing key in the
`KOTLIN_TOOLCHAIN_SIGNING_KEY` and `KOTLIN_TOOLCHAIN_SIGNING_KEY_PASSPHRASE` environment
variables.

The repository includes common tests for message codecs, SMF, variable-length values,
meta events, tempo maps, timelines and stream parsing. JVM device tests use the runtime's
software synthesizer and sequencer. Apple and desktop CoreMIDI tests create virtual MIDI
sources; they do not verify a physical keyboard, USB connection or Bluetooth transport.

The optional corpus tests read the folder that the environment variable
`KITEAUDIO_MIDI_CORPUS` names. They return successfully when the variable is unset or the
folder is absent, so a green clone does not establish corpus coverage. There are no Android
device tests in this checkout. The CI workflow does not run the MIDI modules yet. Commands above describe available checks; consult
their actual output for the targets and tests that ran.

Known defects, open proposals and platform limits are tracked as
[GitHub issues](https://github.com/yuroyami/KiteAudio/issues).

## Contribute

For a file-format issue, include a minimal MIDI file or a short byte sequence, the API
call and the expected events or timing. For device issues, include the OS, runtime,
backend, port direction, connection type and whether the failure occurs while opening,
sending, receiving, cancelling or closing. Remove personal data from attachments.

Changes to the core and shared I/O API should include their applicable tests and ABI
checks. Keep a runtime result distinct from compilation, virtual endpoints and physical
instrument behavior. Report issues through [GitHub](https://github.com/yuroyami/KiteAudio/issues).

## License

Apache-2.0. See [LICENSE](../LICENSE). MIDI file and message formats follow the MIDI
Manufacturers Association specifications; the implementation is Kotlin source in this
repository.

Part of the Kite family: [KiteSynth](https://github.com/yuroyami/KiteAudio/tree/main/kiteaudio-synth),
[KiteRT](https://github.com/yuroyami/KiteAudio/tree/main/kiteaudio-output), [KitePDF](https://github.com/yuroyami/KitePDF).
