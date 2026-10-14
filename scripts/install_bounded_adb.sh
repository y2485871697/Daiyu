#!/usr/bin/env bash
set -euo pipefail
adb_path="$ANDROID_HOME/platform-tools/adb"
test -x "$adb_path"
test ! -e "$adb_path.real"
bash -n scripts/adb_bounded.sh
timeout --kill-after=5 10 "$adb_path" version
mv "$adb_path" "$adb_path.real"
cp scripts/adb_bounded.sh "$adb_path"
chmod +x "$adb_path"
timeout --kill-after=5 10 "$adb_path" version
printf 'Bounded ADB installed at %s\n' "$adb_path"
