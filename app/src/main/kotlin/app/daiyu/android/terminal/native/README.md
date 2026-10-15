# Independent Daiyu PTY helper

`daiyu_pty.c` is a new GPL-3.0-or-later implementation, not the old Eta PTY wrapper.
It uses libc/POSIX/Linux APIs and no bundled third-party code. Keep PRoot's own
source/license obligations separate; this helper can exec the authorized PRoot
launcher but does not reimplement, download or install PRoot.

## Protocol and runtime

Run `daiyu_pty ROWS COLS -- /absolute/shell [arguments...]` with trusted fixed
argv/environment/cwd. All inherited shell stdio connects to the same PTY slave;
master/control transport stays in the helper. stdin/stdout framing is binary:
1-byte type, 4-byte unsigned big-endian length, then at most 65536 bytes.

- Input `W`: terminal input bytes; `R`: 2-byte rows + 2-byte columns; `I`: no
  payload, SIGINT current foreground job group; `C`: no payload, close.
- Output `H`: ASCII `DPTY1` after exec-error pipe confirms exec; `O`: raw PTY
  bytes; `T`: zero-length drain-truncated notification; `X`: 4-byte raw wait status; `E`: 4-byte startup errno.

Neither a prompt nor terminal output is trusted command completion metadata.
A write receipt only acknowledges transport to the helper. Use the `X` frame
for shell exit; it does not say every submitted command succeeded. Output and
input queues are bounded. A full input queue fails closed rather than stalling
control parsing. Helper TERM/HUP/INT and parent death request owned-group
cleanup; waitid WNOWAIT reserves the child PID through cleanup. Child exit
starts a 250ms output drain cutoff, so descendants holding the slave cannot
withhold the shell exit frame forever. Output beyond the cutoff can be lost.
An unrelated process that independently changes session/group is not claimed
killed. Detached daemons are a separate capability, not a PTY job survival claim.

`PtySession.kt` is the Kotlin trusted-host driver. Open and write actions use a
durable session ledger; a used scope never opens a replacement shell. A lost
process/uncertain write is not replayed. The driver is not registered with the
model tool catalog yet. Owner isolation must be provided by the trusted host
that holds this session object; callers must not expose it across owners.

## Build and integration boundary

Host validation (non-Android) uses:

```sh
cc -std=c11 -Wall -Wextra -Werror -O2 daiyu_pty.c -o /trusted/check-dir/daiyu_pty_host
```

The colocated CMake file defines a PIE-capable executable target and 16KiB
Android linker alignment. The public app build currently does not include it.
The 1号 build owner must build with NDK API26 for supported ABIs, package as a
trusted executable in the native-library delivery path, verify actual extraction
and SELinux execution rules on API26/API37.2, then supply that packaged path to
`PtySession.open`. Do not execute a downloaded/copy-to-filesDir binary to evade
Android targetSdk execution restrictions. No host executable is shipped in this
repository, and host success is not Android ABI or background-lifecycle evidence.

For intentionally identical repeated input, the trusted host supplies a distinct
`inputSequence` to `write`; retries retain the original sequence and request ID.
Default sequence zero is conservative and reuses an equivalent completed receipt
instead of injecting identical bytes twice after a commit-durability fault. Do not
allocate a fresh sequence to retry an unknown write.

The same trusted operation-sequence rule applies to `resize(controlSequence)` and
`interrupt(controlSequence)`: a new foreground interrupt or restoring an earlier
size is a new action and advances the sequence; retrying an uncertain action keeps
it. Default zero intentionally reuses same-argument controls conservatively.
