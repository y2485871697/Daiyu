#!/usr/bin/env bash
set -euo pipefail
# The pre-launch wrapper bounds action probes; smoke stages set their own budgets.
export ADB_COMMAND_TIMEOUT=600
adb_stage() {
  local phase=$1 limit=$2 code=0
  shift 2
  printf '%s begin phase=%s budget=%ss\n' "$(date -u +%FT%TZ)" "$phase" "$limit" | tee -a smoke/stages.txt >&2
  timeout --kill-after=5 "$limit" adb "$@" || code=$?
  printf '%s end phase=%s exit=%s\n' "$(date -u +%FT%TZ)" "$phase" "$code" | tee -a smoke/stages.txt >&2
  return "$code"
}
APK=$(find delivery -maxdepth 1 -name '*.apk' -print -quit)
test -n "$APK"
mkdir -p smoke
collect_diagnostics() {
  timeout --kill-after=5 10 adb devices -l > smoke/devices.txt 2>&1 || true
  timeout --kill-after=5 10 adb shell getprop > smoke/properties.txt 2>&1 || true
  timeout --kill-after=5 10 adb shell df -h /data > smoke/storage.txt 2>&1 || true
  timeout --kill-after=5 10 adb shell cat /proc/meminfo > smoke/memory-exit.txt 2>&1 || true
  timeout --kill-after=5 15 adb logcat -d -b all > smoke/logcat-exit.txt 2>&1 || true
}
trap collect_diagnostics EXIT
adb_stage device 60 wait-for-device
api=$(adb_stage api 15 shell getprop ro.build.version.sdk | tr -d '\r')
# Current gfxstream images crash in gesture-bar region sampling before app install.
if test "$api" -ge 36; then
  adb_stage navigation 15 shell cmd overlay enable com.android.internal.systemui.navbar.threebutton
  adb_stage navigation 15 shell cmd overlay disable com.android.internal.systemui.navbar.gestural
  adb_stage navigation 15 shell cmd overlay list > smoke/navigation-overlays.txt
fi
# Boot-completed can precede a framework restart on a freshly created AVD.
stable=0
previous=""
for attempt in $(seq 1 60); do
  current=$(timeout --kill-after=5 5 adb shell 'pidof system_server && pidof surfaceflinger' 2>/dev/null | tr -d '\r') || current=""
  if test -n "$current" && test "$current" = "$previous" &&
     test "$(timeout --kill-after=5 5 adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 &&
     timeout --kill-after=5 5 adb shell pm path android > smoke/package-ready.txt 2>&1 &&
     grep -q '^package:' smoke/package-ready.txt; then
    stable=$((stable + 1))
  else
    stable=0
  fi
  previous="$current"
  printf '%s attempt=%s stable=%s pids=%s\n' "$(date -u +%FT%TZ)" "$attempt" "$stable" "$current" | tee -a smoke/readiness.txt >&2
  if test "$stable" -ge 15; then break; fi
  sleep 2
done
test "$stable" -ge 15
adb_stage memory 15 shell cat /proc/meminfo > smoke/memory-start.txt
if test -n "${EMULATOR_RAM_MB:-}"; then
  awk -v expected="$EMULATOR_RAM_MB" '/^MemTotal:/ {found=1; if ($2 < expected * 1024 * 0.9) exit 1} END {if (!found) exit 1}' smoke/memory-start.txt
fi
adb_stage install-app 120 install "$APK" 2>&1 | tee smoke/install.txt
adb_stage install-tests 120 install delivery/tests/platform-test.apk 2>&1 | tee smoke/test-install.txt
adb_stage instrumentation 300 shell am instrument -w app.daiyu.android.test/android.test.InstrumentationTestRunner 2>&1 | tee smoke/platform-tests.txt
expected=$(python3 - <<'PYCOUNT'
from pathlib import Path
import re
print(sum(len(re.findall(r"^\s*fun test\w+\(",p.read_text(),re.M)) for p in Path("app/src/androidTest").rglob("*.kt")))
PYCOUNT
)
test "$expected" -gt 0
grep -q "OK ($expected tests)" smoke/platform-tests.txt
if grep -q 'FAILURES!!!' smoke/platform-tests.txt; then exit 1; fi
adb_stage packages 20 shell pm list packages > smoke/packages.txt
if grep -q 'package:io.github.mangi.eta$' smoke/packages.txt; then exit 1; fi
adb_stage launch 30 shell am start -W -n app.daiyu.android/.MainActivity > smoke/start.txt
cat smoke/start.txt
grep -q 'Status: ok' smoke/start.txt
for attempt in $(seq 1 20); do
  adb_stage ui-dump 10 shell uiautomator dump /sdcard/boot.xml >/dev/null 2>&1 || true
  adb_stage ui-pull 10 pull /sdcard/boot.xml smoke/boot.xml >/dev/null 2>&1 || true
  if test -f smoke/boot.xml && grep -q '代鱼 · 聊天' smoke/boot.xml; then break; fi
  sleep 1
done
grep -q '代鱼 · 聊天' smoke/boot.xml
grep -q '发送' smoke/boot.xml
adb_stage activity 20 shell dumpsys activity activities > smoke/activity.txt
adb_stage logcat 20 logcat -d > smoke/logcat.txt
if grep -q 'FATAL EXCEPTION' smoke/logcat.txt; then exit 1; fi
if test "$api" -ge 36; then
  adb_stage screenshot 20 emu screenrecord screenshot "$(pwd)/smoke"
  screenshot=$(find smoke -maxdepth 1 -name 'Screenshot_*.png' -print -quit)
  test -n "$screenshot"
  mv "$screenshot" smoke/启动.png
else
  adb_stage screenshot 20 exec-out screencap -p > smoke/启动.png
fi
python3 - <<'PYPNG'
from pathlib import Path
assert Path('smoke/启动.png').read_bytes().startswith(b'\x89PNG\r\n\x1a\n'), 'Invalid screenshot evidence'
PYPNG
