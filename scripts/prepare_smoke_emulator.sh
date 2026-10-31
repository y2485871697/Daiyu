#!/usr/bin/env bash
set -euo pipefail
api=$1
mkdir -p smoke
rm -f smoke/prelaunch-ready.txt
# The API37 userdata image needs about 10 GB; smoke never uses the hosted .NET SDK.
if test "${RUNNER_ENVIRONMENT:-}" = github-hosted; then
  sudo rm -rf /usr/share/dotnet
fi
df -h "$ANDROID_AVD_HOME" | tee smoke/host-disk.txt
"$ANDROID_HOME/emulator/emulator" -version > smoke/emulator-version.txt 2>&1
cat smoke/emulator-version.txt
cp "$ANDROID_HOME/emulator/source.properties" smoke/emulator-package.txt
if test "$api" = 26; then
  grep -Fx 'Pkg.Revision=35.6.11' smoke/emulator-package.txt
  grep -Fx 'Pkg.BuildId=13610412' smoke/emulator-package.txt
fi
bash scripts/install_bounded_adb.sh
python3 -c 'import os; from pathlib import Path; p=Path(os.environ["ANDROID_AVD_HOME"])/"test.avd/config.ini"; values={"hw.lcd.width":"1216","hw.lcd.height":"2432","hw.lcd.density":"160","skin.name":"1216x2432","skin.path":"1216x2432"}; lines=[line for line in p.read_text().splitlines() if line.split("=",1)[0].strip() not in values]; p.write_text("\n".join(lines+[f"{key}={value}" for key,value in values.items()])+"\n")'
cp "$ANDROID_AVD_HOME/test.ini" smoke/avd.ini
cp "$ANDROID_AVD_HOME/test.avd/config.ini" smoke/avd-config.ini
cat smoke/avd.ini
grep -Fx "target=android-$api" smoke/avd.ini
printf '%s\n' "$api" > smoke/prelaunch-ready.txt
