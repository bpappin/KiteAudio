#!/usr/bin/env bash
# Builds the debug APK of the underrun check. It needs no phone.
#
# 1. Builds the Android AARs of kiteaudio-core and kiteaudio-output in the KiteAudio project.
# 2. Stages them in kiteaudio-output/verification/build/maven, a Maven folder of their own.
# 3. Builds the app of this folder against that Maven folder, then runs its host tests.
#
# Usage: kiteaudio-output/verification/android/build.sh
# Needs the Android SDK in ANDROID_HOME.
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/../../.." && pwd)
maven="$root/kiteaudio-output/verification/build/maven"
group_dir="$maven/local/kiteaudio/verification"

if [ -z "${ANDROID_HOME:-}" ]; then
  echo "error: set ANDROID_HOME to the Android SDK folder" >&2
  exit 1
fi

cd "$root"
./kotlin task :kiteaudio-core:aarAndroidRelease :kiteaudio-output:aarAndroidRelease

# Writes one artifact with a POM and checksums. The toolchain refuses an artifact without checksums.
stage() {
  local artifact=$1 aar=$2
  local dir="$group_dir/$artifact/local"
  if [ ! -f "$aar" ]; then
    echo "error: the toolchain wrote no $aar" >&2
    exit 1
  fi
  rm -rf "$dir"
  mkdir -p "$dir"
  cp "$aar" "$dir/$artifact-local.aar"
  cat > "$dir/$artifact-local.pom" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>local.kiteaudio.verification</groupId>
  <artifactId>$artifact</artifactId>
  <version>local</version>
  <packaging>aar</packaging>
</project>
EOF
  local file
  for file in "$dir/$artifact-local.aar" "$dir/$artifact-local.pom"; do
    md5 -q "$file" > "$file.md5"
    shasum -a 1 "$file" | cut -d' ' -f1 > "$file.sha1"
    shasum -a 256 "$file" | cut -d' ' -f1 > "$file.sha256"
  done
}

# The toolchain names the Android AAR of a multiplatform library <module>-jvm.aar.
stage kiteaudio-core-android build/tasks/_kiteaudio-core_aarAndroidRelease/kiteaudio-core-jvm.aar
stage kiteaudio-output-android build/tasks/_kiteaudio-output_aarAndroidRelease/kiteaudio-output-jvm.aar

export KOTLIN_CLI_JAVA_OPTIONS="-Dmaven.repo.local=$maven"
./kotlin build --project-dir="$here" -p android
./kotlin test --project-dir="$here" -p android

apk="$here/build/tasks/_underrun-check_buildAndroidDebug/gradle-project-debug.apk"
if [ ! -f "$apk" ]; then
  echo "error: the build wrote no $apk" >&2
  exit 1
fi
echo "APK: $apk ($(du -h "$apk" | cut -f1 | tr -d ' '))"
