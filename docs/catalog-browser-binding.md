# Browser catalog leaf: parent wiring contract

This leaf is implemented against the actual interfaces in
`components/openminis-browser/adapter/src/main/kotlin/app/daiyu/android/browser/openminis/AndroidBrowserRunHost.kt`.
It does not wire public `MainActivity`, `ChatController`, `RunCatalogTools`, or persistence.
The parent must add the calls below; no second dispatcher belongs in this leaf.

## 1. Prepare and explicitly confirm, without a session

Freeze `BrowserRunIdentity` (conversation, run, assistant profile ID, assistant message),
`BrowserSettings`, and `BrowserGrant`. Call:

```kotlin
val preparation = BrowserRunPreparation.prepare(
    identity, settings, grant,
    allowedActions = selectedActions,
    writeArguments = exactWriteArguments,
)
// Trusted UI displays preparation.preview and obtains explicit approval of this snapshot.
val confirmed = preparation.confirm(preparation.preview)
```

Even the first WRITE preview is pure data: no registry instance, session, host, tab, file,
WebView or network is created. `RunBrowserTools.writeEffect(grant, arguments)` uses the
same schema/access/canonical effect algorithm as the session overload. All selected WRITE
actions still require exact effects (including tab creation/closure under READ_ONLY).
FULL is a ceiling, not approval. Model text / `ask_user` responses do not call `confirm`.
An abandoned preview is cancelled; an approved token is explicitly revoked when abandoned.

## 2. Acquire exactly one real host, after confirmation

New production run, on the Android main Looper, with an already app-private existing root:

```kotlin
val browser = CatalogBrowserBinding.open(context, privateRoot, frozenAssistant, confirmed)
```

`open` directly constructs `AndroidBrowserRunHost`; it does not create a tab or navigate.
History uses `OpenMinisHistoryApproval.DENY`, cookie names are denied, and cookie offload
mapping fails closed. Catalog actions do not expose cookies, fetch/download, or child
owners; the adapter's internal backends are not additional model capabilities.

If the parent already owns a run host, do **not** open another one:

```kotlin
val browser = CatalogBrowserBinding.attach(originalHost, frozenAssistant, confirmed)
check(browser.session === originalHost.session)
```

`attach` transfers lifecycle responsibility to the binding while retaining the original
host and its session, including existing tabs. If preparation supplied `existingSession`,
that identical session must be attached; an equal grant on a replacement session is rejected.
The parent remains responsible for any pre-existing host policy and must not use that host
to bypass catalog admission. Do not silently fall back to the legacy `RunBrowserTools.create`.

## 3. Bind only with the runner's original owner and one shared task

The parent catalog waits for its actual `bindOwner(originalOwner)` callback. IDs are not a
substitute for that opaque object. Build the final mixed-catalog task once, using browser's
`requiredWrites`, `sessionId`, and `runId` along with the other already approved leaf effects:

```kotlin
// Inside the parent's actual bindOwner callback, after validating all leaf identities:
val sharedTask = TaskAuthorization(browser.sessionId, browser.runId,
    otherApprovedEffects + browser.requiredWrites)
val boundBrowser = browser.bindOwner(originalOwner, sharedTask)
check(boundBrowser.taskAuthorization === sharedTask)
boundBrowser.tools.forEach { existingRegistry.register(it) }
// Pass the SAME sharedTask to capture/other leaves/the parent's existing catalog bridge.
```

For a browser-only catalog, `browser.bindOwner(originalOwner)` creates and caches its one
task instead. Repeated binds return the identical `ConfirmedCatalog` and task. A replacement
owner or equivalent-but-distinct task is rejected. `createConfirmed` is called here, not in
the binding constructor. The token pins actual owner/session/profile, confirms exact browser
actions and effects, and gates preparation plus execution-context admission, including
confirmed ledger replay. Extra effects in the mixed task do not grant extra browser actions.
The parent continues to own capability filtering, the registry, ledger and **one dispatcher**.

## 4. Revoke and initiate close; observe real completion separately

```kotlin
browser.close() // synchronously revokes token, then requests nonblocking host teardown
val raw = browser.closeStatus() // calls AndroidBrowserRunHost.closeStatus(), no retry/wait
val parentReceipt = browser.closeResult()
```

`revoke()` alone is also available to synchronously seal catalog admission. `close()` then
calls the original host's `closeWithResult()` outside the binding lock. It does not wait for
UI, worker or native drain. Neither a returned close request nor a completed/cancelled command
Future is evidence of teardown. The parent uses its existing bounded observation budget:

- only `raw.successful` maps to `RunCatalogCloseResult.Drained`;
- pending/unrequested without failure maps to `null` (keep observing; timeout stays unknown);
- native failure maps to `Unknown("browser_close_failed")`, even when native `drained` is true;
- an exception while requesting/observing close stays unknown, with the handle retained;
- repeated explicit `close()` may request a retry, but `closeStatus()`/`closeResult()` never do.

No polling thread, coroutine, timer, dispatcher, or automatic retry is created by the leaf.
The parent must not drop its pending handle or infer success from the compatibility `close()`
return. Successful teardown does not change an UNKNOWN execution into a confirmed result.

### Constructor failure retains cleanup authority

The real adapter can throw `BrowserRunConstructionException` after partially acquiring
resources. `open` revokes the token and rethrows the **same exception**, not its cause or a
message-only wrapper. The parent must retain that exception and call its actual
`closeWithResult()` to observe/retry cleanup within its budget. This exception has no
`closeStatus()` method: do not invent one. Its receipt retains the construction failure
code, so even eventual resource drain is not successful run construction. Ordinary failures
before resource acquisition are also rethrown; no legacy fallback is attempted. Never log or
serialize the exception cause, which can contain private filesystem information.

## Verification sources (not executed)

- `BrowserRunPreparationTest`: existing schema/effect/token tests, updated first-WRITE expectation.
- `CatalogBrowserBindingTest`: zero-session WRITE preview, same task/session identity, mixed-task
  admission, single central dispatcher/replay, synchronous revocation, pending/failed/successful
  receipts, close-lock race, and retained construction-exception cleanup capability.
- `CatalogBrowserBindingAndroidTest`: actual `AndroidBrowserRunHost` attach without an implicit
  tab, original session identity through real owner binding, and real close-status observation.

No build, CI, JVM test, instrumentation test, or device command was run for this change.
These are test sources, not a claim of passing verification. The parent wiring described above
is still required and deliberately not implemented in the public/catalog modules here.
