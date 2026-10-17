# R12 — independent Eta read-only compatibility import

## Scope and evidence

This is **implemented code**, not a plan or a native-backup schema fork. It does not call NeutralBackupService, consume its private v4/v5 schema, or modify Controller/Main/context/action/runtime/database-helper code. All newly imported records are an explicitly separate **read-only compatibility library**. There is no claim that UI transcripts are lossless native assistant/model context.

Format contracts were read from the frozen `y2485871697/Eta` commit `f001fb82eb9941e5673970c656f5c4d5370fbf7f`, supplied read-only under `docs/reference-legacy-contracts/` (excluded reference evidence; **not deliverable source**). Implementation was independently written; no old implementation was copied. Relevant evidence locations:

* `EtaBackupRepository.kt:64–127`: full `eta-backup` schema 1–4, manifest `eta-backup.json`; single `eta-conversation` schema 1–3, manifest `eta-conversation.json`; camelCase serialized fields.
* `ConversationEntities.kt:13–34,73–81,110–130`: conversation, checkpoint, UI message entities and nullable `generatedAtMillis`.
* `EtaBackupRepository.kt:503–530,542–566,675–751`: external conversation payloads, internal-ID set/count checks, ownership and root fields. Payload filenames are not identity.
* `ConversationArchiveMedia.kt:16–21,74–145` and `BackupAttachmentPaths.kt:39–66`: v2/3 attachment declarations, structured history/contentJson and imagesJson media fields, user/assistant prose only. `contentJson` and history are encoded-array strings, not native request DTOs.
* `ConversationArchiveImport.kt:17–58,75–88`: v2+ reference/entry/size/hash pairing and v1 Android archive-locator prefixes. Old filesystem import logic was **not** reused.
* `BackupSubAgentConfig.kt:117–151`: missing generation must not decode obsolete config; known generation `"1"`, archive version 1 empty disabled shape. The nonempty profile schema is delegated to a repository absent from the supplied evidence.
* `AgentConversationCodec.kt:88–124,176–201`: source model history is different from UI messages; persisted `image_file`/`video_file` differ from provider URL media.
* `BackupArchiveSafety.kt:14–18` has much larger old limits. **Those limits are not inherited.**

Reference `provenance.json` identifies the commit; the supplied file itself does not contain per-file hashes, so this implementation does not claim to have independently verified reference SHA values.

## Exact Controller-facing hooks

```kotlin
val legacy = LegacyImportService(existingConversationDatabase) // the SAME helper, DB20 today
fun preview(input: InputStream): LegacyPreview
fun commit(input: InputStream, expectedSha256: String): LegacyImportResult
fun histories(): List<LegacyHistorySummary>
fun reopen(conversationId: String): LegacyReadOnlyHistory
fun originals(importId: String): List<LegacyMedia>
fun original(importId: String, entry: String): ByteArray
```

1. Run synchronous bounded methods on the host's IO executor, not the UI thread. The caller owns/closes streams. `preview` performs all ZIP/JSON/owner/hash/limit checks and allocates **no database tables or rows**.
2. Show `LegacyPreview.report` and inventory. Require explicit user confirmation; call `commit` with a fresh stream and **exactly** `preview.sha256`. Commit repeats all checks, including the full compressed-input SHA; changed input fails before opening a transaction. This is copy, never destructive full-database restore or deduplication.
3. Merge `histories()` into a **separate compatibility catalogue**. Its IDs are not IDs in `conversations`, and `reopen` is not `ConversationStore.messages()`. Render typed UI records and the separate model-history/checkpoint/source JSON panels as read-only. Show the report on reopening, not just at first import.
4. Never pass this DTO/raw JSON into model context, accepted-history, message action, retry/resume, tool execution, child-agent admission, payment, authorization, or runtime recovery APIs. Do not offer such actions in the compatibility viewer. To start a new native conversation, require a separate explicit user action and user-authored text; there is no automatic conversion API here.
5. For original media use the inventory then `original(importId, entry)` only. Do **not** resolve raw `/data/...`, `file://`, `content:`, or network references. Do not wire generic network-loading image/video widgets to raw compatibility fields. `purpose="preview"` is not a video original. `available=false` must stay visibly unavailable.
6. The parent should use its ordinary UI/data lifetime lock when listing/committing/deleting its database. Commit must be called outside any caller-owned SQL transaction; it owns exactly one transaction on the existing helper. No preferences or second helper participate.

### Schema installation / DB20 / P37 merge

`LegacyCompatibilitySchema.install(db: SQLiteDatabase): Unit` is the **only** additive schema hook. It requires `db.inTransaction()`. Commit calls it lazily within the same helper transaction as inserts, so **no DB version bump and no onUpgrade modification is required** for this implementation. If the parent chooses eager installation, call this exact hook from the existing helper's `onCreate` and applicable `onUpgrade` transaction. Do not create another SQLiteOpenHelper, manually bump DB20 in this work, or replace P37's helper/version migration.

Tables (all additive):

* `legacy_r12_imports(id, source_hash, format, version)`
* `legacy_r12_histories(id, import_id, source_id, title, source_created, source_updated)`
* `legacy_r12_assets(import_id, entry, sha256, byte_count)`
* `legacy_r12_chunks(owner, name, part, data)` — 64 KiB SQL blobs to avoid CursorWindow-size failures; raw text and originals share the same atomic SQL commit.

There are **no native conversation/message rows**, live context checkpoints, runtime requests, tools, attempts, usage charges, child configs, credentials, preference edits or external files. A first-import failure rolls back even the lazy DDL. A later failure leaves earlier imports and all original database tables unchanged. SQLite owns crash atomicity; there is no filesystem staging journal or orphan-blob recovery to install. The service is append-only, so history reopening reads immutable imported chunks; originals are SHA/size rechecked every time they are read.

## Supported behavior and explicit limitations

* Strict JSON and ZIP recognition for single v1–3/full v1–4, strict UTF-8/duplicate JSON keys/depth; no lenient JS syntax. Schema/identity/owner/message-order duplicates fail closed. Unknown top-level or entity fields outside frozen field lists reject rather than silently discard or retain possibly secret configuration.
* ZIP: bounded 4096 central entries / 1 MiB directory, compressed input <=112 MiB; exact local/central names, flags, methods, size/CRC/offset/data-descriptor checks; no ZIP64, encryption, multipart, symlinks, overlapping/unindexed data, prepended/trailing data. Empty safe directory entries are verified and ignored. Normalized duplicate files/directories and traversal/backslash/drive/control/percent paths are rejected. Only STORED/DEFLATED entries are accepted.
* New-library budgets: total source text <=16 MiB, total ZIP-original bytes <=64 MiB, complete input <=112 MiB. Persisted compatibility text (raw projection, ID/media view and report) is also preflighted within 16 MiB, and complete persisted text+blobs within112 MiB. Metadata overhead may therefore cause an input close to a boundary to reject. Catalogue titles are additionally bounded to 64 KiB UTF-8 so reopening never depends on an oversized SQL CursorWindow row; longer titles reject rather than truncate. Nothing is truncated and no limit is enlarged.
* v2/3: `/eta-attachments/<relative>` must pair exactly with `attachments/imports/<relative>`, nonnegative size and lowercase 64-hex SHA-256. ZIP original bytes/size/hash and ZIP/declaration exact entry set must match. Declared originals with opaque textual references are still retained/discoverable, not dropped. JSON-only declarations remain read-only/unavailable, with source declaration metadata preserved; no device file/network lookup occurs.
* v1: `/data/user/<number>/<package>/cache/eta-chat-images/<sourceConv>/...` and `/data/data/<package>/...`, plus `files/terminal[-user]/workspace/imports/...`, are **archive lookup keys only**. Structured references require an actual scoped ZIP original when importing ZIP; wrong owner, missing original or unknown local root rejects. V1 did not have the v2 attachment-manifest relationship; `attachmentCount` mismatches are explicitly reported as advisory, with every safe scoped original retained. Unreferenced originals remain import-wide inventory records. Absolute/file URI aliases resolve to the same archive entry, never a current absolute path.
* Full ZIP external `conversations/*.json` payloads are matched by internal conversation ID, not filename. Exact owner set, per-message/checkpoint owner, global message identity, declared count and manifest/payload metadata agreement are checked. Inline/external ambiguity rejects. Full image/import entry counts are verified. Full JSON with absent originals reports that absence and never opens local paths.
* Full global configuration is deliberately not retained: providers/settings/MCP tokens/preferences/assistant/skill/global memory/root-selected-state fields can contain credentials, permissions or executable configuration. An explicit conversion report identifies these exclusions. Conversation/message/checkpoint known record fields, UI type-specific details, historical usage, applied-run identities and model-history strings remain scoped inert source evidence; they are never installed into live tables. A user's authored prose/attachments are not scanned or claimed to be secret-free; the adapter excludes **configuration/credential fields**, not user-authored content.
* Non-dialogue UI types (`thinking`, `tool`, `tool_summary`, `system_notice`, `error_reconnect`, `context_compacted`, `question`, unknown types) remain their original types and raw values. They are not assistant messages or callable tool results. UI and model history are separate; checkpoint is a third read-only history string. Existing execution evidence is preserved, but **no executed tool can revive** because no live runtime/context/action API is used.
* `generatedAtMillis` is generation metadata only; `LegacyUiMessage.createdAt` remains null. `sourceCreatedAt` and `sourceUpdatedAt` mean only the old conversation's explicitly supplied timestamps. Export/import time never invents a message creation time.
* Structured `image_file`/`video_file.path`, `image_url`/`video_url.url`, and nested `imagesJson` arrays/`preview`/`source`/`dataUrl` are recognized. History tool-role media are recognized without turning tools executable. URL/data-string evidence remains raw; no network fetch or media decode. Source/preview remain distinct records.
* Text is never rewritten, so code fences/spans, prose and raw file-reference blocks are preserved literally. Conservative Markdown destination recognition enriches the inventory; unknown Markdown/file-reference grammars remain raw with the original inventory and an explicit report. No claim is made to fully decode the unavailable Eta file-reference prompt codec. This is intentional read-only compatibility, not filesystem-path relocation.
* Single sub-agent gate: missing generation produces disabled empty config without decoding/retaining the obsolete payload; unknown generation rejects. Generation `"1"` must satisfy the v3/archive-version gate; known empty arrays are validated and kept read-only, never activated. **Nonempty profiles/parallel-limits reject**: nested schema is not present in the frozen inputs, and guessing could grant privileges or retain secrets. Full agent preferences are excluded entirely and cannot activate any agent.
* Linux/workspace archive entries **reject the whole import**, before SQL, because their executable/config/secret semantics are outside this scoped compatibility contract. The caller should request a conversation-only archive. There is no tar extraction, script/skill execution, environment import or silent omission of these entries.

## Synthetic tests and validation status

New tests under `app/src/test/kotlin/app/daiyu/android/backup/legacy/`:

* `LegacyArchiveTest`: strict JSON, all container basics, malicious paths, normalized duplicates, same-length stored CRC tamper, trailing bytes, declared text/blob/directory budgets, understated inflation and text over-limit rejection.
* `LegacyParserTest`: JSON+ZIP versions single1–3/full1–4; v2/3 SHA (valid ZIP CRC but same-length tamper), missing/extra/duplicate/path/size declarations; owner/order/checkpoint; external internal IDs despite swapped filenames, exact set/count; v1 archived Android paths/aliases and wrong owner; UI/model/checkpoint separation; literal code/prose; media URLs/tool roles and preview/original distinction; config generation/schema; synthetic credential-field exclusion.
* `LegacyImportServiceTest` (Robolectric, SDK36): actual existing `ConversationDatabase` helper, preview SQL no-op, repeated fresh copies and cross-service reopening, failure injection after all rows/original chunks inserted (first DDL and later copy rollback), changed input/corrupt ZIP/owner rejection, all original runtime/context/action/usage/native tables snapshot-identical, strict network thread policy, chunked originals and read-time hash tamper, bounded input failure.

The service has no model/tool/network ports or clients to invoke. The no-resurrection test verifies **all existing SQL tables** unchanged (not just message counts) and installs a no-network thread policy while importing URL-bearing input. It does not claim a global instrumentation hook over every process/thread; source contains no spawned threads or network API.

**Not run in this subtask:** Kotlin compilation, Gradle/JVM tests, Robolectric, emulator/device tests. This agent has only `workspace_file` access and no shell/build/test tool. Parent must run compilation and the above actual test sources before claiming pass. Suggested parent selection: `:app:testDebugUnitTest --tests 'app.daiyu.android.backup.legacy.*'` (subject to existing repository Gradle/native prerequisites). No actual user data, credentials, paid service, model, tool or network execution was used.
