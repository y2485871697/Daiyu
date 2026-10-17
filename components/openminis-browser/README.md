# OpenMinis browser staging

This directory preserves 20 **unmodified upstream Kotlin files**, GPLv3 LICENSE,
and the referenced string resources in English, Simplified Chinese and Traditional
Chinese from `OpenMinis/OpenMinis@4ef29002e88db1e20e462ec2ff46916e8a7dcb45`.
`source-manifest.json` binds every source byte and each selected resource node.
Resource XML is serialized from original nodes; names/format arguments are unchanged.
Original `com.openminis.app` packages remain deliberately unchanged for source comparison.

This directory is not a standalone Gradle module. The app build **does compile
six pinned upstream sources** from this snapshot via `StageBrowserCore` in
`app/build.gradle.kts`: `BrowserAction.kt`, `BrowserActionInput.kt`,
`BrowserActionResult.kt`, `BrowserUseJS.kt`, `InterceptedDialogQueue.kt`, and
`BrowserUseManager.kt`. It also includes `adapter/src/main/kotlin` and the app's
browser host sources. The remaining preserved upstream files are not thereby enabled.
No Eta `AgentBrowserSession`, `ChildBrowserSession`, `ChildBrowserSupport`,
`ChildBrowserDownloads`, `BrowserHistoryStep` or empty logger is copied.
The old candidate has 19 upstream-derived files; its `BrowserHistoryStep` is not
upstream. `GoogleAuthRouter` is the genuine additional upstream file; it is retained
as source, **not enabled**. It automatically opens an external Custom Tab upstream.

## Integration responsibilities

The app build wires the exact SIX pinned manager sources plus `adapter/src/main/kotlin/**`
and the app browser sources into the app source set; see `app/build.gradle.kts` and
`adapter/README.md`. The component/commit/source-path/license mapping is preserved in
`THIRD_PARTY/license-bundle/source-index.md`; the existing complete `assets/licenses/GPL-3.0.txt`
is byte-identical to the fixed upstream GPL LICENSE, with this project's source/transform
notice at `assets/licenses/openminis/NOTICE.md`.
The selected runtime needs coroutines core/android, not the upstream Compose UI,
global tab pool, Google router or Custom Tabs. Keep minSdk 26 and API26/API37.2 regression.
Do not compile the entire preserved upstream directory or its duplicate host definitions.

The adapter supplies a real `AndroidBrowserRunHost` composition for the pinned manager,
CookieManager and bounded HTTP downloader. Its source set is now wired into the app build;
that compilation fact does not by itself prove catalog, run-lifecycle or device acceptance.
Same-resource lease rebind is present; see the current app integration tests and the single
refactor checklist for business/UI acceptance rather than treating this README as a pass report.
Only host seams are replaced: path resolution, logging, private session storage, opening
callbacks, external navigation, and tool input/output conversion. Local file/minis/search
navigation stays disabled until an explicit path capability exists. Raw cookie values and
arbitrary fetch bytes must not reach tool messages/logs. Unsupported parameter modes and
UA switching are rejected before native dispatch, not by destroying an existing page.

Task 6 owns browser_use registration/ledger; Task 19 owns repository-wide NOTICE
and source inventory. This staging is a source delivery, not completed business acceptance.
