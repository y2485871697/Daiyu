# Video host P1 regression contracts

## Integration hook (no Controller / Activity / DB changes)

`VideoChatHost.bindLifecycle(lifecycleOwner, onChanged)` installs the production
`DefaultLifecycleObserver`. Keep the returned binding only for the active chat
route; close it synchronously on **route departure**, even when the Activity is
still STARTED. `enter(...)` is also a visit boundary. Destruction always detaches.
Do not separately call ordinary `detach()` for the observer's picker onStop.
Ordinary `attach()` / `detach()` remain conservative visit-invalidating APIs.
Retain the same host across Activity recreation; restoration requires the previous
writer to be gone/quiescent, as with process death. There is one live host writer
per journal, not two concurrently recreated hosts.

Picker wiring:

1. Capture `requestSelection()`'s exact object in the result callback.
2. Call `launchSelection(ticket) { launcher.launch(...) }` immediately around the
   actual system-picker launch. Merely requesting a selection does NOT arm an
   excursion. Do not arm for an unrelated Activity or background operation.
3. Deliver the same object to `picked(ticket, uris)` after the lifecycle reaches
   STARTED; do not replace it with a newly requested ticket.

Only that launched, in-memory ticket can cross **one** onStop -> onStart pair.
During the stop, UI visibility is false and previews are cancelled. A second stop,
route close, conversation change (including A -> B -> A), destruction, process
recreation, wrong object, or failed launch does not grant provider access. Import
work cannot overlap creation of a picker ticket. This is not a global lifecycle
fence bypass. The host currently has no app Activity/Controller call sites; this
change supplies the tested hook without claiming that application wiring exists.

## Disk/journal protocol

- The owner and import reservations are committed before provider access.
- Ready/UNKNOWN references remain protected. At restore, the worker enumerates
  **only each journalled VideoAttachmentOwner**, via the existing
  `VideoAssetStore.list(owner)` API. There is no whole-store enumeration or age GC.
- Inventory references absent from that owner's Ready references are committed
  into `pendingDeletes` before deletion. A mismatching reference with the same
  protected ID, foreign inventory, or inventory IO failure grants no deletion or
  new admission. Existence never establishes DB acceptance or authorizes resend.
- User removal and exact authoritative ACCEPTED settlement commit removal from
  Ready and the exact original's `pendingDeletes` entry in the **same journal**.
  Null-slot migration retains its owner and pending queue. REJECTED/UNKNOWN do not
  authorize deleting Ready originals.
- Delete acknowledgements remove queue entries only after successful idempotent
  removal. Absent originals are success; thrown/partial removal keeps the durable
  entry. Attach, explicit `retryCleanup()`, a picker request with pending deletions,
  or restart retries; there is no automatic failure spin.
- Import->Ready-commit crashes are recovered by inventory reconciliation;
  ACCEPTED-empty-Ready->delete crashes by the durable queue (also by exact-owner
  reconciliation for older v1 journals). Old v1 rows without `pendingDeletes`
  remain readable. Interrupted deletion tombstones are retried by their saved
  reference, not guessed from a message copy or file timestamp.
- Failed import UI cards still require explicit removal; recovery reclaims their
  *physical originals*, rather than silently dropping failed attachments or
  inventing an accepted state. After those cards are removed, all four physical
  slots are reusable. MessageVideoOwner files are never listed or deleted here.

## Evidence and limits

`VideoChatHostTest` drives actual Robolectric `ComponentActivity` lifecycle
transitions through the production observer, not only calls to detach/attach.
It covers launched vs unlaunched tickets, one-shot stop exemption, changed visits,
destruction/restart, wrong ticket and launch failure. Other tests inject the
import-on-disk/failed-journal boundary, stop the old executor immediately after
ACCEPTED's synchronous commit, and test repeated deletion failures plus restart,
Ready/UNKNOWN protection, exact-owner isolation, and importing four again.
These host tests use fake metadata and do not prove Android decoding.

`VideoHostDraftStoreTest` checks atomic reference/queue round trips, old-v1
compatibility, and rejection of Ready/delete overlap, duplicate IDs and foreign
owners. `VideoChatHostAndroidTest` uses real on-device encoded AVC/MP4 files,
ContentResolver import, VideoAssetStore inventory/removal and message copies. Its
new recovery tests verify real-disk orphan cleanup without touching UNKNOWN or
message references, and delete failure/restart followed by four fresh imports.
The Android tests model process death by discarding host state at the persisted
boundary; they do not kill an OS process or launch a device DocumentsUI picker.

Build/unit/instrumentation execution belongs to the supervising agent; source
changes alone are not a claim that any test lane has passed.
