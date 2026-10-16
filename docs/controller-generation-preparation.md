# P19/P20 generation controller preparation boundary

This is an **additive preparation layer**, not a claim that production ChatController/MainActivity are already wired. No Controller, Activity, catalog, manifest, database or existing generation leaf is modified. It implements usable constructors/callbacks rather than a second execution engine.

## New entry points

- `model/generation/GenerationPreparationSettings.kt`: `GenerationModelProfile` (explicit generation-only endpoint, credential, model and wire protocol) and `GenerationDownloadHosts` (separate exact HTTPS authorities).
- `ControllerGenerationPreparation.kt`: pure snapshot/preview, one-shot explicit confirmation, real-owner binding, exact paid writes, leaf construction and conservative cleanup.
- `ui/GenerationApprovalHost.kt`: optional controlled Compose form for images, image edits and videos; full preview, local original/mask thumbnails and byte digests, distinct paid-confirm and cancel actions. It has no ChatController dependency and does not mount itself.

There is **no chat-model fallback**. Supply only generation profiles read by the parent from its trusted settings. A missing/unselected/unknown profile is an error. The form cannot set a URL or credential; a model tool call can only select a frozen batch ID through the existing leaf schema. Profile construction requires an exact HTTPS:443 resource endpoint with the selected protocol suffix and a nonblank credential. Credentials never appear in profile/form/preview string representations or UI. Do not log requests or `Batch.requests(...)`.

## Parent integration sequence (one owner, task and dispatcher)

1. Reserve the parent's existing preparation slot before displaying a preview. Parent assigns durable conversation/run/assistant-message IDs via its normal reservation mechanism. **Do not instantiate a temporary `RunExecutionOwner`, run a second Runner, or create a second dispatcher for preview.** Keep the reservation until acceptance or confirmed cleanup. An unknown prior submission/cleanup blocks a replacement reservation, even if the user edits the form.
2. Increment a monotonic editor revision on *every* editor/form/profile/credential/download-setting/attachment/navigation/account edit, including edit-then-revert. Freeze attachment IDs referring to immutable bytes. Cancel a pending preview on these edits. `GenerationEditorSnapshot(revision, exactText, attachmentIds)` additionally compares exact text and ordered attachment IDs, not just a boolean busy flag. Do not recycle a revision.
3. Construct `GenerationPreparationIdentity(accountScope, conversationId, runId, assistantMessageId)`. `accountScope` is the stable account scope used by the generation ledger/assets, not a credential, active-profile index or ephemeral UI object ID.
4. Create `ControllerGenerationPreparation.prepare(identity, editor, explicitProfiles, selections, maxPolls, reviewedDownloadHosts)`. Each `GenerationBatchSelection` has only a trusted profile ID and original immutable `GenerationRunConfig.Item`s. The constructor freezes the complete config including credential, prompt whitespace, every original/mask byte, all parameter values and maxPolls. It performs no media preflight, disk, DB or HTTP operation. A configuration may be previewed without granting permission.
5. Only the trusted explicit paid-confirm handler calls `preparation.confirm(preparation.preview, currentEditor, currentIdentity)`. This consumes that exact preview once. Cancel/dismiss calls `preparation.cancel()`. Saving preferences, choosing a model, selecting media, opening a dialog and toggling UI are not approval.
6. Retain the returned `ConfirmedGenerationPreparation` in the parent's preparation/run slot. During the **existing Runner's** owner-binding callback, recheck the reserved identity and latest editor, obtain writes, then build the parent's one mixed-catalog authorization. Illustrative code inside the existing bind callback:

```kotlin
// realOwner is supplied by Runner; no fabricated/default owner.
val generationWrites = confirmed.paidWrites(realOwner, currentEditorSnapshot(), currentIdentity())
val task = TaskAuthorization(parentSessionId, realOwner.runId.value,
    otherExplicitlyApprovedWrites + generationWrites)
val generationLeaf = confirmed.openLeaf(
    owner = realOwner,
    authorization = task,
    database = { applicationDatabase },
    assetsFactory = { fence -> createMessageGenerationAssets(fence) },
    validateOwner = { assetOwner -> parentStillOwns(assetOwner) },
    networkPolicy = auditedGenerationDownloadNetworkPolicy,
    isCurrentOwner = { parentStillOwns(realOwner) && activeAccountScope() == confirmed.preview.identity.accountScope },
)
// Add generationLeaf.registeredTools to the parent's EXISTING registry snapshot.
// The parent's EXISTING dispatcher uses the SAME task and realOwner objects.
// Persist original tool calls through the existing parent path before dispatch.
```

`paidWrites` calls `RunGenerationTools.writeEffect(accountScope, realOwner, frozenConfig, batch)` for **every exact batch**. It returns an immutable set, not a TaskAuthorization. No alternative hash, synthetic owner or second task is created. A later equal-ID-but-different-owner object is rejected; a replacement TaskAuthorization object is rejected once pinned. The mixed task may contain other explicitly approved writes. `openLeaf` is single-use and pins that same task.

If assembling a leaf directly in an existing factory, the lower-level callbacks are available: `confirmed.approval(realOwner, task, isCurrentOwner)` and `confirmed.downloadAuthorization(realOwner, networkPolicy, isCurrentOwner)`. Pass `confirmed.preview.config` and its original account scope to `RunGenerationTools`. Prefer `openLeaf` because it also retains the leaf for cleanup. If using the lower-level callbacks, the parent **must retain and close/await its separately assembled leaf**; token cleanup alone cannot drain a leaf it was not given.

The approval callback rechecks live ownership, cancellation, exact config/batch object, real owner/task objects, actual tool kind, real invocation-bound plan identities, endpoint/protocol and production write effect. **All external callbacks (including cancellation, ownership and network policy) run outside the token monitor**, followed by a token-local revocation/binding recheck. A callback that returns an earlier `true` after revocation cannot approve the request. The parent may take its own control lock in these callbacks; never hold that lock across leaf `close()`/`awaitClose()`, asset-fence revocation or any blocking cleanup. The existing leaf remains solely responsible for full preflight, POST, polling, downloads, attempt persistence, registration, output receipts and replay. Nothing here duplicates those clients.

## UI callback contract

Mount `GenerationApprovalHost` beside the existing composer, passing the reserved identity, current editor snapshot, generation-only profiles, separately reviewed `GenerationDownloadHosts`, parent-controlled `GenerationApprovalForm`, **a parent-retained `GenerationConfirmationHandoff`**, and callbacks. Allocate the one-shot handoff in the matching parent reservation before composition, not via `remember` in a disposable UI. Its `requestCleanup` callback must only notify/enqueue the parent's existing bounded cleanup worker; it must not execute cleanup inline or create a new thread per rejection. Example lifetime outline (the queue and reservation are the parent's existing facilities):

```kotlin
val handoff = GenerationConfirmationHandoff { retainedSlot ->
    // Nonblocking notification only; retain the parent slot if the bounded queue is full.
    parentCleanupQueue.offer(retainedSlot)
}
parentReservation.generationHandoff = handoff // BEFORE passing it to GenerationApprovalHost
// Pass confirmationHandoff = handoff to the host.

// On the existing cleanup worker, outside the controller/UI locks:
val token = checkNotNull(handoff.token)
token.close()
val result = token.awaitClose(cleanupTimeoutMillis)
// Release the matching reservation/handoff ONLY if result is Drained AND the existing
// runner/dispatcher receipt-persistence drain also succeeded. Otherwise quarantine it.
```

The snippets name parent facilities illustratively; this additive layer does not create a queue, Runner, dispatcher or thread. Notification failure/queue saturation is not cleanup: `handoff.cleanupRequested` remains true and `handoff.token` remains strongly held. The parent must retry notification using its retained slot, including during teardown. Do not discard fenced tokens, replace the reservation, or use an unbounded executor to work around a blocked worker. The pending flag denotes a rejection that needs cleanup; it is not itself a drain receipt and is never cleared by UI disposal. The parent retires the entire one-shot slot only after the required drains.

- `onFormChange(newForm)`: replace form and increment revision synchronously. The form exposes original prompt, size, video duration and maxPolls. It has no implicit selected profile. It can retain other original parameter fields in `otherParameters` for complete preview.
- `onPickReference` / `onPickMask`: use the parent's existing local media-selection path. When its result arrives, freeze `GenerationInput` bytes, update form/attachment IDs and increment revision. A picker action does not grant network permission.
- `onConfirmed(token): Boolean`: the parent-owned handoff stores the exact token **before** this callback executes. Synchronously accept it in the existing parent run slot or return false. False/exception performs only cheap `revokeAdmission()` and cleanup notification on the UI thread. Even if the callback bound an owner, opened a leaf or started a worker before throwing, the handoff still owns that same token for cleanup; do not assume a failed callback means no work started. True leaves lifetime with the parent; UI disposal must not revoke an accepted run. The callback itself must not perform blocking I/O or cleanup. Do not clear the editor merely because it returned true.
- `onCancelled()`: release only the matching pending **unconfirmed** preparation reservation. Once a handoff contains a token, retain it through cleanup even for a rejected confirmation. Never clear another/newer draft or cancel an unrelated accepted run.

The UI additionally checks the displayed form/settings snapshots. Still, the parent revision/account/owner checks are mandatory, especially for changes while the Runner is being scheduled. For editor clearing, wait for the existing **durable accepted-message receipt**, then compare the original revision/text/attachments/identity again. A model response, preview click or scheduling acknowledgement is not that receipt.

## Current parameter limitation: no silent downgrade

The retained `GenerationRequestPlan.prepare` currently rejects **all** optional parameter maps, including `size`, duration, quality, seed, concurrency, count and extra JSON. Image requests have fixed `n = 1`; multiple paid items are explicit parent items (one POST each). This patch cannot add backend support without changing a prohibited existing leaf/client.

Accordingly, original optional parameters are frozen and fully displayed but produce blocking preview errors; paid confirmation stays disabled and the pure API also rejects confirmation. The user must explicitly remove unsupported values and obtain a new revision/preview. They are never silently dropped, trimmed, inferred from a toggle or sent to an alternative model. Image edits retain one original PNG and optional same-dimension PNG mask; videos retain at most one reference and no mask. Authoritative byte decoding/limits/protocol validation occurs in the existing leaf **before** approval/POST for the complete batch. A thumbnail is display only, not evidence that media passed preflight. Invalid media can still fail there without network activity.

## Download authorization is independent

Paying for generation does not authorize arbitrary server-supplied URLs. Empty `GenerationDownloadHosts` denies every remote download. Nonempty sets are exact, explicitly reviewed lower-case DNS hostnames; no wildcard/suffix authorization, userinfo, IP literal, localhost, local/internal suffix, fragment, non-HTTPS or non-443 port. Query strings are allowed for signed URLs. The entire authority must match the reviewed host; `cdn.example.evil` is not `cdn.example`. The video authenticated content endpoint's API host is **not implicitly added**: explicitly review it too if video content downloads are desired.

`downloadAuthorization` ANDs this immutable host policy with the mandatory parent `GenerationDownloadAuthorization` network policy, owner/account binding and revocation. Parent denial/errors fail closed; the policy cannot broaden the host list. No allow-all network implementation is supplied. Host review alone is **not DNS/address validation**: parent must use its audited public-address/DNS-rebinding policy and enforce it at connection time, including authenticated video downloads. This layer neither resolves DNS during preparation nor claims that a separate DNS lookup pins a later connection. If the parent has no audited implementation, inject a deny-all network policy and keep remote downloads unavailable. Do not ship an always-true lambda.

`openLeaf` defaults to existing `UrlConnectionGenerationHttp`, which rejects redirects (including 30x), does not retry, and requires isolated cookies. Injected test/production transports must retain that contract; custom address-pinning transport, if needed, belongs to the parent transport boundary, not to this preparation layer. Do not follow a redirect after checking only its first URL or forward credentials to a redirected host.

## Cancellation, UNKNOWN and recovery

On cancellation/account switch/deletion/parent teardown, call **`confirmed.revokeAdmission()` first** in the parent's short control-lock/UI section. It fences the token and seals leaf admission without waiting for publication fences, invoking validators, aborting transport or doing I/O. Also seal the parent's existing Runner/dispatcher admission through its normal short cancellation path. Keep the token/handoff and reservation owned; revocation is not disposal or drain.

Then schedule **both `confirmed.close()` and `confirmed.awaitClose(timeoutMillis)` on the existing cleanup worker, outside UI/controller locks**. `close()` performs the second phase: actual leaf publication-fence revocation and transport cancellation, which can block on an already admitted validator/publication. Calling `revokeAdmission()` must not skip this phase, even if an earlier await latched UNKNOWN. Do not call `close()` directly from Compose confirmation/rejection/disposal handlers. The token-created asset validator checks the token fence before and after external parent checks as well.

Only `Drained` plus the **parent Runner/dispatcher's receipt-persistence drain** permits releasing/replacing the parent slot. Awaiting or closing is not itself retry authorization. An await after mere revocation but before cleanup has started is UNKNOWN, not a synthetic Drained. Before opening a leaf, cancellation is still terminal; `close()` followed by await can drain the empty token.

`UNKNOWN` is terminal for this token: timeout, await failure or interruption is latched and cannot be upgraded by a subsequent successful leaf drain or used to reopen a leaf. Interrupted await preserves the thread's interrupt flag and returns UNKNOWN, rather than throwing past the owner and losing the handoff. Keep the parent reservation and token quarantined; a notification exception or full cleanup queue must likewise leave the handoff retained for retry. A pending/unknown/billed request must not be resubmitted under fresh call/item/run IDs, even after editing a draft or app recreation. The existing persistent generation attempt store and central ledger remain authoritative; retain exact account/config/owner identities and original tool call for recovery. No automatic resubmit or recovery-ID generator is added here.

## Evidence and validation

`ControllerGenerationPreparationTest` covers independent profile selection/no URL fallback, all-byte snapshots, raw size/duration preservation with fail-closed unsupported confirmation, no default form profile, edit-then-revert revision rejection, one-shot confirm/cancel, real Runner owner/effect identity, single mixed-task pinning, actual preflight-plan identity callback checks, separate exact host and parent network denial, constructor/tool-prepare zero I/O, and latched UNKNOWN while a real dispatched leaf worker has not drained. Additional regression cases use two latches to force the parent-lock/token-lock ordering and stale ownership return, a real blocked asset publication fence to verify rejected/throwing UI handoff returns before leaf close, failed cleanup notification retention, and interrupted await with interrupt preservation and sticky UNKNOWN after physical leaf drain. These test threads/executors are bounded and joined; production introduces no executor or fallback thread. Existing `RunGenerationToolsTest`/device tests remain the evidence for actual preflight/POST/download/registration/replay behavior. No new test reads credentials, uses the network, runs a shell, or opens a database.

These tests and Kotlin/Compose compilation must be run by the supervising agent; the implementation-only workspace tool cannot run builds. UI is intentionally not reachable in production until the parent performs the integration sequence above.
