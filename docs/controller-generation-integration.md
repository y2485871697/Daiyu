# Controller generation production wiring

## Delivery boundary / validation status

Implementation changes are limited to `ChatController.kt`, `MainActivity.kt` and the dedicated
`ControllerGenerationIntegrationTest.kt`. No DB schema, leaf, tools, global manifest or existing
video-input parent implementation was changed.

**Not built or executed here:** this implementation agent has file tools only (no shell, Gradle,
Android device or browser). This tree's original Session API is intentionally not overwritten.
Integration requires the supervising agent to apply the reviewed **0909be0 / 6db5e95** lifetime
candidate first. The supplied files under `build/agent-input/generation-lifetime/` were read to
verify the three-argument Session constructor and `offer(session, onFinished)` semantics.

**Known release-blocking security integration gap:** current `token.openLeaf` creates the writer
fence internally and current `Resources.openOutputs` creates a separate reader fence. Reader
caching and deletion tombstones in this parent are NOT proof of one shared publication gate.
The supervisor explicitly owns a subsequent narrow Preparation/Resources adapter change to
share the same real owner/generation gate, with reader close revoking only the reader, not the
writer. The independent output-store recovery/final-register candidate (e6452b5) is not copied
or reimplemented here. Do not report that shared-fence guarantee as complete with this patch.

## Actual entry path

1. The existing chat composer adjunct now has `图片 / 视频生成审批` and `读取本会话生成资产`
   buttons. No setting-save callback invokes these methods or creates a session.
2. `MainActivity.openGenerationApproval` captures conversation, editor generation, original
   revision and exact text. It runs `ChatController.prepareGeneration` off-main using the
   existing coroutine IO/UI handoff. The controller reserves its existing preparation slot and
   loads the independent `GenerationSettingsStore`. It never substitutes the chat profile.
3. The existing `GenerationApprovalHost` and `GenerationConfirmationHandoff` are mounted, not
   rebuilt. No profile is preselected. Every form change advances the parent form revision.
   The host shows exact prompt/config/parameters and requires its explicit paid confirmation.
   The handoff is retained in the controller before composition and owns the token before the
   confirmation callback runs. Navigation/edit/disposal cancels an unsubmitted entry.
4. `sendGeneration` retains a `ControllerGenerationSession` before queueing the existing send
   executor. It rejects changed saved generation settings before preparation. It calls the
   original `sendPrepared` with the frozen generation identity, not a replacement send engine.
5. The original draft/history CAS and `DatabaseTransactions`/`database.send` path inserts the
   real user/assistant rows. Only successful return records `Accepted`; a pre-write failure is
   `Rejected`; an unconfirmed write is `Unknown`. Queue acceptance and stop are not receipts.
6. `RunCatalogTools.authorizationFactory` receives the real Runner-issued owner and obtains
   the session's one `TaskAuthorization`. The existing catalog sends that exact object through
   its existing device/child/background/skill factories and generationFactory. The generation
   factory calls `session.openLeaf` once with the same resources, owner and task. A late stopped
   owner can validate a receipt only; it cannot resurrect paid authorization.
7. Both acceptance and owner binding notify the existing UI coordinator. The activity compares
   the original editor generation/revision/text and calls `consumeGenerationReceipt`. The
   session consume and the memory editor removal run under the same controller monitor.
   A newer editor, an unvalidated owner, or Unknown never clears a draft. Accepted-but-unmatched
   state remains retained and visibly reports that history is saved but the receipt is unmatched.

Generation still uses the ordinary chat model as its tool-calling orchestrator; the frozen
independent generation profile is used only by the generation client. Model arguments can select
only the frozen batch ID, not arbitrary endpoints, credentials, prompts or new paid requests.

## Lifetime and storage

- There is one controller-owned `ControllerGenerationCleanupQueue(workers=2, capacity=8)`.
- The reserved entry remains in both idle admission and storage-close conditions. The parent
  cleanup callback targets `entry.runner`/`entry.catalog`, never whichever run is current later.
- `runnerDone` is signalled only after the existing slice has returned and the original catalog
  has physically drained. `persistenceDone` is signalled after the send worker's receipt path.
  Parent drain checks both and the real catalog result. HTTP cancellation alone cannot drain.
- The first queue offer carries a real worker completion callback; it posts the retirement check
  to the existing Handler. Duplicate/terminal offers are not treated as completion callbacks.
  Queue rejection retains the reservation and retries scheduling, never the paid operation.
- Unknown is sticky: no replacement reservation, asset deletion, automatic resubmit or new call
  identity is used to evade it. Accepted without a matching editor is also retained.
- The resources provider captures the controller's actual application DB. The first independent
  generation account scope is pinned for this controller; later different scopes are refused.
  This is a fixed single-account application boundary, not multi-account isolation on one DB.

## Assets and limitations shown to users

`loadGenerationOutputs` runs on the existing worker and counts its output-read reservation for
idle/DB-close. It reads registered `generation_outputs` rows for the pinned account/conversation,
reconstructs the original binding/attempt, then uses `ControllerGenerationOutputReader.metadata`
and `openOriginal`. It does not parse a Tool.result URL or require central Confirmed: registered
outputs beside a central UNKNOWN can be read. Image thumbnails are decoded from verified private
original descriptors on the worker. Video originals are verified, with metadata displayed; this
entry does not yet provide video playback/export. No raw path, signed URL or reusable receipt is
sent to the UI.

Reader objects are cached per account/original operation, never evicted and recreated under the
same scalar identity. Conversation deletion/delete-all now use a worker mutation boundary:
reader revocation precedes DB mutation, and tombstones reject reopening those owners in this
controller. Import invalidates existing owner readers before restoring DB contents. Reader close
and controller final reader cleanup are worker-only. These are conservative parent protections,
not a substitute for the shared writer publication gate still required above.

The UI explicitly says:

- URL downloads, including authenticated video content URLs, remain disabled because there is
  no audited connect-time safe adapter. `DENY_DOWNLOADS` is passed explicitly; an allowlisted
  host is not treated as DNS pinning.
- This entry currently supports text-driven generation. Reference-image/mask import has not
  been connected; their buttons explain that no request or draft change occurred.
- Optional size/duration/etc. remain blocked by the existing approval/preflight behavior.
- Video playback/export is not available in this entry. Saving generation configuration never
  starts a request.

## Test source added, not executed

`app/src/androidTest/kotlin/app/daiyu/android/ControllerGenerationIntegrationTest.kt` uses the
real existing `RunControlFixture`, application SQLite, production send CAS and real Runner owner.
Its text-only fake model never invokes a paid tool or network transport. Cases cover:

- missing settings and preview cancellation: no model open, no message rows;
- real two-row admission, same owner/task binding through the existing catalog;
- exact receipt consumption once, rejection of newer editor generation/revision;
- noncooperative model cancellation does not imply drained/retired;
- changed persisted draft and changed saved generation config do not send or clear drafts;
- settings save alone does not create a generation entry and default downloads are denied.

The existing Preparation/Session/Resources tests remain unchanged. The supervisor should compile
all production, JVM and Android test sources after applying the API prerequisite, run the existing
and dedicated suites, then separately validate shared gate/deletion races with the forthcoming
narrow adapter. No successful test, image/video production or paid request is claimed here.
