package app.daiyu.android

import android.content.Context
import android.os.Handler
import android.os.Looper
import app.daiyu.android.assistants.AssistantProfile
import app.daiyu.android.browser.BrowserPrivateRoot
import app.daiyu.android.browser.BrowserRunAuthorization
import app.daiyu.android.browser.BrowserRunIdentity
import app.daiyu.android.browser.BrowserRunPreparation
import app.daiyu.android.browser.BrowserRunPreview
import app.daiyu.android.browser.BrowserSettings
import app.daiyu.android.browser.network.BrowserCloseResult
import app.daiyu.android.browser.openminis.BrowserRunConstructionException
import app.daiyu.android.browser.session.BrowserGrant
import app.daiyu.android.domain.Conversation
import app.daiyu.android.domain.ConversationId
import app.daiyu.android.domain.MessageId
import app.daiyu.android.domain.RunId
import app.daiyu.android.integration.tools.CatalogBrowserBinding
import app.daiyu.android.integration.tools.RunCatalogCloseResult
import app.daiyu.android.model.ProviderSnapshot
import java.nio.file.Path
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch

/** Identity, not text equality: changing away and back still changes generation/revision. */
internal data class BrowserDraftOwner(
    val conversationId: ConversationId?,
    val editorGeneration: Long,
    val draftRevision: Long,
) {
    init { require(editorGeneration >= 0 && draftRevision >= 0) }
}

internal data class BrowserDraftSnapshot(val owner: BrowserDraftOwner, val text: String)

/** Only a durable DB acceptance receipt may consume the exact submitted draft. */
internal class BrowserSendReceipt internal constructor(
    val preparation: ControllerBrowserPreparation,
    val conversation: Conversation,
) {
    private var consumed = false
    @Synchronized fun consume(current: BrowserDraftSnapshot): Boolean {
        if (consumed || current != preparation.draft) return false
        consumed = true
        return true
    }
}

/** Queue rejection (false or throw) MUST mean the runnable was not accepted. */
internal interface BrowserMainDispatcher {
    fun isMainThread(): Boolean
    fun post(block: () -> Unit): Boolean
}

internal class AndroidBrowserMainDispatcher : BrowserMainDispatcher {
    private val handler = Handler(Looper.getMainLooper())
    override fun isMainThread() = Looper.myLooper() == handler.looper
    override fun post(block: () -> Unit) = handler.post { block() }
}

/**
 * One reservation/approval/partially constructed resource owner, retained by the parent controller.
 * prepare/confirm are pure data operations. Only openOnWorker dispatches native construction.
 * Never creates a Runner owner, tab, navigation, dispatcher, or a second task authorization.
 *
 * The parent must keep this object while opening/closing/UNKNOWN, even after UI disposal or stop.
 * A returned binding is BORROWED until accept() transfers it under the parent's DB admission gate.
 */
internal class ControllerBrowserPreparation private constructor(
    val conversation: Conversation?,
    val draft: BrowserDraftSnapshot,
    val assistant: AssistantProfile,
    val provider: ProviderSnapshot,
    private val preparation: BrowserRunPreparation,
) {
    enum class State { PREVIEW, CONFIRMED, OPENING, READY, TRANSFERRED, CLOSING, DRAINED, UNKNOWN }
    val preview: BrowserRunPreview get() = preparation.preview
    val identity: BrowserRunIdentity get() = preview.identity
    val text: String get() = draft.text
    val settings: BrowserSettings get() = preview.settings
    private val lock = Any()
    private var phase = State.PREVIEW
    private var cancelled = false
    // READY remains visible during commit, but only its claimant may publish or release the binding.
    private var acceptInFlight = false
    private var authorization: BrowserRunAuthorization? = null
    private var binding: CatalogBrowserBinding? = null
    // Retain the ORIGINAL exception, not just its message/cause or a fabricated close receipt.
    private var constructionFailure: BrowserRunConstructionException? = null
    private var partialReceipt: BrowserCloseResult? = null
    private var cleanupRequests = 0
    private var stickyUnknown = false
    @Volatile var childPlan: ControllerChildBrowserPlan? = null
        private set
    fun attachChild(plan: ControllerChildBrowserPlan): Boolean = synchronized(lock) {
        if (phase != State.PREVIEW || cancelled || childPlan != null || plan.parent !== this) return false
        childPlan = plan
        true
    }
    val state: State get() = synchronized(lock) { phase }
    val isCancelled: Boolean get() = synchronized(lock) { cancelled }
    val isSubmitted: Boolean get() = state != State.PREVIEW

    /** This is the ONLY token-producing entry. A settings save/ask_user cannot call it implicitly. */
    fun confirm(displayed: BrowserRunPreview, current: BrowserDraftSnapshot): Boolean = synchronized(lock) {
        if (phase != State.PREVIEW || displayed !== preview || current != draft || cancelled ||
            childPlan?.confirmed == false) return false
        authorization = preparation.confirm(displayed)
        phase = State.CONFIRMED
        true
    }

    fun openOnWorker(context: Context, privateRoot: Path,
                     main: BrowserMainDispatcher = AndroidBrowserMainDispatcher(),
                     onDownloadCompleted: (app.daiyu.android.browser.download.BrowserCompletedDownload) -> Unit = {}): CatalogBrowserBinding? =
        openOnWorker(main) { token -> CatalogBrowserBinding.open(context, privateRoot, assistant, token, onDownloadCompleted) }

    /**
     * Worker-only wait. Interruption cancels admission but NEVER drops a queued/late native handle.
     * The posted runnable captures this owner until construction and cleanup publication finish.
     * The factory seam is internal for deterministic tests; production always uses open above.
     */
    internal fun openOnWorker(main: BrowserMainDispatcher,
                              factory: (BrowserRunAuthorization) -> CatalogBrowserBinding): CatalogBrowserBinding? {
        check(!main.isMainThread()) { "browser_worker_required" }
        val token = synchronized(lock) {
            if (phase != State.CONFIRMED || cancelled) return null
            phase = State.OPENING
            checkNotNull(authorization)
        }
        val finished = CountDownLatch(1)
        val accepted = try {
            main.post {
                try {
                    val shouldOpen = synchronized(lock) {
                        if (cancelled) { phase = State.DRAINED; false } else true
                    }
                    if (shouldOpen) {
                        try {
                            check(main.isMainThread()) { "browser_main_required" }
                            val opened = factory(token)
                            val mustClose = synchronized(lock) {
                                binding = opened
                                phase = if (cancelled) State.CLOSING else State.READY
                                cancelled
                            }
                            if (mustClose) requestCleanup()
                        } catch (failure: Exception) {
                            synchronized(lock) {
                                cancelled = true
                                token.revoke()
                                constructionFailure = failure as? BrowserRunConstructionException
                                phase = if (constructionFailure == null) State.DRAINED else State.CLOSING
                            }
                            if (failure is BrowserRunConstructionException) requestCleanup()
                        }
                    }
                } finally { finished.countDown() }
            }
        } catch (_: Exception) { false }
        if (!accepted) {
            synchronized(lock) {
                cancelled = true
                token.revoke()
                phase = State.DRAINED
            }
            return null
        }
        try { finished.await() } catch (_: InterruptedException) {
            cancel()
            Thread.currentThread().interrupt()
            return null
        }
        return synchronized(lock) { binding.takeIf { phase == State.READY && !cancelled } }
    }

    /**
     * Invoke on the parent worker INSIDE its existing acceptance/stop gate, before publishing tools.
     * commit must durably save these exact conversation/run/assistant-message IDs, text, profile and
     * frozen provider, then return that saved conversation. Null means definitely not accepted.
     * An exception or wrong ID is ambiguous: pin UNKNOWN; never manufacture a receipt or auto-resend.
     * The callback runs WITHOUT this object's lock and must not wait on main or execute a model.
     * Cancellation during commit fences admission, not the DB outcome: a definite success still
     * transfers the original binding. The stopped parent must then close it without execution.
     */
    fun accept(opened: CatalogBrowserBinding, commit: () -> Conversation?): BrowserSendReceipt? {
        synchronized(lock) {
            if (phase != State.READY || cancelled || binding !== opened || acceptInFlight) return null
            acceptInFlight = true
        }
        var ambiguous = false
        val saved = try { commit() } catch (_: Exception) {
            ambiguous = true
            null
        }
        val result = synchronized(lock) {
            acceptInFlight = false
            if (ambiguous || saved == null || saved.id != identity.conversationId) {
                stickyUnknown = stickyUnknown || ambiguous || saved != null
                cancelled = true
                authorization?.revoke()
                phase = if (stickyUnknown) State.UNKNOWN else State.CLOSING
                null
            } else {
                phase = State.TRANSFERRED
                // Even after stop, durable success belongs to the parent, never to rejection cleanup.
                binding = null
                BrowserSendReceipt(this, saved)
            }
        }
        if (result == null) requestCleanup()
        return result
    }

    /** Short admission fence; an in-flight accept owns publication and defers native cleanup. */
    fun cancel() {
        val (child, clean) = synchronized(lock) {
            if (phase == State.TRANSFERRED || phase == State.DRAINED) return
            cancelled = true
            preparation.cancel()
            authorization?.revoke()
            val clean = if (acceptInFlight) false // Commit still borrows the binding; its result decides ownership.
                else when (phase) {
                    State.PREVIEW, State.CONFIRMED -> { phase = State.DRAINED; false }
                    State.OPENING -> false // The queued constructor owns the final publication/cleanup.
                    else -> { if (!stickyUnknown) phase = State.CLOSING; true }
                }
            childPlan to clean
        }
        child?.close()
        if (clean) requestCleanup()
    }

    /** Explicit retry only. Polling never retries a constructor, close request, or native operation. */
    fun retryCleanup() { requestCleanup() }

    private fun requestCleanup() {
        val handles = synchronized(lock) {
            if (!cancelled || acceptInFlight || phase == State.OPENING ||
                phase == State.TRANSFERRED || phase == State.DRAINED) return
            cleanupRequests++
            binding to constructionFailure
        }
        try {
            handles.first?.close()
            handles.second?.let { failure ->
                val status = failure.closeWithResult()
                synchronized(lock) { partialReceipt = status }
            }
        } catch (_: Exception) {
            synchronized(lock) { stickyUnknown = true; phase = State.UNKNOWN }
        } finally { synchronized(lock) { cleanupRequests-- } }
        pollCleanup()
    }

    /** null = pending; UNKNOWN is terminal for release but retains all actual cleanup capabilities. */
    fun pollCleanup(): RunCatalogCloseResult? {
        val handles = synchronized(lock) {
            when (phase) {
                State.DRAINED -> return RunCatalogCloseResult.Drained
                State.PREVIEW, State.CONFIRMED, State.OPENING, State.READY, State.TRANSFERRED -> return null
                else -> Unit
            }
            if (cleanupRequests != 0) return null
            binding to partialReceipt
        }
        val observed = handles.first?.closeResult() ?: handles.second?.let {
            when {
                it.failures.isNotEmpty() -> RunCatalogCloseResult.Unknown("browser_partial_close_failed")
                it.successful -> RunCatalogCloseResult.Drained
                else -> null
            }
        }
        return synchronized(lock) {
            if (observed is RunCatalogCloseResult.Unknown) stickyUnknown = true
            when {
                stickyUnknown -> {
                    phase = State.UNKNOWN
                    RunCatalogCloseResult.Unknown("browser_preparation_unknown")
                }
                cleanupRequests != 0 -> null
                observed == RunCatalogCloseResult.Drained -> {
                    phase = State.DRAINED
                    binding = null
                    constructionFailure = null
                    partialReceipt = null
                    authorization = null
                    RunCatalogCloseResult.Drained
                }
                else -> null
            }
        }
    }

    companion object {
        /** Parent sendBrowser must call on its worker AFTER confirmation, BEFORE openOnWorker.
         * Fresh installs have no files/browser-runs yet; native host deliberately requires an
         * existing private root. No preview-time mkdir and no arbitrary model-supplied path.
         */
        fun preparePrivateRootOnWorker(
            context: Context,
            main: BrowserMainDispatcher = AndroidBrowserMainDispatcher(),
        ): Path {
            check(!main.isMainThread()) { "browser_worker_required" }
            return BrowserPrivateRoot.prepare(context.applicationContext.filesDir.toPath())
        }

        /** No host/session/registry/file/network, and no active-profile/settings fallback. */
        fun prepare(
            conversation: Conversation?,
            draft: BrowserDraftSnapshot,
            assistant: AssistantProfile,
            provider: ProviderSnapshot,
            settings: BrowserSettings,
            grant: BrowserGrant,
            allowedActions: Set<String>,
            writeArguments: List<String> = emptyList(),
            runId: RunId = RunId(grant.owner.sessionId),
            assistantMessageId: MessageId = MessageId(UUID.randomUUID().toString()),
            conversationId: ConversationId = conversation?.id ?: ConversationId(UUID.randomUUID().toString()),
        ): ControllerBrowserPreparation {
            require(draft.text.isNotBlank()) { "browser_empty_text" }
            require(draft.owner.conversationId == conversation?.id) { "browser_draft_owner_changed" }
            require(conversation == null || conversation.id == conversationId) { "browser_conversation_changed" }
            require(conversation?.assistantId == null || conversation.assistantId == assistant.id) {
                "browser_assistant_changed"
            }
            val frozen = assistant.copy(enabledSkillIds = Collections.unmodifiableList(assistant.enabledSkillIds.toList()))
            val identity = BrowserRunIdentity(conversationId, runId, frozen.id, assistantMessageId)
            return ControllerBrowserPreparation(conversation?.copy(), draft.copy(), frozen, provider,
                BrowserRunPreparation.prepare(identity, settings.copy(), grant.copy(), allowedActions, writeArguments))
        }
    }
}
