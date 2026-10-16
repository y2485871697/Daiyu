package app.daiyu.android.integration.tools

import android.content.Context
import app.daiyu.android.assistants.AssistantProfile
import app.daiyu.android.browser.BrowserRunAuthorization
import app.daiyu.android.browser.host.BrowserToolSession
import app.daiyu.android.browser.network.BrowserCloseResult
import app.daiyu.android.browser.openminis.AndroidBrowserRunHost
import app.daiyu.android.browser.openminis.OpenMinisHistoryApproval
import app.daiyu.android.domain.runtime.RunExecutionOwner
import app.daiyu.android.domain.tools.TaskAuthorization
import app.daiyu.android.domain.tools.WriteEffect
import java.nio.file.Path

/**
 * One confirmed browser leaf, not a dispatcher. Construction never binds a synthetic runner
 * owner, creates a tab or navigates. The parent registers bound.tools in its existing registry
 * and passes bound.taskAuthorization (the identical object) to its one catalog/dispatcher.
 *
 * close() revokes admission synchronously, then requests nonblocking native teardown. Returning
 * from it is NOT a drain receipt: only a later successful closeStatus() proves successful close.
 */
internal class CatalogBrowserBinding private constructor(
    private val host: Host,
    private val assistant: AssistantProfile,
    private val confirmed: BrowserRunAuthorization,
) : AutoCloseable {
    /** Deterministic lifecycle seam; production always wraps the actual Android run host below. */
    internal interface Host {
        val session: BrowserToolSession
        fun closeWithResult(): BrowserCloseResult
        fun closeStatus(): BrowserCloseResult
    }

    private class AndroidHost(private val original: AndroidBrowserRunHost) : Host {
        override val session: BrowserToolSession get() = original.session
        override fun closeWithResult() = original.closeWithResult()
        override fun closeStatus() = original.closeStatus()
    }

    val session: BrowserToolSession get() = host.session
    val requiredWrites: Set<WriteEffect> = java.util.Collections.unmodifiableSet(
        confirmed.preview.writes.map { it.effect }.toSet())
    val sessionId: String get() = confirmed.preview.grant.owner.sessionId
    val runId: String get() = confirmed.preview.identity.runId.value
    private val lock = Any()
    private var closed = false
    private var owner: RunExecutionOwner? = null
    private var bound: RunBrowserTools.ConfirmedCatalog? = null
    private var closeRequests = 0
    private var requestFailed = false
    private var statusFailed = false

    /** Only call from the parent's actual bindOwner callback, with its original opaque owner.
     * For a mixed catalog, authorization is the parent's final union, NOT a per-leaf copy.
     * Token admission still pins owner/session/profile and checks each exact browser effect.
     */
    fun bindOwner(
        owner: RunExecutionOwner,
        authorization: TaskAuthorization? = null,
    ): RunBrowserTools.ConfirmedCatalog = synchronized(lock) {
        check(!closed) { "browser_binding_closed" }
        check(this.owner == null || this.owner === owner) { "browser_owner_already_bound" }
        bound?.let {
            require(authorization == null || authorization === it.taskAuthorization) {
                "browser_task_authorization_mismatch"
            }
            return@synchronized it
        }
        RunBrowserTools.createConfirmed(session, owner, assistant, confirmed, authorization).also {
            this.owner = owner
            bound = it
        }
    }

    /** Idempotent admission fence. Does not wait for active execution, a Looper or native I/O. */
    fun revoke() = synchronized(lock) {
        closed = true
        confirmed.revoke()
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            confirmed.revoke()
            closeRequests++
        }
        // No lifecycle call under the binding lock. The Android API is a nonblocking request;
        // keeping the returned snapshot out of drain decisions also avoids stale receipts.
        var failed = true
        try {
            host.closeWithResult()
            failed = false
        } catch (_: Exception) {
            // Keep the cleanup handle for explicit retry; never report a thrown request drained.
        } finally {
            synchronized(lock) {
                requestFailed = requestFailed || failed
                closeRequests--
            }
        }
    }

    /** Observation only: no retry, thread creation, dispatcher, Future wait or Looper pumping. */
    fun closeStatus(): BrowserCloseResult {
        val native = try { host.closeStatus() } catch (_: Exception) {
            synchronized(lock) { statusFailed = true }
            BrowserCloseResult(false, pending = setOf("BROWSER_CLOSE_STATUS"))
        }
        return synchronized(lock) {
            BrowserCloseResult(closed,
                pending = buildSet {
                    if (closeRequests != 0 || requestFailed) add("BROWSER_CLOSE_REQUEST")
                    if (statusFailed) add("BROWSER_CLOSE_STATUS")
                },
                failures = if (requestFailed || statusFailed) setOf("BROWSER_CLOSE_OBSERVATION") else emptySet())
                .merge(native)
        }
    }

    /** Parent polling adapter: pending stays pending; failed teardown must never become Drained. */
    fun closeResult(): RunCatalogCloseResult? {
        val status = closeStatus()
        return when {
            status.successful -> RunCatalogCloseResult.Drained
            status.failures.isNotEmpty() -> RunCatalogCloseResult.Unknown("browser_close_failed")
            else -> null
        }
    }

    companion object {
        /** Own the ORIGINAL host and its session, including tabs already owned by that host. */
        fun attach(
            host: AndroidBrowserRunHost,
            assistant: AssistantProfile,
            confirmed: BrowserRunAuthorization,
        ): CatalogBrowserBinding = CatalogBrowserBinding(AndroidHost(host), assistant, confirmed)

        /** Android-main-Looper operation AFTER trusted confirmation, never during preview.
         * No history, cookie-name authorization, offload path, fetch or child capability is added.
         * The actual constructor's BrowserRunConstructionException is deliberately rethrown
         * unchanged: its closeWithResult() owns partial cleanup and must remain with the parent.
         */
        fun open(
            context: Context,
            privateRoot: Path,
            assistant: AssistantProfile,
            confirmed: BrowserRunAuthorization,
        ): CatalogBrowserBinding = create(assistant, confirmed) {
            AndroidHost(AndroidBrowserRunHost(context, confirmed.preview.grant, privateRoot,
                cookieEnvPath = { _, _ -> error("browser_cookie_offload_disabled") },
                cookieAuthorizeName = { _, _, _ -> false },
                historyApproval = OpenMinisHistoryApproval.DENY))
        }

        internal fun create(
            assistant: AssistantProfile,
            confirmed: BrowserRunAuthorization,
            factory: () -> Host,
        ): CatalogBrowserBinding = try {
            CatalogBrowserBinding(factory(), assistant, confirmed)
        } catch (failure: Exception) {
            confirmed.revoke()
            throw failure // In particular, retain BrowserRunConstructionException's capability.
        }
    }
}
