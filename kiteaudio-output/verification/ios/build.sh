#!/usr/bin/env bash
# Builds the output check app for a real iPhone (iosArm64).
#
# 1. Compiles kiteaudio-core and kiteaudio-output for iosArm64 in the KiteAudio project.
# 2. Links KiteAudioOutput.framework with .github/scripts/apple_frameworks.py.
# 3. Builds OutputCheck.app with xcodebuild.
#
# Usage: kiteaudio-output/verification/ios/build.sh
# With KITEAUDIO_TEAM_ID set to an Apple development team ID, Xcode signs the app with automatic
# signing. Without it, the build stops before signing, and the app cannot be installed.
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/../../.." && pwd)

cd "$root"
./kotlin task :kiteaudio-output:compileIosArm64Debug
python3 .github/scripts/apple_frameworks.py --platforms iosArm64 --frameworks KiteAudioOutput \
  --build-type release --no-xcframework

args=(-project "$here/OutputCheck.xcodeproj" -scheme OutputCheck -configuration Debug
  -destination generic/platform=iOS -derivedDataPath "$here/build/DerivedData")
if [ -n "${KITEAUDIO_TEAM_ID:-}" ]; then
  xcodebuild "${args[@]}" DEVELOPMENT_TEAM="$KITEAUDIO_TEAM_ID" \
    -allowProvisioningUpdates -allowProvisioningDeviceRegistration build
else
  echo "KITEAUDIO_TEAM_ID is not set, so the app is built without signing."
  xcodebuild "${args[@]}" CODE_SIGNING_ALLOWED=NO build
fi
echo "App: $here/build/DerivedData/Build/Products/Debug-iphoneos/OutputCheck.app"
