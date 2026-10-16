# Controller generation production wiring

## Scope and verification status

This final combination targets the supplied **82184cb** baseline, which already includes the
parent/UI wiring, the real main Session API, shared Resources publication gates and final SQL
registration protection. This change edits only `ChatController.kt`,
`ControllerGenerationIntegrationTest.kt` and this document. It does not change Main, DB/schema,
Session, Resources, generation clients, tools or backup implementations.

**Source-reviewed only; not built or executed by this implementation agent.** No Gradle, device,
network or paid generation request was run. The previous prerequisite/shared-gate gap documented
here is no longer applicable to this baseline. Compilation and test execution remain the
supervising agent's responsibility.

## Actual parent entry and receipt path

1. The existing Main composer opens generation approval with the captured conversation, editor
   generation, revision and exact text. `prepareGeneration` reserves the existing preparation
   lane and loads the independent generation settings on a worker. It does not substitute the
   chat profile or start a request when settings are saved.
2. The existing `GenerationApprovalHost` / `GenerationConfirmationHandoff` owns explicit paid
   confirmation. `sendGeneration` retains the real `ControllerGenerationSession` before queueing
   the original send executor and rechecks the saved settings revision there.
3. The original `sendPrepared` / `DatabaseTransactions` / `database.send` path persists the real
   user and assistant rows. Only its confirmed return records `Accepted`. Pre-write rejection
   records `Rejected`; unconfirmed persistence remains `Unknown`. Scheduling and stop are not
   commit receipts.
4. The catalog's `authorizationFactory` gets the Runner-issued owner and calls
   `session.bindOwner`. The **same TaskAuthorization object**, not a reconstructed equivalent,
   continues through the existing catalog factories and generation factory.
5. `generationFactory` passes the controller-retained Resources to the actual
   `session.openLeaf(realOwner, grants, task, retainedResources, ...)`. The baseline Session
   passes `retainedResources::createFence` to the token, so its writer and historical readers
   use the same account/conversation/run publication gate. Downloads remain explicitly denied.
6. `consumeGenerationReceipt` combines exact editor/identity matching, the Session's one-shot
   receipt consume, and the UI editor consume under the same controller monitor. Newer editors,
   unvalidated owners and Unknown cannot consume the original draft. Accepted without a matching
   editor remains reserved rather than being automatically resent.

## One Resources per actual account/DB lifetime

`retainGenerationResources(accountScope)` is the **only** Resources constructor in the parent.
It pins the first independent account scope to this controller's actual application database;
subsequent different scopes fail closed. Both preparation/writer sessions and historical output
loads reuse that same object. It is never reset on stop, successful completion, import, deletion,
reader close, or cleanup failure. There is no per-run/per-reader replacement or alternate gate
registry. This application's schema is not a shared multi-account database.

The historical reader cache is keyed by account and original operation. It reads the authoritative
`generation_outputs` rows, reconstructs the original owner/attempt and calls
`openOutputs`, `metadata` and `openOriginal`. It does not trust a Tool.result URL, guess a path,
or require central Confirmed. Image thumbnails come from verified original descriptors on the
worker; video originals are verified but video playback/export is not added by this change.

## Destructive owner boundary

The two existing Main deletion callers both reach `mutateGenerationOwners`:

- conversation deletion supplies its copied set of conversation IDs;
- delete-all supplies `null`; its worker snapshots and seals the existing conversation IDs while `dataBusy` excludes new admissions.

Admission checks and conversation sealing use the short controller monitor. The parent records
permanent admission seals for the selected existing conversation IDs and calls the retained Resources'
`sealConversationPublicationAdmission`. These APIs only
modify in-memory admission; they do not run validators, acquire SQLite/file locks, or wait.

The queued worker then calls **every captured `revocation.revokeOnWorker()` before closing the
selected cached readers and before invoking the caller's actual deletion transaction**. Thus an
already-admitted SQL publication finishes before deletion, while a validator-to-publication race
cannot register afterward. The worker waits outside the controller monitor and outside any
SQLite transaction. A failed/ambiguous deletion keeps its seal; no import or retry clears it.

Deletion also works before any generation settings or Resources exist: there can be no admitted
generation publication then. The parent retains the seal without loading/minting settings. The
first eventual Resources inherits those seals before any fence can be returned. Consequently
same-scalar owner recreation cannot escape an earlier deletion.

**Clear-history scope:** delete-all removes existing conversations, not the account. Its
worker snapshots existing conversation IDs while `dataBusy` blocks new preparation/read
admission, seals those exact IDs, waits for their publications, and then performs deletion.
Their seals remain permanent, including if an old ID is later imported. New conversation IDs
remain usable through the same retained Resources; no account-wide tombstone is set or reset.
A conversation-only delete likewise leaves other conversations usable.

The supplied Resources also has `sealRunPublicationAdmission` for genuinely destructive
run-owner changes. There is no such parent mutation entry in this patch. **Ordinary stop is not
run deletion:** it revokes execution-local admission and drains the original Session/Runner, not
historical publication admission. `closeGenerationOutputReaders` likewise only closes local
reader fences; even final controller reader cleanup is not a history seal.

## Imports do not revoke existing owners

The unconditional `dataTask(importing=true)` tombstone/reader revocation has been removed.
Inspection of `ConversationDatabase.importBackup` shows append-only inserts and full rollback on
identity/draft conflict. The neutral importer similarly appends or validates an already-imported
package; it does not replace an old generation owner. Therefore malformed input, failed/conflicting
imports, successful unrelated appends and duplicate packages must leave the old Resources,
reader identities, admission seals and readable outputs unchanged. Existing deletion seals also
remain intact: imports do not clear them or swap Resources.

## Drain, close and Unknown

The controller retains its dedicated bounded generation cleanup queue. Session parent-stop and
parent-drain callbacks still target the captured entry's real Runner/catalog. Persistence and
Runner latches plus catalog physical drain are prerequisites, not merely HTTP cancellation.
Only actual cleanup completion can trigger the retirement check. Queue rejection retries cleanup
scheduling, never the paid operation. Unknown remains sticky/non-retriable and cannot retire the
entry or bypass idle admission.

`generationEntry` and `generationReaders` remain in storage-close conditions. Read reservations
last through worker descriptor cleanup and callback delivery. Final reader cleanup and database
close stay on the worker, after the existing Runner/preparation/children/background resources
have drained. No new DB owner or schema lifecycle was introduced.

## Dedicated regression sources (not executed)

`ControllerGenerationIntegrationTest.kt` retains the real Runner/two-row send/receipt tests and
adds real application SQLite plus static-PNG publication/readback coverage. Its seeded historical
outputs use the production attempt store, media store and output registration store, with an
actual persisted original tool invocation; no fake reader or Tool.result URL is used. Fixtures
are offline and are not a claim of an end-to-end paid provider request. The seeded central
attempt is durably `Submission.Unknown`; an existing reservation is verified before readback,
so a readable registered output is not confused with permission to issue another request.

Added/strengthened assertions cover:

- real Session Resources identity as well as the single Runner owner/TaskAuthorization;
- successive real sessions and stop retaining the same Resources and readable old output;
- malformed input, rollback after attempted append, actual unrelated neutral append, neutral
  duplicate, and text identity collision preserving old rows/readers and original PNG readback;
- conversation deletion racing the **actual output store's final registration validator**;
- clear-history waiting for an admitted real SQLite publication transaction to commit;
- permanent old-conversation seals, while both clear-history and conversation deletion leave
  new unrelated conversations usable through the same Resources;
- failed deletion staying sealed, and deletion before the first generation settings save carrying
  its seal into the first actual Resources;
- controller close not closing the DB while an admitted historical read is waiting on the gate.

The existing stop/noncooperative-Runner, changed draft/config, one-shot receipt, no-settings and
no-network tests remain. The supervising agent should compile main/androidTest sources, run this
suite and the existing Preparation/Session/Resources/output-store/backup/lifecycle suites, and
exercise the already-integrated Main approval and deletion UI on a device. No passing build,
test run, paid image/video request or provider transport behavior is claimed here.
