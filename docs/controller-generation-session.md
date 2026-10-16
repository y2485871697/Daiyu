# P19/P20 app-private generation lifetime (additive)

This layer **does not wire production UI or start a second run**. It adds only:

- `ControllerGenerationSession.kt`: retained confirmed token, real-owner/single-task binding, durable admission receipt gate, synchronous revocation, dual-drain retirement and a dedicated bounded cleanup lane.
- `ControllerGenerationResources.kt`: account-pinned same-application-DB provider, persisted assistant/tool-call validator, retained `MessageGenerationAssets`, and private metadata/original readers.
- Offline JVM and Android test source. No paid service, full Gradle build or device execution was performed by the implementation agent.

`ChatController`, `MainActivity`, `RunCatalogTools`, existing token/leaf and database schemas are unchanged. This is the minimum parent integration API, not an alternative controller or dispatcher.

## Retain before handing UI the confirmation callback

Allocate **one application/controller-scoped** `ControllerGenerationCleanupQueue()` independently of the send/HTTP executor. It has two workers and eight queued slots by default, `AbortPolicy`, no CallerRuns and no thread-per-rejection fallback. Keep the existing parent reservation until `session.canRetire` and all parent ownership rules permit releasing it. Never allocate one queue per run.

The existing `GenerationConfirmationHandoff` must still be parent-retained **before** mounting `GenerationApprovalHost`. Its callback stores the token before parent code runs, so a callback that throws after constructing a session/leaf cannot orphan the token. A rejected callback's handoff can be adopted into a `ControllerGenerationSession` by the parent if it has not already created one; that session must use the same real parent drain. Do not perform cleanup inline from the handoff notification.

Inside `onConfirmed(token)`:

```kotlin
// Under the existing parent's control lock; verify the same reservation/account/editor first.
val lifetime = ControllerGenerationSession(token, parentStop = {
    // Invoked ONLY on the independent cleanup worker, before token.close().
    // Target this reservation's ORIGINAL Runner, not whichever run is now current.
    requestStopOfOriginalParentRun()
}) { timeoutMillis ->
    // Wait for the existing catalog, original Runner AND receipt-persistence drain.
    // This callback runs outside the session/queue locks, not on UI or send/HTTP.
    // Return GenerationToolsCloseResult.Unknown if any drain is unknown.
    drainExistingParentRun(timeoutMillis)
}
reservedSlot.generationSession = lifetime // before scheduling the existing send/run path
```

The parent-supplied stop and `drainExistingParentRun` callbacks are intentionally not implemented here: only the parent owns that original Runner/catalog and knows whether its durable admission transaction and real run exit have finished. Requesting stop (or cancelling HTTP) is **not** proof of exit or admission completion. The drain must await both; it must not report Drained merely because HTTP was cancelled. Stop must target the captured original run, never a replacement/current run. Return `true` to the UI handoff only if the parent accepted this exact slot. UI callback acceptance is **not** a persistence receipt and must not clear the editor.

`parentStop` is optional through the existing two-argument constructor: both `ControllerGenerationSession(token, parentDrain)` and `ControllerGenerationSession(token) { timeout -> ... }` retain their no-op-stop behavior. New parent wiring with a Runner must supply the stop callback. Construction and `revokeAdmission()` never invoke it; only the winning cleanup worker does, once.

## Exact existing catalog callbacks

Create resources with a provider captured for **this account's actual application database**, not a provider that follows the active account. `privateMediaRoot` must be the same app-private generation media root used for recovery. Construction does no file/DB/network I/O. A shared multi-account database without per-message account ownership is unsupported by the existing schema; do not use an account string as a substitute for DB isolation.

```kotlin
val media = ControllerGenerationResources(
    accountScope = lifetime.identity.accountScope,
    accountDatabase = { capturedApplicationDatabase },
    currentAccountScope = { currentAccountScope() },
    privateMediaRoot = existingPrivateMediaRoot,
)

// Named arguments to the ONE existing RunCatalogTools constructor:
authorizationFactory = { realOwner ->
    synchronized(controllerLock) {
        lifetime.bindOwner(
            realOwner, currentEditorSnapshot(), currentGenerationIdentity(),
            capabilities.sessionId, otherExplicitlyApprovedWrites,
        )
    }
},
generationFactory = { realOwner, caps, sameTask ->
    lifetime.openLeaf(realOwner, caps, sameTask, media,
        isCurrentOwner = {
            parentStillOwns(realOwner) && currentAccountScope() == lifetime.identity.accountScope
        })
},
```

`bindOwner` invokes `token.paidWrites(realOwner, currentEditor, currentIdentity)` with the exact frozen revision/text/ordered attachments/account/durable IDs and constructs exactly one `TaskAuthorization`. Repeated calls only return that same object for the same owner/session/write set. `openLeaf` rejects an equal-but-different task object or owner, delegates to `token.openLeaf`, and retains resources before construction. The leaf reuses `MessageGenerationAssets`, `SQLiteGenerationAttemptStore` and `SQLiteGenerationOutputStore`. No temporary owner, second Runner, second dispatcher, replacement task, alternate paid-effect hash or preflight is created.

The parent must persist original assistant tool calls through its existing path before dispatch. `media.validateOwner` checks the actual assistant role/conversation/run and exactly one matching original tool ID/name, respecting authoritative history segments. It rejects foreign accounts, missing/duplicate/stale calls and outer SQL transactions; it does not merely check that a row exists. The token separately checks live owner/cancellation.

**Default download policy is deny.** No production connect-time DNS-pinning adapter was found at this boundary. `openLeaf` therefore defaults to `ControllerGenerationResources.DENY_DOWNLOADS`. `DOWNLOAD_STATUS` explicitly says URL downloads, including authenticated video content URLs, are unavailable. Inline image bytes can still use the existing client. Exact HTTPS authority allowlists and `InetAddress` pre-queries are not DNS pinning. The optional network policy argument is only for a future audited adapter paired with an actually enforcing transport; do not pass `{ _, _ -> true }` or claim URL/video download availability with the current default.

## Durable Accepted may precede bindOwner

After the parent's completed admission transaction, report the **real** result exactly once:

```kotlin
lifetime.recordPersistence(reservedIdentity,
    GenerationPersistenceReceipt.Accepted(savedConversation))
// Or Rejected only if the parent proves no durable admission occurred.
// Or Unknown if admission cannot be established. Neither stop nor bind failure proves rejection.
```

`recordPersistence` performs no DB transaction itself. It requires the same reserved identity and accepted conversation ID; the parent is responsible for the actual committed transaction evidence. `persistenceReceipt` exposes truth for pending/status/history reconciliation but **not permission to consume the editor**.

On UI, under the same parent lock as the editor compare-and-remove operation:

```kotlin
val accepted = lifetime.consumeAccepted(currentEditorSnapshot(), currentGenerationIdentity())
if (accepted != null) {
    // Remove only the original frozen text/attachments, atomically with this matching check.
    // Load accepted.conversation using the existing accepted-history path.
}
```

Consumption is single-use and unavailable before real-owner validation, on a newer revision (even edit-then-revert), foreign account/identity or non-Accepted receipt. Receipt arrival and owner binding can occur in either order; the parent should re-attempt the UI consumption gate after **both** events.

Explicit stop before binding synchronously revokes paid work but preserves a real Accepted, even if its transaction completes after stop. A late **real Runner-issued** owner can use `validateOwnerForReceipt(realOwner, currentEditor, currentIdentity)` to validate only the receipt after stop; this cannot reopen paid writes or a leaf. If the Runner never issued an owner, retain the Accepted as non-consumable and surface saved history separately. Never fabricate an owner, synthesize Rejected, clear a newer draft or silently retry under new IDs.

## Stop and cleanup are distinct phases

```kotlin
synchronized(controllerLock) {
    lifetime.revokeAdmission() // cheap token/leaf admission fences only; no I/O/wait
    // Revoke parent owner before replacing it.
}
val queued = cleanupLane.offer(lifetime, onFinished = {
    // Runs on the cleanup worker AFTER closeResult is published and the queue marker removed.
    // No session/queue lock is held. Post a short event to the existing parent/UI coordinator;
    // there, recheck the SAME reserved slot plus canRetire under the parent's control lock.
    notifyParentToRecheckRetirement(lifetime)
}) // only enqueues; also idempotently revokes
// false => full/shutdown: keep reservedSlot + lifetime; retry notification later.
```

The cleanup worker first invokes `parentStop`, then attempts token `close()`/`awaitClose()` and the supplied parent drain. Both parent callbacks run outside the session/queue locks. Token `close()` is attempted even if stop throws; a thrown stop produces sticky Unknown (`generation_cleanup_failed`, or `generation_cleanup_interrupted` with interrupt restored), skips the parent drain and never reports Drained. Neither the stop request nor the token's own successful close proves the original parent has exited. Close may block on an already admitted publication/transport; it is never called on the UI or send executor.

`offer(session, onFinished = {})` has an optional worker completion notification. The **first accepted offer** owns that notification: duplicate queued/active offers and already-terminal sessions return `true` without adding work or a second notification. Always attach the parent's callback on its first offer. A full/shutdown rejection returns `false`, invokes neither stop nor notification, and leaves the session pending. Successful cleanup publishes `closeResult`, removes the queued marker, then notifies on that same worker, including on Unknown. A notification exception is isolated and cannot discard the result or kill the worker. Notifications never run inline on the offer caller or under queue/session locks; keep them short, since they occupy a cleanup worker. The parent must also recheck retirement on later admission/receipt-consumption events; an already-terminal offer is not a new completion event.

Capacity is bounded even if all workers are stuck. `cleanupRequested` stays true; `closeResult` is published when the cleanup attempt finishes. Unknown is sticky, retains token/leaf/assets and reservation, and is **not** permission to retry paid generation, delete assets or make a replacement reservation. Queue shutdown drains existing queued tasks; it does not forcibly interrupt them. A rejected offer remains parent-owned.

`canRetire` requires Drained plus a definite Rejected or an Accepted already consumed by its exact editor. Unknown or an unresolved/non-consumed Accepted never retires automatically. It is deliberately conservative; the parent may need a separate explicit saved-history/manual reconciliation path, but cannot reinterpret that as automatic draft consumption.

## Reading real outputs without exporting paths

On the existing background read worker:

```kotlin
val reader = media.openOutputs(persistedGenerationAssetOwner)
try {
    val metadata = reader.metadata(originalAttempt, outputIndex)
    reader.openOriginal(originalAttempt, outputIndex)?.use { original ->
        // App-private decode/read only; this is the verified original, also for video.
    }
} finally {
    reader.close() // worker only: fence revocation can wait
}
```

The caller supplies the actual persisted binding and original `GenerationAttempt` fingerprint/protocol, not model-supplied paths or a guessed receipt. Metadata comes from the real output side table through the existing full authorization/readback; original opening re-verifies the private mapping/digest. It never treats a `Tool.result` URL/string as an output reference. The UI projection contains only output index/kind/MIME/size/dimensions/duration, with no path, URI, credential, receipt or provider URL. Account, assistant, run, original tool call and attempt fingerprint/protocol are checked again. Caller owns the returned read-only descriptor; revocation cannot recall an already opened descriptor. Do not turn it into a `file://` URI or export it to other apps.

## Added test sources and verification boundary

- `app/src/test/.../ControllerGenerationSessionTest.kt`: real Runner-issued owner; Accepted-before-bind and stop ordering; wrong revision/account/owner; same mixed task/leaf; zero construction/preparation I/O; default URL deny; sticky parent-drain Unknown; both legacy constructor forms; parent stop/drain/notification worker ordering, no UI-inline stop and stop-failure retention; default two-worker/eight-slot saturation, active/queued deduplication and no rejection fallback; notification after marker removal/outside locks and observer-exception isolation; unresolved/Unknown/unconsumed Accepted retention; concurrent stop/Accepted.
- `app/src/androidTest/.../ControllerGenerationResourcesTest.kt`: isolated in-memory SQLite with static PNG; exact persistent account/assistant/run/tool and authoritative history validation; duplicate call denial; real output registration/metadata/original roundtrip; wrong fingerprint/account/tool rejection; no path/receipt in metadata.

Tests are **source additions only**. Compilation, the targeted tests and any device run remain the main agent's responsibility. No paid calls or full Gradle execution are required for these fixtures.
