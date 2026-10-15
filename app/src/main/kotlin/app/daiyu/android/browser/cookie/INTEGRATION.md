# Trusted cookie capability

`BrowserCookieBackend` implements only `get_cookies` and `set_cookies`; compose it before the upstream backend, never forward these actions to upstream. No raw value is placed in a result, exception cause or log. A failure after admission remains unknown; do not replay mutations.

Constructor (all authority supplied by host):

```kotlin
BrowserCookieBackend(
    sessions, ui, AndroidCookieStore(),
    currentUrl = { wholeLease -> /* resolve live WebView by owner + both generations; UI only */ },
    privateRoot = privateCookieDirectory.toPath(),
    envPath = { wholeLease, actualPrivateFile -> /* trusted existing private tool-env mapping */ },
    authorizeName = { wholeLease, canonicalOriginUrl, name -> /* frozen host grant */ },
)
```

Create AndroidCookieStore on the same main Looper as the WebView. `currentUrl` returns null when the whole execution lease is stale or resource is missing. CookieManager is app-global; lease ownership alone is NOT authorization to disclose cookies for another tenant. Do not replace the explicit name grant with model arguments. Root must exist, have no symlink ancestors, and be app-private/non-renameable by pages, tools, shared-storage users. Mapping is mandatory; there is no guessed `/workspace` location. Reuse the host's actual environment mapping. Mapping callback runs on I/O, not UI. `authorizeName` runs on UI. Injected `io` must be nonblocking serial FIFO with capacity >=512 (default supplied). UI dispatcher must really enqueue, not execute inline.

Get arguments: optional `keywords: List<String>` (<=32, each nonempty printable ASCII <=128), `fuzzy: Boolean` (default false). Exact and fuzzy matching are case-insensitive, on names only. Unknown keys/null/wrong types fail. Only `http(s)://current-host[:port]/` is read, deliberately not path-specific cookies from other paths. Summary contains selected authorized names, count, and real mapped private path; no value preview. `filePath` is the same mapped path.

Env file is UTF-8, atomically renamed on the private filesystem, mode 0600. Safe schema:

```
export BROWSER_COOKIE_0_NAME='name'
export BROWSER_COOKIE_0_VALUE='shell-quoted-value'
```

Each selected cookie gets an indexed NAME/VALUE pair, preserving duplicates without unsafe identifiers. Single quotes use POSIX `'\''` escaping; `$`, backticks and substitutions remain literal. These env contents are secret, never tool text. Successful files persist until that whole lease is canceled/invalidated or the backend closes. Cancellation/close do not await disk; serial cleanup deletes staging/owned target and fences publication. Keep I/O alive to drain cleanup. Failed filesystem cleanup remains tracked for a later explicit cancel/close; no action is replayed. Already submitted CookieManager writes cannot be undone.

Set `cookies` MUST be a strict JSON string containing 1..32 flat objects (input <=32768 chars). Required `name`, `value`; optional `domain`, `path`, `secure`, `httpOnly` OR `http_only` (both together rejected), `sameSite`, `expires` (Unix seconds integer 0..253402300799, rendered RFC1123 GMT). Unknown fields, null, duplicate keys, floats, nested values and trailing input fail. Domain, when provided, must equal the exact current host (case-insensitive, no leading dot). Writes are always host-only: no Domain attribute emitted. Defaults: Path=/, secure=false, HttpOnly=false. Secure/SameSite=None and __Host-/__Secure- constraints are enforced. Control characters, non-cookie-octets, semicolon/header injection are rejected before *any* write. Callback false is failure, never success. Entire batch is prevalidated; callbacks are processed serially and fenced by whole lease and current origin before continuing. A partial batch can remain applied on failure; no rollback or replay.

JVM deterministic tests are in `BrowserCookieBackendTest.kt`, using queued UI/I/O and fake CookieStore. Android platform behavior still needs host integration/device validation.
