package app.daiyu.android

import android.content.Context
import app.daiyu.android.assistants.AssistantProfile
import app.daiyu.android.domain.*
import app.daiyu.android.domain.tools.TaskAuthorization
import app.daiyu.android.integration.tools.*
import app.daiyu.android.model.ProviderSnapshot
import java.util.UUID

/** Original object is the approval capability. Command, backend, mode and editor never come from model JSON. */
internal class ControllerTerminalJobPreparation(
    private val context: Context,
    val conversation: Conversation?,
    val draft: TerminalDraftSnapshot,
    val assistant: AssistantProfile,
    val provider: ProviderSnapshot,
    val plan: TerminalJobPlan,
) {
    val identity = TerminalRunIdentity(plan.conversationId, plan.sessionId, plan.runId, assistant.id,
        MessageId(UUID.randomUUID().toString()))
    private val lock = Any()
    private var confirmed = false
    private var transferred = false
    private var attempted = false
    private var cancelled = false
    private var drained = false
    private var session: TerminalJobSession? = null
    val isConfirmed: Boolean get() = synchronized(lock) { confirmed && !cancelled }
    fun confirm(current: TerminalDraftSnapshot, profile: AssistantProfile, snapshot: ProviderSnapshot): Boolean = synchronized(lock) {
        if (confirmed || cancelled || attempted || current != draft || profile != assistant || snapshot != provider) return false
        attempted = true
        val token = TaskAuthorization(plan.sessionId, plan.runId.value, setOf(plan.effect))
        try {
            session = TerminalJobSession.approve(context, plan, token)
            confirmed = true
            true
        } catch (_: Exception) { cancelled = true; false }
    }
    fun transfer(current: TerminalDraftSnapshot, profile: AssistantProfile, snapshot: ProviderSnapshot): TerminalJobSession? = synchronized(lock) {
        if (!confirmed || cancelled || transferred || current != draft || profile != assistant || snapshot != provider) return null
        transferred = true
        checkNotNull(session)
    }
    /** Called only on parent's cleanup worker, never UI/control while it owns the admission lock. */
    fun cleanupOnWorker(): Boolean {
        val owned = synchronized(lock) {
            if (drained) return true
            cancelled = true
            session
        }
        val complete = if (owned != null) { owned.close(); owned.awaitCloseOnWorker(30_000) }
        else if (!attempted) { (plan.launch as? TerminalPtyLaunch.Linux)?.close(); true }
        else false // uncertain claim is retained, never release an assumed preview pin
        synchronized(lock) { drained = complete }
        return complete
    }
}
