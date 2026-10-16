# Browser explicit confirmation → central catalog

## Status and boundary

`RunBrowserTools.createConfirmed` is an opt-in central adapter entry point. It now
retains the **whole** `BrowserRunAuthorization`, rather than reducing confirmation
to `TaskAuthorization.allowedWrites`. The existing `create(session, owner,
taskAuthorization)` remains the separate host-reviewed legacy path. It does not
acquire explicit-confirmation semantics automatically.

This change does **not** wire a production confirmation UI, `Controller`,
`RunCatalogTools`, settings save, or a database. A caller still has to choose the
new entry point explicitly. Do not describe merely saving FULL access, or calling
the legacy entry point, as user-confirmed browser execution.

No entry point here creates a WebView, session, tab, host, network client, or child
browser owner. The parent owns the actual `BrowserToolSession`, its lifecycle and
the real `RunExecutionOwner` issued by `ChatRunner`. Tests use the real adapter,
facade, dispatcher and ledger with fake lifecycle/port implementations; they are
not evidence of an end-to-end Android UI integration.

## Host sequence

1. Freeze a `BrowserRunIdentity` with conversation ID, run ID, assistant **profile**
   ID and assistant reply message ID. Freeze settings, the grant, the selected
   action subset and each exact write argument set in `BrowserRunPreparation`.
   The grant must be a MAIN owner whose session ID equals the run ID. FULL is a
   ceiling, not a write grant. Cookie/fetch/download and child paths are excluded
   from this confirmation workflow.
2. Display the preparation's actual `preview` in trusted UI. Call
   `preparation.confirm(preparation.preview)` only on an explicit confirmation.
   Confirm/cancel consumes the preparation. Model content and a settings save
   must never call this on the user's behalf.
3. Supply the real runner owner, trusted `AssistantProfile`, the same actual
   session and the returned confirmation token:

   ```kotlin
   val binding = RunBrowserTools.createConfirmed(
       session = existingSession,
       owner = actualRunnerOwner,
       assistant = frozenAssistantProfile,
       confirmed = confirmedSnapshot,
   )
   val registry = ToolRegistry()
   binding.tools.forEach { registry.register(it) }
   val dispatcher = ToolDispatcher(
       registry.snapshot(),
       existingSessionCapabilities,
       existingToolLedger,
   )
   // After the model's whole batch and protocol terminal are confirmed:
   val receipts = dispatcher.dispatchOwned(
       binding.taskAuthorization, invocations, actualRunnerOwner, cancelled,
   )
   ```

   These are existing host-owned inputs, not instructions to manufacture a
   temporary session, runner token, ledger or successful execution port. Browser
   capabilities still have to be enabled in `SessionCapabilities`. Keep
   `binding.taskAuthorization` and `binding.tools` together: the adapter requires
   that same task object, not a copied or independently widened authorization.
   This example is an isolated browser binding; mixed-adapter catalog/task
   composition must be reviewed separately, not assumed to be wired here.
4. Retain the confirmation token and call `confirmedSnapshot.revoke()` when its
   authority ends (for example cancellation, owner/profile replacement or session
   teardown). Revocation is monotonic and idempotent. There is no reset API, no
   persistence/restore path and no automatic reconfirmation. A new explicit
   confirmation requires a new preparation and trusted user action. Revocation
   is an admission boundary, not rollback or interruption of an already-entered
   browser effect. The host still owns cancellation, serialization and cleanup.

## Two admission stages

At creation, the token validates all frozen identity fields, grant equality and
session identity. Task binding also pins the opaque runner owner object and the
actual session object: equal IDs/grants do not authorize a replacement owner or
session. Read and write preparation may omit the session; the first task binding pins
the real session. If preparation supplies an existing session, binding must
retain that exact object. Write effects use the pure frozen-grant overload.
The assistant profile is trusted creation-time data, not a model argument;
`ToolExecutionContext` itself has no assistant-profile field.

Before the existing catalog command preparation, each tool calls the retained
token's `checkInvocation`. This checks revocation, identity/session binding, the
selected action subset, read/write tool classification and exact confirmed write
effect. The normal catalog schema/access validation still follows. Invalid
preparation becomes the dispatcher's `NotExecuted("preparation_failed")` without
ledger access.

`PreparedTool.contextual(..., admit = ...)` repeats admission with the actual
`ToolExecutionContext`, including the exact owner and task objects and matching
invocation name/arguments. The confirmation check is retained inside this
admission, not just inside execution. `ToolDispatcher` runs every plan's admission
before **any** ledger transaction, reservation, or `CONFIRMED` receipt replay.
This also rejects a token revoked after preparation. A confirmation denial at
this stage is `NotExecuted("browser_confirmation_not_authorized")`. Fresh
execution checks the token again before entering the facade; this is not used as
a substitute for pre-ledger admission.

The encoding is unchanged: `RunBrowserTools.writeEffect` uses the production
catalog schema/canonical arguments and the existing `WriteEffect` representation
(`browser`, action, length-prefixed session/owner-kind target, canonical change).
There is no parallel confirmation protocol or wildcard write grant. Explicit
`tab_id` is required for writes except `new_tab`; tab creation/closure are writes
even under a READ_ONLY ceiling. Equivalent JSON key order is not a different
effect. Changed URL, tab, action or options need their own exact confirmation.

The central ledger still owns deduplication: matching `CONFIRMED` effects replay
only after admission, while `UNKNOWN` effects must not be resent under the same
or a fresh provider call ID. This adapter does not reopen a tab, retry an uncertain
operation, grant permissions, or invent a successful receipt.

## Verification sources

- `BrowserRunPreparation.kt`: pure preview/confirmation, identity and grant
  checks, exact effects, first binding, monotonic revocation.
- `RunBrowserTools.kt`: explicit/legacy entry points, pre-prepare gate, contextual
  admission, production schema/effect encoding, existing facade execution.
- `core-domain/.../tools/ToolDispatcher.kt`: whole-batch preparation/admission
  precede `ledger.reserve`; `CONFIRMED` replay happens only afterwards.
- `BrowserConfirmedCatalogTest.kt`: real dispatcher plus counting ledger proves
  no ledger access on wrong owner/task/action/effect; a wrong assistant profile
  is rejected even earlier, at binding. Covers allowed execution/effect replay,
  revocation before prepare, revocation between real prepare and admission before
  read/write `CONFIRMED` replay, and UNKNOWN protection under fresh IDs. The
  between-stage test uses only a timing wrapper; it returns the real adapter's
  prepared plan unchanged and never synthesizes receipts.
- `BrowserRunPreparationTest.kt` and `RunBrowserToolsTest.kt`: supporting pure
  preparation and legacy adapter regression coverage.

Tests must be built/run by the integrating parent. Source additions alone are
not a claim that tests passed or that the confirmation UI is deployed.
