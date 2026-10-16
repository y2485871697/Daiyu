# P19/P20 generation attempt ledger leaf

## Integration boundary (not wired in this change)

`SQLiteGenerationAttemptStore` implements the existing
`model.generation.GenerationAttemptStore` with the **same application SQLite DB**.
It does not create/open a second DB or own/close the injected DB. No changes to
`ConversationDatabase`'s public version, the controller, HTTP, or `GenerationAssets`
are included. The parent must:

1. Call `SQLiteGenerationAttemptStore.installSchema(db)` from its coordinated
   application DB `onCreate` / additive upgrade. This helper does not change
   `user_version`, install itself lazily, delete history, or reset attempts.
   Existing installations therefore need the parent's coordinated migration
   before this store is injected. A missing schema fails closed.
2. Inject `{ conversationDatabase.writableDatabase }` from the account's real,
   app-private DB. The parent owns its lifetime and must retain `synchronous=FULL`
   (or stronger), a durable rollback/WAL journal, and the existing private-file /
   backup policy. Memory DBs, read-only handles, OFF/MEMORY journals and weaker
   synchronous settings fail closed. No cross-DB attachment is used.
3. Persist the actual assistant tool invocation before reserving. Construct
   `GenerationAttemptBinding(conversationId, assistantMessageId, runId,
   toolCallId, toolName)` from that persisted owner. Assign each request its
   `binding.identity(stableItemId)` and inject the bound store into `GenerationIo`.
   The item ID must be stable for the logical batch item, not a retry UUID.
   No user input or provider-generated ID should become the operation ID.
4. Never call reserve/finish inside an outer transaction. `Started` is returned
   only after this store's own top-level transaction **has committed**. Any read,
   constraint, commit or ambiguous persistence failure throws; `GenerationIo`
   then sends nothing. There is intentionally no retry/reset/delete API.

Example wiring (illustrative; the parent must choose real owner/item values):

```kotlin
// In parent's coordinated migration:
SQLiteGenerationAttemptStore.installSchema(db)

// After the owner tool invocation has actually been persisted:
val binding = GenerationAttemptBinding(conversationId, assistantId, runId, callId, toolName)
val attempts = SQLiteGenerationAttemptStore({ conversationDatabase.writableDatabase }, binding)
val identity = binding.identity(stableItemId)
// Pass attempts to the existing GenerationIo and identity to GenerationRequest.
```

### Ownership and identity

The operation ID is a versioned SHA-256 of length-delimited conversation,
assistant, run and call IDs. Tool name is validated but deliberately not part of
that hash: renaming a call cannot create a second operation. Both SQLite's
primary key and a unique owner/call/item key protect it. A restart or second
helper/instance reconstructs the same operation; a mismatched fingerprint returns
`Existing` with the **original** fingerprint, never `Started`.

Every access verifies that the conversation exists, the actual message belongs
to that conversation/run, has assistant role, and contains exactly the bound tool
call/name. Nonempty `history_segments` are authoritative rather than the legacy
`blocks` projection. New reservations also require RUNNING status and no result
on the invocation. A completed owner may retrieve/finish an existing attempt,
not reserve a new item. This is identity validation, **not** a replacement for the
parent's live execution-owner/cancellation or asset-publication fence.

The current message schema has no account-owner column. This implementation's
account scope is the injected DB itself, and it does not claim multi-account
row isolation inside one shared DB. If the parent introduces that arrangement,
add and validate the real account ownership before adopting this implementation.

### Monotonic outcomes and privacy

* The first reservation is a durable tombstone with no outcome. Reopening it
  returns Existing even if no outcome ever committed. Only `outcome` is updated.
* Completed dominates every non-completed result. Completed/Completed merges by
  output index: sparse/empty late completions cannot erase prior indices, a valid
  source upgrades an earlier failed sibling, and the first known source wins if
  another result supplies a conflicting source for the same index.
* Pending survives transient Unknown/NotExecuted. REMOTE_FAILED is terminal
  relative to Pending; its known handle is retained. Conflicting task handles
  fail closed. Handles are checked against identity/fingerprint/video protocol.
* Outcomes are private binary BLOBs, not JSON/base64, debug strings, public files,
  or logs. Bounds: 32 indices (0..31), 20 MiB per inline source, 32 MiB total
  encoded outcome, 16 KiB UTF-8 per URL, 256 bytes for MIME, 512 bytes for a
  fingerprint. These match the current clients' bounded image JSON and remote
  video outputs; they are not a general arbitrary-size video-inline store.
  A rejected/oversize/corrupt result never deletes/reopens its tombstone or
  replaces a previously persisted outcome. A large union may fail atomically
  rather than discard any known output to fit.
* Large BLOBs are read through 64 KiB `substr` slices after a length check;
  message owner JSON uses the existing bounded `SQLiteText` reader. No large
  BLOB or message text enters a single CursorWindow row. In-memory decode remains
  bounded, but is not streaming media playback.
* SQL/codec failures are not logged. The parent must not log returned submissions
  or handles either (their existing contract debug strings can contain URLs).
  The store's transaction exception omits the SQLite exception cause to avoid
  leaking private values through host diagnostics.
  This is app-sandbox private persistence, not new at-rest cryptography. The
  current manifest disables platform backup; the parent must not add these
  signed URLs/media to public backups or general exports. The existing neutral
  backup tables do not include this ledger.

Tombstones intentionally do **not** cascade on message/conversation deletion;
otherwise deletion/recreation could permit a second billed POST. Subsequent
access to a deleted owner fails closed. The parent must define retention/privacy
policy for these private outcomes separately from identity tombstones (e.g. clear
outcome while retaining ownership/fingerprint). Do not indiscriminately export,
restore, purge, or clone these rows into a new operation. No maintenance API is
added by this leaf.

## Explicit contract gaps / work left to parent

`GenerationAttemptStore` exposes only `reserve` and `finish(Submission)`.
`Submission.Completed` supplies provider output indices/sources/failures, **not**
`GenerationAssetReceipt`. There is no way here to observe `GenerationAssets.commit`
or verify a receipt belongs to a real asset record. This ledger preserves output
index outcomes, but cannot honestly maintain an asset-ID/index publication receipt
or atomic asset fence with the current interface. Do not fabricate receipts or
relabel provider URLs as assets. An actual index-to-asset receipt API, same-DB asset
ownership schema, and parent-wired atomic publication boundary are prerequisites
for that extension. Existing asset idempotency remains the `GenerationAssets`
contract and is not implemented/changed here.

The leaf also cannot select the correct account DB, install the app migration,
choose/reuse stable batch item IDs, provide an execution lease, or enforce the
parent's cancellation fence. Its existence alone does not wire P19/P20 end-to-end.

## Directed tests (added, not executed in this implementation pass)

* `app/src/androidTest/.../storage/SQLiteGenerationAttemptStoreTest.kt`: real
  application schema/owner fixtures; committed visibility to another helper;
  reopen/tombstone/fingerprint conflict; two independent helper reservation race;
  top-level rejection; ignored insert/update triggers; deferred-constraint commit
  failure; weak durability; conversation/assistant/run/call/name/history binding;
  completed/deleted owner behavior; >CursorWindow inline + signed URL round-trip;
  sparse output merges and late Pending/Unknown; authenticated video source;
  bad handle, oversized URL/index and corrupt outcome fail-closed behavior.
* `app/src/test/.../storage/GenerationAttemptCodecTest.kt`: binary round trips,
  all result/source variants, output union/first-known-source semantics,
  Pending/terminal failure handle monotonicity, protocol binding, truncation,
  trailing data/version/duplicate/invalid indices, per-source and aggregate limits.

Parent-only suggested invocations (no build/network was run here):

```text
./gradlew :app:testDebugUnitTest --tests app.daiyu.android.storage.GenerationAttemptCodecTest
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.daiyu.android.storage.SQLiteGenerationAttemptStoreTest
```

The helper-race test exercises distinct SQLite helper instances in one process;
SQLite's file writer lock is the cross-process serialization primitive, but this
leaf does not claim to have executed a separate-process/instrumented crash test.
The deferred commit-failure test must be verified on the parent's target Android
SQLite runtime, along with minimum-API (26) large-row reads.
