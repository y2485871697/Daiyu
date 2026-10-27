package app.daiyu.android.integration.tools

import android.content.Context
import app.daiyu.android.assistants.AssistantProfile
import app.daiyu.android.browser.*
import app.daiyu.android.browser.network.BrowserCloseResult
import app.daiyu.android.browser.openminis.BrowserRunConstructionException
import app.daiyu.android.browser.session.BrowserGrant
import app.daiyu.android.domain.delegation.ChildRunScope
import app.daiyu.android.domain.runtime.RunExecutionOwner
import java.nio.file.Path

/** One trusted child admission, NOT a model tool factory or a second dispatcher/ledger.
 * Construct/confirm are pure. Parent worker prepares the private root, then posts openOnMain.
 * Parent retains this object through construction, Stop and UNKNOWN until physical cleanup is
 * observed. Bind tools only from the actual child Runner bindOwner callback, before schema read.
 * Never reuse for another group/generation/scope, nor infer permission from FULL or parent text.
 */
internal class CatalogChildBrowserSession private constructor(
    val scope: ChildRunScope,
    private val assistant: AssistantProfile,
    private val preparation: BrowserRunPreparation,
    private val onDownloadCompleted: (app.daiyu.android.browser.download.BrowserCompletedDownload) -> Unit,
) : AutoCloseable {
    val preview: BrowserRunPreview get() = preparation.preview
    private val lock = Any()
    private var token: BrowserRunAuthorization? = null
    private var opening = false
    private var attempted = false
    private var closed = false
    private var binding: CatalogBrowserBinding? = null
    private var partial: BrowserRunConstructionException? = null
    private var partialStatus: BrowserCloseResult? = null
    private var unknown = false
    private var cleanupInFlight = 0

    fun confirm(displayed: BrowserRunPreview): Boolean = synchronized(lock) {
        if (closed || token != null || displayed !== preview) return false
        token = preparation.confirm(displayed)
        true
    }

    /** Main Looper only, after the worker mkdir helper. Opens no tab and executes no command. */
    fun openOnMain(context: Context, privateRoot: Path): Boolean {
        check(android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) { "browser_main_required" }
        return open { CatalogBrowserBinding.open(context, privateRoot, assistant, it, onDownloadCompleted) }
    }

    internal fun open(factory: (BrowserRunAuthorization) -> CatalogBrowserBinding): Boolean {
        val confirmed = synchronized(lock) {
            if (closed || attempted || token == null) return false
            attempted = true
            opening = true
            checkNotNull(token)
        }
        try {
            val opened = factory(confirmed)
            val stop = synchronized(lock) {
                binding = opened
                opening = false
                closed
            }
            if (stop) retryCleanup()
            return !stop
        } catch (failure: Exception) {
            synchronized(lock) {
                opening = false
                closed = true
                confirmed.revoke()
                // Retain the exact cleanup capability, never just a cause/message.
                partial = failure as? BrowserRunConstructionException
            }
            retryCleanup()
            return false
        }
    }

    /** Borrowed tools only: install in the child's existing central registry/dispatcher, using
     * scope.session and the SAME scope.authorization. This does not create or dispatch a Runner.
     */
    fun bindOwner(owner: RunExecutionOwner): RunBrowserTools.ConfirmedCatalog = synchronized(lock) {
        check(!closed && !opening) { "browser_child_closed" }
        checkNotNull(binding) { "browser_child_not_open" }.bindOwner(owner, scope.authorization)
    }

    /** Stop fences replay before initiating teardown; never releases a child capacity permit. */
    override fun close() {
        synchronized(lock) {
            closed = true
            preparation.cancel()
            token?.revoke()
            binding?.revoke()
        }
        retryCleanup()
    }

    /** Explicit retries are cleanup ONLY, never a constructor, fetch, tab or model retry. */
    fun retryCleanup() {
        val handles = synchronized(lock) {
            if (!closed || opening) return
            cleanupInFlight++
            binding to partial
        }
        try {
            handles.first?.close()
            handles.second?.let {
                val status = it.closeWithResult()
                synchronized(lock) { partialStatus = status }
            }
        } catch (_: Exception) {
            synchronized(lock) { unknown = true }
        } finally { synchronized(lock) { cleanupInFlight-- } }
    }

    /** Observation only. UNKNOWN pins the owner; it is not permission to reopen or replay. */
    fun closeResult(): RunCatalogCloseResult? {
        val handles = synchronized(lock) {
            if (!closed || opening || cleanupInFlight != 0) return null
            binding to partialStatus
        }
        val result = handles.first?.closeResult() ?: handles.second?.let {
            when {
                it.failures.isNotEmpty() -> RunCatalogCloseResult.Unknown("browser_child_partial_close_failed")
                it.successful -> RunCatalogCloseResult.Drained
                else -> null
            }
        }
        return synchronized(lock) {
            if (result is RunCatalogCloseResult.Unknown) unknown = true
            when {
                opening || cleanupInFlight != 0 -> null
                unknown -> RunCatalogCloseResult.Unknown("browser_child_close_unknown")
                binding == null && partial == null -> RunCatalogCloseResult.Drained
                else -> result
            }
        }
    }

    companion object {
        fun prepare(scope: ChildRunScope, assistant: AssistantProfile, settings: BrowserSettings,
                    grant: BrowserGrant, allowedActions: Set<String>,
                    writeArguments: List<String> = emptyList(),
                    onDownloadCompleted: (app.daiyu.android.browser.download.BrowserCompletedDownload) -> Unit = {},
                    defaultPermission: BrowserDefaultPermission? = null): CatalogChildBrowserSession {
            val frozen = assistant.copy(enabledSkillIds = java.util.Collections.unmodifiableList(assistant.enabledSkillIds.toList()))
            return CatalogChildBrowserSession(scope, frozen, BrowserRunPreparation.prepareChild(
                scope, frozen.id, settings, grant, allowedActions, writeArguments,
                defaultPermission = defaultPermission), onDownloadCompleted)
        }
    }
}
