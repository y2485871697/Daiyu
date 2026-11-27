#!/usr/bin/env bash
# Bound emulator-runner's ADB calls too: its boot loop awaits each call indefinitely.
set -uo pipefail
real="$(dirname "$0")/adb.real"
limit=${ADB_COMMAND_TIMEOUT:-10}
# The emulator runner sends this immediately after sys.boot_completed. Input can
# still be initializing; keep a separate bound for exactly this early command.
if [[ -z ${ADB_COMMAND_TIMEOUT:-} && $# == 6 && ${1:-} == -s && ${2:-} =~ ^emulator-[0-9]+$ &&
      ${3:-} == shell && ${4:-} == input && ${5:-} == keyevent && ${6:-} == 82 ]]; then
  limit=45
fi
[[ "$limit" =~ ^[1-9][0-9]*$ ]] || exit 2
log=${ADB_COMMAND_LOG:-smoke/adb-commands.txt}
mkdir -p "$(dirname "$log")"
printf '%s begin timeout=%s command=%q\n' "$(date -u +%FT%TZ)" "$limit" "$*" >> "$log"
timeout --kill-after=5 "$limit" "$real" "$@"
code=$?
printf '%s end exit=%s\n' "$(date -u +%FT%TZ)" "$code" >> "$log"
if test "$code" -eq 124 || test "$code" -eq 137; then
  printf 'ADB command timed out (%ss): %s\n' "$limit" "$*" >&2
fi
exit "$code"
