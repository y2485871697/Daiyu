package app.daiyu.android

import android.content.Context
import app.daiyu.android.browser.*
import app.daiyu.android.browser.session.*
import app.daiyu.android.domain.ModelPort
import app.daiyu.android.domain.delegation.*
import app.daiyu.android.domain.runtime.RunExecutionOwner
import app.daiyu.android.domain.tools.*
import app.daiyu.android.integration.tools.*
import java.util.UUID
import java.util.concurrent.Semaphore

/** Independently approved child, not permission for run_child_models or arbitrary descendants.
 * The REAL IDs, original captured scope and its browser session are retained before any IO.
 * Parent and child approvals are distinct; the parent dispatcher reuses parentAuthorization.
 */
internal class ControllerChildBrowserPlan private constructor(
    val parent: ControllerBrowserPreparation,
    val prompt: String,
    val scope: ChildRunScope,
    val parentAuthorization: TaskAuthorization,
    private val session: CatalogChildBrowserSession,
) : AutoCloseable {
    val preview: BrowserRunPreview get() = session.preview
    private val gate = Any()
    private var approved = false
    private var closed = false
    private var launched = false
    private var child: ToolEnabledChildRun? = null
    val confirmed: Boolean get() = synchronized(gate) { approved && !closed }
    val drained: Boolean get() = synchronized(gate) { child?.drained ?: (closed && session.closeResult() == RunCatalogCloseResult.Drained) }
    val cleanupUnknown: Boolean get() = synchronized(gate) { child?.cleanupUnknown == true || session.closeResult() is RunCatalogCloseResult.Unknown }

    fun confirm(displayed: BrowserRunPreview): Boolean {
        if (parent.state != ControllerBrowserPreparation.State.PREVIEW) return false
        return synchronized(gate) {
            if (closed || approved || displayed !== preview) return false
            if (!session.confirm(displayed)) return false
            approved = true
            true
        }
    }

    /** Called only after the parent's DB acceptance, at catalog setup on its Runner worker.
     * Scope was captured ONCE from the independently approved parent task; no browser-only copy.
     */
    fun launch(context: Context, owner: RunExecutionOwner, capabilities: SessionCapabilities,
        authorization: TaskAuthorization, ledger: ToolLedger, store: ChildTaskStore,
        capacity: Semaphore, model: () -> ModelPort, onChanged: () -> Unit) {
        val retained = synchronized(gate) {
            check(approved && !closed && !launched) { "child_plan_unavailable" }
            require(owner.conversationId == parent.identity.conversationId && owner.runId == parent.identity.runId &&
                owner.assistantMessageId == parent.identity.assistantMessageId &&
                authorization === parentAuthorization && capabilities.sessionId == scope.session.sessionId &&
                capabilities.enabled.containsAll(scope.session.enabled)) { "child_parent_owner_mismatch" }
            launched = true
            val record = ChildTaskRecord(scope.owner, owner.assistantMessageId.value, owner.ownerId, 0,
                parent.assistant.id, prompt)
            // Retain both resource owners BEFORE start/prepare/native constructor can run.
            val assembly = CatalogChildBrowserAssembly(context, session, ledger)
            ToolEnabledChildRun(scope, record, owner, assembly, model, store, capacity, onChanged).also { child = it }
        }
        retained.start()
    }

    override fun close() {
        val retained = synchronized(gate) { closed = true; child }
        if (retained != null) retained.close() else session.close()
    }

    companion object {
        fun prepare(parent: ControllerBrowserPreparation, prompt: String, allowedActions: Set<String>,
            writes: List<String>): ControllerChildBrowserPlan {
            require(parent.state == ControllerBrowserPreparation.State.PREVIEW && prompt.isNotBlank() &&
                prompt.length <= 4096 && prompt.toByteArray(Charsets.UTF_8).size <= 16 * 1024) { "invalid_child_prompt" }
            val identity = parent.identity
            val owner = ChildRunOwner(identity.conversationId.value, identity.runId.value,
                UUID.randomUUID().toString(), 1, UUID.randomUUID().toString(), UUID.randomUUID().toString())
            val grant = BrowserGrant(BrowserOwner(owner.childRunId, BrowserOwnerKind.CHILD), parent.settings.access)
            val childEffects = writes.map { RunBrowserTools.writeEffect(grant, it) }.toSet()
            val capabilities = SessionCapabilities(identity.runId.value,
                setOf(RunBrowserTools.READ_CAPABILITY, RunBrowserTools.WRITE_CAPABILITY))
            val authorization = TaskAuthorization(capabilities.sessionId, identity.runId.value,
                parent.preview.writes.map { it.effect }.toSet() + childEffects)
            val scope = ChildRunScope.capture(owner, capabilities, authorization, capabilities.enabled, childEffects)
            val session = CatalogChildBrowserSession.prepare(scope, parent.assistant, parent.settings, grant,
                allowedActions.toSet(), writes.toList())
            return ControllerChildBrowserPlan(parent, prompt, scope, authorization, session)
        }
    }
}
