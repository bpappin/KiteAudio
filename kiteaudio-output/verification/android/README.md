# Android verification

An Android app for what the host tests cannot check: that `DeviceUnderrun` reports the dropouts that `AudioTrack` counts on a real device.

## What the check proves

The app opens the default output at 48000 Hz, stereo, with blocks of 1024 frames. It plays a quiet 440 Hz tone for 20 seconds of sound. At 3, 6, 9, 12 and 15 seconds, the render callback sleeps for 300 ms. The track holds about 85 ms of sound, so it runs dry each time. The callback still returns every frame. So each dropout must come out as `DeviceUnderrun`, not as `Underrun`.

The app logs every event with the logcat tag `KiteAudioCheck`. At the end it writes one line that starts with `SUMMARY`. The screen shows the same lines.

### The platform counter

The public API gives no access to the `AudioTrack` of an output. The app reads the track through two private fields of kiteaudio-output 0.3.0, `AndroidAudioOutput.track` and `PlatformPcmTrack.track`. Then it calls `AudioTrack.getUnderrunCount()` itself. If a later version renames these fields, the summary shows `platformCounter=unreadable`, and the check uses the events alone.

The output counts from the first block of each stream. A dropout before that block is in the platform counter, but not in `total`. So the check does not ask for `total` to equal the counter. It asks for the difference to stay the same at every `DeviceUnderrun` and at the end. The summary reports this difference as `platformOffset`. When `platformOffset` is 0, the last `total` equals the platform counter.

### The PASS rule

The result is PASS when every rule holds:

1. The API level is 24 or higher.
2. One `Started` and one `Stopped` arrive, no `Failed` and no `DeviceLost` arrive, and the tone reaches 20 seconds.
3. No `Underrun` arrives, because every render is full.
4. A `DeviceUnderrun` arrives within 1 second after each of the 5 forced gaps.
5. Each `DeviceUnderrun` has a `count` of 1 or more. Its `total` is the previous `total` plus its `count`. So the last `total` is the sum of the counts.
6. When the platform counter is readable, the counter minus `total` has the same value at every `DeviceUnderrun` and after `Stopped`.

`unforced` in the summary counts the `DeviceUnderrun` events that no forced gap explains. They do not fail the check, because a busy phone can drop out by itself.

### What the check cannot prove

- That the sound really stopped. The check reads counters and cannot hear. Listen for the five gaps.
- How many underruns one gap gives. Android can count one gap as one underrun or as several, so rule 4 asks for at least one.
- That a dropout in the first block of a stream is reported. The output does not count it, by design.
- Behavior on Android 6 (API level 23), where the output sends no `DeviceUnderrun`.

## Build

The build needs no phone. It needs the Android SDK in `ANDROID_HOME`. Run it from the repository root:

```sh
kiteaudio-output/verification/android/build.sh
```

The script does these steps:

1. It builds the Android AARs of kiteaudio-core and kiteaudio-output in the KiteAudio project.
2. It stages the AARs in `kiteaudio-output/verification/build/maven`, with the group `local.kiteaudio.verification` and the version `local`. It does not write to `~/.m2`.
3. It builds the app of this folder, a separate Kotlin Toolchain project, against that Maven folder.
4. It runs the host tests of the PASS rule.

The debug APK is `kiteaudio-output/verification/android/build/tasks/_underrun-check_buildAndroidDebug/gradle-project-debug.apk`. The debug key of the Android SDK signs it.

## The sitting

1. Turn on USB debugging on the phone.
2. Connect the phone to the computer with a cable.
3. Disconnect every other Android device and stop every emulator.
4. Unlock the phone and accept the USB debugging prompt.
5. Set the media volume to a low level that you can still hear.
6. Run `kiteaudio-output/verification/android/run.sh` from the repository root.
7. Listen. The tone plays for about 22 seconds, with five short gaps.
8. Read the `SUMMARY` line at the end of the output of the script.

`run.sh` installs the APK, starts the app, waits for the summary line and prints the log of the run. It reads `adb` from `ANDROID_HOME`.

## What a pass looks like

The script exits with 0. The last line looks like this:

```text
SUMMARY result=PASS android=15 api=35 model="<maker> <model>" deviceUnderrunEvents=5 countSum=5 lastTotal=5 platformCounter=5 platformOffset=0 gapsSeen=5/5 unforced=0 underrun=0 overrun=5
```

The counts can be higher than 5, and `platformOffset` can be higher than 0. Only the rules above decide the result.

On a FAIL the script exits with 1. The log lines that start with `problem:` name each rule that failed. Exit code 2 means that the run did not happen: no device, more than one device, or no summary within 60 seconds.
