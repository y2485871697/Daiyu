# R09: application-owned terminal UI integration

This change only adds `terminal/TerminalUiOwner.kt`, its JVM contract tests, and this note.
It does not modify `Main`, `ChatApplication`, `TerminalScreen`, or `TerminalEnvironmentHost`.

## Exact integration API

```kotlin
TerminalUiOwner.android(
    context: Context,
    trustedInstallations: List<LinuxInstallation> = emptyList(),
): TerminalUiOwner

val host: TerminalEnvironmentHost
val caller: StateFlow<TaskAuthorization>
val uiSessionId: String
fun openAndroid(conversationId: ConversationId? = null): Boolean
fun openLinux(reference: LinuxGenerationRef, conversationId: ConversationId? = null): Boolean
fun close()
```

The application should create and retain **one** owner, e.g.
`val terminalOwner = TerminalUiOwner.android(applicationContext)`. Merely creating it does not
inspect files, launch a PTY, select Linux, install anything, or perform a download. The default
trusted list is empty and therefore rejects every Linux open.

A route can observe the caller and supply the existing screen without modifying its interface:

```kotlin
val owner = application.terminalOwner // same application-owned object on every entry
val caller by owner.caller.collectAsStateWithLifecycle()
TerminalScreen(
    host = owner.host,
    caller = caller,
    onOpenAndroid = { owner.openAndroid(currentConversationId) },
    onOpenLinux = { reference -> owner.openLinux(reference, currentConversationId) },
    onBack = onBack,
)
```

`currentConversationId` is a real `ConversationId?`, captured from current parent state at the
explicit user action. For an unsaved/blank chat, pass `null`; do not create a database conversation
just to open a terminal. The owner uses its independent `terminal-ui-…` session as a standalone
PTY binding. This is an ephemeral UI namespace, **not** a durable chat or a persistence request.
A later explicit run in a saved chat can bind to that real conversation instead.

Do not create this owner with `remember`, per route, or per Activity. Do not call `close()` from
`DisposableEffect`, cancellation, `onStop`, or `Activity.onDestroy` (including rotation). It is
only for genuine application-owner teardown. An explicit terminal close button already invokes
`host.requestClose(caller)` and is distinct from disposing the owner. Process death is not a
promise of Android lifecycle cleanup callbacks, and a new application instance never auto-replays
a previous run.

## Identity and admission

* `caller` starts with one stable idle `TaskAuthorization` and only changes after `host.open`
  synchronously returns `true`. The very same object (`===`) is in `PtyRunOwner.authorization`;
  never reconstruct it from its string IDs.
* Each explicit new run gets a fresh scope and a fresh authorization. Android bindings use
  `TerminalEnvironmentHost.ANDROID_ENVIRONMENT` and `ANDROID_GENERATION`; Linux bindings use
  the admitted `LinuxGenerationRef`'s exact environment and generation.
* The Boolean is **admission**, not an asynchronous startup-success result. Observe `host.state`
  for opening, running, failure, cleanup and actual termination. An accepted run whose backend
  later fails remains the most recent caller; it is not automatically retried.
* Failed admission, inspection, navigation, Activity recreation and process exit do not replace
  the most recent caller. An unknown input delivery or cleanup still owning resources prevents
  reopening. The owner does not send, retry, replay, or queue any terminal input.
* These authorizations have **empty `allowedWrites`**. They are UI-only PTY ownership identities,
  not model-tool WRITE grants and must not be registered as dispatcher write approval.
* Route all opens through the owner, not directly through the exposed host. The host is exposed
  for the existing screen's observation/input/close APIs, not as an alternative launch path.

## Current Linux trust gate

The factory takes a defensive snapshot of a **fixed, parent-approved configuration valid for
this owner's lifetime**, and passes the same installations to `TerminalEnvironmentHost.android`.
Neither cached `host.state.linux.selected`, `FILES_READY`, choices, nor a persisted selection can
add a trusted reference. The host/backend must still validate the selected installation and own
its lease. This change does not replace the separate trusted-installation host fix.

If parent settings can revoke/change trust during the owner's lifetime, use the constructor's
current-provider seam instead of treating the factory snapshot as live settings:

```kotlin
TerminalUiOwner(
    host = retainedHostWithTrustedInstallations,
    trustedLinux = { currentParentApprovedInstallations.map {
        LinuxGenerationRef(it.environmentId, it.generation)
    }.toSet() },
)
```

The provider is evaluated on **each Linux open**, before admission; it must be a cheap,
thread-safe in-memory parent configuration lookup, never derived from model arguments, cached UI
selection or disk I/O on the main thread. Revocation denies a replacement run even if old
`FILES_READY` state remains visible. The existing host also has its own installation snapshot;
newly added configurations do not thereby become usable by that old host. Supporting live
installation additions requires explicit parent/backend lifecycle design and is not implemented
here. Do not replace an owner while it still owns resources. No installer is supplied.

## JVM evidence and execution status

`app/src/test/kotlin/app/daiyu/android/terminal/TerminalUiOwnerTest.kt` uses the real host and its
worker, with an in-memory backend/resource. It checks stable idle/page re-observation; exact
published identity and constants; blank-chat binding; fresh explicit runs; opening/cleanup refusal;
unknown input with no replay/reopen; current Linux gate versus stale selection; default Linux
denial; disposed host rejection; asynchronous startup failure; and teardown retaining ownership
until join. These are not Activity rotation or Android/native PTY instrumentation tests.

Suggested main-agent verification:

```text
./gradlew :app:testDebugUnitTest --tests 'app.daiyu.android.terminal.TerminalUiOwnerTest'
```

Tests have **not been executed by the implementation subagent** (no shell/build capability).
Compilation and runtime results must be recorded by the main agent. Existing Gradle test setup
may also run native host prerequisites on Linux; the owner tests themselves need no native PTY.
