# R07 final GUI increment — review-ready, not an end-to-end completion claim

## Scope and evidence

This document describes the implementation-agent GUI increment only. The task supplied baseline `7176f43` with reviewed backend `6eadee9`; a later instruction identifies parent main `8058f20`. Git metadata, shell, build/test execution and devices are not available to this agent, so these revisions and integration results were **not independently verified**. Existing files were changed with exact replacements, not whole-file overwrites. MainActivity, ChatController, RunCatalogTools, root/notification permissions and unrelated voice/memory/timestamp code were not edited by this increment.

Reviewed local sources: `docs/R07-gui-production.md`, `gui/GuiContracts.kt`, `GuiAccessibilityService.kt`, `GuiRunSession.kt`, `GuiScreenToolFactory.kt`, `GuiSystemSettings.kt`, `GuiSettingsPanel.kt`, service XML and dedicated GUI tests. This is a continuation of the independent Android implementation, not imported third-party GUI code. This document supersedes the old document's statement that there is no overlay and that settings-preview/switch-page confirmation is the precise-write path. The prior leaf bounds, semantic-action-only policy, no coordinate fallback, no automatic retry, and screenshot limitations still apply.

**Helpers existing is not production completion.** Real Main/Controller/catalog wiring remains parent-owned. No build, unit test, installation, APK, branch, push, UI operation or physical-device validation was performed here. The parent must review/integrate and run the final unified tests. No credentials were read or requested.

## New exact-confirmation path

`GuiConfirmationOverlay.kt` is service-hosted `TYPE_ACCESSIBILITY_OVERLAY`, touchable and `FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL`; it does not start an Activity, open a permission prompt or request `SYSTEM_ALERT_WINDOW`. The parent opens it only after a visible user action, with an already-retained prepared session. The user switches to the actual external app themselves, reads a bounded candidate tree, and selects a real semantic target. The operation and complete input are proposals, never blanket write permissions.

The overlay then renders its confirmation content **before** taking the final observation: actual package/window, target class/view-ID/text/description/bounds, exact operation and all input characters (maximum 4096 UTF-16 characters, no ellipsis). A scrollable native text area holds long content. No text, button state, observation ID, navigation or dismissal is changed on successful final capture or confirmation. The 350 ms queued capture is merely layout/event settling, not a guarantee of quiescence: late content/window/text/scroll events still invalidate the observation and require confirmation again.

The final bounded observation must contain exactly one target matching every displayed target field and semantic flag; a changed package/window, duplicate, missing node, password, disabled target or truncated tree is refused. Node IDs may be different from the provisional picker tree; only the **final** actual ID is delivered. A global operation is tied to that actual external application window, not to a fabricated node.

At the user's confirm click, `validateExactTarget` reacquires the actual external root and makes a complete bounded traversal to verify uniqueness against that final observation, without publishing a new observation. Dispatch checks it again, in addition to the reviewed retained-node refresh/identity checks. There is no coordinate/ancestor fallback, generation reset, generation relaxation, extended expiry, or ignoring real target events. The final observation still expires after 10 seconds. No subsequent model observe is required or authorized by this write path.

Root verification refuses own-package Activity/overlay roots, blank/oversized package names, unavailable/non-active/non-application windows, and non-default/unknown displays on API 30+. This deliberately fails closed on OEMs where the platform cannot expose the external root while an overlay exists.

The original window/content/text/scroll event classes still invalidate generation even if attributed to our own package. Real-target click/long-click/focus/selection events are additionally subscribed and invalidate generation. Only these **newly added local interaction classes** may be excluded when all checks prove they belong to this exact attached panel: matching own package, matching panel accessibility window ID, and a system-reported `TYPE_ACCESSIBILITY_OVERLAY` window of that ID. This is not a package-wide event suppression; failure to prove identity falls back to invalidation. Real external target events cannot match that panel window. This narrow distinction prevents pressing the confirmation button itself from clearing the observation, without loosening the previously reviewed invalidators.

Service interrupt/unbind/destroy/reconnection revokes the old lease. Secure accessibility-state content observers (read/listen only), plus a system-enabled check before Android screen I/O, revoke on permission loss; no system setting is written. Failure to register revocation observation does not advertise a connected host. Reconnection never restores approvals.

Settings no longer offers the old preview-then-switch-page exact-action path. A pending action is only an operation/input proposal for a new overlay selection; its old observation/node ID is not reused. A settings page with `session == null` never opens a prepared run or grants model tools. Read-only settings approval is still separately available and must not be called an exact-write completion.

## Exact parent interface

All preparation/user-approval calls below run on main. The parent owns the preparation before opening the overlay, not only after confirmation:

```kotlin
val opened = GuiRuntime.openForUser(GuiOwner(realSessionId, reservedNewRunId))
// On Value, immediately retain opened.value in the parent's prepared-resource owner.
val session: GuiRunSession = /* retained Value */
session.prepareExactActionFromUser(
    operation = proposalOperation,
    text = completeInputOrNull, // INPUT iff non-null, <=4096 UTF-16 characters
    ownerCurrent = { /* this exact prepared owner is still current */ },
    onNotExecuted = { code ->
        // No confirmation was transferred. Update the matching preparation only;
        // callbacks can arrive late. Close/cancel preparation and retain until releaseConfirmed.
    },
    onConfirmed = { delivery: GuiConfirmedDelivery ->
        // Called once after exact user confirmation and session authorization freeze.
        // Retain this SAME session in the new run lifecycle; do not navigate/re-render
        // or dismiss/change the overlay here. Deliver modelContext() as prepared context.
        // Return true only after ownership/acceptance by the parent is secured.
        // A false return must mean the parent did NOT start an execution.
        acceptPreparedGuiRun(delivery) // parent-owned, not supplied by this leaf
    },
)
```

`GuiConfirmedDelivery` contains:

* `session`: the exact prepared object;
* `observation`: the same immutable bounded final `GuiObservation` approved on the overlay;
* `action`: exact final observation ID, operation, actual node ID if applicable, complete input;
* `writes`: the frozen exact GUI `WriteEffect` set;
* `modelContext()`: bounded observation JSON plus explicit `screen_action` argument JSON and a warning not to observe again/retry Unknown;
* `actionArgumentsJson()`: schema-valid action arguments; absent node/text fields are omitted, not inserted as JSON null.

The overlay freezes via `session.freezeAuthorizationFromUser()` before calling `onConfirmed`. It makes **no model call, dispatcher, ledger or alternate executor**. Model context must go to the parent's **new execution run with the already-reserved run ID**, not an old read-only run and not an auxiliary-model request. The parent must not ask the model to observe again to reconstruct the target: new observation publication clears the approved action. If acceptance/dispatch is slow enough to expire or if the target changes, the action receipt must be explicit NotExecuted, and a new explicit confirmation is necessary. Do not copy approvals into another run or synthesize another observation.

The settings optional entry is `onExactConfirmed: (GuiConfirmedDelivery) -> Boolean`; it has the same contract. The page transfers its preparation before the user leaves for the external target. Parent ownership must survive that deliberate page disposal but **not** owner replacement/cancellation. Do not use a page-local `active` flag that becomes false merely because the user deliberately left the settings page; `ownerCurrent` must identify the retained preparation. Ordinary read-only `onConfirmed(session, writes)` is unchanged and is not the precise-write callback.

### Freeze / bind / catalog invariants

The parent forms its **one final, non-competing** `TaskAuthorization(realSessionId, reservedNewRunId, existingExplicitWrites + delivery.writes)`. Only this explicitly confirmed run gets capability `GuiScreenToolFactory.CAPABILITY` (`gui.accessibility`). Merely enabling the service is not authorization. Ordinary send must neither acquire a GUI lease nor advertise these tools.

The real `RunCatalogTools.bindOwner` path calls:

```kotlin
session.bind(originalRunnerOwner, identicalFinalTaskAuthorization)
val tools = GuiScreenToolFactory.create(session)
// Register these in the existing registry/catalog/dispatcher/ledger only.
```

`originalRunnerOwner.runId.value`, authorization task ID and the session's reserved run ID must match; session ID must match too. Authorization must contain all frozen GUI writes. The session pins **object identity** of both Runner owner and the final authorization; different replacement objects, even textually equal, are refused. Bind performs no Android I/O. Never make a leaf-only TaskAuthorization or synthesize a Runner owner. Each contextual prepared tool checks admission before execution and ledger replay.

### Central Unknown: pure no-I/O fence

```kotlin
session.onCentralUnknown()
```

Forward **every final central Unknown associated with this GUI run**, including `receipt_persistence_failed` after the leaf apparently returned a confirmed value. Do this synchronously before another admission/replay, not merely for leaf platform exceptions. The endpoint is monotonic/idempotent, uses only atomic memory state, does not post to main, inspect a root, release resources, allocate a call ID, or retry. Session admission and worker queue entry check it; queued work checks again on main before its Android block. Local Unknown is fenced before a following main-loop operation can enter. A fence is not a drain receipt. Never change call ID/backend or observe to recover/retry an Unknown.

### Close / drain

```kotlin
session.close()                  // synchronous admission revocation
session.releaseConfirmed       // poll/await through parent's existing drain accounting
```

Close the retained preparation/session on abandoned acceptance, user cancellation, owner replacement/loss, stop, failed creation, end of task and final run cleanup. Do not confuse deliberate settings-to-external-target transfer with an abandoned owner. Do not dismiss/re-render the overlay before the confirmed action dispatch: it stays static until close.

`close()` schedules main-loop cleanup if necessary. `releaseConfirmed` becomes true only after the overlay is actually removed, the lease is released, and any already-started platform screenshot callback has completed/disposed its buffers. A pending screenshot keeps the global slot until drained; late results are discarded under the revoked lease. If posting fails, removal fails while still attached, or the platform capture callback never returns, **do not report Drained**. Retain the resource; repeated close may retry removal. Old-lease cleanup cannot release a new owner's lease. A user cancel after transfer closes subsequent admission but cannot promise rollback of an action already delivered; the static cancel label says this explicitly.

Before transfer, expiration/cancel/service loss produces a not-executed completion callback after cleanup is attempted. The parent must check the matching preparation identity when handling that potentially late callback. Target changes are visibly rejected with reconfirmation instructions; no dispatch has begun. After transfer, expiry/staleness is returned by the actual tool admission/dispatch as NotExecuted, not fabricated success. After delivery begins, uncertainty is Unknown, not NotExecuted.

## Review/test evidence and remaining validation

Added `GuiProductionConfirmationTest.kt` covers callback ticket invalidation, final-capture unique matching/renumbering, target text/bounds/flags/package/window switches, duplicate/truncated target refusal, service loss/cancel/old-owner cleanup, central Unknown fencing of actual session observe/action/screenshot entry **without Android calls**, monotonic Unknown/close behavior, same-observation/full-input delivery and action JSON round-trip through the existing catalog. Existing gate/factory tests remain applicable. These tests were **added and source-reviewed, not executed** by this agent. They do not emulate real accessibility event scheduling, OEM overlay behavior or the actual parent acceptance/drain pipeline.

Parent final validation must include compilation/unified JVM tests and an authorized device test: external app target and full long input; real content/window/scroll/text changes before and after confirm; duplicate target; target switch; old callbacks after cancel/owner replacement; permission disable and service disconnect/reconnect; >10-second acceptance delay; cancel before and after delivery; central receipt persistence failure and blocked subsequent/replayed/new-ID calls; stop with queued main work and with a pending screenshot callback; successful drain and explicit non-drain when cleanup cannot be proved. Verify the overlay does not steal focus and stays unchanged through actual dispatch. Secure-window screenshot refusal and no fallback remain required.

Until those parent integrations and device checks are completed and recorded, the accurate status is **review-ready GUI completion increment, not verified production end-to-end GUI availability**.
