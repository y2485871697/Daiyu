#!/usr/bin/env bash
set -euo pipefail
APK=$(find delivery -maxdepth 1 -name '*.apk' -print -quit)
test -n "$APK"
mkdir -p smoke
adb logcat -c
adb install "$APK"
adb shell pm list packages > smoke/packages.txt
! grep -q 'package:io.github.mangi.eta$' smoke/packages.txt
adb shell am start -W -n io.github.y2485871697.daiyu/.MainActivity > smoke/start.txt
cat smoke/start.txt
grep -q 'Status: ok' smoke/start.txt
for attempt in $(seq 1 20); do
  adb shell uiautomator dump /sdcard/boot.xml >/dev/null 2>&1 || true
  adb pull /sdcard/boot.xml smoke/boot.xml >/dev/null 2>&1 || true
  if test -f smoke/boot.xml && grep -q '代鱼 · 独立工程' smoke/boot.xml; then break; fi
  sleep 1
done
grep -q '代鱼 · 独立工程' smoke/boot.xml
grep -q '代鱼：已就绪' smoke/boot.xml
adb shell dumpsys activity activities > smoke/activity.txt
adb logcat -d > smoke/logcat.txt
! grep -q 'FATAL EXCEPTION' smoke/logcat.txt
adb exec-out screencap -p > smoke/启动.png
