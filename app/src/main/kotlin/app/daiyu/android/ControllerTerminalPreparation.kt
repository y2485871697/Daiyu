package app.daiyu.android

import android.content.Context
import android.os.Looper
import app.daiyu.android.assistants.AssistantProfile
import app.daiyu.android.domain.Conversation
import app.daiyu.android.domain.ConversationId
import app.daiyu.android.domain.MessageId
import app.daiyu.android.domain.RunId
import app.daiyu.android.domain.tools.TaskAuthorization
import app.daiyu.android.integration.tools.CatalogTerminalBinding
import app.daiyu.android.integration.tools.TerminalPtyLaunch
import app.daiyu.android.integration.tools.TerminalPtyPlan
import app.daiyu.android.model.ProviderSnapshot
import java.util.Collections
import java.util.UUID

/** Increment revision even when editing away and back to identical text. Text-only send path. */
internal data class TerminalDraftOwner(
    val conversationId: ConversationId?,
    val editorGeneration: Long,
    val draftRevision: Long,
) {
    init { require(editorGeneration >= 0 && draftRevision >= 0) }
}
internal data class TerminalDraftSnapshot(val owner: TerminalDraftOwner, val text: String)

internal data class TerminalRunIdentity(
    val conversationId: ConversationId,
    val sessionId: String,
    val runId: RunId,
    val assistantId: String,
    val assistantMessageId: MessageId,
)

/** Deliberately not a data class: only the original displayed object can authorize its plan. */
internal class TerminalRunPreview internal constructor(
    val identity: TerminalRunIdentity,
    val plan: TerminalPtyPlan,
)

/** The parent now owns cleanup, including failed DB admission and UNKNOWN. Not a send receipt. */
internal class TerminalConfirmedRun internal constructor(
    val preview: TerminalRunPreview,
    val authorization: TaskAuthorization,
    val binding: CatalogTerminalBinding,
)

/**
 * Owns a frozen host plan (including an UNCLAIMED Linux lease) from prepare until transfer/drain.
 * No shell, Runner, ledger, dispatcher, download, install, or DB operation occurs here. In particular
 * confirm only creates ONE exact TaskAuthorization and calls the non-starting binding approval.
 *
 * Parent reserves the draft before prepare, retains this owner after UI disposal, and uses its
 * existing acceptance/stop gate to takeConfirmed(). The returned binding/token are passed unchanged
 * to RunCatalogTools. Only later Runner dispatch may execute the first Open step. Parent must retain
 * the transferred owner through cancellation/UNKNOWN and await the catalog's actual drain receipt.
 * Neither confirmation nor transfer is durable acceptance or permission to clear the editor.
 */
internal class ControllerTerminalPreparation private constructor(
    val conversation: Conversation?,
    val draft: TerminalDraftSnapshot,
    val assistant: AssistantProfile,
    val provider: ProviderSnapshot,
    val preview: TerminalRunPreview,
    private val approve: (TerminalPtyPlan, TaskAuthorization) -> CatalogTerminalBinding,
) {
    enum class State { PREVIEW, CONFIRMED, TRANSFERRED, CLOSING, DRAINED, UNKNOWN }
    private val lock = Any()
    private var phase = State.PREVIEW
    private var cancelled = false
    private var approvalAttempted = false
    private var token: TaskAuthorization? = null
    private var binding: CatalogTerminalBinding? = null
    private var joining = false
    val state: State get() = synchronized(lock) { phase }
    val isCancelled: Boolean get() = synchronized(lock) { cancelled }
    val identity: TerminalRunIdentity get() = preview.identity

    /** No I/O. Original preview identity AND every draft field must still match. */
    fun confirm(displayed: TerminalRunPreview, current: TerminalDraftSnapshot,
        currentAssistant: AssistantProfile, currentProvider: ProviderSnapshot): Boolean = synchronized(lock) {
        if (phase != State.PREVIEW || cancelled || displayed !== preview || current != draft ||
            currentAssistant != assistant || currentProvider != provider) return false
        val authorization = TaskAuthorization(identity.sessionId, identity.runId.value,
            preview.plan.effects.values.toSet())
        token = authorization
        approvalAttempted = true
        try {
            binding = approve(preview.plan, authorization)
            phase = State.CONFIRMED
            true
        } catch (_: Exception) {
            // Never try approval again or guess whether a failed approver claimed the Linux lease.
            // Retain plan + token; without a binding there is no affirmative drain receipt.
            cancelled = true
            phase = State.UNKNOWN
            false
        }
    }

    /**
     * Atomic ownership handoff only, under the parent's acceptance/stop gate; DOES NOT execute.
     * Parent owns the result even if subsequent persistence fails. It must close + await it then.
     */
    fun takeConfirmed(displayed: TerminalRunPreview, current: TerminalDraftSnapshot,
        currentAssistant: AssistantProfile, currentProvider: ProviderSnapshot): TerminalConfirmedRun? =
        synchronized(lock) {
            if (phase != State.CONFIRMED || cancelled || displayed !== preview || current != draft ||
                currentAssistant != assistant || currentProvider != provider) return null
            val result = TerminalConfirmedRun(preview, checkNotNull(token), checkNotNull(binding))
            phase = State.TRANSFERRED
            binding = null
            result
        }

    /** Short cancellation fence. Claimed leases are NEVER closed as though still preview leases. */
    fun cancel() {
        val owned = synchronized(lock) {
            if (phase == State.TRANSFERRED || phase == State.DRAINED || cancelled) return
            cancelled = true
            phase = State.CLOSING
            approvalAttempted to binding
        }
        // Lease and transport calls are outside the lifecycle monitor.
        val result = try {
            if (!owned.first) {
                (preview.plan.launch as? TerminalPtyLaunch.Linux)?.close()
                State.DRAINED
            } else {
                owned.second?.close()
                if (owned.second == null) State.UNKNOWN else State.CLOSING
            }
        } catch (_: Exception) { State.UNKNOWN }
        synchronized(lock) { phase = result }
    }

    /** Explicit worker join, never composition/polling. False retains the binding/lease for retry. */
    fun awaitCleanupOnWorker(timeoutMillis: Long): Boolean {
        check(Looper.myLooper() != Looper.getMainLooper()) { "terminal_cleanup_requires_worker" }
        return awaitCleanup(timeoutMillis)
    }

    internal fun awaitCleanup(timeoutMillis: Long): Boolean {
        require(timeoutMillis in 1..30000)
        val owned = synchronized(lock) {
            if (phase == State.DRAINED) return true
            if (!cancelled || phase == State.TRANSFERRED || joining) return false
            val retained = binding ?: return false // Unknown claim: never release an assumed preview lease.
            joining = true
            retained
        }
        val complete = try { owned.close(); owned.awaitTermination(timeoutMillis) }
        catch (_: Exception) { false }
        return synchronized(lock) {
            joining = false
            if (complete) {
                phase = State.DRAINED
                binding = null
            } else phase = State.UNKNOWN
            complete
        }
    }

    companion object {
        /**
         * Takes exclusive ownership of plan on success only. Host creates its 1..64 steps and IDs;
         * model text is not parsed into commands. If this call throws, caller still owns any lease.
         * Android plan construction is pure; Linux freezing must precede this on the worker via
         * LinuxSettingsApplication.freezeTerminalLaunchOnWorker (trusted, already selected only).
         */
        fun prepare(
            context: Context,
            conversation: Conversation?,
            draft: TerminalDraftSnapshot,
            assistant: AssistantProfile,
            provider: ProviderSnapshot,
            plan: TerminalPtyPlan,
            assistantMessageId: MessageId = MessageId(UUID.randomUUID().toString()),
        ): ControllerTerminalPreparation {
            val application = context.applicationContext
            return prepareOwned(conversation, draft, assistant, provider, plan, assistantMessageId) { frozen, token ->
                CatalogTerminalBinding.approve(application, frozen, token)
            }
        }

        /** Test-only transport seam; production must use prepare, never a caller-supplied approver. */
        internal fun prepareOwned(
            conversation: Conversation?,
            draft: TerminalDraftSnapshot,
            assistant: AssistantProfile,
            provider: ProviderSnapshot,
            plan: TerminalPtyPlan,
            assistantMessageId: MessageId = MessageId(UUID.randomUUID().toString()),
            approve: (TerminalPtyPlan, TaskAuthorization) -> CatalogTerminalBinding,
        ): ControllerTerminalPreparation {
            require(draft.text.isNotBlank()) { "terminal_empty_text" }
            require(draft.owner.conversationId == conversation?.id) { "terminal_draft_owner_changed" }
            require(conversation == null || conversation.id == plan.conversationId) { "terminal_conversation_changed" }
            require(conversation?.assistantId == null || conversation.assistantId == assistant.id) { "terminal_assistant_changed" }
            val frozen = assistant.copy(enabledSkillIds = Collections.unmodifiableList(assistant.enabledSkillIds.toList()))
            val identity = TerminalRunIdentity(plan.conversationId, plan.sessionId, RunId(plan.runId), frozen.id, assistantMessageId)
            return ControllerTerminalPreparation(conversation?.copy(), draft.copy(), frozen, provider,
                TerminalRunPreview(identity, plan), approve)
        }
    }
}
