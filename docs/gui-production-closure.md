# R07 GUI production wiring and drain evidence

This implementation does not change the feature inventory or claim device acceptance.

## Explicit user entry

`MainActivity.openGuiPreparation` is an explicit text-only composer action. It accepts a bounded JSON operation (`tap`, `long_press`, `scroll_forward`, `scroll_backward`, `input`, `back`, `home`, `recents`, `notifications`). Only `input` accepts/requires the complete `text` (0–4096 UTF-16 characters); coordinates, fabricated nodes and observation IDs are rejected. The system-settings button only opens Android settings. It does not enable a service, write settings, or grant access.

`ChatController.prepareGuiSend` first retains `ControllerGuiEntry` with its real conversation/run/assistant/session identities, then calls `GuiRuntime.openForUser`. Its returned session is retained before Main can attach the accessibility confirmation overlay. The user must switch to the actual external application and select/confirm its real target. Merely preparing, entering text, rendering an overlay, or reading a preview never executes an action. Explicit preparation may remain mounted while Main pauses/stops so that switching to an external application is possible; leaving the chat route, changing the editor, cancellation, Activity destruction, service loss and screen lock invalidate the relevant authority. A recreated Activity cannot inherit the old approval. Overlay revocation has a separate original-parent callback even after delivery, stopping that retained task without falsely labelling a potentially delivered action NotExecuted. A throwing transfer is likewise treated as unconfirmed, not as a refusal receipt.

The overlay retains its exact final observation and action, validates the target again, freezes the approved write effect and delivers those same objects. `sendGui` checks the retained session, real identities, operation/full input, observation ID and exact write set. It uses the existing text ticket and durable two-row acceptance path, preserving the persisted draft CAS. `GuiConfirmedModelPort` adds that same bounded evidence to the accepted user's model input, without rewriting the persisted draft or performing another observe. No new Runner, dispatcher, ledger, replacement call ID, or fallback backend is introduced.

The action entry does not grant any additional model observations or screenshots. `screen` remains governed by explicit read grants; `screen_action` is bound to the confirmed observation and exact effect. Ordinary sends have neither the GUI session nor its capability/leaf registration.

## One central authorization and final outcomes

The parent builds one `TaskAuthorization` containing the exact GUI writes. `RunCatalogTools.bindOwner` binds the session to Runner's original opaque owner and that same authorization object. `initialized` registers `GuiScreenToolFactory` in the existing registry/bridge/ledger.

`RunCatalogTools.observeOutcomes` synchronously calls `GuiRunSession.onCentralUnknown` for **any** final central Unknown, including an unrelated leaf and `receipt_persistence_failed`. This occurs before other leaf feedback can throw and before returning to Runner. The central dispatcher already stops fresh later batch execution after Unknown. Subsequent GUI reads/actions/screenshots are fenced before Android I/O; no automatic retry is permitted. Missing/throwing final feedback also fences the GUI conservatively. The short-lived, exact-action GUI route does not advertise `ask_user` or allow USER_BARRIER setup: suspended barrier completion lives outside this catalog observer, so GUI-plus-enabled-barrier setup fails closed. Ordinary interactive sends are unchanged; no domain/barrier API is modified.

## Cancellation is not drain

`close` claims the GUI session as a real catalog resource. `advanceClose` counts it as an eighth independent close observation (the previous seven observations and virtual-display-session conditions are unchanged). The production GUI observer reports Drained only on `releaseConfirmed`, after main-loop overlay removal, retained-node cleanup, capture terminal/resource disposal and lease release. A timeout, failed stop/registration, or inability to post is Unknown, never Drained. Parent accepted/unaccepted retention records and admission remain pinned on unconfirmed cleanup or persistence.

`GuiCaptureRequest.submit` deliberately does not release a capture when `takeScreenshot` throws: delivery may already have happened. The consumer gets an unavailable/unconfirmed result, but the platform reservation persists. A late failure callback is terminal. A late success releases only after every bitmap/buffer disposal succeeded; disposal failures retain the reservation. Consumer timeout/cancellation and service loss do not fabricate capture completion. `GuiRuntime.close` consequently cannot release the foreground slot while that capture remains pending.

## Controller storage retention after send persistence Unknown (P1)

`closeStorageWhenIdle` now includes `guiEntry != null` alongside the existing preparation, accepted-run, generation and virtual-display retention gates. This is a storage-lifetime fix only; GUI entry, authorization, catalog and execution behavior are unchanged.

The missing case is an exception **after entering `database.send`**: the text receipt and original GUI entry become persistence-Unknown, while the unaccepted catalog can still physically drain, remove itself from `unacceptedTools`, and let preparation finish. Physical GUI/catalog release does not confirm the database receipt. The original GUI entry must continue to pin storage and the Controller executor, reject ordinary sends, and prevent `closeDone` acknowledgement. No Unknown is cleared and no retry is introduced. A confirmed accepted run still releases its GUI entry at the existing physical-drain boundary and can close storage normally.

Android `ControllerGuiStorageRetentionTest` exercises the real `sendGui` → SQLite → unaccepted-catalog cleanup → Controller close path. A SQLite trigger aborts the assistant INSERT inside `database.send`, after the user INSERT and draft updates; the test expects Unknown despite SQLite rollback. It waits for the production catalog's Drained receipt, main-loop session release, empty `unacceptedTools` and completed preparation, then verifies original entry/session/catalog identity, ordinary-send refusal even after acknowledging the UI receipt, unchanged draft/no rows/no model open, and unacknowledged close with the **original** SQLite handle, runtime-store identity and usable executor retained. The accepted-send control uses the real Runner and production close observer and expects entry release, acknowledged close, closed original SQLite handle and executor shutdown.

The test follows existing Controller reflection/SQLite-trigger and GUI detached-session injection conventions. Only the trusted, already-confirmed overlay input (retained entry, preparation reservation and exact frozen write snapshot) is installed by test-only reflection; production admission/commit/retention gates and cleanup/persistence outcomes are not replaced or relaxed. It does not enable an accessibility service or establish real-user approval/action success. The Unknown fixture intentionally stays pinned until instrumentation-process teardown; test cleanup does not clear Unknown or fabricate a Controller drain.

## Test source and unverified evidence

Added source coverage:

- JVM `GuiCaptureRequestTest`: submission-throw followed by late success/disposal or late failure; cancellation does not drain; terminal accounting is idempotent.
- JVM `GuiProductionEntryContractTest`: all operation/full-input parameter boundaries and the real model-input wrapper preserving original user/history and catalog definitions.
- Android `GuiCatalogProductionTest`: original owner/same union authorization; ordinary catalog omission; both execute/start central persistence-Unknown paths; barrier fail-closed setup; eighth close observation; real runtime close and the production catalog observer waiting for a submitted-but-thrown capture's late disposal. Runtime drain bookkeeping is injected through reflection without enabling a system service or taking a screenshot.
- Android `GuiMainActivityTest`: real Main parameter/cancel callbacks, disabled-service rejection, unchanged draft/no model/database acceptance, and return to ordinary-send admission.
- Android `ControllerGuiStorageRetentionTest`: send-internal SQLite failure producing persistence-Unknown after physical catalog drain retains the original entry/storage/executor and does not acknowledge close; confirmed acceptance plus physical drain closes normally (test-only trusted-input injection detailed above).

**None of these tests or a build were run in this implementation environment.** Shell/build/device/network tools were unavailable by instruction. This source review does not establish OEM overlay/window-event ordering, real system binding/revocation, API-30 screenshot delivery/disposal, real external-app action success, or device lifecycle acceptance. Those require the parent agent's build/test lane and an explicitly authorized device acceptance run. Platform action acceptance remains explicitly unverified business success.
