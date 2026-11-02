#!/system/bin/sh
# Run only via the authorized Android root daemon tool. No install, input or app launch.
set -eu
BASE=${1:?supply an absolute evidence directory}
case "$BASE" in /data/local/tmp/eta/*) ;; *) echo 'evidence directory must be under /data/local/tmp/eta' >&2; exit 2;; esac
CONFIG=${2:?supply the ui-jank.pbtxt path}
# The wrapper deadline includes startup, waits, postprocessing and bounded cleanup.
if test "${3:-}" != --bounded; then
  exec timeout -s TERM -k 5 900 /system/bin/sh "$0" "$BASE" "$CONFIG" --bounded
fi
mkdir -p "$BASE"
chmod 700 "$BASE"
command -v perfetto >/dev/null
command -v trigger_perfetto >/dev/null
LOG="$BASE/events.log"
logcat -v brief -T 1 -s DaiyuJank:I > "$LOG" &
LOGGER=$!
TRACE_PID=
DIR=/data/user/0/app.daiyu.android/no_backup/jank-diagnostics-v1
copy_metadata() {
  if test -d "$DIR"; then mkdir -p "$BASE/metadata-$1"; cp "$DIR"/incident-*.json "$BASE/metadata-$1/" 2>/dev/null || :; fi
}
cleanup() {
  kill "$LOGGER" 2>/dev/null || :
  if test -n "$TRACE_PID"; then
    kill -TERM "$TRACE_PID" 2>/dev/null || :
    for attempt in 1 2 3; do kill -0 "$TRACE_PID" 2>/dev/null || break; sleep 1; done
    kill -KILL "$TRACE_PID" 2>/dev/null || :
  fi
  copy_metadata final
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
START=$(date +%s)
INDEX=0
# Fix the trigger baseline before READY so the first trigger is never swallowed.
BASELINE=0
while ! grep -q DAIYU_JANK_READY "$LOG"; do
  test $(( $(date +%s) - START )) -lt 900 || exit 0
  sleep 1
done
while test "$INDEX" -lt 6 && test $(( $(date +%s) - START )) -lt 900; do
  INDEX=$((INDEX + 1))
  OUT="$BASE/incident-$INDEX.perfetto-trace"
  # Acknowledge actual data-source startup, rather than assuming a fixed sleep.
  if ! perfetto --background-wait --txt -c "$CONFIG" -o "$OUT" > "$BASE/perfetto-$INDEX.log" 2>&1; then
    echo 'PERFETTO_START_FAILED' >> "$BASE/recorder.log"
    exit 1
  fi
  TRACE_PID=$(sed -n '/^[0-9][0-9]*$/p' "$BASE/perfetto-$INDEX.log" | tail -n 1)
  case "$TRACE_PID" in ''|*[!0-9]*) echo 'PERFETTO_PID_MISSING' >> "$BASE/recorder.log"; exit 1;; esac
  echo "TRACE_ARMED $INDEX" >> "$BASE/recorder.log"
  while kill -0 "$TRACE_PID" 2>/dev/null; do
    COUNT=$(grep -c DAIYU_JANK_TRIGGER "$LOG" || :)
    if test "$COUNT" -gt "$BASELINE"; then
      BASELINE=$COUNT
      trigger_perfetto daiyu_ui_jank_v1
      break
    fi
    sleep 1
  done
  # background-wait's recorder is detached; wait(non-child) cannot reap it.
  while kill -0 "$TRACE_PID" 2>/dev/null; do sleep 1; done
  TRACE_PID=
  test -s "$OUT" || { echo 'TRACE_EMPTY' >> "$BASE/recorder.log"; exit 1; }
  PID=$(pidof app.daiyu.android || :)
  if test -n "$PID"; then
    printf '%s\n' "$PID" > "$BASE/target-pid-$INDEX.txt"
    timeout -s TERM -k 1 10 dumpsys gfxinfo app.daiyu.android framestats > "$BASE/frames-$INDEX.txt" || :
  fi
  copy_metadata "$INDEX"
  BASELINE=$(grep -c DAIYU_JANK_TRIGGER "$LOG" || :)
done
