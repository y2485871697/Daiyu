# R07 — independent accessibility backend and explicit authorization host

> Historical leaf/backend review record. The review-ready static-overlay/same-observation increment and exact parent integration/Unknown/drain contracts are in [R07-final-production-completion.md](R07-final-production-completion.md). In particular, the settings-preview-then-switch-page write flow described below is no longer the precise-confirmation path. Neither document claims unperformed parent integration or device validation.

## Scope, sources, and evidence

Task-supplied baseline: `af18af3` (Git metadata is not available to this implementation agent, so the revision itself was not independently checked).

Before implementation, the accessible new worktree was searched for `Gui`, `GUI`, `Accessibility`, `Screen`, `ToolFactory`, and `backend-kit`. There was no preserved GUI implementation/adapter or existing Screen tool factory in the accessible source tree. `THIRD_PARTY/linux-installer/NOTICE.md` and `docs/linux-installer-integration.md` reference an **external** backend-kit location for a different component. The isolated UTF-8 file tool cannot inspect that location. No old Eta live tree, external source, or original-author GUI implementation was read or copied. This is a small, independent Android platform implementation. Existing user-developed/authorized browser, terminal, Shizuku, model and virtual-display components are unchanged. If a separately preserved authorized GUI adapter is later supplied, review it explicitly; do not silently replace it or import unlicensed implementation code.

No shell, build, test runner, device/UI interaction, installation, service enabling, network lookup, CI, or commit command was run by this agent. **Tests below are added, not reported passing. No physical-device result is claimed.** Parent integration and authorized device validation remain necessary.

## Files and guarantees

* `gui/GuiContracts.kt`: immutable owner/observation/action contracts and pure lease gate.
* `gui/GuiAccessibilityService.kt`: real system-bound `AccessibilityService`, on-demand bounded tree, retained node handles, platform node/global actions, optional platform screenshots.
* `gui/GuiRunSession.kt`: user-issued in-memory run lease; immutable explicit authorization freeze; real Runner owner / exact TaskAuthorization binding; cancellable worker-to-main posting.
* `gui/GuiScreenToolFactory.kt`: two contextual RegisteredTools for the **existing** catalog/dispatcher/ledger, not another executor.
* `gui/GuiSystemSettings.kt`: user-click-only system settings and system-governed rebind request.
* `ui/screens/settings/GuiSettingsPanel.kt`: independent status/settings and optional per-run approval page.
* Manifest service declaration, `res/xml/gui_accessibility_service.xml`, and dedicated `res/values/gui_strings.xml`.

The service requires `android.permission.BIND_ACCESSIBILITY_SERVICE`; its exported binding is for the system, not an unprotected app command endpoint. Enabling the service **does not grant the Agent any tool access**. No background tree collection, logs of event text, root, shell, Shizuku fallback, overlay, settings writes, forced keepalive, permission auto-click, automatic retry, or automatic screenshot was added. A service interrupt/unbind/destroy revokes all old leases. A later system connection never restores old approvals.

There is one process-wide screen lease. A second acquire, including one using identical text IDs, is rejected `FOREGROUND_BUSY`. Different leases cannot release or overwrite each other. Keyguard-locked or noninteractive screens reject with `SCREEN_LOCKED_NOT_EXECUTED`; no wake/unlock attempt is made. Service absence rejects `SERVICE_DISCONNECTED_NOT_EXECUTED`. Runtime observation on API 30+ refuses a non-default/unknown display; this is **not** an R08 secondary-display backend.

## Observation and actions

An explicit read approval grants a fixed budget (API maximum 8 observations and 1 screenshot; page buttons grant 1 observation and optionally 1 screenshot). Budgets are replaced, never accumulated; they freeze before model dispatch. Each observation has a fresh UUID, generation, elapsed-time timestamp, active package/window ID, and at most:

* 128 nodes; depth 0–16; 64 children visited per node;
* 512 UTF-16 characters per field and 16,384 aggregate characters across node text/description/class/view-ID;
* explicit `truncated`, node IDs, parent IDs, screen bounds and supported semantic flags.

Password-node text/description is hidden; password targets are refused. This is **not** a guarantee that all sensitive information elsewhere on screen is hidden. No screenshot is embedded in an ordinary observation. Observations expire after 10 seconds. Window/content/text/scroll events invalidate their generation and discard retained handles. New observation publication clears old action approvals.

`GuiAction` includes the **same** `observationId`, operation, exact node ID (for node operations), and complete exact input text. Node IDs are scoped to that observation, never a later tree. Before action dispatch, the host checks the active window/package, refreshes the retained node, checks window/package/bounds/visibility/enabled and bounded captured text/description/class/view-ID identity. Oversized target identity refuses, rather than matching only a prefix. There is no ancestor/coordinate fallback.

Operations: `tap` uses `ACTION_CLICK`; `long_press` uses `ACTION_LONG_CLICK`; `scroll_forward` / `scroll_backward` use node accessibility scrolling; `input` uses `ACTION_SET_TEXT`; `back`, `home`, `recents`, `notifications` use platform global actions. Coordinate tapping, arbitrary swipe paths and clipboard injection are intentionally not advertised. Unsupported node actions return `PLATFORM_ACTION_REJECTED`, not fabricated success. `PLATFORM_ACTION_ACCEPTED_NOT_VERIFIED` means only Android accepted the action request, **not** that the intended UI/business effect was verified.

A user must confirm the exact action from that observation, and its complete `WriteEffect` must also be in the parent's frozen TaskAuthorization. An approval is single-use; the first attempted action invalidates the entire snapshot even on a confirmed negative platform response. Exceptions after delivery begins return Unknown, never a false NotExecuted. Worker cancellation/timeout while still queued prevents execution. Timeout/interruption after starting is Unknown and fences subsequent operations; never automatically retry with a new ID/backend. Closing admission is synchronous; `releaseConfirmed` becomes true only after main-loop lease release. Retain the resource until that receipt, not merely until `close()` returns.

## Screenshot contract

Only `screen(operation="screenshot", observation_id=...)` can request capture, and only after separate screenshot consent. API <30 returns `SCREENSHOT_UNSUPPORTED_API`, with no alternate backend or invented image. API 30+ calls AccessibilityService.takeScreenshot on `Display.DEFAULT_DISPLAY`. Secure windows, OEM policy, rate limits, platform errors and encoding limits remain explicit failures. No MediaProjection prompt, root, hidden API, secondary-display fallback, scaling or synthesized image is used.

The result is PNG bytes in a base64 JSON tool receipt, including actual width/height and the same observation ID. This leaf does **not** claim a separate model image-content/vision transport is wired. Bounds: width ≤2048, height ≤4096, ≤8,388,608 pixels, encoded PNG ≤2 MiB. Hardware buffer and temporary bitmaps are closed/recycled. Late results after UI generation change, revoked lease, locked screen, cancellation or close are discarded. Parent must preserve its existing history/attachment privacy rules; the settings page explicitly discloses that tool results go to the model and may enter task records. Do not convert screenshots into an independently authorized auxiliary-model request automatically.

## Exact parent ToolFactory / catalog seam

No `ToolFactory` symbol exists in the supplied accessible baseline. The concrete new entry point is:

```kotlin
GuiScreenToolFactory.create(session: GuiRunSession): List<RegisteredTool>
GuiScreenToolFactory.CAPABILITY // "gui.accessibility"
```

Definitions are `screen` (READ_ONLY, observe/screenshot) and `screen_action` (WRITE). The existing domain ToolDispatcher requires fixed ToolEffect per definition; combining polymorphic read+write under one definition would violate its contract. If the parent has since introduced a ToolFactory abstraction, have its GUI branch return **these two tools**, not a placeholder Screen implementation and not a second dispatcher.

Parent flow, with existing durable admission/acceptance/lifecycle preserved:

1. Reserve stable session/run IDs using the parent's real task preparation. On an explicit visible user operation, on main, call `GuiRuntime.openForUser(GuiOwner(sessionId, runId))`. Handle Rejected; never steal an active lease. Hold the returned exact `GuiRunSession` immediately, including before acceptance, and close it on cancellation/failure.
2. Pass this unbound session into `GuiSettingsPanel`. The ordinary settings route supplies **no session** and therefore no model grants. User chooses one finite read grant or previews/consents to an exact action. `onObservation` supplies the actual preview to the trusted parent so it may present an explicit `pendingAction`; do not invent a target/node/observation ID. No action is executed by this page.
3. `onConfirmed(session, writes)` receives the same session plus the frozen exact GUI writes. Return true **only after** retaining the session in the existing run resource owner. The parent forms its one final `TaskAuthorization(sessionId, runId, existingExplicitWrites + writes)` and freezes `SessionCapabilities` with `gui.accessibility` only for this explicitly confirmed run. No grant is inferred from the system service being enabled. Ordinary send must not acquire a session or advertise screen tools.
4. The real `RunCatalogTools.bindOwner` path must call `session.bind(originalRunnerOwner, identicalFinalTaskAuthorization)` before registering/advertising `GuiScreenToolFactory.create(session)` in the existing registry/catalog. This bind performs no Android I/O. Never synthesize a RunExecutionOwner, create a separate TaskAuthorization for the leaf, or construct a separate ToolDispatcher/ledger. Each prepared tool context pins both object identities before execution and before ledger replay.
5. Include the same session in the parent's existing creation/dispatch/stop/drain accounting. On stop, replaced owner, abandoned preparation, failed acceptance, controller/page loss or task end, call `session.close()` and retain it until `releaseConfirmed`. A posted cleanup failure is not Drained. No run/permit restoration after process death or system rebind.

The leaf does not modify RunCatalogTools because its shared factory/lifecycle wiring belongs to the parent. Until steps 1–5 are integrated, do not claim model/tool end-to-end availability.

### Important precise-action UX boundary

The settings panel preview reads the **actually active window**, which may be this application's own settings UI. It does not pretend to see a previously foregrounded app. Bringing a confirmation page/window to the foreground may invalidate a foreign-app observation. This fails closed (`OBSERVATION_STALE`), rather than quietly clicking stale coordinates. The parent must supply a separately reviewed precise-consent UX that can satisfy the same-window/generation contract, or limit its initial integration to read-only runs. A read-only model run cannot upgrade itself into a write-capable run: the fixed TaskAuthorization must be frozen from actual user-confirmed effects before that run starts. Do not relax generations, extend grants indefinitely, mutate the frozen authorization, or grant all GUI writes just to make a cross-app demo succeed. Cross-app precise-consent UX and full Controller plumbing are not proven by these leaf files.

## Exact settings-route seam (parent-owned files)

Import `app.daiyu.android.ui.screens.settings.GuiSettingsPanel` and render:

```kotlin
GuiSettingsPanel(onBack = { /* parent's existing return-to-settings transition */ },
    ownerCurrent = { /* same route/page owner still current */ })
```

Map `accessibility` to that page. The existing `accessibility_keep` entry is labelled a forced-keep toggle: the parent should change it to a page/status entry (or an explicit unsupported explanation) and route it to the same system-governed rebind explanation, **not** persist a successful-looking toggle. For `enhancements` (currently “Root 与系统增强”), route only its accessibility subsection here or use a separate parent explanation; this leaf does not implement root/system enhancement authority. Do not redirect overlay, terminal, Shizuku or R08 settings to GUI grants. Existing global catalog/routes, MainActivity and Controller were intentionally not edited here.

`GuiSystemSettings.openFromUser` opens system service details with fallback to the generic accessibility settings page, only on explicit click. `requestRebindFromUser` first checks Android's enabled-service list, then calls `AccessibilityService.requestRebind`. A successful request is not a connected-service receipt. There is no boot receiver, background retry, root keepalive or silent re-enable.

## Added tests and remaining verification

Added (not executed by this implementation agent):

* `GuiLeaseGateTest`: disconnected/rebound revocation; same/different-owner contention; stale close cannot release a new owner; finite read/screenshot budgets; exact node/text/operation/observation effects; generation/expiry/new-observation invalidation; defensive authorization snapshot copies.
* `GuiScreenToolFactoryTest`: distinct fixed catalog effects; strict observe/screenshot shape; exact WRITE normalization; malformed/expanded argument rejection; explicit observation ID/truncation and no implicit image in observation serialization.

Parent should compile first, then run these host tests through the authorized project test setup. Required **separately authorized** device tests include system enable/disable/interrupt/rebind, disconnected actions never delivered, two-run screen contention, lock-screen refusal, real tree size limits/password fields, node actions/global actions with visible verification, stale tree refusal, cancellation/UNKNOWN no retry, API 26–29 screenshot refusal, API 30+ screenshot consent/platform errors/secure windows, and real Controller stop/drain ownership. Do not enable the user's service or manipulate their current UI merely to obtain test evidence without separate authorization.
