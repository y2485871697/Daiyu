# OpenMinis browser staging

This directory preserves 20 **unmodified upstream Kotlin files**, GPLv3 LICENSE,
and the referenced string resources in English, Simplified Chinese and Traditional
Chinese from `OpenMinis/OpenMinis@4ef29002e88db1e20e462ec2ff46916e8a7dcb45`.
`source-manifest.json` binds every source byte and each selected resource node.
Resource XML is serialized from original nodes; names/format arguments are unchanged.
Original `com.openminis.app` packages remain deliberately unchanged for source comparison.

This staging directory is **not configured as a Gradle module and is not runnable**.
No Eta `AgentBrowserSession`, `ChildBrowserSession`, `ChildBrowserSupport`,
`ChildBrowserDownloads`, `BrowserHistoryStep` or empty logger is copied.
The old candidate has 19 upstream-derived files; its `BrowserHistoryStep` is not
upstream. `GoogleAuthRouter` is the genuine additional upstream file; it is retained
as source, **not enabled**. It automatically opens an external Custom Tab upstream.

## Integration responsibilities

Task 1 wires the exact SIX pinned manager sources plus `adapter/src/main/kotlin/**`
and the app browser sources into the app source set; see `adapter/README.md`.
The selected runtime needs coroutines core/android, not the upstream Compose UI,
global tab pool, Google router or Custom Tabs. Keep minSdk 26 and API26/API37.2 regression.
Do not compile the entire preserved upstream directory or its duplicate host definitions.

The adapter supplies a real `AndroidBrowserRunHost` composition for the pinned manager,
CookieManager and bounded HTTP downloader. It is not yet wired into the app build/catalog
or run lifecycle. Same-resource lease rebind exists; visible user takeover UI does not.
Only host seams are replaced: path resolution, logging, private session storage, opening
callbacks, external navigation, and tool input/output conversion. Local file/minis/search
navigation stays disabled until an explicit path capability exists. Raw cookie values and
arbitrary fetch bytes must not reach tool messages/logs. Unsupported parameter modes and
UA switching are rejected before native dispatch, not by destroying an existing page.

Task 6 owns browser_use registration/ledger; Task 19 owns repository-wide NOTICE
and source inventory. This staging is a source delivery, not completed business acceptance.
