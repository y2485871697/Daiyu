#!/usr/bin/env bash
# Bound emulator-runner's ADB calls too: its boot loop awaits each call indefinitely.
set -uo pipefail
real="$(dirname "$0")/adb.real"
limit=${ADB_COMMAND_TIMEOUT:-10}
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
