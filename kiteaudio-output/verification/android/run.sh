#!/usr/bin/env bash
# Installs the underrun check on the one connected Android device, runs it and prints its summary.
# Exits 0 on PASS, 1 on FAIL, and 2 when the run could not happen.
#
# Usage: kiteaudio-output/verification/android/run.sh
# Run build.sh first. Needs the Android SDK in ANDROID_HOME.
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
apk="$here/build/tasks/_underrun-check_buildAndroidDebug/gradle-project-debug.apk"
package=io.github.yuroyami.kiteaudio.verification.underrun
activity=io.github.yuroyami.kiteaudio.verification.MainActivity
tag=KiteAudioCheck
timeout_seconds=60

if [ -z "${ANDROID_HOME:-}" ]; then
  echo "error: set ANDROID_HOME to the Android SDK folder" >&2
  exit 2
fi
adb="$ANDROID_HOME/platform-tools/adb"
if [ ! -f "$apk" ]; then
  echo "error: no APK at $apk. Run build.sh first." >&2
  exit 2
fi

devices=$("$adb" devices | awk 'NR > 1 && $2 == "device" { print $1 }')
count=$(printf '%s' "$devices" | grep -c . || true)
if [ "$count" -ne 1 ]; then
  echo "error: expected one connected device in the 'device' state, found $count" >&2
  "$adb" devices >&2
  exit 2
fi
export ANDROID_SERIAL=$devices
echo "device: $ANDROID_SERIAL"

"$adb" install -r "$apk" > /dev/null
"$adb" shell am force-stop "$package"
"$adb" logcat -c
"$adb" shell am start -n "$package/$activity" > /dev/null
echo "running: about 22 seconds of tone with 5 forced dropouts"

summary=""
for _ in $(seq "$timeout_seconds"); do
  sleep 1
  summary=$("$adb" logcat -d -s "$tag:I" | grep -m 1 ' SUMMARY ' || true)
  [ -n "$summary" ] && break
done

echo "--- log of the run"
"$adb" logcat -d -s "$tag:I" | sed -n "s/^.* $tag *: //p"
echo "---"
if [ -z "$summary" ]; then
  echo "error: no summary line within $timeout_seconds seconds" >&2
  exit 2
fi
line=${summary#* SUMMARY }
echo "SUMMARY $line"
case $line in
  result=PASS*) exit 0 ;;
  *) exit 1 ;;
esac
