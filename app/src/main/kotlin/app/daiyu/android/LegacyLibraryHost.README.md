# R12 legacy library leaf (parent integration contract)

This is a separate compatibility library, not a native conversation importer or a chat runner.
Full/native backups **do not include the compatibility library**. This leaf does **not** implement
library deletion. Do not describe either as supported.

`LegacyLibraryHost` and `ui/screens/settings/LegacyLibraryScreen.kt` are the isolated production
leaf. Main owns navigation, Android document launchers and the existing controller's `dataTask`
serialization. Do not create a new executor, database helper or service-specific database.
Construct the legacy import service with the **same database instance** owned by the controller.
`serviceFactory` receives the host's pre-commit assertion and must wire it into `beforeCommit`:

```kotlin
val host = LegacyLibraryHost(
    resolver = applicationContext.contentResolver,
    serviceFactory = { beforeCommit ->
        LegacyImportService(database = { sameConversationDatabase.writableDatabase }, beforeCommit = beforeCommit)
    },
    gateway = LegacyLibraryHost.Gateway { work, completed ->
        try {
            controller.dataTask(
                importing = false, // compatibility operations must not mutate native chat identities
                failureNotice = "兼容库操作未确认；请在原页面核实，不自动重试。目标可能不完整。",
                completed = completed,
                work = { work(); "兼容库数据操作已结束，请查看本页结果。" },
            )
            true
        } catch (_: Exception) { false } // dataTask rejects before executing work
    },
)
```

Do not inject the convenience `LegacyImportService(store)` constructor: it cannot install this
visit's final pre-commit fence. Do not share the constructed service with an unrelated executor.
The factory is lazy and runs on the existing worker, never in composition.

## Visit and picker ownership

- Create one `host.openPage(ownerCurrent)` for each exact route visit. Never save/parcel a Page,
  snapshot, preview, raw URI, ticket or picker request. `ownerCurrent` must be fast, thread-safe,
  fail-closed and distinguish A -> B -> A. Never replace an old visit's predicate.
- Revoke the old Page synchronously **before** parent navigation, activity teardown, replacing
  the controller or switching away from the route. Screen back/disposal also revokes defensively.
- Keep the exact `SourcePicker` / `SavePicker` passed to the Main hook. A launcher callback must
  consume that captured request, not query whichever Page happens to be current then. Clear the
  Main pending slot before dispatch. Never restore pending requests after recreation.
- Each launcher has at most ONE outstanding slot. **Keep a revoked request as a tombstone until
  its result drains**; do not clear/overwrite it on navigation or launch a new request into that
  launcher while it is outstanding. If a new hook arrives then, call the new request's
  `result(null)` rather than replacing the slot. Otherwise an old Android result could be bound
  to a new page even though the host's own request tokens are correct. After recreation ignore
  restored results without a matching in-memory request; do not launch replacements before the
  lifecycle has drained restored launcher results.
- On launch failure or user cancellation pass `null` to the captured request's result method.
  Do not retain URI grants by default. Source access is read-only `content:` JSON/ZIP; original
  reference strings are never treated as paths, URLs or resolver requests.
- Save uses an explicit SAF `ACTION_CREATE_DOCUMENT` target. Do not silently choose a file,
  reuse a previous target or retry an unknown outcome. Partial targets may remain after failure.
- A history switch or cancellation synchronously invalidates old previews, confirmations,
  pending pickers and worker result publication. Already-admitted durable work is not rolled back
  by navigation; explicitly reload histories to inspect its result.

Main's source hook launches `ACTION_OPEN_DOCUMENT` with read permission only (JSON/ZIP MIME
choices; parser validates actual content). Main's target hook launches `ACTION_CREATE_DOCUMENT`
with `SavePicker.mimeType` and `SavePicker.suggestedName`. No persisted grants, output fallback,
automatic relaunch, or default confirmations. Each callback is exactly:

```kotlin
val captured = pendingSource // analogous pendingSave for the target launcher
pendingSource = null
captured?.result(uri) // null on cancellation; never currentPage.accept(uri)
```

A launch exception must clear only that matching slot and call its captured `result(null)`.
Screen takes `page`, `onPickSource`, `onPickTarget`, and `onBack`; parent creates and remembers
`host.openPage(ownerCurrent)` by its exact visit token, not by route name.

All service calls, source reads and target writes belong inside the existing `dataTask` gateway.
The gateway must report completion on UI, after work settles; it must not run work on a new helper
or publish an old callback through a replacement Page. The gateway should use a fixed redacted
failure notice, and not publish exception messages, raw JSON, original content or URI strings.

## Activity epoch and request-code recipe (for the current Main style)

The inspected Main uses native backup codes 101..106 and browser codes starting at 14000.
Parent should reserve **12000 until 14000** for legacy selections (verify against its integration
branch). Allocate a distinct monotonically increasing code for EVERY source/save launch; never
reuse a code after cancellation or failure. Exhaustion fails closed, never wraps. Save only the
next integer as `legacy-next-code` in instance state, not pending requests, URIs or visit objects.
This prevents an old/duplicate callback from matching a later selection even after the old slot
has drained. On restored activity, unknown callbacks in the reserved range are ignored.

```kotlin
private val legacyEpoch = java.util.concurrent.atomic.AtomicLong(0)
private var legacyPage: LegacyLibraryHost.Page? = null
private var nextLegacyCode = 12000 // restore only from savedInstanceState["legacy-next-code"]
private data class LegacySelection(val code: Int, val deliver: (Uri?) -> Unit)
private var pendingLegacy: LegacySelection? = null // NEVER restore this

private fun enterLegacy() {
    legacyPage?.revoke()
    val visit = legacyEpoch.incrementAndGet()
    legacyPage = host.openPage { legacyEpoch.get() == visit }
    // show legacy route; render LegacyLibraryScreen(page, ::pickSource, ::pickTarget, ::leaveLegacy)
}
private fun revokeLegacy() {
    legacyEpoch.incrementAndGet() // first: terminal worker-visible revocation
    legacyPage?.revoke()
    legacyPage = null
    // Keep pendingLegacy as an inert tombstone until Android returns it.
}
// Call revokeLegacy BEFORE route departure, onDestroy, controller replacement or owner teardown.

@Suppress("DEPRECATION")
private fun launchLegacy(intent: Intent, deliver: (Uri?) -> Unit) {
    if (pendingLegacy != null || pendingBackupRequest != null || nextLegacyCode !in 12000 until 14000) {
        deliver(null); return // do not overwrite another feature's pending slot
    }
    val selection = LegacySelection(nextLegacyCode++, deliver)
    pendingLegacy = selection
    pendingBackupRequest = selection.code
    try { startActivityForResult(intent, selection.code) }
    catch (_: Exception) {
        if (pendingLegacy === selection) pendingLegacy = null
        if (pendingBackupRequest == selection.code) pendingBackupRequest = null
        deliver(null) // consumed; never reuse selection.code or automatically relaunch
    }
}
private fun pickSource(request: LegacyLibraryHost.SourcePicker) {
    launchLegacy(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = "*/*"
        putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "application/zip",
            "application/x-zip-compressed", "application/octet-stream", "text/plain"))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }, request::result)
}
private fun pickTarget(request: LegacyLibraryHost.SavePicker) {
    launchLegacy(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = request.mimeType
        putExtra(Intent.EXTRA_TITLE, request.suggestedName)
        addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }, request::result)
}
```

Place this dispatch BEFORE Main's browser/native-backup branches in `onActivityResult`:

```kotlin
val captured = pendingLegacy
if (captured?.code == requestCode) {
    pendingLegacy = null // consume before delivery, even cancellation or malformed URI
    val ownsSharedSlot = pendingBackupRequest == requestCode
    if (ownsSharedSlot) pendingBackupRequest = null // never clear a different feature's slot
    captured.deliver(if (ownsSharedSlot && resultCode == RESULT_OK) data?.data else null)
    return
}
if (requestCode in 12000 until 14000) return // stale/unrestored legacy result: never guess a page
```

Screen handles its own back and disposal defensively, but parent epoch revocation must happen
synchronously when navigation is requested, not only in a later Compose effect. A single host is
bound to the captured controller/database; do not use a mutable current-controller property in its
factory/gateway closures. Parent's factory/gateway must capture that exact controller instance.

## Presentation and evidence

UI/model/checkpoint content is separate pure text. Text sections are paged in bounded nodes with
all content reachable; no Markdown links, file loading, networking, model or tool dispatch.
An import needs a newly selected URI, exact-source SHA and a single-use confirmation bound to the
current visit/scope. Saving needs a reopened matching history and exact inventory entry/hash/bytes,
then original-byte validation again immediately before owner-checked output admission.

### Source-URI limitation requiring parent review

The supplied service models persist no source content URI. This host rejects all source document
identities selected during its lifetime (also tree/document aliases), and source/reference URI
matches in reopened history, without opening references. It **cannot prove** a newly chosen SAF
target differs from an unrecorded source URI of an import from before this host/process existed.
Do not claim cross-process source-URI exclusion from this API alone. Persisted source provenance
or a service/API extension is needed for that stronger guarantee; a document provider can also
have opaque aliases that cannot be resolved safely by pure URI comparison.

Targeted JVM/Robolectric tests cover terminal visit fencing, one-shot requests, actual Page
picker closures, stale/duplicate callbacks, rejected queues, deferred service construction,
source document aliases, inventory conflicts/unreferenced originals, and independent hash/bytes
validation. They do not substitute for real service/DB import-save integration tests. Tests/builds
are run by the parent agent; this implementation worktree has no shell/build capability. Do not
claim a passing build or device test without the parent's independent result.
