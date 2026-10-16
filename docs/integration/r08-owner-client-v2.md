# R08 application-side owner transport v2

## Scope and provenance

This change adds only application-side Kotlin leaf components and independent tests. It does not
wire a dispatcher, GUI tool, RunSession, journal, Controller, Main or RunCatalog. The parent must
retain the host and its observations, and must provide authenticated run identity and stable
operation IDs. No runtime/device success is asserted by this document.

The implementation was written against this repository's Java `OwnerProtocol`,
`OwnerOperationIdentity`, `OwnerReceiptCoordinator`, `OwnerReceiptStore`, `OwnerIpcServer` and
`VirtualDisplayOwnerMain`. `OwnerV2Contract` is a same-package adapter to the existing Java
normalizer and canonical identity functions, not another definition of effective parameters.
No external Eta files or algorithms were copied or read for this implementation; no external
attribution or license grant is implied. Existing repository licensing applies to this new code.

## Host-only API (not a tool API)

Classes are in `app.daiyu.android.vd`:

1. Construct `AndroidVirtualDisplayOwnerHost(applicationContext)`. Construction does not invoke
   `su`, start a process, connect a socket, initialize an owner, or touch the filesystem.
2. From a worker, call `start(generation, binding)`. Generation and the six-field
   `OwnerRunBinding` must come from trusted parent/journal state, not model JSON. One host accepts
   only one start attempt. `READY` means kernel-verified transport availability, **not a display**.
3. Retain the host even when `start` returns `UNKNOWN`. Its private `Process`, stdout observer,
   eventual READY capability and exit observer are retained. Do not replace it to pretend the
   old lease ended. The caller can deliberately use `reconnect()`; that method neither spawns
   nor initializes, reads receipts, executes operations, invents identity nor replays requests.
4. When the parent's policy authorizes it, explicitly call `host.client.initialize()` from a
   worker. The per-client call is one-shot; receipts remain the durable authority.
5. `execute(operationId, operation, parameters)` takes an externally allocated stable ID.
   Parameters are detached/default-completed using Java `OwnerProtocol`. This is a host-only API;
   never forward arbitrary envelopes/tokens/bindings/paths from a model. The client sends one
   request, never retries it, and refuses a second execute with the same ID in that connection.
6. `lookupGeneration()` and `lookup(id, operation, originalParameters)` send **only `receipt`**.
   They do not invoke initialize, status, snapshots, backend work, cleanup, or execution. Use the
   original effective parameters and trusted binding. Existing STARTED/UNKNOWN receipts are not
   execution permits. A receipt-only success has `executed=false` and is not a business success.
7. Handoff and release are deliberately explicit `execute` calls with parent-provided IDs. No
   exception handler, close method, disconnect or reconnect sends them. The parent must enforce
   its journal policy; the Java owner additionally enforces its durable controlled-cleanup policy.

`OwnerReply.Outcome.REPLIED` only says a strictly validated protocol reply arrived. Inspect its
`ok`, `receiptOnly`, `executed`, receipt state and other validated fields; **REPLIED is not an
execution success enum**. `UNKNOWN` provides no raw response. `payloadCopy()` is for trusted
host integration, not automatic model/tool publication. Reply/client/credential/host `toString`
never renders an envelope, bootstrap line or token. Parsing/transport failures have no raw chained
cause. Even escaped credential echoes are rejected before a reply can be exposed.

All client I/O methods reject the Android main thread. Calls are synchronous and serialized.
Connection, write and response read are bounded by a 20-second socket-closing watchdog; the
response cap is 9 Mi characters (6 MiB snapshot + base64/envelope). UTF-8 decoding reports malformed
input. A failed request poisons/disconnects that transport, but **not the process**. JSON is
strictly gated (duplicate keys, comments, single quotes, trailing values, invalid Unicode and
non-JSON numbers rejected); v2 envelope, schema/type, response operation, generation, receipt
binding and full canonical parameter digest are checked. Unknown response fields fail closed.

## Trusted bootstrap and filesystem

The only launch classpath is the installed application's PackageManager `sourceDir` and
`splitSourceDirs`, with the application's own UID. No downloaded/copied dex or configurable
classpath is accepted. The root `su -c` script uses `exec env CLASSPATH=... /system/bin/app_process`
and the existing Java entrypoint, with shell-quoted host-derived arguments. stdout and stderr
are separate private pipes. stderr is discarded; stdout accepts only a bounded exact READY shape
and exact STOPPED line; arbitrary output, tokens and parse errors are never returned or logged.

The receipt root is fixed to:

```
<actual application Context.noBackupFilesDir.canonicalFile>/vd-owner-receipts-v2
```

There is no public receipt-root/path/shell-fragment parameter. Before launch the app captures the
real ancestors' device/inode/UID/GID/mode. The root script verifies those identities, directory
and non-symlink status, and canonical parent path, then creates only the absent leaf with mode
0700 and owner/group 0:0. An existing leaf must already be a non-symlink directory owned 0:0 with
exact mode 0700; it is never repaired, adopted, recursively chowned or chmodded. Identities are
rechecked before exec. Ancestors may be root/system/the application UID only; world-writable
ancestors are rejected; group write requires a root/system/application group. The threat model,
as for Java `OwnerReceiptStore`, excludes a compromised same-UID application process or root.
Normal Android trusted app ancestors are not converted to root ownership. Java then independently
validates its receipt tree and acquires the root lifetime lock.

The kernel `LocalSocket.peerCredentials` must report UID **0** and the exact positive PID from
the private READY line **before any token-bearing write**. UID/PID claims in JSON alone are not
accepted. Socket name randomness is solely transport addressing, never generation/operation ID.
READY says `initialized=false`; the current Java owner only creates a display after authenticated,
durably receipted initialize.

## Lifecycle truth and limitations

`observe()` returns `NOT_STARTED`, `UNKNOWN`, or `DRAINED`. `DRAINED` requires all of:

- exact private `VD_OWNER_STOPPED released=true receiptsFinal=true` observation;
- actual retained launcher `Process.waitFor()` exit code 0;
- the READY owner PID has actually disappeared (`Os.kill(pid, 0)` returns ESRCH).

Signal zero is an existence/permission probe, not a terminating signal. EPERM, PID reuse or any
other inability to prove disappearance stays UNKNOWN. Closing a socket, returning a release
reply, or exiting `su` alone is not drainage. The app never calls `destroy`, `kill`, or implicit
release. Process and pipe observations survive handshake failures, deadlines and ambiguous writes.

**There is no pre-initialize shutdown protocol in the current Java owner.** Starting solely for
receipt lookup can leave an uninitialized root process holding the lifetime receipt lock. This
client does not send fake initialization/release, kill it, or report it drained. It remains UNKNOWN
and needs a future explicitly designed owner shutdown protocol/parent policy. A second process
contending for the same fixed root is rejected by the existing lock, not used as an automatic
recovery mechanism. `su` implementation, root policy/SELinux, installed APK accessibility,
Android socket behavior and filesystem support can all prevent startup; errors remain honest.

## Verification evidence and unverified device matrix

Added `VirtualDisplayOwnerClientTest` uses scripted `OwnerTransport` and Robolectric's Android
stubs; it never runs su/app_process or a display. Cases cover:

- host construction without touching Context/startup;
- lookup before initialize stays receipt-only with exact stable binding/generation;
- original request and lookup preserve external operation ID and canonical defaults;
- one-shot initialization and duplicate-execute rejection;
- malformed payload rejection before I/O;
- dropped response poisons only transport and never sends release/replay;
- wrong version/op/type/digest/generation/state, unknown fields, duplicate keys, permissive JSON,
  trailing objects and both raw/escaped credential echoes are rejected without raw exposure;
- protocol failures stay failures rather than fabricated successful execution.

Implementation agent did **not** execute build/tests (no shell/build capability); the supervising
agent must record actual commands/results separately. Real-device tests have **not run** here.
Required device validation remains: successful root/SELinux bootstrap, real installed base/split
classpath, root-owned 0700 receipt persistence, unsafe leaf/ancestor refusal, UID/PID spoof refusal,
READY timeout/late readiness, startup failures retaining the Process, byte-fragmented/truncated
UTF-8 and huge responses, stalled writes, socket loss during each operation, independent STOPPED
and process-exit ordering, PID visibility/reuse, and initialize/lookup-only no-shutdown UNKNOWN.
No dispatcher/GUI or parent journal/session integration has been tested or claimed.
