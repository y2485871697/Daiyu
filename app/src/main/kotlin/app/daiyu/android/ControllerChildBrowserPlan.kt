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

/** Run-owned child, not permission for run_child_models or arbitrary descendants.
 * The REAL IDs, original captured scope and its browser session are retained before any IO.
 * Product defaults inherit only the parent's browser action/capability intersection;
 * exact legacy approvals remain separate. The parent dispatcher reuses parentAuthorization.
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
    private var pauseRequested = false
    private var controlEpoch = 0L
    val snapshot: RetainedChildTask? get() = synchronized(gate) { child?.snapshot }
    val progress: String get() = synchronized(gate) { child?.progress.orEmpty() }
    val contextLimited: Boolean get() = synchronized(gate) { child?.contextLimited == true }
    val pauseState: ChildTaskPauseState get() = synchronized(gate) {
        child?.pauseState ?: if (pauseRequested) ChildTaskPauseState.PENDING else ChildTaskPauseState.ACTIVE
    }
    fun capturePause(): Long? = synchronized(gate) {
        if (closed || drained || pauseRequested) return@synchronized null
        controlEpoch++; pauseRequested = true; child?.pause()
        controlEpoch
    }
    fun finishPause(epoch: Long, disposition: ChildTaskStopDisposition) {
        val stop = synchronized(gate) {
            if (closed || epoch != controlEpoch) return
            controlEpoch++
            if (disposition == ChildTaskStopDisposition.CONTINUE) { pauseRequested = false; child?.resume() }
            disposition == ChildTaskStopDisposition.STOP
        }
        if (stop) close()
    }
    fun pause(): ChildTaskControlReceipt = synchronized(gate) {
        if (closed || drained) return@synchronized ChildTaskControlReceipt.STALE
        controlEpoch++; pauseRequested = true
        child?.pause() ?: ChildTaskControlReceipt.PENDING
    }
    fun resume(): ChildTaskControlReceipt = synchronized(gate) {
        if (closed || drained) return@synchronized ChildTaskControlReceipt.STALE
        controlEpoch++; pauseRequested = false
        child?.resume() ?: ChildTaskControlReceipt.RESUMED
    }
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
        capacity: Semaphore, model: () -> ModelPort, onChanged: () -> Unit,
        childUsage: app.daiyu.android.domain.usage.ChildUsageRecorder? = null,
        contextRuntime: app.daiyu.android.domain.runtime.RunContextRuntime? = null,
        onCheckpoint: (app.daiyu.android.domain.runtime.RunCheckpoint) -> Unit = {}) {
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
            require(contextRuntime == null || childUsage != null) { "child_compression_requires_usage_writer" }
            val usageStore = childUsage?.let { recorder -> object : app.daiyu.android.domain.runtime.RuntimeStore {
                override fun save(checkpoint: app.daiyu.android.domain.runtime.RunCheckpoint) {
                    scope.requireOwner(checkpoint.message)
                    recorder.record(record, checkpoint)
                    onCheckpoint(checkpoint)
                }
            } }
            ToolEnabledChildRun(scope, record, owner, assembly, model, store, capacity, onChanged,
                runtimeStore = usageStore, contextRuntime = contextRuntime).also {
                child = it
                if (pauseRequested) it.pause()
            }
        }
        retained.start()
    }

    override fun close() {
        val retained = synchronized(gate) { closed = true; child }
        if (retained != null) retained.close() else session.close()
    }

    companion object {
        fun prepare(parent: ControllerBrowserPreparation, prompt: String, allowedActions: Set<String>,
            writes: List<String>, downloads: app.daiyu.android.browser.download.BrowserDownloadShelf? = null): ControllerChildBrowserPlan {
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
            val defaultPermission = parent.preview.defaultPermission?.let { BrowserDefaultPermission.child(grant, scope, it) }
            val session = CatalogChildBrowserSession.prepare(scope, parent.assistant, parent.settings, grant,
                allowedActions.toSet(), writes.toList(),
                downloads?.recorder(identity.conversationId.value, grant.owner) ?: {}, defaultPermission)
            return ControllerChildBrowserPlan(parent, prompt, scope, authorization, session).also { plan ->
                // Trusted product permission is inherited locally; never simulate a UI approval.
                if (defaultPermission != null) check(plan.confirm(plan.preview)) { "child_default_permission_failed" }
            }
        }
    }
}
