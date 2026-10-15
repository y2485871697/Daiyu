# OpenMinis Android run host

Daiyu adapter composition calls the pinned `BrowserUseManager`, not the upstream tab pool.
It is not itself a configured Gradle module or tool registration. Public build wiring belongs
 to task 1; dispatcher/schema/ledger integration belongs to task 6.

## Exact production source selection

Compile these SIX pinned sources from `../upstream/src/main/kotlin/com/openminis/app/browser/`:
`BrowserAction.kt`, `BrowserActionInput.kt`, `BrowserActionResult.kt`, `BrowserUseJS.kt`,
`InterceptedDialogQueue.kt`, `BrowserUseManager.kt`. Compile `adapter/src/main/kotlin/**`
and app browser host sources. Do not compile upstream BrowserHistory.kt, GoogleAuthRouter,
or ui/browser/BrowserExternalSchemeHandler alongside the adapter compatibility definitions.
All 23 preserved upstream manifest entries remain unchanged. Runtime history is the explicitly
independent adapter implementation, not the preserved upstream synchronous history class.
Android, coroutines core/android, JUnit4 and a real org.json implementation for host tests are needed.

## Trusted production lifecycle

Construct ONE `AndroidBrowserRunHost` per run/owner on main Looper with its frozen BrowserGrant,
existing app-private root, explicit cookie environment mapping and origin/name authorization.
Retain that SAME instance across tool calls; stop/child termination closes it. Worker calls execute;
never synchronously wait for the queued port from main Looper. MAIN and CHILD resources each have
0/1/2 tabs; distinct child tasks require distinct trusted owner identities. Shared CookieManager
means this is resource ownership isolation, NOT site-account isolation.

The run host routes fetch to a bounded independent HTTP worker, cookie actions to the dedicated
main-Looper store plus serial private-file worker, and other actions to the pinned manager adapter.
No owner/grant/session parameter comes from the model. Unknown outcomes must never be replayed.

## Current capabilities and boundaries

Body reads page complete innerText with UTF-16 offsets and continuation metadata. All WebView result
strings, diagnostics and real JPEG path share 16000 chars. No base64 image is returned. Cookie values
are withheld from tool text and appear only in the explicitly mapped 0600 private env file.
HTTP fetch reports actual private file path, size and SHA-256, not a fabricated URL or body.

Manager mappings include supported click/type/hover/scroll/JS/wait/collect modes.
The production facade performs pure mapper preflight before selecting/reserving a tab or
entering the execution port. Unsupported modes return NotExecuted without retiring the page.
UA switching is disabled: the pinned manager implicitly reloads without a correlated
navigation token. Do not advertise set_user_agent until that lifecycle is implemented. Local/minis/file/search, coordinate type/hover,
submit=true, read_image=true, navigate.timeout_ms and some wait limits remain unsupported.
Viewport/history native methods are rejected by the same preflight; visible browser takeover UI
is not connected. Lifecycle
supports same-resource lease rebind, but that alone is NOT accepted user-facing UI takeover.

## Navigation, history, and teardown

Native start and navigation callbacks use full-lease registry fences. Request-thread failures post
to UI rather than invert the registry/navigation lock order. Body private frames do not consume
queued dialog diagnostics; later ordinary results can report them. URL reuse is quarantined because
the fixed manager has no callback token; a late callback must never confirm a different navigation.

History consent defaults to DENY. Trusted approval authorizes app-global history admission, not
per-owner isolation. The adapter history store loads and persists on its own bounded serial worker;
getInstance/record do no disk I/O. Admission is not durability. Already accepted events may persist
after close; close never waits for the disk. loadState/persistenceFailed expose async failure.
Approval hooks must be bounded, perform no I/O, and not reenter ownership mutation.

Unknown resources are permanently quarantined. Cleanup enqueue rejection retains original-allocation
responsibility: trusted owner may inspect cleanupFailure and retry rejected enqueue only. Native
destroy is not replayed. The owner must retain the host/allocation until cleanup is accepted or
explicitly record destruction as unconfirmed under permanent UI failure.

## Evidence, not business completion

Exact snapshot9: `/workspace/daiyu-coordination/task-10/checks/phase3-history-tests-2/check.json`:
compile=0, JUnit=0, 164 tests, source_unchanged=true (Debian Kotlin2.4.10/JDK).
The four original real-WebView instrumentation cases and the new unsupported-mode/page-survival
case are authored; the latter has not run on Android. Host preflight tests use fake resources,
not a real WebView. The device-group handoff records final source-bound host results. API26/API37.2,
module/dispatcher/run lifecycle production wiring, visible takeover UI and unsupported actions
remain acceptance work. Do not mark P34/R10 complete from JVM or compilation alone.
