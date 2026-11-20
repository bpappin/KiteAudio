# Android source regression checks

Run with Python 3 and a JDK 21 installation:

```sh
python3 kiteaudio-output/verification/android/run.py --java-home "$JAVA_HOME"
```

The runner uses the Kotlin 2.4.21 compiler and its dependencies already present in the
Gradle cache. `--kotlin-version` selects another cached compiler version. It neither
invokes Gradle nor downloads dependencies. Missing cache entries are a setup failure.

The runner copies the current common and Android production sources to a temporary
directory, removes the expect/actual factory declarations, and copies common default
arguments onto the Android factory. It compiles these source bodies against the
small framework doubles in this directory. Seven assertion groups check buffer bounds,
exact PCM order across short and zero writes, bounded no-progress failure, canceled
worker ownership and terminal close, startup retry and event exception containment,
invalid renderer results, and write unblocking before stop waits.

This is a deterministic control-flow check. It does not run Android, open an output
device, measure latency, validate scheduling, or demonstrate physical playback.
