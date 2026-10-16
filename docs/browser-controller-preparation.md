# Browser controller preparation / approval host wiring

## Scope and source of truth

This addition is deliberately **not** automatically mounted or enabled. It adds:

- `ControllerBrowserPreparation.kt`: one pending run's pure snapshot, explicit confirmation, native-construction ownership, cancellation, cleanup and acceptance receipt.
- `ui/BrowserApprovalHost.kt`: opt-in Compose editor and complete scrollable approval dialog.
- `ControllerBrowserPreparationTest`: deterministic worker/main-queue and native-lifetime tests.
- `browser/BrowserApprovalHostTest`: on-device approval-only UI tests (no browser host/network).

It does not edit `ChatController`, `MainActivity`, `RunCatalogTools`, `BrowserRunPreparation`, manifests or catalog declarations. The parent owns those integration changes. Current APIs were checked against `BrowserRunPreparation`, `CatalogBrowserBinding`, `AndroidBrowserRunHost`, `RunCatalogTools`, `ProviderSnapshot`, and the existing Shizuku/text editor lifecycle. No compatibility shim or synthetic `RunExecutionOwner` is introduced.

## 1. Reserve a pure snapshot

Under the controller's existing idle/preparation gate, reserve the run before showing a preview. Keep the exact preparation object, the controller reservation token, and the existing persisted composer owner/generation/revision together. Do not use equality of text alone as an editor identity.

```kotlin
val runId = RunId(UUID.randomUUID().toString())
val frozenProvider = modelSettings.requestSnapshot() // pure validation, no model request
val snapshot = BrowserDraftSnapshot(
    BrowserDraftOwner(conversation?.id, editorGeneration, draftRevision),
    exactPersistedText,
)
val pending = ControllerBrowserPreparation.prepare(
    conversation = conversation,
    draft = snapshot,
    assistant = resolvedAssistantProfile, // resolved now; never fall back to active profile later
    provider = frozenProvider,
    settings = browserSettingsSnapshot, // read the saved preference before this pure step
    grant = BrowserGrant(BrowserOwner(runId.value, BrowserOwnerKind.MAIN), selectedAccess),
    allowedActions = explicitlySelectedActions,
    writeArguments = completeRawWriteJsonObjects,
    runId = runId,
)
```

`prepare()` performs only validation/copying and the production pure write-effect calculation. It creates **no session, registry, host, tab, file, executor, WebView or network request**. `ProviderSnapshot` is already immutable; the assistant's mutable skill list is copied into an unmodifiable list. The preparation freezes conversation, run, assistant-message ID, entire profile, provider settings, browser settings, exact text, selected actions and complete raw write JSON. The run ID defaults to the supplied grant's session ID if not passed explicitly. The future conversation and assistant-message IDs are available through `pending.identity`; persist those, not newly generated replacements.

A FULL preference is a ceiling, not confirmation. `new_tab` and `close_tab` require exact write JSON even under READ_ONLY. An empty session remains empty: do not add an initial tab or navigate as a convenience. `BrowserRunPreparation` rejects unavailable actions and validates writes using the same `RunBrowserTools.writeEffect` as execution. No cookie, history, fetch, child browser owner, or automatic network bootstrap is added.

## 2. Mount the complete approval surface

Mount once beside the existing composer, with the **actual** current editor identity/revision/text and resolved profile. Pass an observed lifecycle `state` value on each controller notification; passing only an unchanged preparation reference is not enough to recompose worker progress.

`BrowserApprovalHost` takes these callbacks:

- `onPrepare(actions, rawWrites)`: reserve the preparation as above; return false without side effects when the controller is not idle or validation fails.
- `onConfirm(preparation, displayedPreview, currentDraft)`: under the same controller admission gate, verify the pending object is identical, the reservation has not been revoked, the current profile/browser/provider settings match the intended frozen run, and the editor still belongs to this exact version. Call `preparation.confirm(displayedPreview, currentDraft)` once, then enqueue the worker below. Return false on rejection.
- `onCancel(preparation)`: invalidate the reservation and call `preparation.cancel()`. Notify observers; do not clear a reservation until its cleanup is actually drained. After transfer use the parent's normal accepted-run stop path instead.

No callbacks are wired to ordinary SEND, image SEND, settings save, `ask_user`, assistant text, dialog visibility or recomposition. No default read/write action is selected. The dialog renders full identity, text, profile prompt, action list, raw JSON, operation and complete effect target/change with selectable text in a scroll container; no ellipsis, maxLines or hash-only substitute. Model credentials are not displayed. Disposing/changing the preview invokes cancellation, but the parent **must continue to own and observe cleanup after the UI disappears**. A freshly mounted host starts disabled.

## 3. Worker construction and durable acceptance

The existing controller worker calls:

```kotlin
val borrowed = pending.openOnWorker(application, existingAppPrivateRoot)
```

This is a worker-only wait. A controlled Android-main Handler runnable invokes the **actual** `CatalogBrowserBinding.open`. Do not hold the controller/UI monitor around this wait, and do not call it on main. The app-private root must already be valid; do not derive paths from model text. Construction creates neither a tab nor a navigation. The method returns null on cancellation, dispatcher rejection or construction failure. That is **not** necessarily a drain receipt.

The binding is borrowed until acceptance. Build the parent's one mixed catalog with this binding, the frozen assistant/provider/IDs and a **single union** `TaskAuthorization` containing `borrowed.requiredWrites` plus other approved leaves. Use the same object for catalog and execution; do not make a browser-only duplicate. Current `RunCatalogTools` accepts `browser = borrowed` and has the actual Runner `bindOwner` callback. Let that callback invoke `CatalogBrowserBinding.bindOwner`; never fabricate, fetch early, or replace an opaque Runner owner. Do not call model/tool execution before durable acceptance.

Use the parent's existing acceptance/stop lock order, on the worker:

```kotlin
val receipt = pending.accept(borrowed) {
    // Inside the existing durable admission gate; recheck reservation/current ownership.
    // Save exact pending.identity IDs, pending.text, pending.assistant and pending.provider.
    // Install the parent run resources/catalog without exposing them before commit.
    // Return the durable saved Conversation, or null ONLY for definite non-acceptance.
    persistAndInstallFrozenRun()
}
```

The shown persistence function is a **parent integration placeholder**, not a new API. `accept()` serializes its admission against `cancel()`. Its callback must not wait for main, re-enter the preparation or invoke Runner/model execution. Establish one consistent parent→preparation lock order; never acquire the parent controller monitor from a different thread while waiting for this callback. A thrown commit is ambiguous and leaves sticky UNKNOWN, not a fabricated rejection. A returned conversation with a different ID is also UNKNOWN. If an existing DB API returns ambiguous outcomes normally, propagate ambiguity by throwing from this callback rather than returning null.

After successful acceptance, ownership of the **identical binding** transfers to accepted parent run resources. `pending.cancel()` and `pending.retryCleanup()` no longer close it; the parent's accepted-run stop/close path and `RunCatalogTools` own it. Do not use `pollCleanup()` to release an accepted run (it returns null for TRANSFERRED). If assembling a mixed catalog fails before acceptance, revoke/close that unaccepted catalog through its existing cleanup owner too; this preparation owns only its browser leaf, not unrelated leaves.

If worker submission itself is rejected, call `pending.cancel()`. When construction succeeds but admission fails, cancellation/`accept` closes the borrowed binding. A concurrent cancellation or interrupted worker cannot publish a late binding: the main runnable retains ownership, sees revocation, closes the late result and only then permits drain observation. Never cancel a Future and discard the preparation as proof of teardown.

## 4. Receipt and cleanup rules

Only `accept()` can create `BrowserSendReceipt`; a button click or completed native construction is not an acceptance receipt. On the UI thread, before removing any composer draft:

```kotlin
if (receipt.consume(currentBrowserDraftSnapshot)) {
    // Clear ONLY that exact submitted text revision using the host's existing draft transaction.
    // Do not delete images/attachments or the currently visible unrelated conversation.
}
```

`consume` checks conversation editor owner, editor generation, revision and exact text and is single-use. Compare/clear must be in one UI/draft transaction. Preserve the original editor identity until handling the receipt; navigating to the newly assigned conversation ID first is a different editor and must not be blindly cleared. A same-text edit-away-and-back has a new revision and is rejected.

For an unaccepted preparation, after cancellation or failure, call `pollCleanup()` on the parent's existing lifecycle observation mechanism:

| Result | Parent action |
|---|---|
| `null` | Pending or not yet closing: retain preparation/reservation/storage. Observe again; no retry implied. |
| `Drained` | Browser resources provably closed (or never acquired). Release this preparation reservation only after other unaccepted leaves also drain. |
| `Unknown` | Retain preparation, native capability and storage. Block replay/new admission through the unresolved reservation. Surface a fixed safe error; never serialize exception causes/private paths. |

`BrowserRunConstructionException` itself is retained, including its real `closeWithResult()` closure. It has **no observation-only `closeStatus` API**; `pollCleanup` observes the last explicit cleanup receipt, and `retryCleanup()` is the explicit retry operation. The retry may release more native resources, but once UNKNOWN was observed it never changes into permission to release/replay within this owner's lifetime. The same sticky rule applies to native teardown failures and ambiguous DB commits. A returned close-request snapshot is not mistaken for a successful binding close: `CatalogBrowserBinding.closeResult` still requires live status.

## Validation

Added host tests cover pure copied input, original-preview and exact-revision confirmation, main-thread exclusion, real read-only empty sessions, in-flight/queued cancellation, worker interruption, partial-constructor capability retention, pending versus UNKNOWN cleanup, transfer, versioned single-use receipts, ambiguous commits, queue/commit rejection, and forbidden action ceilings.

Added Android tests cover FULL not enabling anything on mount, complete long JSON semantics plus scrollability, explicit one-time approval, and same-text/new-revision invalidation. Existing `CatalogBrowserBindingTest` separately exercises real Runner-owned binding, shared task identity, central dispatch and revocation.

Build/test execution is intentionally delegated to the parent integration agent. Suggested focused filters: `app.daiyu.android.ControllerBrowserPreparationTest`, existing `BrowserRunPreparationTest`/`CatalogBrowserBindingTest`, and instrumented `app.daiyu.android.browser.BrowserApprovalHostTest`. No claim of a passing build or device run is made by this implementation-only change.
