# F17/F18 candidate integration — not runtime evidence

The seven product files are the four Terminal/File sources, this note, and two corresponding androidTest files, delivered directly at their formal app source paths (not hidden docs). Supplied candidate source: `6c6d0ba3ef147b07fe0110e7d3b5b7ef21e695f3`, with the two narrowly scoped fixes below. No Controller, DB, Android Manifest, signing, build or old Eta integration changes.

## Wiring and identity

Both registries expose `constructor(context: Context)`, `schemas: JSONArray`, and blocking `execute(name, arguments): JSONObject`. Invoke off the UI thread. Parent owns unified registration, capability confirmation, trusted IDs and global durable tool ledger. Schemas alone are not evidence of availability.

* Configure once per run. Terminal conversation owner comes from trusted controller state. Ordinary jobs are run-scoped; daemon jobs use conversation owner. Live safe process handles are shared only inside the same app process.
* Android backend probes selected app/root identity before commands. Explicit root executable is required. Linux backend uses the explicitly owner-supplied launcher and environment; identity probe goes through that same shell. `identityArgv` remains advisory compatibility data. No fallback, installation or replacement of reviewed LongShellCommand/LinuxGuestPathResolver/PRoot sources is implemented.
* Linux launcher argv must end in a shell accepting `-c` for identity and file-backed stdin for commands. stdin is a private 0600 app-side bounded script passed as an inherited input descriptor, not an injected shell path in the guest. Owner guest cwd and shared mapping remain unchanged. `sharedMounts` is metadata, not a mount operation.
* Each command is a separate fixed-environment shell, not an interactive persistent shell. Variables/cwd from one invocation do not persist. stderr is merged with stdout; byte pages include base64.
* File roots and attachments are immutable trusted canonical snapshots. Absolute attachment matches return the frozen File and reject later symlink replacement. Directory listing checks that this canonical snapshot is unchanged before probing/enumerating, for both attachment IDs and exact absolute paths; replacement returns `path_changed_not_authorized` without entries/names. Attachment writes are rejected through attachment IDs, exact paths, shared paths and canonical aliases. Parent translates Linux guest paths to authorized host roots; no shell read or implicit channel change.
* Image/video tools return decoded PNG previews and metadata, not vision descriptions. Parent must attach supported model image inputs or explicitly report lack of vision. Video selects the closest requested-time frame, not necessarily an exact timestamp.

## At-most-once, dispatch and recovery

OperationJournal durably reserves pending before the operation, releases its lock during execution, and commits under a short lock. Pending/unknown equivalent operations cannot evade the guard with new request IDs. No unresolved entry is deleted for retention. Ledger commit failures preserve result identifiers while returning unknown. This local run guard does not replace parent cross-run/channel equivalence.

Terminal persists pending job metadata before starting. Command delivery uses a <=128KiB private fsynced script as stdin, avoiding unbounded pipe writes. No registry/ledger lock is held across command wait. A startup or metadata failure retains the job ID where a reservation exists; list/poll can inspect pending jobs even without logs. Started async/daemon responses are `execution=unknown`, `state=dispatched`, with job ID, never `not_executed`. Sync timeout is unknown, never resend. Poll original job. Replay of a successful terminal_open checks the live session set; after close or new registry it returns expired, without opening a replacement.

File writes fsync a temporary file, then rename/link. Before destination commit, errors are not_executed; exceptions once commit is attempted are conservatively unknown and equivalence blocks retry. fsync of parent directories/power-loss durability is not asserted.

## Explicit resource and lifecycle limits

* Fail-closed retention: 64 retained jobs per app process and job directory, 256 directory entries scanned, <=256KiB retained per log and <=16MiB total retained logs. Before dispatch, the locked admission check reserves a full 256KiB for every same-directory live reservation or retained metadata record (including unreadable/pending metadata), plus the new job. Orphan logs consume their actual bytes in addition; unknown logs are never deleted. This conservative reservation remains even after a job completes, so delayed output cannot oversubscribe the budget. Logs are drained with a fixed buffer and surplus discarded with `log_truncated`; no unbounded redirected log file. Jobs are not automatically evicted. At quota, further dispatch fails rather than deleting unresolved metadata/journals. This intentionally limits long-running deployment; safe operator archival/ledger-preserving cleanup is future parent work.
* Journal: <=256 operations/run, <=4MiB/scope, <=1024 process lock scopes, <=2048 directory entries. Over quota fails closed; unresolved entries remain. Directory/file size checks bound reads/scans. File directory pages cap offset at 10000 and page at 200.
* Daemon uses an actual setsid child only on supported Android app identity, not nohup/background-shell claims. It is independent of registry/run close **only while the same app process is alive**. Output draining is app-process-local. Android may kill the app or children; restart is not daemon recovery. Linux/root daemon lifecycle remains unsupported.
* After app restart metadata/logs remain readable with unknown state, never invented exit status or reconstructed handle. Stop requires retained Java Process and verified `/proc` starttime, and targets only the owned shell; descendants are not claimed stopped. Lost handle/PID reuse/starttime denial fail safe. Timeout does not claim cancellation of descendants.
* This is not a filesystem sandbox against adversarial same-UID races. Canonical root checks and special-file rejection do not eliminate TOCTOU; stronger isolation needs descriptor-relative native operations. File/metadata durability does not include directory fsync.

## Tests and evidence boundary

Dedicated instrumentation source covers directory-list replacement through both frozen attachment IDs and exact absolute paths with no name leakage; 63 x 256KiB orphan logs plus two gated-output dispatch attempts (sequential and concurrent) where only one can start and final retained output is 16MiB with orphan contents unchanged; absolute attachment read replacement, all write aliases, before/after file commit faults, journal final-save failure and pending replay, expired open replay, pending metadata before fake launch, private stdin/no pipe delivery, async unknown, metadata failure job ID/list/poll, equivalent dispatch retry suppression, bounded output, sync lock availability/timeout, retention and bounded scanning. Process tests inject fake Process objects and do not execute shell commands. Existing unavailable Linux/root checks remain.

Implementation agent cannot run shell, build or device tests. These tests are authored, **not claimed passed**. Parent owns typecheck and real Android/root/Linux/daemon/video execution. No actual-process/daemon survival acceptance is claimed; UI/media paid-generation wiring remains parent work.

## Frozen terminal path boundary

`TerminalPaths` now provides a narrow, non-mounting host/guest path boundary for an explicitly authorized host directory and absolute guest root. It normalizes the host root, records its filesystem identity, rejects NUL/relative/`..` guest paths, resolves each attachment with `NOFOLLOW_LINKS`, and returns a frozen `ResolvedAttachment`. Callers must invoke `validate`/`revalidate` immediately before use; root or descendant replacement returns `path_changed_not_authorized`. This helper does not create mounts, copy files, start commands, or replace controller authorization. Its instrumentation tests are authored and host source compilation is verified; Android instrumentation and real filesystem race coverage remain pending.

## R09 continued implementation: durable dispatch and descriptor reads

The numbered task continues; the prior handoff is not acceptance. New terminal-local changes:

- Journal writes now require temp-file fsync, atomic replace and parent-directory force; no non-atomic rename fallback. Directory setup also forces its parent even when a prior creation attempt left a visible directory. Reservation failures do not execute commands. A commit failure preserves identifiers and returns unknown. Equivalent completed command requests return the recorded job/result rather than launching again, including a commit visible after its final directory force failed. This is per run/session; intentional repeated commands need a new session. v3 command identity excludes wait timeout and treats sync/async as the same session-command dispatch; daemon remains distinct. Old unresolved fingerprints cannot be safely reinterpreted and block new execution in the scope without deleting evidence. Old same-ID identities return an explicit legacy identity error.
- JVM and file locks share a three-second admission deadline and honor interruption. No command executes while the ledger lock is held. The missing-entry commit path now throws and returns unknown instead of dropping its error result.
- `openRead()` anchors each component to a live descriptor, checks the opened object's identity, and returns the same leaf descriptor as a stream. Legacy `File` getters are display-only, not permission to reopen. Public API26 lacks O_DIRECTORY: opens use O_NOFOLLOW/O_NONBLOCK plus fstat type checks; the proc descriptor path is not silently replaced with pathname I/O when unavailable. Read semantics pin object identity, not immutable file contents or a full same-UID filesystem sandbox. No write API is added.
- Backend alias/real-path identity snapshots surround fresh same-launcher UID probes, plus a pre-launch path recheck. Changes reject without backend fallback. This detects changed launch paths; ProcessBuilder is still pathname-based exec, not a descriptor-exec sandbox. Root executables must be absolute. Linux guest UID0 does not assert Android root; Linux host-root execution is explicitly unsupported by this adapter rather than conflating PRoot root mapping with host privilege.
- Session close and dispatch share a short claim point. A start already claimed can finish after close; pending cancellation remains recorded, and the late immutable Process/pid/start handle undergoes the same ownership verification before signalling, including error exits. Registry close marks all ordinary jobs before signalling and preserves conversation-scoped daemon jobs. It does not claim descendant/process-group termination. Pending close responses expose incomplete cancellation rather than success-as-stopped.
- New JVM tests exercise real filesystem journal locks/fsync/atomic moves on Debian, default/wait equivalence, final-save faults, missing entries, legacy records, interruption, and scope independence. Android-only descriptor and lifecycle tests are authored; host compilation is not evidence those instrumented tests ran.

## Actual PTY implementation (not yet Android packaged)

The continued R09 work now includes a new independently implemented POSIX PTY
helper (`native/daiyu_pty.c`) plus `PtySession.kt`. These are separate from the
existing fixed-command `TerminalRegistry`; that registry's session semantics
have not silently changed. The helper creates a controlling terminal and a
persistent shell, transports binary frames, implements terminal resize and
foreground interrupt, keeps bounded queues, drains output with explicit
truncation on exit, and cleans owned groups while holding the child PID unreaped.
The Kotlin host uses durable open/write reservations and never recreates an
expired session scope; failed input fences queued input. `helper_input_written`
means the transport write completed, while `pty_delivery` and shell-command
completion remain unknown. There is no fabricated helper ACK.

Actual local verification:

- Debian cc C11 `-Wall -Wextra -Werror -O2` compiled the helper successfully.
- Eight POSIX helper cases passed: isatty(0/1/2), stateful cwd/export across
  writes, resize, foreground SIGINT with shell surviving, shell wait status,
  oversize frame rejection, background slave-holder exit bound, and interrupt
  under terminal-input backpressure.
- Eleven JVM tests passed: eight journal cases and three PTY driver cases, including
  a real helper-backed persistent shell and blocked-input/queued-input fencing.
- Full domain/app/androidTest host typecheck passed; final source checks are
  recorded outside the repository with hashes.

Known product dependencies remain concrete: the native helper is not yet wired
into public Gradle/ABI packaging (1号 owns that file), tool registration and
trusted owner/session routing belong to 6号/1号, and Android API26/API37.2 runtime
regression has not run. PRoot installation/distribution persistence and truly
app-restart-independent daemon ownership still require their separate production
integration. No host binary is committed, no APK is compiled/downloaded/installed,
and no claim is made that R09 as a whole is accepted.

PTY write fingerprints include an explicit trusted input sequence; intentional
identical repeated input advances that sequence, while retries keep it frozen.
Equivalent completed PTY controls are cached as well, so a directory-sync failure
after publishing an executed receipt cannot cause a new-ID duplicate input.

## Frozen Linux selection and real bind argv

`LinuxEnvironmentStore` now persists one explicitly selected installation and its
generation, verifies bounded helper/loader digests, rootfs shell presence, and
frozen filesystem identities, and reports FILES_READY rather than boot/identity
verification. Inspection does not start processes, install files, choose another
distribution, or fall back to Android. A lease freezes the generation and blocks
selection replacement across store instances in this app process. Lease.backend
builds explicit PRoot -r/-w/-0/-b argv from the exact same shared mapping it
reports, without hidden /dev or /proc binds. Distinct/overlapping bind destinations
are rejected. Loader variables cannot be overridden through TerminalConfiguration.
Guest root remains a PRoot mapping, never Android root. Configured host-root Linux
requests reject before any identity probe. The store checks a guest shell with
host canonical containment; unsupported absolute guest symlink shapes fail closed,
not via host filesystem escape. It does not validate all rootfs bytes, run a
package manager, or prove the packaged PRoot ABI/CLI actually works on Android.

Trusted host flow: construct store at private filesDir; select an owner-validated
installation only after installation completes; acquire lease for run; obtain
lease.configuration(tempDir, hostCwd); configure TerminalRegistry; close registry
and then lease. Keep the lease for the entire PTY lifetime when using its backend
argv/env with PtySession. Registry/PTY objects must never be shared across owners.
Public settings, installation/download transactions, and tool dispatch registration
remain outside 9号's owned files. New unit fixtures use inert fake executables and
never launch PRoot; FILES_READY cannot be reported as a successful Linux boot.

Environment follow-up verification: six pure-JVM installation/selection cases plus
the control-sequence regression bring the current combined JVM suite to 18 tests,
all passed on Debian. The suite now rejects oversized metadata before replacing a
valid selection and rejects binds that shadow the lexical or resolved guest shell.
Resize A→B→A and distinct interrupts carry separate control sequences, while
retries keep their original sequence. Full source typecheck also passed. None of
these fake PRoot metadata fixtures prove that a packaged PRoot boots on Android.

### Scope binding and explicit retirement (R09 incremental hardening)

`TerminalRegistry.configure()` durably binds the existing run journal to its
trusted conversation owner, run, private storage domain and frozen configuration
before any cache replay or job access. It does not create a new namespace on
restart. A conflicting owner/configuration fails configuration; an unbound
nonempty legacy journal fails closed and is retained rather than adopted.
`execute()` revalidates that binding before read/list and cached results. Live
jobs, stop and close additionally check owner and canonical storage directory;
identical owner/run strings in different private stores cannot share handles or
consume each other's per-store reservation count. These checks enforce the
trusted host's routing but do not authenticate model-supplied identity: configure
parameters must still come from the upstream trusted run owner, never tool JSON.

Journal format 4 stores binding digest/lifecycle/entries in an envelope. Legacy
unbound journals remain readable only through the unbound API. Unknown outcomes
and pending records remain intact. No TTL/LRU eviction occurs, including for
successful records: forgetting a successful fingerprint would permit a second
side effect under a new request ID.

After closing a registry, a trusted host may explicitly call
`retireCompletedScope()`. `OperationJournal.retireScope()` performs the same
short file lock as reservation and refuses any unresolved or malformed entry.
It atomically replaces only an entirely definite scope with a permanent retired
fence. Old and new instances reject all requests for that scope, even new IDs
or different commands. `.lock` files and retired markers stay; this compacts
result bytes, not the number of admitted scopes. Capacity pressure still fails
closed. Retirement sync failures report `operation_retirement_unconfirmed` and
never authorize an operation. This host-only API is not an automatic lifecycle
hook or a model tool; 1/6号 must choose the final trusted retirement point.

`LinuxEnvironmentStore.acquire(LinuxGenerationRef(...))` checks the expected
selection and increments its lease under one lock, avoiding status/acquire
races. Combined environment/generation identity is capped at the backend's 128
characters before replacing selection. Callers must still retain leases until
all owned processes have actually exited; no daemon restart supervisor or PTY
host facade is claimed by this change.

Verification for this increment: 25 JVM tests passed on Debian, including
binding/replay isolation, legacy refusal, permanent retirement, unknown/pending
retention, concurrent reservation, fsync failure and expected-generation checks.
The Android Registry tests for cross-owner cached results and cross-storage close
are added and host-typechecked; they have not run on API26/API37.2. Existing real
POSIX PTY protocol evidence is reused because this increment does not change
`PtySession.kt` or native helper bytes. Android ABI/SELinux/packaging and trusted
catalog routing remain separate integration gates.

Review corrections included in this increment: an existing empty legacy file is
claimed by a durable v4 envelope before binding verification succeeds; repeated
retirement re-persists the fence rather than equating visibility with durability.
An injected repeated directory-sync failure stays unconfirmed on every attempt.
Configuration collections are frozen before computing their binding digest. A
1024-entry global handle ceiling remains in addition to the 64-job/store quota.

### PTY event identity follow-up

For newly reserved PTY controls, input sequence and control sequence are durable
event identities, not merely part of the content fingerprint. Retrying the same
sequence and content replays its receipt; changing content or changing resize to
interrupt at the same control sequence fails with
`operation_event_identity_conflict`, even under a new request ID. Input and
control have separate sequence domains. Existing legacy entries without event
identity retain their earlier fingerprint-only semantics; no historical identity
is fabricated. Trusted callers must allocate sequences and never reuse them for
new events. Default zero stays conservative: a second different event at zero is
rejected. The original four-argument journal API remains source-compatible.

PTY launch freezes argv/environment before validating, journaling or spawning,
so mutable host collections cannot change the dispatched configuration after its
identity was reserved. Verification now totals 26 passing JVM tests, including
the real persistent PTY shell with explicit increasing input/control sequences,
and full host source typecheck. The first follow-up typecheck exposed a positional
lambda compatibility break; the existing overload was restored. The real PTY
fixture then correctly rejected reused default sequence zero; it now allocates
explicit sequences. A fixture syntax typo was corrected before the final passing
run. No native helper bytes or Android packaging changed.
The focused review also caught a test-masking issue: the queued-input fence
fixtures reused sequence zero and could pass at the earlier identity check.
They now use distinct sequences and assert `pty_session_unavailable`, preserving
actual queue-fence coverage; all 26 JVM tests passed after that correction.


## Device-group run-bound PTY facade (new2 local candidate)

`PtyRunOwner` captures trusted conversation, the exact host `TaskAuthorization` instance,
environment ID and generation. `PtyRunSession.open` binds these plus canonical storage to
its durable scope; a mismatched owner/generation or a nonempty legacy unbound scope fails
without clearing the journal or starting a replacement shell. Full launch arguments remain
part of the durable open fingerprint. A second open never reconstructs a historical process.

Read/write/resize/interrupt require the original authorization object, not an object with
copied fields. This is an ownership fence, NOT a replacement for central WRITE approval.
The trusted host allocates and retains request IDs/sequences; they are not model fields.
The Dispatcher currently has no call-context/sequence planner API: the facade does not
pretend that it supplies one or expose a WRITE tool without exact authorization.

`close()` revokes new admission and requests helper shutdown, without waiting. Read output
snapshots share a short in-memory lifecycle boundary with revocation; reads blocked behind
close are rejected. Already-admitted writes may settle after close and retain their actual
unknown/confirmed transport semantics. `awaitTermination(1..30000)` is a worker-only wait
for the helper Process, not command success or proof detached descendants were killed.

This facade does not acquire a selected Linux generation lease, package an Android helper,
or provide a startup-failure termination handle. A future Linux factory must retain the
expected-generation lease until actual helper exit, including failed/late startup; do not
release a lease merely because `open` threw. Those factory/cleanup paths, central registration,
UI and real Android/native runtime verification remain integration work for the owners.

`PtyRunSessionHostTest` executes the real Debian helper when `daiyu.pty.helper` is set;
otherwise JUnit assumption skips it. Final handoff must record the actual helper property,
source hashes and execution, not infer runtime success from compilation or test counts.
