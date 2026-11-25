#!/usr/bin/env bash
# Installs the signed output check app on the one connected iPhone and opens it.
#
# Usage: kiteaudio-output/verification/ios/run.sh
# Run build.sh with KITEAUDIO_TEAM_ID set first.
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
app="$here/build/DerivedData/Build/Products/Debug-iphoneos/OutputCheck.app"
bundle=io.github.yuroyami.kiteaudio.verification.output

if ! codesign --verify "$app" 2> /dev/null; then
  echo "error: no signed app at $app. Run build.sh with KITEAUDIO_TEAM_ID set first." >&2
  exit 2
fi

list=$(mktemp)
trap 'rm -f "$list"' EXIT
xcrun devicectl list devices --json-output "$list" > /dev/null
# A physical iOS device with a transport is connected now, by cable or by the network.
device=$(python3 - "$list" <<'EOF'
import json, sys
devices = json.load(open(sys.argv[1]))["result"]["devices"]
found = [d["identifier"] for d in devices
         if d["hardwareProperties"].get("platform") == "iOS"
         and d["hardwareProperties"].get("reality") == "physical"
         and d["connectionProperties"].get("transportType")]
print(found[0] if len(found) == 1 else "", end="")
EOF
)
if [ -z "$device" ]; then
  echo "error: expected one connected iPhone. Check the cable and unlock the phone." >&2
  xcrun devicectl list devices >&2
  exit 2
fi

xcrun devicectl device install app --device "$device" "$app"
xcrun devicectl device process launch --device "$device" "$bundle"
