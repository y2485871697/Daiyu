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

`app/build.gradle.kts` now links the colocated CMake project with
`externalNativeBuild`, explicitly selects the `daiyu_pty` executable target, and
builds/packages `arm64-v8a`, `armeabi-v7a`, `x86_64` and `x86` at NDK API26. The
NDK executable CRT/PIE entry point is retained: this is **not** a shared library
with `main` substituted as an entry point and must not be loaded with JNI.
CMake names the Android output `libdaiyu_pty.so` in AGP's native output directory
so the native packaging/installer path accepts it as `lib/<abi>/libdaiyu_pty.so`.
The `-Wl,-z,max-page-size=16384` linker option remains in force. Non-Android CMake
and `compileHostPty`/`verifyHostPty` continue producing host executables only.

`packaging.jniLibs.useLegacyPackaging = true` is intentional: the merged manifest
requests native extraction (`extractNativeLibs=true`), and the **package
installer**, not application code, materializes an executable file under
`ApplicationInfo.nativeLibraryDir`. APK compression is unrelated to the ELF's
16KiB segment alignment. Do not remove extraction merely because a JNI library
can be mmap-loaded directly from an APK. No host executable is shipped in this
repository, and no download, asset-copy, chmod or filesDir executable fallback
exists.

`AndroidPtyHost.helper(Context): File` is the shared trusted resolver. It reads
this app's PackageManager installation record, verifies package/UID, requires
the expected file beneath the read-only APK installation/native directory, and
rejects missing ABI payloads, symlink escapes and non-executable or writable
files before a process or journal is opened. A helper path cannot be supplied to
`AndroidPtyHost.open`; that worker-only host entrypoint actually calls
`PtyRunSession.open(owner, helper, argv, environment, cwd, journalDirectory,
sessionScope, rows, columns)`. Context, owner and frozen launch values must come
from the trusted host after central authorization, never model JSON. The adapter
does not manufacture WRITE grants, register tools, change receipts or replay
UNKNOWN operations. `close()` still only requests shutdown; use
`awaitTermination()` and retain any selected environment lease until it returns
true.

### Focused verification (build/integration owner)

No Android build or device execution is implied by host tests. Before delivery,
perform a minimal Android build with the existing toolchain and inspect:

- APK entries `lib/<abi>/libdaiyu_pty.so` for all four declared ABIs; merged
  manifest extraction enabled (also verify a delivered split if using bundles).
- NDK `llvm-readelf -h -l` reports the matching machine, a PIE executable with
  an interpreter, and LOAD alignment at least `0x4000`; the minimum API is 26.
- Only `app.daiyu.android.terminal.AndroidPtyHostTest` on API26 and API37.2:
  installed immutable executable resolution, app-data/missing-ABI preflight
  refusal without a journal, real PTY launch/dimensions, owner isolation,
  UNKNOWN delivery receipts and an actual exit wait status. It intentionally
  uses the installed Android payload, not the host-test helper property.

These checks still require execution by the build/integration owner; neither
SELinux compatibility on a particular device nor background process survival
is established merely by the build configuration. The app's normal sandbox and
Android lifecycle restrictions continue to apply.

References: [AGP external native builds/explicit executable targets](https://developer.android.com/studio/projects/gradle-external-native-builds),
[AGP native packaging DSL](https://developer.android.com/reference/tools/gradle-api/9.0/com/android/build/api/dsl/JniLibsPackaging).

For intentionally identical repeated input, the trusted host supplies a distinct
`inputSequence` to `write`; retries retain the original sequence and request ID.
Default sequence zero is conservative and reuses an equivalent completed receipt
instead of injecting identical bytes twice after a commit-durability fault. Do not
allocate a fresh sequence to retry an unknown write.

The same trusted operation-sequence rule applies to `resize(controlSequence)` and
`interrupt(controlSequence)`: a new foreground interrupt or restoring an earlier
size is a new action and advances the sequence; retrying an uncertain action keeps
it. Default zero intentionally reuses same-argument controls conservatively.
