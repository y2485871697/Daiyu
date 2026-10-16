# P19/P20 generation run leaf integration (static delivery)

## Scope and status

This adds `integration/tools/RunGenerationTools.kt` and the independent
`model/generation/GenerationRunConfig.kt`. It does **not** change `RunCatalogTools`,
`ChatController`, `MainActivity`, `ConversationDatabase`, the runner, or UI. Until the
parent performs the wiring below, generation is **not available through the application**.
There is no placeholder-success tool and no claim of an APK/device test run.

The implementation uses the existing `ImageGenerationClient`, `VideoGenerationClient`,
`GenerationIo`, `GenerationCancellation`, `GenerationAssetFence`,
`MessageGenerationAssets`, `SQLiteGenerationAttemptStore`, and
`SQLiteGenerationOutputStore` APIs as present in the supplied worktree (including the
supplied additive output-store contract). It neither duplicates their request/codec/media
algorithms nor owns a database helper, migration, retry scheduler, or network stack.

### Deliberately narrow tool surface

* `generate_images` / `generate_videos`, `ToolEffect.WRITE`, capability `generation.paid`.
* Arguments: exactly `{"batch_id":"<parent-frozen ID>"}`. Protocol, endpoint, credential,
  model, prompts, reference/mask bytes, item IDs and unsupported-parameter checks are NOT
  delegated to the model. The leaf does not accept arbitrary prompts/URLs/options from it.
* Only configured protocol families are registered. Null configuration registers **nothing**.
* A selected batch is 1–32 items of one explicit protocol, submitted sequentially by the
  original client's `submitBatch`. Every item of this selected batch is preflighted before
  its first POST. This is not a transaction across separate tool invocations.
* Image JSON, single-reference image edit multipart, and explicit video multipart retain
  the existing clients' limits. No provider guessing, fallback, extra parameters or codecs.

## Required parent wiring, in order

1. **Separate settings and permissions.** Build an immutable `GenerationRunConfig` from
   explicit generation settings, not the active chat model or a later settings lookup.
   Its lists/maps/inputs are defensive snapshots. Keep this exact config instance for the
   run. Each item has a stable parent-selected item ID. `maxPolls` is 0–32, fixed before
   approval. A capability toggle is NOT paid-write permission.
2. **Obtain the real owner, then freeze exact paid writes.** Wait for the runner's
   `bindOwner` callback. With that exact `RunExecutionOwner`, call
   `RunGenerationTools.writeEffect(accountScope, owner, config, batch)` for each explicitly
   permitted batch, then create the actual run's `TaskAuthorization.allowedWrites`. The
   trusted `accountScope` must be the same stable local account/DB scope passed to the leaf,
   never model input or a credential. The effect target is a length-delimited digest of
   account + conversation + run + assistant message + batch ID; its change is the persistent
   complete config fingerprint (all ordered batches' request fields, credential/reference/
   mask/parameter/prompt digests and poll bound). It deliberately excludes model call ID,
   random object identity and dispatcher session ID: a new call ID in the same persisted
   owner/config must still replay Confirmed or protect UNKNOWN, but another owner must not
   inherit either. Retain the exact config values on recovery, not a fresh random scope.
   Never use `READ_ONLY`, a wildcard effect, an unrelated task authorization, or an empty/
   default approval. Independently verify the session ID belongs to the captured runner
   session. Existing old unscoped effect keys, if any were previously persisted by a host,
   require explicit reconciliation/quarantine, not an automatic re-dispatch with new keys.
3. **Construct with that same owner and permission.** Pass the exact owner object and the
   SAME `TaskAuthorization` instance used by the central dispatcher. Persist the original
   assistant tool call before dispatch. The prepared tool requires `PreparedTool.contextual`;
   direct `prepared.execute()` fails. Pure context admission checks owner reference,
   authorization reference, original tool name and canonical arguments, including before
   confirmed-ledger replay. At execution it builds `GenerationAttemptBinding` from the
   actual context's conversation, assistant message, run, tool call ID and tool name.
   Never manufacture an owner/call on recovery. Call ID remains in the durable attempt/
   asset binding, but NOT in the semantic paid-write effect.
4. **Supply actual host boundaries.** The constructor requires:
   * Stable local `accountScope`, not a token/URL.
   * `database: () -> SQLiteDatabase` returning the SAME account application database on
     every call, outside any outer transaction. Parent migrations must install both existing
     store schemas after messages exist, with durable synchronous/journal settings. No new
     DB/helper is opened by this leaf. Retain attempt tombstones per the store contract.
   * `assetsFactory: (GenerationAssetFence) -> MessageGenerationAssets`: create the real
     private store with the **supplied fence**, fixed private media root and real inspectors.
     Do not replace the fence with an unrelated one or export a staging directory.
   * `validateOwner`: validate actual account/conversation/run/assistant/call ownership and
     current live admission. It is also called by the asset fence. Use stable owner evidence
     and/or direct DB validation, plus a separately captured atomic live-admission token if
     needed. **Never call `Controller.isRunUnfenced` or any Controller-lock-taking method**,
     never acquire a Controller lock under a DB transaction/fence, and never implement this
     as `{ true }`. It must be safe on the worker and while the publication fence is held.
   * `GenerationRunApproval`: explicit APPROVED/DENIED review after the real client has
     preflighted **all** selected items. It receives the original context, exact config and
     batch object, plans and write effect. A denied review causes zero POSTs. There is no
     default implementation. Marshal UI review from the worker without retaining Controller
     locks; cancelled/closed review must not turn into approval for changed settings. A
     previously frozen exact user approval may be validated here; a capability flag alone
     may not. Do not silently run synchronous review on the UI thread.
   * `GenerationDownloadAuthorization`: independent URL/host/DNS/SSRF policy for each
     download, INCLUDING authenticated video content URLs. No allow-all policy is provided.
     The existing transport does not follow redirects or send credentials to CDN URLs.
     The policy must account for the actual resolver/transport environment (the leaf does
     not claim to solve DNS rebinding by string comparison).
   * Optionally the exact `GenerationHttp` transport; it must obey at-most-once/no-redirect/
     no-auth-retry contract. The default is the existing URLConnection implementation.
   * The final `attemptStoreFactory` parameter is an exact-interface test seam. **Production
     must leave the default**, which constructs `SQLiteGenerationAttemptStore(database,
     binding)`. In-memory ledgers and stores from another database are not production-safe.
5. **Register through the existing dispatcher.** Add `registeredTools` to the parent's
   central registry, not a second dispatcher or direct call route. The parent remains owner
   of capability filtering, central tool ledger, receipt persistence and worker scheduling.
   All execution, review, SQL, media inspection, publication and download run on that worker.
   Construction/prepare/pure context admission do not perform network/SQL/media I/O.
6. **Expose only confirmed registrations.** `GenerationIo.downloadAndCommit` yields a local
   file receipt, then this leaf calls `assets.pending(receipt)` and the same-DB
   `SQLiteGenerationOutputStore.register(attempt, pending)`. Only a successfully returned,
   read-back-confirmed reference can appear in a Confirmed tool payload. Do not turn a
   pending file into a MessageBlock/Image UUID, insert Tool.result inside the output-store
   transaction, or expose original paths, credentials or provider URLs. Parent UI/history
   rendering should query authorized side-table references and use `open`/verified originals.

## Results, partial success and no retry

* Full success returns a Confirmed JSON envelope containing item IDs, status, and registered
  asset IDs/index/kind/MIME. A mixed completed/not-executed batch retains explicit per-item
  status, not a blanket success for every child.
* Pending, ambiguous provider results, invalid output siblings, download failures and
  registration failures return `ExecutionReceipt.Unknown`. This is intentional: “the worker
  returned” does not mean paid generation or registration completed successfully.
* `outcomes()` supplies the parent a thread-safe snapshot of completed batch observations,
  with each original `Submission`, independently registered references, and indexed failures.
  A partial batch's valid assets remain in the DB even if the central receipt is UNKNOWN.
  These observations contain private provider outputs/possibly signed URLs: do not log or
  export raw objects. Use the authorized side table for durable UI recovery. Parent integration
  must surface partial registered assets separately rather than waiting for a Confirmed tool
  receipt; this leaf intentionally does not patch conversation history/UI.
* Every call goes through the durable attempt reservation before POST. Missing final outcome
  writes leave tombstones, not retry permission. Central semantic effects also prevent a
  model from repeating the same configured operation under a fresh tool call ID.
* Video POST occurs once. Subsequent reads use `videos.poll(plan, originalHandle)` up to the
  frozen bound. Unknown stops polling; no fallback POST, ID replacement, polling scheduler,
  implicit new batch or credential refresh occurs. Exhaustion leaves Pending/UNKNOWN.
* This leaf is not a separate recovery/retry tool. Recovery must revalidate the persisted
  original owner/config and operate on the original attempt/handle/side-table references via
  existing store/client APIs. Do not create another call/operation/item to “retry”. A changed
  desired generation requires a new explicit user action and separately approved configuration,
  not an automatic response to failure. Reconstructing a fresh central ledger alone does not
  reopen a durable generation attempt.

## Close, revocation and lock ordering

The parent must integrate these two distinct barriers in this order; the leaf does not add
another dispatcher, scheduler, Controller completion path or synthetic parent drain signal:

1. **Synchronous control section:** under the parent's existing short control lock, stop new
   parent dispatch admission, capture the exact old leaf/owner, and call
   `leaf.revokeAdmission()`. If the parent has a separately captured atomic validator
   admission token, mark it revoked here too. This method only takes the leaf's short
   monitor and sets its admission flag. It does NOT acquire publication fences, call a
   validator, cancel HTTP, touch SQLite or wait for workers. It is safe for the synchronous
   UI/Controller cancellation path. Keep the old owner/resources/leaf retained as retiring;
   do not destructively delete/replace its persisted owner or start a replacement yet.
2. **After releasing all UI/Controller/DB locks:** schedule `leaf.close()` on the parent's
   cleanup worker. Close seals admission as a fallback, revokes every captured publication
   fence, THEN invokes the original `GenerationCancellation.cancel()`. It may block behind
   a publication/validator already holding a fence or inside HTTP disconnect. Never call it
   synchronously from the UI or while holding a Controller lock/DB transaction. A worker
   whose fence registered before admission was sealed is captured; no later worker can
   enter. Already admitted publication/registration can linearize before revocation and is
   retained, not deleted as “cancelled”. `revokeAdmission()` alone is NOT publication
   revocation and must never be used as permission to dispose the owner.
3. **Observe cleanup off the control/UI thread:** call `leaf.awaitClose(timeoutMillis)`.
   Before `close()` has started it returns Unknown even if admission is sealed and no
   worker exists. A separate cleanup-started flag makes a preceding `revokeAdmission()`
   unable to skip later `close()`. Repeated/concurrent close calls are idempotent; they do
   not imply cleanup finished. Drained requires every leaf worker (including factory,
   approval, media, download and DB registration) to leave its `finally`, all fence/close
   work to return, and the original cancellation handle to report physical HTTP/cleanup
   exit with no failure. Blocked work, timeout or cleanup failure remains Unknown.
4. **Drain the real parent, not just this leaf:** the existing catalog's `activeDispatches`
   must reach zero only through its original outer-dispatch/final-receipt-persistence
   completion path. Await the original catalog and all other parent cleanup barriers as
   well as this leaf. The leaf worker may have exited while the central ledger/final tool
   receipt is still being persisted; never decrement or bypass `activeDispatches` from this
   leaf or treat `GenerationToolsCloseResult.Drained` as parent completion. Only after both
   barriers complete may a short parent control section recheck the captured owner/version
   and declare parent drain, then dispose/delete/replace the old owner, DB/assets/root.
   On Unknown retain the old resources and cleanup observation; do not silently reopen
   admission, drop the leaf or report successful cancellation.

Validators must use captured stable owner evidence/direct DB checks and lock-free live
admission, not `Controller.isRunUnfenced`. `close()` can wait for a validator inside a fence;
a callback to the Controller would create a fence/Controller lock inversion. No external
callback or blocking cleanup runs while the leaf's own control monitor is held.

## Added test sources (not executed in this delivery)

* `GenerationRunConfigTest`: defensive freezing and complete request-field/config fingerprints,
  stable reconstruction, ordered batches and poll-bound scope.
* `RunGenerationToolsTest`: real dispatcher/context to original clients; no permission/denial
  and invalid last sibling cause zero POSTs; original binding and frozen approval; bounded
  same-handle polling; shared central ledger UNKNOWN isolation for two real Runner owners;
  account/conversation/run/assistant/config effect separation and stable reconstruction;
  Unknown/new-call no re-POST; denied download/no exposed receipt; synchronous admission
  revocation returns with a validator holding the publication fence, performs no disconnect,
  and does not skip subsequent worker close; fence revoke precedes disconnect and physical
  HTTP/worker exit is required for drain.
  Transport, attempt-store seam and media inspectors are test substitutes, not codec/SQLite
  proof. No live network is used.
* `RunGenerationToolsDeviceTest`: real application SQLite tables, both existing stores,
  original image client, real Android image validation/private file store. Covers partial
  sibling preservation, full success payload only after registration, shared central-ledger
  Confirmed/UNKNOWN isolation across two real Runner owners with identical frozen config,
  no cross-owner asset-ID replay, exact-authorization rejection even on cached outcomes,
  same-owner new-call deduplication, ignored SQL insertion never exposing a pending receipt,
  and registration worker completion required for drain. Shared-ledger fixtures use separate
  per-run registry snapshots with one common ledger (not a production second dispatch route).
  Only HTTP is substituted. Parent should run this targeted instrumentation after integration.

Build/test invocation, migration wiring and device validation belong to the parent agent. No
APK build, full suite, push or branch creation was performed for this leaf delivery.
