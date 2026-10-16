# Generation v12 migration instrumentation coverage

## Scope

This change addresses the two v12 integration blockers from static review `ef08a06e`, against the supplied `a342b9e` baseline. Only `GenerationSchemaMigrationTest.kt` and this document are changed; production code, other tests, and refactoring inventories are outside scope.

The four assertions that describe the **destination** schema now expect version **12** (fresh creation, the v1–10 upgrade loop, the valid v10 copy, and the repaired v9 retry). Historical source versions 9 and 10 remain unchanged. Version 11 is tested separately, not by extending the legacy loop.

## Fixture fidelity and assertions

- `seedCore(10)` still derives the unchanged core tables from the current helper, but drops `generation_outputs`, `generation_attempts`, **and `message_video_inputs`** before setting the historical version. It asserts that the sidecar is absent. The v1–10 loop independently checks each source version and the absence of the sidecar before opening the real helper.
- Fresh creation and every v1–10 upgrade check the sidecar immediately and after closing/reopening the helper. The DDL assertion compares normalized `sqlite_master.sql` against an independent expected definition, including the primary key, `NOT NULL`, TEXT storage-class check, and UTF-8 byte-length bounds (1–1048576). It also checks the column list and the `message_id -> messages(id) ON DELETE CASCADE` foreign-key declaration via `PRAGMA foreign_key_list`.
- The separate `testStrictV11AddsOnlyVideoSidecarAndPreservesOutputsTombstoneAndBlobAcrossReopen` starts with the unchanged v10 core and explicitly installs the production **strict v11** generation schemas, then sets `user_version=11`. It never uses the weak legacy-output fixture or `seedCore(11)`. The sidecar must still be absent before migration.
- That v11 fixture contains one live output metadata row, a completed attempt, and a second completed attempt whose owner rows have been deleted, leaving a real tombstone. Attempts and outcome BLOBs are written with `reserve`/`finish` and the production codec. The output metadata uses the existing valid `ContentValues` fixture; no asset registration or actual generated file is required for a metadata-only `find` read.
- Before upgrade, invalid output width and TEXT outcome updates must raise `SQLiteConstraintException` without changing the snapshot, proving the v11 fixture has strict generation constraints. After the real helper upgrade, the entire pre-existing schema (including indexes), all rows/storage classes/BLOBs, and sequences must be unchanged; only version 12 and the empty sidecar/table-owned index may be added. Reopening must preserve the complete post-upgrade snapshot. Store `find` and `reserve` reads must work, both outcome BLOBs must be byte-identical, and recreating the deleted owner must return `Existing`, not a new reservation.
- The valid v10-copy path additionally checks the sidecar before and after reopening; the repaired v9 retry checks it after upgrade. Existing corruption, rollback, and deletion coverage is retained.

## Production contracts inspected

- `app/src/main/kotlin/app/daiyu/android/storage/ConversationDatabase.kt`: version 12; fresh installs include the sidecar; upgrades run generation migration only for source versions below 11 and install the sidecar afterward.
- `app/src/main/kotlin/app/daiyu/android/storage/SQLiteMessageVideoStore.kt`: authoritative sidecar DDL, payload byte bound, and message foreign key.
- `app/src/main/kotlin/app/daiyu/android/storage/SQLiteGenerationAttemptStore.kt`: strict attempt schema, real owner/tool-call validation, durable `reserve`/`finish`, and non-cascading tombstone semantics.
- `app/src/main/kotlin/app/daiyu/android/storage/SQLiteGenerationOutputStore.kt`: strict output schema and metadata-only `find` authorization/read contract.

## Failure handling and verification status

No new catch-and-ignore path is introduced. Expected constraint failures use the existing `rejected` helper, which catches `Exception` only and fails if the operation succeeds; it does not catch `AssertionError`. Unexpected setup, migration, store-read, and reopen failures propagate to the test runner.

Source inspection and a scoped diff review were performed. **No build, compilation, emulator, or instrumentation test was run by this implementation agent.** The main agent must run the Android instrumentation class in its supported environment; these are coverage assertions, not a claim of a passing runtime result. The test uses isolated on-disk test databases and no network, paid generation, UI, or private-asset GC.
