#!/usr/bin/env bash
set -euo pipefail
APK=$(find delivery -maxdepth 1 -name '*.apk' -print -quit)
test -n "$APK"
mkdir -p smoke
collect_diagnostics() {
  timeout 10 adb devices -l > smoke/devices.txt 2>&1 || true
  timeout 10 adb shell getprop > smoke/properties.txt 2>&1 || true
  timeout 10 adb shell df -h /data > smoke/storage.txt 2>&1 || true
  timeout 15 adb logcat -d -b all > smoke/logcat.txt 2>&1 || true
}
trap collect_diagnostics EXIT
adb wait-for-device
# Boot-completed can precede a framework restart on a freshly created AVD.
stable=0
previous=""
for attempt in $(seq 1 60); do
  current=$(timeout 5 adb shell 'pidof system_server && pidof surfaceflinger' 2>/dev/null | tr -d '\r') || current=""
  if test -n "$current" && test "$current" = "$previous" &&
     test "$(timeout 5 adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 &&
     timeout 5 adb shell pm path android > smoke/package-ready.txt 2>&1 &&
     grep -q '^package:' smoke/package-ready.txt; then
    stable=$((stable + 1))
  else
    stable=0
  fi
  previous="$current"
  printf '%s stable=%s pids=%s\n' "$attempt" "$stable" "$current" >> smoke/readiness.txt
  if test "$stable" -ge 15; then break; fi
  sleep 2
done
test "$stable" -ge 15
collect_diagnostics
adb install "$APK" 2>&1 | tee smoke/install.txt
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
