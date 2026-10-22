package app.daiyu.android.browser.openminis

import java.net.URI
import java.util.Locale

/**
 * Production gate for the pinned manager's tokenless navigationDeferred and history-writing finish
 * callback. Used by GuardedClient, not an alternative browser implementation. All lifecycle calls
 * are on UI; synchronized also fences retirement from the request thread.
 *
 * A URL already issued/observed in another epoch cannot be used again on this resource: no WebView
 * callback can distinguish its late finish from the new load's finish. Such reuse is quarantined,
 * rather than guessed from timing, WebView.url, or a fabricated callback token. Distinct fresh URLs
 * can be navigated repeatedly, and reads remain available after a completed navigation.
 *
 * Finished means only that a correlated main-frame finish was forwarded. It is NOT a DOM-stable
 * or visually-ready assertion; WebView's finish callback promises neither.
 */
internal class NavigationCallbacks(
    private val live: () -> Boolean,
    private val approveHistory: (url: String, title: String) -> Boolean,
    private val reject: (String) -> Unit,
    private val permits: (String?) -> Boolean = OpenMinisNavigationPolicy::permits,
    private val nowNanos: () -> Long = System::nanoTime,
    private val withoutHistory: (() -> Unit) -> Unit = { it() },
) {
    internal enum class Phase { IDLE, AWAITING_START, LOADING, FORWARDING, FINISHED, QUARANTINED }

    @Volatile
    var phase: Phase = Phase.IDLE
        private set
    private val used = mutableSetOf<String>()
    private val epochUrls = mutableSetOf<String>()
    private val started = mutableSetOf<String>()
    private var current: String? = null
    private var pending: String? = null
    private var directLoadSeen = false
    private var begunNanos = 0L
    private var budgetNanos = 0L

    var manualSequence: Long = 0
        private set
    @Volatile var isManual: Boolean = false
        private set

    @Synchronized fun enterManual() {
        check(phase == Phase.IDLE) { "BROWSER_OPENMINIS_NATIVE_ACTIVE" }
        isManual = true
    }

    @Synchronized fun leaveManual() {
        check(phase == Phase.IDLE) { "BROWSER_OPENMINIS_NATIVE_ACTIVE" }
        isManual = false
    }

    @Synchronized fun manualOverride(url: String, mainFrame: Boolean): Boolean {
        if (!isManual || !available()) return false
        val key = permittedKey(url) ?: return false
        if (!mainFrame) return true
        if (phase == Phase.IDLE) {
            used += key
            epochUrls += key
            pending = key
            directLoadSeen = true
            begunNanos = 0L
            budgetNanos = 0L
            phase = Phase.AWAITING_START
            return true
        }
        if (phase !in setOf(Phase.AWAITING_START, Phase.LOADING)) return fail("BROWSER_OPENMINIS_NAVIGATION_UNKNOWN")
        if (key == pending) return true
        used += key
        epochUrls += key
        pending = key
        return true
    }

    @Synchronized fun cancelManualLoad() {
        if (!isManual || phase == Phase.QUARANTINED) return
        current = null; pending = null; epochUrls.clear(); started.clear()
        directLoadSeen = false; begunNanos = 0L; budgetNanos = 0L; phase = Phase.IDLE
    }

    @Synchronized
    fun begin(url: String, timeoutMillis: Long = 8_000): Boolean {
        if (isManual) return false
        if (!available()) return false
        if (phase != Phase.IDLE) return fail("BROWSER_OPENMINIS_NAVIGATION_UNKNOWN")
        require(timeoutMillis in 1..60_000)
        begunNanos = nowNanos()
        // Fixed upstream completes its deferred with Unit after 30 seconds even on timeout.
        // A later finish must not retroactively convert that timeout to a confirmed operation.
        budgetNanos = minOf(timeoutMillis, 30_000) * 1_000_000
        val key = permittedKey(url) ?: return false
        if (key in used) return fail("BROWSER_OPENMINIS_NAVIGATION_URL_REUSE")
        // The command itself reserves the URL even if loadUrl/start never arrives.
        used += key
        epochUrls += key
        pending = key
        directLoadSeen = false
        phase = Phase.AWAITING_START
        return true
    }

    /** The actual manager loadUrl must match the admitted command; no spontaneous direct loads. */
    @Synchronized
    fun directLoad(url: String): Boolean {
        if (!available()) return false
        val key = permittedKey(url) ?: return false
        if (phase != Phase.AWAITING_START || directLoadSeen || key != pending) {
            return fail("BROWSER_OPENMINIS_NAVIGATION_UNKNOWN")
        }
        directLoadSeen = true
        return true
    }

    /** Modern override identifies the main frame. Subframes never gain history approval. */
    @Synchronized
    fun allowOverride(url: String, mainFrame: Boolean): Boolean {
        if (!available()) return false
        val key = permittedKey(url) ?: return false
        if (!mainFrame) return true
        if (!directLoadSeen || phase !in setOf(Phase.AWAITING_START, Phase.LOADING)) {
            return fail("BROWSER_OPENMINIS_NAVIGATION_UNKNOWN")
        }
        if (key == pending) return true
        // Repeated/same-document main-frame navigation is ambiguous too. Fail before upstream.
        if (key in used) return fail("BROWSER_OPENMINIS_NAVIGATION_URL_REUSE")
        used += key
        epochUrls += key
        pending = key
        return true
    }

    @Synchronized
    fun onStarted(url: String?): Boolean {
        if (!available()) return false
        val key = permittedKey(url) ?: return false
        if (isManual && phase == Phase.IDLE && !manualOverride(checkNotNull(url), true)) return false
        if (!directLoadSeen || phase !in setOf(Phase.AWAITING_START, Phase.LOADING)) {
            return fail("BROWSER_OPENMINIS_NAVIGATION_UNKNOWN")
        }
        if (isManual) {
            manualSequence++
            used += key
            epochUrls += key
            current = key
            pending = null
            phase = Phase.LOADING
            return true
        }
        if (key in started || (key in used && key !in epochUrls)) {
            return fail("BROWSER_OPENMINIS_NAVIGATION_URL_REUSE")
        }
        if (pending != null && key != pending) {
            return fail("BROWSER_OPENMINIS_NAVIGATION_UNKNOWN")
        }
        if (key !in epochUrls) {
            // Some WebView redirect paths have a start without an override callback.
            if (phase != Phase.LOADING) return fail("BROWSER_OPENMINIS_NAVIGATION_UNKNOWN")
            used += key
            epochUrls += key
        }
        started += key
        current = key
        pending = null
        phase = Phase.LOADING
        return true
    }

    /**
     * The ONLY route to upstream.onPageFinished (which both completes its deferred and writes
     * global history). Old, duplicate, unstarted and superseded finishes get no history hook/write.
     * visibleUrl is an additional consistency check, never the sole callback identity.
     */
    @Synchronized
    fun onFinished(url: String?, title: String, visibleUrl: String?, forward: () -> Unit): Boolean {
        if (!available()) return false
        val key = permittedKey(url) ?: return false
        if (phase != Phase.LOADING || key != current || pending != null) {
            // Even an unmatched observed callback taints that URL for future epochs. It must
            // not become eligible merely because we did not forward its earlier finish.
            used += key
            return false
        }
        if (key != keyOf(visibleUrl)) return fail("BROWSER_OPENMINIS_NAVIGATION_UNKNOWN")
        if (isManual) {
            current = null
            pending = null
            epochUrls.clear()
            started.clear()
            directLoadSeen = false
            begunNanos = 0L
            budgetNanos = 0L
            phase = Phase.IDLE
            return true
        }
        // The host may suppress history persistence without forbidding normal navigation.
        val recordHistory = try { approveHistory(checkNotNull(url), title) } catch (_: Exception) { false }
        if (!available()) return false
        phase = Phase.FORWARDING
        return try {
            if (recordHistory) forward() else withoutHistory(forward)
            if (!available() || phase != Phase.FORWARDING) return false
            phase = Phase.FINISHED
            true
        } catch (_: Exception) {
            fail("BROWSER_OPENMINIS_NAVIGATION_UNKNOWN")
        }
    }

    /** Even an upstream 'success' from its timeout must not be published as confirmed. */
    @Synchronized
    fun returned(success: Boolean): Boolean {
        if (isManual) return false
        if (!available()) return false
        if (!success || phase != Phase.FINISHED) return fail("BROWSER_OPENMINIS_NAVIGATION_UNKNOWN")
        current = null
        pending = null
        epochUrls.clear()
        started.clear()
        directLoadSeen = false
        phase = Phase.IDLE
        return true
    }

    @Synchronized
    fun failed(reason: String = "BROWSER_OPENMINIS_NAVIGATION_FAILED") {
        if (phase != Phase.QUARANTINED) fail(reason)
    }

    /** Cancellation/unknown executions are terminal; no late callback can re-arm the manager. */
    @Synchronized
    fun retire() { phase = Phase.QUARANTINED }

    private fun available(): Boolean {
        if (phase == Phase.QUARANTINED) return false
        if (!live()) { phase = Phase.QUARANTINED; return false }
        if (phase != Phase.IDLE && budgetNanos > 0 && nowNanos() - begunNanos >= budgetNanos) {
            return fail("BROWSER_OPENMINIS_NAVIGATION_TIMEOUT")
        }
        return true
    }

    private fun permittedKey(url: String?): String? {
        if (!permits(url)) { fail("BROWSER_OPENMINIS_PAGE_URL_BLOCKED"); return null }
        return keyOf(url) ?: run { fail("BROWSER_OPENMINIS_PAGE_URL_BLOCKED"); null }
    }


    private fun fail(reason: String): Boolean {
        if (phase != Phase.QUARANTINED) {
            phase = Phase.QUARANTINED
            reject(reason)
        }
        return false
    }

    private fun keyOf(url: String?): String? {
        return try {
            val uri = URI(url ?: return null).normalize()
            val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
            val host = uri.host?.lowercase(Locale.ROOT) ?: return null
            val port = when {
                uri.port == -1 || (scheme == "https" && uri.port == 443) ||
                    (scheme == "http" && uri.port == 80) -> ""
                else -> ":${uri.port}"
            }
            // Fragment changes do not produce a new main-frame document. Treat them as reuse.
            "$scheme://$host$port${uri.rawPath.orEmpty().ifEmpty { "/" }}" +
                (uri.rawQuery?.let { "?$it" } ?: "")
        } catch (_: Exception) { null }
    }
}
