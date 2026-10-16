# Controller video review blockers: integration contract

This artifact implements the helper fixes but does **not** modify `ChatController`, `Main`,
`ConversationDatabase`, `SQLiteMessageVideoStore`, or the backup implementation. The parent must
integrate the hooks below before release. No network, build, or tests were run by this agent.
The code uses existing Android SQLite/SharedPreferences and SHA-256 APIs available at minSdk 26.

## 1. Recover a fenced host draft without text or a replacement identity

Call on the existing serialized worker, not on Main:

```kotlin
val receipt = ControllerVideoJournal(receiptPreferences).recover(restoredHostDraft, database)
// restoredHostDraft: VideoHostDraftStore.Draft, as read from the durable host store.
// Return the receipt through the host's existing owner/revision reconciliation API.
// UNKNOWN stays fenced. Never prepare(), reserve(), send(), or construct a new request here.
```

The overload retaining `VideoDraftRequest` remains compatible and additionally checks that
request's text. The new overload has **no text argument**. It validates slot, owner, revision,
ordered video metadata, original journal user/assistant/run IDs, the authoritative DB original
user shape/body, its old v1 fingerprint, and the sidecar/witness. It accepts an assistant that has
since advanced/completed/interrupted but still belongs to that exact run. It neither reads the
composer nor rewrites a journal nor creates random recovery IDs. An ordinary new attempt only
allocates UUIDs lazily when its fresh reservation is recorded. Missing/corrupt journal, missing
DB rows/sidecar/witness, changed body/identity/metadata all return UNKNOWN, never guessed REJECTED.
Journal tombstone phase alone is not acceptance evidence. Original draft files are irrelevant.

The original journal format and prefix remain unchanged. No second receipt ledger is introduced.
New process tests drop *all* request/attempt/prepared/model objects, reopen SQLite and the durable
host journal, edit the now-cleared composer, remove draft originals, and recover twice from Draft.
They also simulate a crash after DB commit but before the ACCEPTED advisory journal write.

## 2. Persistent manifest expectation (not an asset index or receipt ledger)

`ControllerVideoManifestWitness` owns one small independent row per **physical message**:

```sql
CREATE TABLE IF NOT EXISTS message_video_witnesses(
    message_id TEXT PRIMARY KEY NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
    reference_count INTEGER NOT NULL
        CHECK(typeof(reference_count)='integer' AND reference_count BETWEEN -1 AND 1024),
    manifest_sha256 TEXT,
    CHECK((reference_count=-1 AND manifest_sha256 IS NULL) OR
      (reference_count>=0 AND typeof(manifest_sha256)='text' AND length(manifest_sha256)=64
       AND manifest_sha256 NOT GLOB '*[^0-9a-f]*'))
);
```

- Hash = lowercase SHA-256 of UTF-8 `MessageVideoInputCodec.encode(physicalRow, inputs)`.
  Count = sum of reference counts, not owner count. Physical/expanded owners, run IDs, ordered
  metadata are bound by the canonical codec. JSON whitespace is deliberately irrelevant.
- Zero + its canonical hash is **positive evidence** of a known text/image-only physical row.
  It is not inferred by a runtime reader from absence of sidecar/files/journal.
- `-1/NULL` means legacy uncertainty, never an empty manifest. Reads fail closed.
- Missing table/row, unknown/corrupt witness, or a sidecar/hash/count mismatch fail closed, including
  before the first run capture. There is no read repair, fallback capability, or text downgrade.
- `insert` is insert-or-verify (not replace/upsert). Existing conflicting/unknown evidence cannot
  be overwritten by passing empty inputs. Only an explicit versioned migration establishes legacy
  evidence; never call migration from `capture`, `read`, `onOpen`, or normal send.
- This protects independent row/sidecar loss, not an attacker capable of consistently rewriting
  both authoritative manifests and witnesses. Deleting both for a retained physical row is detected.
  Deleting the whole physical history row cannot be diagnosed from this per-row certificate alone.

### Exact schema integration: one parent-owned version bump

Current worktree uses database version 12. Fold **all threads' additions into one parent migration**;
example target is 13 (if parent chooses a later shared target, adjust both constants together).
Do not leave schema at 12: installed v12 databases otherwise never execute `onUpgrade`.

1. Change helper constructor `..., null, 12` to `..., null, 13`.
2. Change upgrade guard to `check(oldVersion in 1..12 && newVersion == 13)`.
3. Preserve existing `<10`, `<11` migrations. Install sidecar schema before the witness migration.
4. Add `ControllerVideoManifestWitness.installSchema(db)` to `onCreate` after sidecar schema.
5. At the end of `onUpgrade`, inside SQLiteOpenHelper's existing transaction, execute:

```kotlin
// import app.daiyu.android.ControllerVideoManifestWitness
SQLiteMessageVideoStore.installSchema(db)
ControllerVideoManifestWitness.installSchema(db)
val keys = db.rawQuery("SELECT conversation_id,id FROM messages ORDER BY seq", null).use { c ->
    buildList { while (c.moveToNext()) add(c.getString(0) to c.getString(1)) }
}
keys.forEach { (conversation, message) ->
    val physical = storedMessage(db, ConversationId(conversation), message, verifyVideoWitness = false)
    ControllerVideoManifestWitness.migrateLegacy(db, listOf(physical), emptyIsProven = false)
}
```

The `verifyVideoWitness=false` parameter below is **migration-only**. Row decoding, sidecar decoding,
message/history/image validation still happen and any corruption aborts the *whole* upgrade. Never
use `INSERT OR IGNORE`/catch-and-continue on migration errors. Optionally assert no orphan sidecars
before migration with this exact diagnostic query (any row must abort, not be deleted):

```sql
SELECT 1 FROM message_video_inputs v
LEFT JOIN messages m ON m.id=v.message_id WHERE m.id IS NULL LIMIT 1;
```

### Existing v12 compatibility is necessarily conservative

v12 has no independent expectation for a missing sidecar. A legitimate old text user and a video
user whose sidecar was already lost are byte-for-byte indistinguishable here. Do **not** call all
absent-sidecar rows text merely for backward compatibility.

The supplied migration preserves all rows/files and:

* Canonicalizes each existing, valid sidecar into a hash/count witness.
* Marks an absent-sidecar physical user (or assistant containing expanded users) `-1/NULL`.
* Proves zero for an absent-sidecar physical row having **no expanded USER**: under the codec
  ownership contract it cannot own original video at all (ordinary assistant rows, for example).

Thus existing v12 video runs with intact sidecars + ordinary assistants can resume. Ambiguous
v12 text users remain retained, but model sending on those histories is blocked with a
data-repair/legacy-uncertainty error; a fresh known-text conversation still sends. A read-only
recovery UI may use an explicitly unchecked reader for retained data, never for model input or
a text-only export. Lossless export must preserve uncertainty as explained below.
UI must expose this honestly, not loop retries or silently discard ambiguous history. If the parent
has *independent proven deployment/schema evidence* that a particular pre-video DB could never
contain original video, only that migration may use `emptyIsProven=true`. Version 12 by itself is
**not** such evidence. Already-lost v12 video metadata cannot be reconstructed or certified by this
patch. User confirmation of a text label, file absence, current composer, or empty journal is not
sufficient evidence. Any less conservative compatibility policy requires an explicit product/data
migration decision, not an automatic helper fallback.

## 3. Parent DB and sidecar hooks (must be atomic for ALL entry points)

Keep the existing raw `SQLiteMessageVideoStore.read` for migration/witness internals. Add a checked
runtime accessor in that object:

```kotlin
fun readVerified(db: SQLiteDatabase, message: Message): List<MessageVideoInput> =
    read(db, message).also { ControllerVideoManifestWitness.verify(db, message, it) }
```

In `ConversationDatabase.storedMessage`, add the optional migration flag to the existing signature:

```kotlin
internal fun storedMessage(db: SQLiteDatabase, conversation: ConversationId, messageId: String,
    verifyVideoWitness: Boolean = true): Message {
    val metadata = db.rawQuery("SELECT run_id,role,status FROM messages WHERE id=? AND conversation_id=?",
        arrayOf(messageId, conversation.value)).use { c ->
        check(c.moveToFirst()) { "消息不存在" }
        Triple(c.getString(0), c.getString(1), c.getString(2))
    }
    fun text(column: String) = SQLiteText.read(db, "messages", column, "id", messageId)
    return Message(MessageId(messageId), conversation, RunId(metadata.first), metadata.second,
        decode(JSONArray(requireNotNull(text("blocks")))), RunStatus.valueOf(metadata.third), text("error"),
        jsonStrings(JSONArray(requireNotNull(text("protocol")))),
        MessageHistoryCodec.decode(JSONArray(requireNotNull(text(historyColumn))))).also {
            MessageHistoryCodec.validate(it)
            MessageImages.validate(it)
            if (verifyVideoWitness) SQLiteMessageVideoStore.readVerified(db, it)
            else SQLiteMessageVideoStore.read(db, it)
        }
}
```

This retains the existing chunked reader and only adds the conditional verification. Other
runtime readers (including backup capture and `videoInputs`) must use `readVerified` in
their existing read transaction. Never catch a witness exception and return empty inputs.
`ControllerVideoHistory.capture` and journal recovery already explicitly verify each read.
All `readVerified` calls require a DB transaction. In particular the existing
`ConversationDatabase.conversations(query=...)` search loop currently invokes `storedMessage`
outside a transaction: the parent must wrap that search/read in its existing transaction utility
(or use a strictly read-only diagnostic reader that cannot feed model/export). Do not globally
turn checked reads into unchecked reads merely to avoid this audit.

Replace `SQLiteMessageVideoStore.insert` body with this exact sequence (existing imports plus
`app.daiyu.android.ControllerVideoManifestWitness`):

```kotlin
fun insert(db: SQLiteDatabase, message: Message, inputs: List<MessageVideoInput>) {
    check(db.inTransaction())
    val encoded = MessageVideoInputCodec.encode(message, inputs)
    if (inputs.isNotEmpty()) {
        check(db.insertOrThrow(TABLE, null, ContentValues().apply {
            put("message_id", message.id.value)
            put("payload", encoded)
        }) != -1L) { "video_sidecar_not_inserted" }
    }
    // Even for zero videos: authoritative passed inputs, NOT inferred missing metadata.
    ControllerVideoManifestWitness.insert(db, message, inputs)
    check(MessageVideoInputCodec.encode(message, readVerified(db, message)) == encoded) {
        "video_sidecar_receipt_mismatch"
    }
}
```

In `ConversationDatabase.send`, immediately after inserting both physical message rows, before
`storedMessage` receipt checks / draft clearing / transaction success:

```kotlin
SQLiteMessageVideoStore.insert(db, user, videoInputs)
SQLiteMessageVideoStore.insert(db, assistant, emptyList())
```

This covers direct DB sends and **ordinary text sends** as well as videos. Helper `commit` also
inserts/verifies both witnesses while still in the same OUTERMOST transaction, so parent wiring
and helper wiring agree. Never commit message/sidecar first then insert witness in another worker
or transaction. A witness failure rolls back the rows, sidecars, draft clearing and acceptance.

`ConversationDatabase.save`/checkpoint must verify retained sidecar/witness after physical updates
(in the same transaction). Pure assistant content/status changes do not change the witness hash.
If a history transformation relocates expanded video owners to another physical row, it must:
validate old manifests/witnesses first, derive and validate new complete manifests, update the
physical rows + sidecars + replacement witnesses atomically, then verify all results. Do not simply
recompute evidence from a missing sidecar, and do not permit ordinary insert-or-verify to overwrite
conflicts. Until such a transformation is explicitly integrated, fail closed instead of moving
video ownership silently.

### Backup/restore (coordinate with the backup thread)

Runtime backup capture must validate witnesses for every physical row before deciding a backup
is text-only; checking `message_video_inputs` presence alone reintroduces this exact bug. Restoring
an authoritative validated archive must insert **each physical row's** complete manifest (including
known zero-video rows) and its witness in the SAME restore transaction, before receipt checks and
commit:

```kotlin
// Existing restore transaction, after row insertion; archive input already validated losslessly:
SQLiteMessageVideoStore.insert(db, restoredPhysicalRow, restoredVideoInputsForThatPhysicalRow)
// For text-only archive with authoritative format semantics, pass emptyList().
// On conflict use the existing restore collision policy; do not overwrite witness evidence.
```

Never manufacture an empty manifest because an archive field is missing, its version is unknown,
or sidecar verification failed. Lossless exports of legacy unknown rows must preserve their
uncertainty; they cannot be imported as newly certified text without independent evidence.
Witnesses can be reconstructed from a complete validated archive manifest; they need not become
a separate portable receipt ledger. Parent must decide how its archive represents legacy unknown
rows. Until supported, reject that export/restore, preserving the original DB.

For legacy text-export gating, replace the old sidecar-only `hasVideos` semantics with conservative
“has video OR unknown video provenance”. This query finds positives, absent/unknown witnesses, and
unexpected sidecars (append `AND m.conversation_id=?` for scoped use, outside the parentheses):

```sql
SELECT 1 FROM messages m LEFT JOIN message_video_witnesses w ON w.message_id=m.id
WHERE (w.message_id IS NULL OR w.reference_count<>0
       OR EXISTS(SELECT 1 FROM message_video_inputs v WHERE v.message_id=m.id))
LIMIT 1;
```

Also use checked per-row decoding for malformed non-null witness detection; the query is a gate,
not a substitute for validation. A `hasVideos=false` result must not bypass capture verification.

### Deletion lifecycle

Witnesses live as long as their physical rows; they are **not** deleted with draft originals,
preview cleanup, journal tombstones, or sidecar-only deletion. Do not install a sidecar-delete
trigger that removes witnesses. `ON DELETE CASCADE` handles intentional single-message deletion
with foreign keys enabled. For conversation deletion, replace the sidecar helper's lifecycle with:

```kotlin
fun deleteConversation(db: SQLiteDatabase, conversationId: String) {
    check(db.inTransaction())
    db.execSQL("DELETE FROM message_video_inputs WHERE message_id IN " +
        "(SELECT id FROM messages WHERE conversation_id=?)", arrayOf(conversationId))
    ControllerVideoManifestWitness.deleteConversation(db, conversationId)
    // Confirm deletions directly, NOT with the conservative runtime hasVideos predicate:
    for (table in listOf("message_video_inputs", ControllerVideoManifestWitness.TABLE)) {
        db.rawQuery("SELECT 1 FROM $table WHERE message_id IN " +
            "(SELECT id FROM messages WHERE conversation_id=?) LIMIT 1", arrayOf(conversationId)).use {
            check(!it.moveToFirst()) { "video_metadata_delete_unconfirmed" }
        }
    }
}
```

Call this from the existing conversation-delete transaction **before** deleting messages. Both
metadata sets and physical rows then commit/delete together. Replacement restore/delete-all paths
must follow the same lifecycle or rely on enabled message FK cascades in their own transaction.

## 4. Verification handoff / release gates

Implemented tests:

- `ControllerVideoSendingTest`: true request-free restart (null and existing slot), stale journal
  phase, edited/cleared composer, missing originals, original body/run/owner/revision/order mismatch,
  repeated read-only recovery, sidecar loss before first capture, missing witness with sidecar,
  text-only no-capability preflight, and retained earlier controller helper coverage.
- `ControllerVideoManifestWitnessTest`: minSdk-26 SQLite schema/constraints, canonical hash checks,
  positive-zero evidence, immutable conflicts, atomic rollback, deletion cascade, conservative v12
  migration and explicitly proven pre-video text migration.

The helper test harness installs schema and explicitly emulates parent text-send witness hooks;
it is **not proof that production DB/backup paths are wired**. Parent must run these tests after
integration, add v12 SQLiteOpenHelper open/upgrade/reopen tests (including failed/corrupt migration
rollback), direct DB text/video send + backup-restore atomicity tests, and a host/controller restart
integration test on the actual worker. No build/test execution is claimed in this artifact.

## Parent composition update

The v13 parent composition writes a zero-video witness for `insertRuntimeSupplement` in the same
runtime checkpoint transaction, before `writeRuntimeMessage` validates that run. This supplements
the original list of all physical-row insert paths. UI pagination/search use explicitly unchecked
read-only projections so quarantined legacy text remains visible; model history and export paths
require verified witnesses. Pre-video schema versions prove an empty manifest; v12 users without
sidecars remain uncertain. The normal send gate now captures full physical history before any
context/model preparation and performs a final in-transaction CAS. Until a matching original-video
upload capability is wired, it rejects video histories rather than silently sending them as text.
Full video send/host UI wiring remains pending; no runtime tests were executed for this composition.
