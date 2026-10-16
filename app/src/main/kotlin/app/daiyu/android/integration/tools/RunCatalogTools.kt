package app.daiyu.android.integration.tools

import app.daiyu.android.domain.*
import app.daiyu.android.domain.runtime.ReceiptBatchToolExecutor
import app.daiyu.android.domain.runtime.RunExecutionOwner
import app.daiyu.android.domain.tools.*
import app.daiyu.android.shizuku.*
import app.daiyu.android.skills.SkillRunSession
import java.util.concurrent.atomic.AtomicBoolean

internal sealed interface RunCatalogCloseResult {
    data object Drained : RunCatalogCloseResult
    data class Unknown(val reason: String) : RunCatalogCloseResult
}

/** An unforgeable session/commands pair: never accept unrelated or default-approved commands. */
internal class CatalogShizukuBinding private constructor(
    internal val session: ShizukuRunSession,
    internal val commands: RunShizukuCommands
) : AutoCloseable {
    override fun close() = session.close()

    companion object {
        /** Explicit worker-thread operation, after the user approved this session's exact command. */
        fun open(session: ShizukuRunSession, access: ShizukuAuthorization): CatalogShizukuBinding =
            CatalogShizukuBinding(session, session.open(access))

        /** Same transport seam as ShizukuRunSession.open, for deterministic integration tests. */
        internal fun open(session: ShizukuRunSession, connect: (Int, String) -> ShizukuBackend): CatalogShizukuBinding =
            CatalogShizukuBinding(session, session.open(connect))
    }
}

/** Runner reads definitions on its worker, after durable admission and before model.open. */
internal class RunCatalogTools(
    private val registry: ToolRegistry,
    private val capabilities: SessionCapabilities,
    private val ledger: ToolLedger,
    private val conversationId: ConversationId,
    private val runId: RunId,
    private val deviceResources: RunDeviceResources? = null,
    private val children: RunChildModels? = null,
    private val backgroundFactory: ((RunExecutionOwner, SessionCapabilities, TaskAuthorization) -> RunChildTasks)? = null,
    authorization: TaskAuthorization? = null,
    private val shizuku: CatalogShizukuBinding? = null,
    // Same-package failure injection; production always invokes the resource's own stop action.
    private val closeResource: (AutoCloseable) -> Unit = { it.close() },
    private val capture: (SessionCapabilities, TaskAuthorization) -> SkillRunSession
) : CatalogTool, ReceiptBatchToolExecutor, AutoCloseable {
    // Only the legacy, non-Shizuku path may manufacture an EMPTY authorization.
    private val authorization = authorization ?: run {
        require(shizuku == null) { "shizuku_task_authorization_required" }
        TaskAuthorization(capabilities.sessionId, runId.value)
    }
    init {
        require(this.authorization.sessionId == capabilities.sessionId && this.authorization.taskId == runId.value) {
            "tool_run_owner_mismatch"
        }
        require(shizuku == null || (shizuku.session.sessionId == capabilities.sessionId && shizuku.session.runId == runId.value)) {
            "shizuku_approval_run_mismatch"
        }
    }
    private val closed = AtomicBoolean(false)
    @Volatile private var skills: SkillRunSession? = null
    @Volatile private var bridge: CatalogModelBridge? = null
    private val closeLock = Any()
    private var activeDispatches = 0
    private var observingClose = false
    private var activeCreations = 0
    private var pendingStops = 0
    private val stopped = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<AutoCloseable, Boolean>())
    private val failedStops = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<AutoCloseable, Boolean>())
    private var observationsStarted = false
    private var registeringObservations = false
    private var remainingObservations = 0
    private var closeFailure: RunCatalogCloseResult.Unknown? = null
    private var closeResult: RunCatalogCloseResult? = null
    private val closeObservers = mutableListOf<(RunCatalogCloseResult) -> Unit>()
    // Serializes worker setup only. close() never acquires this potentially long-held lock.
    private val setupLock = Any()

    @Volatile private var background: RunChildTasks? = null
    @Volatile private var owner: RunExecutionOwner? = null
    override fun bindOwner(owner: RunExecutionOwner) {
        var creating = false
        try {
            synchronized(setupLock) {
                require(owner.conversationId == conversationId && owner.runId == runId) { "tool_run_owner_mismatch" }
                check(this.owner == null || this.owner === owner) { "tool_owner_already_bound" }
                beginCreation(); creating = true
                deviceResources?.bind(owner, authorization, capabilities)
                children?.bind(owner, capabilities, authorization)
                shizuku?.commands?.bind(owner, capabilities, authorization)
                val created = if (background == null) backgroundFactory?.let { factory ->
                    createResource { factory(owner, capabilities, authorization) }
                } else null
                val publish = synchronized(closeLock) {
                    if (closed.get()) false else {
                        if (created != null) background = created
                        this.owner = owner
                        true
                    }
                }
                if (!publish) {
                    // Keep the creation counted until its late result has actually been sealed.
                    stopResources(listOfNotNull(created))
                    error("run_closed")
                }
            }
        } catch (failure: Exception) {
            if (creating) close()
            throw failure
        } finally { if (creating) endCreation() }
    }

    override val definitions: List<app.daiyu.android.domain.ToolDefinition> get() = initialized().definitions

    private fun initialized(): CatalogModelBridge {
        var creating = false
        try {
            return synchronized(setupLock) {
                check(!closed.get()) { "run_closed" }
                bridge?.let { return@synchronized it }
                val bound = checkNotNull(owner) { "tool_owner_not_bound" }
                beginCreation(); creating = true
                val scope = createResource { capture(capabilities, authorization) }
                val publish = synchronized(closeLock) {
                    if (closed.get()) false else { skills = scope; true }
                }
                if (!publish) {
                    stopResources(listOf(scope))
                    error("run_closed")
                }
                ScopedSkillTools.create(scope, authorization).forEach { registry.register(it) }
                children?.let { registry.register(it.tool()) }
                background?.let { registry.register(it.tool()) }
                shizuku?.let { registry.register(it.commands.tool()) }
                val result = CatalogModelBridge(registry.snapshot(), capabilities, ledger, conversationId, runId, authorization)
                result.bindOwner(bound)
                synchronized(closeLock) {
                    check(!closed.get()) { "run_closed" }
                    bridge = result
                }
                result
            }
        } catch (failure: Exception) {
            if (creating) close()
            throw failure
        } finally { if (creating) endCreation() }
    }
    private fun <T> createResource(factory: () -> T): T = try { factory() }
    catch (failure: Exception) {
        // A throwing factory handed us no handle proving cleanup of any partial allocation.
        synchronized(closeLock) { recordCloseFailure() }
        throw failure
    }
    private fun beginCreation() = synchronized(closeLock) {
        check(!closed.get()) { "run_closed" }
        activeCreations++
    }
    private fun endCreation() {
        synchronized(closeLock) { activeCreations-- }
        advanceClose()
    }
    override fun execute(invocation: ToolInvocation): String = error("whole_batch_dispatch_required")
    private fun validateOwner(message: Message) {
        require(message.conversationId == conversationId && message.runId == runId && message.role == Message.ASSISTANT_ROLE) {
            "tool_run_owner_mismatch"
        }
        require(checkNotNull(owner) { "tool_owner_not_bound" }.owns(message)) { "tool_run_owner_mismatch" }
    }
    override fun execute(message: Message, invocations: List<ToolInvocation>, cancelled: () -> Boolean): List<ToolDispatchResult> {
        validateOwner(message)
        if (!beginDispatch()) return invocations.map { ToolDispatchResult(it.id, DispatchOutcome.NotExecuted("run_closed")) }
        try {
            return checkNotNull(bridge) { "catalog_not_prepared" }.execute(message, invocations) { closed.get() || cancelled() }
                .also(::observeOutcomes)
        } finally { endDispatch() }
    }
    override fun start(message: Message, invocations: List<ToolInvocation>, cancelled: () -> Boolean): ReceiptBatchStart {
        validateOwner(message)
        if (!beginDispatch()) return ReceiptBatchStart.Completed(
            invocations.map { ToolDispatchResult(it.id, DispatchOutcome.NotExecuted("run_closed")) })
        try {
            return checkNotNull(bridge) { "catalog_not_prepared" }.start(message, invocations) { closed.get() || cancelled() }
                .also { if (it is ReceiptBatchStart.Completed) observeOutcomes(it.receipts) }
        } finally { endDispatch() }
    }
    private fun observeOutcomes(results: List<ToolDispatchResult>) {
        // Includes ledger reservation conflicts and persistence failure AFTER a confirmed adapter reply.
        results.firstNotNullOfOrNull { it.outcome as? DispatchOutcome.Unknown }?.let {
            shizuku?.session?.markOutcomeUnknown(it.code.ifBlank { "dispatcher_outcome_unknown" })
        }
    }
    private fun beginDispatch(): Boolean = synchronized(closeLock) {
        if (closed.get()) false else { activeDispatches++; true }
    }
    private fun endDispatch() {
        synchronized(closeLock) { activeDispatches-- }
        notifyClosed()
    }
    // Fence publication under the same short lock as creation admission. Never wait for a factory,
    // capture, dispatch or drain callback on the UI/control thread.
    override fun close() {
        val resources = synchronized(closeLock) {
            closed.set(true)
            claimStops(listOfNotNull(shizuku, background, children, deviceResources, skills))
        }
        stopClaimed(resources)
    }
    // All callers hold closeLock. Claim once even when a stop throws; later observers never retry it.
    private fun claimStops(resources: List<AutoCloseable>): List<AutoCloseable> =
        resources.filter { stopped.add(it) }.also { pendingStops += it.size }

    private fun stopResources(resources: List<AutoCloseable>) =
        stopClaimed(synchronized(closeLock) { claimStops(resources) })

    private fun stopClaimed(resources: List<AutoCloseable>) {
        resources.forEach { resource ->
            try { closeResource(resource) }
            catch (_: Exception) {
                synchronized(closeLock) {
                    failedStops.add(resource)
                    recordCloseFailure()
                }
            } finally { synchronized(closeLock) { pendingStops-- } }
        }
        advanceClose()
    }
    // Called only with closeLock held; a generic code avoids leaking resource exception details.
    private fun recordCloseFailure(reason: String = "catalog_close_unconfirmed") {
        if (closeFailure == null) closeFailure = RunCatalogCloseResult.Unknown(reason)
    }
    internal fun revokeChildren() { background?.revoke() }

    /** Legacy callback means DRAINED only. Shizuku callers must use closeWithResult instead. */
    internal fun closeWhenIdle(onClosed: () -> Unit) = closeWithResult {
        if (it == RunCatalogCloseResult.Drained) onClosed()
    }

    /** One terminal observation, cached for later callers; Unknown is never retried or treated as drained. */
    internal fun closeWithResult(onClosed: (RunCatalogCloseResult) -> Unit) {
        synchronized(closeLock) {
            closeObservers.add(onClosed)
            observingClose = true
        }
        close()
    }
    private fun advanceClose() {
        val begin = synchronized(closeLock) {
            if (!closed.get() || !observingClose || observationsStarted || activeCreations != 0 || pendingStops != 0) false
            else {
                observationsStarted = true
                registeringObservations = true
                remainingObservations = 3
                true
            }
        }
        if (begin) {
            // Independent registrations: one broken resource must not suppress another's drain.
            observeResourceClose(children) { done ->
                if (children == null) done(RunCatalogCloseResult.Drained)
                else children.closeWhenIdle { done(RunCatalogCloseResult.Drained) }
            }
            observeResourceClose(deviceResources) { done ->
                if (deviceResources == null) done(RunCatalogCloseResult.Drained)
                else deviceResources.closeWhenIdle { done(RunCatalogCloseResult.Drained) }
            }
            observeResourceClose(shizuku) { done ->
                if (shizuku == null) done(RunCatalogCloseResult.Drained)
                else shizuku.session.closeWithResult { result ->
                    done(when (result) {
                        ShizukuCloseResult.Drained -> RunCatalogCloseResult.Drained
                        is ShizukuCloseResult.Unknown -> RunCatalogCloseResult.Unknown(result.reason)
                    })
                }
            }
            synchronized(closeLock) { registeringObservations = false }
        }
        notifyClosed()
    }
    private fun observeResourceClose(resource: AutoCloseable?, action: ((RunCatalogCloseResult) -> Unit) -> Unit) {
        val delivered = AtomicBoolean(false)
        val done: (RunCatalogCloseResult) -> Unit = { result ->
            if (delivered.compareAndSet(false, true)) {
                synchronized(closeLock) {
                    if (result is RunCatalogCloseResult.Unknown) recordCloseFailure(result.reason)
                    remainingObservations--
                }
                notifyClosed()
            }
        }
        if (synchronized(closeLock) { resource != null && resource in failedStops }) {
            done(RunCatalogCloseResult.Unknown("catalog_close_unconfirmed"))
            return
        }
        try { action(done) }
        catch (_: Exception) {
            // Also covers a registration that calls back synchronously and then throws.
            synchronized(closeLock) { recordCloseFailure() }
            done(RunCatalogCloseResult.Unknown("catalog_close_unconfirmed"))
        }
    }
    private fun notifyClosed() {
        val notification = synchronized(closeLock) {
            if (closeResult == null && observationsStarted && !registeringObservations &&
                activeCreations == 0 && pendingStops == 0 && activeDispatches == 0 &&
                (closeFailure != null || remainingObservations == 0)) {
                closeResult = closeFailure ?: RunCatalogCloseResult.Drained
            }
            val result = closeResult ?: return
            val callbacks = closeObservers.toList()
            closeObservers.clear()
            result to callbacks
        }
        // No lifecycle/setup lock is held; reentrant close/observation is safe.
        notification.second.forEach { callback -> runCatching { callback(notification.first) } }
    }
}
