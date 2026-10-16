# P19/P20 incremental generation assets (leaf, not wired)

## Scope and exact API

`media.MessageGenerationAssets` implements the existing
`model.generation.GenerationAssets` without changing it, the controller, Main,
message domain types, database/schema, ordinary image sending, or either existing
store's complete-batch `prepare` contract. It owns a **separate**
`<privateRoot>/generation-v1` namespace. Do not put that namespace in a draft,
ordinary message-image or video directory or export it as a backup asset.

Public production construction:

```kotlin
val binding = GenerationAttemptBinding(
    persistedConversationId, persistedAssistantId, persistedRunId,
    persistedToolCallId, persistedToolName,
)
val owner = GenerationAssetOwner(stableLocalAccountScope, binding)
val fence = GenerationAssetFence(owner) { boundOwner ->
    // REQUIRED parent implementation, not a default supplied by this leaf:
    // verify the actual account DB + conversation + assistant/run + exact tool
    // invocation and the current live execution lease/admission state.
    validatePersistedOwnerAndLiveLease(boundOwner)
}
val assets = MessageGenerationAssets(privateAccountAssetRoot, fence)
val attempts = SQLiteGenerationAttemptStore({ applicationDatabase }, binding)
val io = GenerationIo(assets, attempts, authorizeDownload)
// Requests MUST use binding.identity(stableItemId). Never choose a new retry UUID.
```

The validator is mandatory, has no permissive production default, and throws/
returns false on missing/unavailable ownership. Supplying a callback that always
returns true is only a storage-test seam, **not** real owner validation. This leaf
cannot select an account DB or infer a persisted invocation from arbitrary strings.
The account scope must be a stable local account/DB ID, not a credential or URL.
Validate its correspondence to the actual DB in the callback. Reuse the SAME
binding as the existing attempt ledger; `commit` rejects a different operation.
Recovery may allow an already-completed but still-owned invocation, as the attempt
ledger does. A new live publication still needs the parent's execution lease.

Public methods:

* `inspect(kind, bytes, declaredMime): GenerationMediaInfo`: bounded defensive
  copy; production uses `AndroidImageInspector` (real sampled decode) or
  `AndroidVideoInspector` (container + video track/sample + decoded frame).
  PNG/JPEG/WebP and MP4/WebM only. Empty or octet-stream MIME is treated as unknown,
  never as evidence; a specific declaration must match the detected MIME.
* `commit(identity, outputIndex, kind, bytes, info): GenerationAssetReceipt`:
  reinspects actual bytes, checks all metadata and owner admission, and publishes
  one output. `outputIndex` is 0..31. It does NOT create a message or DB row.
* `pending(identity, outputIndex): PendingGenerationAsset?`: restart recovery of
  a known slot, with owner, metadata, size, digest and real-media verification.
* `pending(receipt): PendingGenerationAsset`: verify a known receipt in this
  owner's namespace. A foreign/forged receipt fails closed.
* `openOriginal(receipt): FileInputStream`: caller-owned, read-only, rewound
  ORIGINAL descriptor. Size/digest and actual media metadata are verified first.
  For video this is the original video, never a thumbnail. No path/URI is exported.
* `GenerationAssetFence.revoke()`: sticky cancellation/owner-invalidation fence.

Internal output-store integration: `admitRegistration(expectedOwner) { SQL }`
revalidates the same owner through the existing fence and holds its gate until the
SQL callback has committed or failed. It does not read files. Verify via `pending`
first, release file locks, then acquire this gate **before** starting SQL; never
enter from a DB transaction or call asset/file APIs inside the callback. The
expected owner's scalar fields must match even when recovery finds no slot.

`PendingGenerationAsset` carries `receipt`, `owner`, `identity`, `outputIndex`,
`kind`, `mimeType`, `bytes: Long`, `sha256`, `width`, `height`, `durationMs` (0 for
images). Its name is intentional: it always means **awaiting parent registration**,
not "already attached to chat". `GenerationAssetReceipt.assetId` has the exact
format `genasset-v1-<64-lowercase-hex-slot>-<64-lowercase-hex-original-sha256>`.
The slot hashes a versioned, length-delimited encoding of account, conversation,
assistant, run, call, operation, item and index. Like `GenerationAttemptBinding`,
it excludes the descriptive tool name so renaming a call cannot create a second
slot; the immutable record still binds and verifies the original tool name.
No URL, random output UUID, fake message, draft ID or provider task ID is used as
an asset identity.

Errors are redacted `GenerationAssetException(failure)` with `CANCELLED`,
`OWNER_REJECTED`, `IDENTITY_CONFLICT`, `INVALID_MEDIA`, `CORRUPT`, or `STORAGE`.
Existing `GenerationIo.downloadAndCommit` maps commit exceptions to
`GenerationFailure.ASSET_REJECTED`; it does not expose this finer classification.
No change to that existing behavior is made. An exception does not prove that an
atomic rename failed to publish: recover the known key before deciding anything.

## Idempotency, cancellation, crash and partial-result rules

Each slot has one directory with `original` and a versioned private binary
`record-v1`. The metadata includes actual ownership, identity/index, MIME, size,
SHA-256, dimensions and video duration. Both files are fsynced before an atomic
rename of the entire directory. That record IS the durable index-to-receipt
mapping; there is no second in-memory map that can be lost on restart. Existing
slots are fully verified; identical retries return the same receipt. Different
bytes/kind/metadata at the same slot fail, never overwrite or allocate a new ID.
Files/records/parents are checked for symlinks and expected shapes; originals
must be regular files. The metadata read is bounded to 16 KiB. Media bounds use
the generation contract (20 MiB image, 100 MiB video) and existing pixel/duration
validators. Heap usage is bounded but not streaming: the existing generation
interface already takes byte arrays and this adapter makes defensive copies.

A JVM root mutex plus an OS file lock serialize publication/recovery between
helpers/processes. The file lock supplies file-idempotency serialization, NOT a
cross-process execution lease or DB transaction. Within a live process all
writers for one owner MUST share its fence. If multiple application processes can
own executions, the parent must additionally serialize their owner/lease changes
and publications; a callback alone cannot atomically bridge another process's DB
owner mutation. The tests below do not claim a multiprocess lease test.

The final owner validation and rename run under the same monitor as `revoke()`.
If revocation wins admission, that output is not published. If publication wins,
it stays successful even when revocation is requested immediately afterwards.
On owner replacement/deletion/cancellation the parent MUST do:

```kotlin
fence.revoke()                 // first, before mutating the execution owner
io.cancellation.cancel()       // then abort the HTTP side
```

Do not rely only on `GenerationCancellation.isCancellationRequested`, a callback
check, interrupt or a transport exception to revoke asset admission. They are not
atomic publication fences. Do not hold a DB transaction while acquiring a fence
or calling this adapter. File operations order namespace/file lock -> fence ->
validator DB reads. Final output registration releases file locks and orders
fence -> validator DB reads -> SQL transaction/commit, holding the gate throughout
SQL. The validator must not call back into the adapter. Same-fence revocation wins
before registration, or waits for an admitted registration to commit/fail; there
is no post-commit cancellation that invalidates the successful receipt.
Revocation does not recall an original descriptor already handed to the caller:
close it at parent operation end. A fresh fence after restart requires fresh owner
validation AND current cancellation/account policy, not blindly reviving an old
cancelled run. In-process recovery must never replace a revoked fence. Sharing the
same live-owner fence across leaves and revocation is a necessary parent lifecycle
integration task; this asset/output-store patch alone does not supply that wiring.

Only this invocation's *never-published* known staging files may be cleaned on a
pre-rename failure. After a rename is attempted there is NO cleanup, even if the
operation throws. Published/unknown assets are never deleted on exception,
cancellation, failed registration or unknown DB outcome. An earlier successful
sibling is unaffected by a later failed/cancelled sibling. There is no all-output
rollback, scan/age GC, delete, auto repair, or hidden retry API.

A preexisting `<slot>.pending` with no published slot fails closed with `STORAGE`;
it is not guessed to be safe to discard. Partial staging/manual recovery remains
a parent maintenance task after authoritative reconciliation. A published slot
can still be recovered/retried even if unrelated/old staging is present. Temporary
video-inspection files are private, non-ID transient files; a process crash can
leave them too. No arbitrary root scan claims message ownership or acceptance.
Atomic move must be supported; there is no copy-over fallback. Filesystem+SQLite
atomicity and power-loss directory-fsync durability are NOT claimed (files are
fsynced; process reopen recovery is provided). Test required Android filesystems
and minimum API 26 before integrating.

## REQUIRED same-application-DB reference registration

`SQLiteGenerationAttemptStore.finish` still records `Submission` (provider
sources/failures), **not receipts**. It does not observe `assets.commit`, and this
leaf deliberately does not install a new DB or mutate the existing ledger/schema.
A successful `GenerationAssetResult.Committed` is a real private file publication,
not proof of a message attachment. The existing image/video message resolvers do
not resolve this namespace; do not manufacture their UUID references or put this
ID into a domain `MessageBlock` that does not support it.

After each output success (or recovery), the parent must:

1. Obtain/verify the descriptor with `assets.pending(receipt)` outside the DB
   transaction. Retain the original known output index and failed sibling results.
2. Reacquire the same fence via `admitRegistration` and, inside that admission,
   open ONE transaction on the SAME application/account DB as the persisted
   assistant invocation and generation attempts. Hold the gate through commit.
   Revalidate actual owner/run/call,
   account, execution/registration policy and original attempt identity/fingerprint.
   Add a parent-coordinated generation asset/reference schema with a unique key
   `(accountScope, operationId, itemId, outputIndex)` (or equivalent account DB
   scope) and immutable kind, actual MIME, bytes, SHA-256, dimensions/duration,
   namespace/version and asset ID. Enforce that a repeated slot has the identical
   receipt/metadata. Never use `INSERT OR REPLACE` to overwrite a conflicting slot.
3. Atomically register the asset ownership/reference with the actual assistant
   tool/result/message relation. If the parent wants a per-output registration
   receipt in its ledger, update that in this SAME transaction. The current
   `GenerationAttemptStore` API cannot express that update, so it needs a separate
   parent-owned schema/API, not a call to `finish(Completed)` with fabricated URLs.
4. Only after transaction commit may the parent/UI claim that the asset is attached
   to chat. On failed/unknown commit, keep this pending file receipt and reconcile
   the exact unique key in that DB after restart. Neither a source URL nor filesystem
   existence is evidence of chat acceptance; never delete the file to "roll back"
   an unknown DB result. No sibling result may disappear when registering another.

The output-store leaf implements file verification + fenced side-table registration
as described in `docs/generation-output-store-integration.md`; it does not update
Tool.result/message projection atomically. Its `recoverPublished(originalAttempt,
index)` reopens only the original fixed slot and uses the same registration path.
An absent slot returns explicit `GenerationOutputRecovery.Missing` after owner and
original-attempt authorization; corruption/denial is an error, never Missing.
No scan, new identity/fingerprint/protocol, POST, download, or file deletion is a
recovery strategy. Missing is not permission to regenerate a paid output.

Existing migrations, backup/restore/export, retention and account deletion need
an explicit parent decision for the new private namespace and reference schema.
No automatic registration or global manifest changes are supplied by this leaf.

## Offline test sources and limitations (not executed here)

* `src/test/.../media/MessageGenerationAssetsTest.kt`: deterministic fake-inspector
  storage tests for incremental/reordered outputs, original video bytes, reopen,
  stable receipts, same-slot content conflict, ownership/account/item isolation,
  false metadata, owner rejection, cancellation before/after admission, independent
  helper races, original/record/size/digest/symlink tampering, unknown staging/DB
  outcomes, failed siblings and existing download authorization denial. Registration
  admission tests check scalar owner matching, sticky revocation and monitor-held
  callback ordering. Fakes are explicitly NON-media, not real codec evidence.
* `src/androidTest/.../media/MessageGenerationAssetsDeviceTest.kt`: static PNG real
  decoding/original reopen plus MIME spoofing and malformed/header-only/video-PNG
  rejection using production inspectors. No encoding or real generation/network.
  No known-good MP4/WebM fixture exists in this repository; positive device video
  decoding is **not** claimed and must be verified with a licensed fixed fixture
  in the parent's device lane. Existing `AndroidVideoInspector` is reused unchanged.

No compilation, Gradle, device execution, HTTP, paid submission or true generation
was performed for this implementation. Parent may select the two exact test
classes for its offline JVM/device checks; platform filesystem locking/atomic move,
static PNG decode, minimum-API video rejection, and genuine positive video fixture
coverage remain verification gates, not reported passes.
