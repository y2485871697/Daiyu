# R12 / DB15 complete backup: production closure

## Baseline and change boundary

The assigned handoff identifies `d78bbae` on `8058f20`, with the seven-file `0bc25e2` backup extension already transplanted. This follow-up consumes that implementation; it does not replace the generation/child codecs. The checked source owns SQLite **DB15** (`ConversationDatabase`, version 15), including nullable original `Message.createdAt` timestamps. No schema/version, message-time codec, refactoring inventory, download transport or paid-run authorization is changed here. Commit ancestry must be verified by the supervising agent; the implementation agent has file-only access, not Git/Shell.

Production entry points are `ChatController.exportUserBackup(..., complete = true)`, `exportConversationBackup`, `importUserBackup(..., complete = true)`, and the chat generation-assets dialog's independent imported-history display/save section. The existing `GenerationPublicNetworkHttp` transport injection remains intact.

## Scope and wire contract

`NeutralBackupService` now writes `daiyu-neutral-complete` **version 2**, and still accepts version 1. The existing text backup, original-image manifest, original-video sidecar/witness and immutable `neutral-context-blobs` formats are reused unchanged. Unknown envelope/history versions and unknown history fields fail closed. Version 2 requires both `generation_history` and `child_task_history`, even when empty; an unsupported complete export fails instead of succeeding with references only.

The two new sections each have an independently versioned, closed version-1 codec:

* `generation_history`: scalar `generation_attempts` tombstones and all registered `generation_outputs` metadata, with deduplicated original bytes in the existing blob list. The exact conversation / physical assistant message / run / tool call / operation / item / output slot, original local account scope and receipt are retained as **provenance**, not reassigned to a new owner. Present owners must contain the exact original assistant tool invocation; authoritative history segments take precedence over legacy blocks. Deleted owners may retain attempt tombstones without an output. Dangling output owners fail export/import.
* `child_task_history`: persisted sequence-ordered tasks, command fingerprints/receipts and `child_request_usage`. Parent message/run ownership, predecessor order, same-conversation source links, group slots, command successor links, expected revisions and recomputed command fingerprints are validated. Missing predecessors/command tombstones, cycles and corrupt usage fail rather than silently losing history.

The inventory adds generation output/attempt and child task/command/request counts. `attachments` includes registered generated originals; `blob_bytes` remains the deduplicated total. Existing version-1 inventory fields are checked using their original contract.

Bounds: each history array is limited to 50,000 entries and 8 MiB encoded metadata; SQL history reads check count/aggregate field bytes before loading rows. Original bytes retain the existing 64 MiB deduplicated budget and 112 MiB complete-envelope ceiling. Oversize/unsupported data is explicitly rejected, not truncated. Generation metadata is captured before opening original descriptors and compared again in the final package snapshot. Owner mappings are checked for every output, including shared-digest originals; retained bytes are deduplicated.

## Deliberately excluded authority

No `generation_attempts.outcome` BLOB is selected or serialized. No generation request body, API key, authorization header, provider endpoint, signed output URL, provider polling/content handle or download authorization is included in either new codec. The normal text backup remains user conversation content, not a mechanism for transferring execution authority. Custom non-SHA-256 attempt fingerprints or non-local account-scope identifiers are unsupported and fail explicitly rather than serializing opaque possibly private material. Existing non-secret model configuration follows the existing allowlist; it is not a generation credential export.

Restore does **not** invoke a generation capability, media publication fence, HTTP client, child supervisor, model factory, tool executor or scheduler:

* Attempts are inserted into the existing `generation_attempts` table with **NULL outcome**. The existing reservation contract returns `Existing(fingerprint, null)`, which `GenerationIo` treats as `ALREADY_ATTEMPTED`, not permission for a new POST. Tombstones for deleted owners are included in whole-library exports so recreating the same identity does not reopen billing.
* Generated outputs are **not** inserted into live `generation_outputs` or `MessageGenerationAssets`. Metadata is stored in backup-owned `neutral_backup_history` and original bytes in the existing immutable blob namespace. Final and duplicate receipts reject a restored slot that also appears as a live output. `historicalGenerationOutputs()` returns a deliberately distinct `HistoricalGenerationOutput`, not a live output reference/receipt; `historicalGenerationOriginal(history)` reads verified historical bytes only. The normal live registration/recovery API cannot find these bytes in its publication namespace.
* Child rows remain accessible to the existing historical record reader. `QUEUED` becomes terminal `NOT_EXECUTED` (`backup_history_not_dispatched`); `RUNNING` becomes terminal `UNKNOWN` (`backup_history_not_resumable`). Other terminal outcomes/text are preserved. Original parent-owner/profile/request-owner values remain historical identifiers only; no profile configuration, runtime checkpoint, live OS lease or request binding is reconstructed. Command tombstones stay in the existing command table: a repeated call returns the already terminal successor instead of enqueueing it again. A separately initiated new child request still requires the host's normal fresh consent/admission; restoring a record is not that consent.
* Original child snapshots, including their pre-backup observed status and exact owner/revision fields, remain in the backup-owned history table for honest re-export. A modified physical restored row cannot silently substitute for that snapshot. History rows have a foreign key to their physical parent message and cascade on normal message deletion, so the helper does not invent a second retention policy. Attempt tombstones deliberately do not cascade.

`neutral_backup_history` is an additive **backup-owned helper table**, installed only when restoring nonempty history inside the existing import transaction. It does not change `ConversationDatabase`, its public schema version, migrations, runtime schema or P35's v15 ownership. It is not a live execution ledger.

## Wired production construction and read seam

`ChatController.kt` has one `private val neutralBackup by lazy` construction. It retains the existing `MessageImageBackupAssets(messageImageAssets)`, `MessageVideoBackupAssets(videoAttachments.store)` and both `BackupVideoIntegration` database callbacks, and adds:

```kotlin
generationAssets = BackupGenerationAssets(::readGenerationBackup)
```

The callback and ordinary live history both use the worker-only `generationOutputReader(scope, binding)` helper. Its account/operation key indexes the **same** `generationOutputReaders` cache, and `generationOutputConversations` is installed alongside it so deletion/close also discovers readers first opened for backup. `retainGenerationResources` is still the only production `ControllerGenerationResources` constructor: its private media root, persisted-owner gates and existing public-network transport are not replaced by backup. Reader-local close is not owner-gate revocation.

1. `GenerationSettingsStore.readAccountScope()` validates only the local manifest. It never invokes either credential callback, creates defaults, mints identity, writes/repairs preferences, opens a credential vault or returns runnable profiles. Missing is null; corrupt metadata throws. A validated `saving` manifest/process save fence retains its stable observational scope, without permitting execution. This allows reading existing originals even after keys become unavailable. Production backup and live history no longer use `loadForEditing` to obtain scope.
2. `readGenerationBackup` compares the selected output's scope to that independently read manifest identity, reconstructs the exact `GenerationAttemptBinding`, and checks its operation ID. Export rows cannot establish which account is current.
3. The retained `ControllerGenerationOutputReader.readBackup` reauthorizes the real persisted attempt/tool and requires the registered output to equal **all** selected scalar fields. After that SQL read closes, it invokes the existing `MessageGenerationBackupAssets` adapter with this same reader's fenced `MessageGenerationAssets`. The adapter compares receipt, owner, operation/item/index and all pending media fields, reads exactly the bounded byte count, requires EOF and verifies SHA-256. The service verifies size/hash independently as well.
4. No `reserve`, `recoverPublished`, register, live output restoration, download approval or model/tool request is involved. Missing/mismatched identity or original explicitly fails complete export; it never silently drops the output.

**Lock ordering:** `dataTask` marks the entire worker export busy, from scalar capture through original prefetch, final snapshot and destination write. The service prefetches generation originals outside every SQL transaction, then compares the generation scalar snapshot again in its final transaction. The controller monitor is used only for short admission/seal checks; it is released before reader construction, gate validation or filesystem I/O. Live history now materializes its bounded scalar selection and closes its cursor before any gate read. Existing gate → SQL and file-lock → gate ordering is unchanged. The DB15 message-time path and image/video backup callbacks are unchanged.

## Independent imported-history display/save

Imported generation metadata remains exclusively in `neutral_backup_history`, originals in `neutral-context-blobs`, with no restored `generation_outputs` registrations. `NeutralBackupService.historicalGenerationOutputs(conversationId)` scopes the existing observational API to one conversation; `historicalGenerationOriginal` rechecks membership and exact original size/hash.

`ChatController.loadHistoricalGenerationOutputs` uses only these backup-owned APIs. It does not load generation settings/keys or construct live `Resources`/readers/gates. It holds the controller's reader admission while the worker verifies originals; image previews decode bounded display pixels and videos retain original bytes without transcoding. The dialog explicitly shows at most 32 imported history items (the complete export still includes all supported records).

`saveHistoricalGenerationOriginal` uses the existing whole-worker `dataTask` exclusion, rechecks the selected history reference/original and writes unmodified bytes to an explicitly selected destination. Main's generation-assets dialog labels this separate history section, displays verified pictures/video metadata and offers **保存历史原件** for both kinds. Request code 106 accepts only a user-selected `content` document and the original conversation; its reference is deliberately memory-only, so activity/process recreation requires a fresh selection instead of guessing a new owner. It never shares a private path/receipt or restores authorization. There is no video playback/re-encoding, live output registration, automatic retry or historical request resubmission.

## Duplicate import and corruption behavior

The import marker is not treated as evidence. Before returning `alreadyImported=true`, the service rechecks text owner rows/witnesses, every expected ordinary context/branch/archive/usage/attachment row, configuration receipts, image/video sidecars and original assets, all generation tombstones/history, terminal child rows, commands/usage and **every package blob in the target immutable namespace**, including ordinary attachments and archived original text. Reads are hash-addressed and bounded. A missing/wrong-sized/corrupt blob or changed side-table reference fails the operation. The duplicate path makes no writes and never repairs or overwrites a bad destination.

The nonduplicate path still uses the existing single SQL transaction. After the final import-marker write it rechecks ordinary and extended receipts, including a NULL-outcome attempt and absence of a live restored output slot. Ignoring/mutating triggers therefore fail and roll back visible text/tables/config/marker. Immutable files published before an SQL failure may remain unreferenced, as in the pre-existing backup protocol; existing files are never overwritten or deleted as rollback cleanup.

## Verification sources / handoff

Added `app/src/androidTest/kotlin/app/daiyu/android/backup/ExtendedNeutralBackupTest.kt` covers:

* real application SQLite plus Android decoding of a fixed static PNG, exact registered ownership and original-byte round trip;
* source read-only export, original provider signed URL/API secret exclusion, explicit failure without the source asset capability;
* generation/child/command/usage counts, independent reopen, historical-only generated-output type, no live generated outputs/runtime checkpoints;
* a real imported attempt reused through `GenerationIo` with an injected HTTP/download counter remaining zero;
* original child source/owner/command receipt preservation and terminalization, re-export without a generation capability;
* deleted-owner tombstones followed by recreation of the same old text owner without reopening a reservation;
* unknown fields, owner mismatch, non-neutral fingerprints, broken command lineage, missing originals;
* corrupt/missing generated blobs, corrupt ordinary attachment/archive blobs, changed ordinary/command references on duplicate with no repair;
* final-marker trigger mutation rolling back the complete visible import after reopen;
* legacy version-1 import/duplicate and unknown future-version rejection.

Follow-up source tests in `app/src/androidTest/kotlin/app/daiyu/android/ControllerGenerationIntegrationTest.kt` add:

* the actual Controller complete-export constructor path, static PNG plus a tiny offline device-encoded AVC/MP4 through real production inspectors and original storage;
* source read-only export, full/individual-conversation paths, manifest scope versus forged/missing scope, exact registered mapping mismatch, shared retained resources and both reader-cache indexes;
* a guarded synthetic fixture that forbids generation credential-vault preference access during actual export/live-history reads (not a fake exporter); independent target history display/save with no generation settings or live reader construction;
* malformed original rejection, final-receipt-trigger transaction rollback of messages/tombstones/history/child rows/commands/usage/marker, successful append and duplicate receipt, corrupt destination-blob duplicate rejection without repair;
* byte-identical image/video historical saves, DB15 message creation times, command receipts/source ownership and request-usage preservation, re-exported extended sections, zero model opens and no restored live outputs/runtime checkpoints;
* a real export worker blocked on the retained owner gate while the Controller monitor remains available and `dataBusy` rejects deletion admission.

`OfflineBackupVideoFixture.kt` encodes only local synthetic pixels, using the existing device-codec technique from `VideoChatHostAndroidTest`; AVC encoder/decoder availability is a test prerequisite. `GenerationSettingsStoreTest.manifestOnlyScopeNeverReadsKeysOrWritesAndSurvivesUnavailableCredentialsAndSavingFence` supplies throwing credential callbacks and covers missing/ready/saving/process-fenced/corrupt manifest identities without credential reads or observational writes.

### Remaining verification / delivery boundary

The implementation agent has no Shell/build/Git capability and did **not** run compilation, unit tests, instrumentation tests, full suites, APK builds or networking. Source checks are not a claim of runtime success. The supervising agent should review/compile and perform the final **unified offline** verification, including the tests above, the existing extended codec suite, message-time tests, retained-resource deletion/close races, and Main's history-save document selection/cancellation/recreation behavior. No APK build, paid network or real credential access is authorized by this document. The requested nonempty commit and main/detached/no-push constraints are a supervising-agent Git handoff; a file-only implementation agent cannot create or attest that commit. No `ConversationDatabase` schema/version, existing download transport, paid-run controls, CI, refactoring inventory or legacy Eta files were changed.
