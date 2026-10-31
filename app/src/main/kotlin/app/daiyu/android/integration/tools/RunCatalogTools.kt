package app.daiyu.android.integration.tools

import app.daiyu.android.ControllerVirtualDisplaySession
import app.daiyu.android.vdapproval.R08CleanupApprovalRequest
import app.daiyu.android.gui.GuiRunSession
import app.daiyu.android.gui.GuiScreenToolFactory
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
    terminal: CatalogTerminalBinding? = null,
    private val terminalJobs: TerminalJobSession? = null,
    private val browser: CatalogBrowserBinding? = null,
    private val observeBrowserClose: (CatalogBrowserBinding, (RunCatalogCloseResult) -> Unit) -> Unit = ::observeBrowserDrain,
    // Same-package failure injection; production always invokes the resource's own stop action.
    private val closeResource: (AutoCloseable) -> Unit = { it.close() },
    // Host factories run only after Runner supplies its actual opaque execution owner.
    private val authorizationFactory: ((RunExecutionOwner) -> TaskAuthorization)? = null,
    private val generationFactory: ((RunExecutionOwner, SessionCapabilities, TaskAuthorization) -> RunGenerationTools)? = null,
    private val virtualDisplayFactory: ((RunExecutionOwner, SessionCapabilities, TaskAuthorization) -> R08VirtualDisplayCatalogLeaf)? = null,
    // Return an UNBOUND session; this catalog invokes its authorizationFactory with Runner's token.
    // Session owns authorization and its leaf; never also supply authorizationFactory or a leaf factory.
    virtualDisplaySession: ControllerVirtualDisplaySession? = null,
    private val virtualDisplaySessionFactory: ((RunExecutionOwner, SessionCapabilities) -> ControllerVirtualDisplaySession)? = null,
    private val memoryContext: app.daiyu.android.assistants.AssistantRunContext? = null,
    // Explicit trusted child admission only. Runs at worker schema setup, NOT in bindOwner,
    // and only after the parent Runner's durable admission. Text-only tools are unchanged.
    private val onCatalogReady: ((RunExecutionOwner, SessionCapabilities, TaskAuthorization) -> Unit)? = null,
    private val gui: GuiRunSession? = null,
    private val observeGuiClose: (GuiRunSession, (RunCatalogCloseResult) -> Unit) -> Unit = ::observeGuiDrain,
    private val mcp: app.daiyu.android.mcp.McpRunScope? = null,
    private val liveMcp: app.daiyu.android.mcp.McpLiveScope? = null,
    private val platformAction: PlatformActionScope? = null,
    private val commandFactory: ((RunExecutionOwner, TaskAuthorization) -> RunCommandTools)? = null,
    private val childTaskAccess: RunChildTaskAccess? = null,
    private val ordinaryFactory: ((RunExecutionOwner, SessionCapabilities, TaskAuthorization) -> List<RunOwnedToolScope>)? = null,
    private val workspacePin: AutoCloseable? = null,
    private val referenceReader: app.daiyu.android.references.ConversationReferenceReader? = null,
    private val allowedToolNames: Set<String>? = null,
    private val capture: (SessionCapabilities, TaskAuthorization) -> SkillRunSession
) : CatalogTool, ReceiptBatchToolExecutor, AutoCloseable {
    // No temporary owner or authorization for generation. Legacy read-only callers retain
    // their empty authorization; a host factory never falls back to one if binding fails.
    private val usesVirtualDisplaySession = virtualDisplaySession != null || virtualDisplaySessionFactory != null
    @Volatile private var retainedVirtualDisplaySession = virtualDisplaySession
    private var virtualDisplaySessionDrained = false
    private val initialAuthorization: TaskAuthorization? = authorization ?: if (authorizationFactory != null || usesVirtualDisplaySession) {
        terminal?.authorization ?: terminalJobs?.authorization // Pre-approved terminal pins the exact object.
    } else run {
        require(shizuku == null) { "shizuku_task_authorization_required" }
        require(terminal == null && terminalJobs == null) { "terminal_task_authorization_required" }
        require(browser == null) { "browser_task_authorization_required" }
        require(gui == null) { "gui_task_authorization_required" }
        require(generationFactory == null) { "generation_task_authorization_required" }
        require(virtualDisplayFactory == null) { "virtual_display_task_authorization_required" }
        TaskAuthorization(capabilities.sessionId, runId.value)
    }
    @Volatile private var boundAuthorization: TaskAuthorization? = null
    // No PTY is opened here, during binding, capture, advertising, or preparation.
    private val terminalLeaf = terminal?.let {
        TerminalCatalogLeaf(it, capabilities, ledger, checkNotNull(initialAuthorization), conversationId, runId)
    }
    init {
        require(terminal == null || terminalJobs == null) { "terminal_modes_conflict" }
        terminalJobs?.let {
            require(it.authorization === initialAuthorization && it.plan.conversationId == conversationId &&
                it.plan.runId == runId && it.plan.sessionId == capabilities.sessionId) { "terminal_job_approval_mismatch" }
        }
        require(virtualDisplaySession == null || virtualDisplaySessionFactory == null) { "virtual_display_session_ambiguous" }
        require(!usesVirtualDisplaySession || (virtualDisplayFactory == null && authorizationFactory == null)) {
            "virtual_display_session_factory_conflict"
        }
        require(mcp == null || liveMcp == null) { "mcp_modes_conflict" }
        require(mcp == null || (authorization === mcp.authorization && authorizationFactory == null && !usesVirtualDisplaySession)) {
            "mcp_task_authorization_required"
        }
        require(platformAction == null || (authorization === platformAction.authorization &&
            authorizationFactory == null && !usesVirtualDisplaySession &&
            platformAction.conversationId == conversationId && platformAction.runId == runId)) {
            "platform_task_authorization_required"
        }
        initialAuthorization?.let(::validateAuthorization)
        require(shizuku == null || (shizuku.session.sessionId == capabilities.sessionId && shizuku.session.runId == runId.value)) {
            "shizuku_approval_run_mismatch"
        }
    }
    private fun validateAuthorization(authorization: TaskAuthorization) {
        require(authorization.sessionId == capabilities.sessionId && authorization.taskId == runId.value) {
            "tool_run_owner_mismatch"
        }
    }
    private val closed = AtomicBoolean(false)
    @Volatile private var skills: SkillRunSession? = null
    @Volatile private var bridge: CatalogModelBridge? = null
    private val closeLock = Any()
    private var activeDispatches = 0
    private val commandDispatchLock = Any()
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
    @Volatile private var generation: GenerationCatalogLeaf? = null
    @Volatile private var virtualDisplay: R08VirtualDisplayCatalogLeaf? = null
    private var browserTools: List<RegisteredTool> = emptyList()
    @Volatile private var commands: RunCommandTools? = null
    @Volatile private var ordinary: List<RunOwnedToolScope> = emptyList()
    @Volatile private var owner: RunExecutionOwner? = null
    private var binding = false

    override fun bindOwner(owner: RunExecutionOwner) {
        synchronized(closeLock) {
            require(owner.conversationId == conversationId && owner.runId == runId) { "tool_run_owner_mismatch" }
            check(!closed.get()) { "run_closed" }
            this.owner?.let {
                check(it === owner) { "tool_owner_already_bound" }
                return // The factories and leaf registration belong to exactly one binding.
            }
            check(!binding) { "tool_owner_binding_in_progress" }
            binding = true
            activeCreations++
        }
        var createdBackground: RunChildTasks? = null
        var createdGeneration: GenerationCatalogLeaf? = null
        var createdVirtualDisplay: R08VirtualDisplayCatalogLeaf? = null
        var createdCommands: RunCommandTools? = null
        try {
            // No catalog lifecycle/setup lock is held across host code or external open.
            val session = retainedVirtualDisplaySession ?: virtualDisplaySessionFactory?.let { factory ->
                createResource { factory(owner, capabilities) }.also { created ->
                    // Keep the returned session even if close won while its factory was running.
                    synchronized(closeLock) { retainedVirtualDisplaySession = created }
                }
            }
            if (closed.get()) {
                session?.let { stopResources(listOf(it)) }
                error("run_closed")
            }
            val authorization = when {
                session != null -> session.authorizationFactory(owner)
                authorizationFactory != null -> authorizationFactory.invoke(owner)
                else -> checkNotNull(initialAuthorization)
            }
            validateAuthorization(authorization)
            require(initialAuthorization == null || authorization === initialAuthorization) { "tool_run_authorization_mismatch" }
            check(!closed.get()) { "run_closed" }
            gui?.bind(owner, authorization)
            deviceResources?.bind(owner, authorization, capabilities)
            children?.bind(owner, capabilities, authorization)
            shizuku?.commands?.bind(owner, capabilities, authorization)
            platformAction?.bind(owner, capabilities, authorization)
            terminalLeaf?.bindOwner(owner)
            terminalJobs?.bind(owner, capabilities, authorization)
            mcp?.bind(owner, capabilities, authorization)
            liveMcp?.bind(owner, capabilities, authorization)
            val boundBrowserTools = browser?.bindOwner(owner, authorization)?.tools ?: emptyList()
            createdBackground = backgroundFactory?.let { factory ->
                createResource { factory(owner, capabilities, authorization) }
            }
            check(!closed.get()) { "run_closed" }
            createdGeneration = generationFactory?.let { factory ->
                createResource { GenerationCatalogLeaf(factory(owner, capabilities, authorization)) }
            }
            check(!closed.get()) { "run_closed" }
            createdVirtualDisplay = virtualDisplayFactory?.let { factory ->
                createResource { factory(owner, capabilities, authorization) }
            }
            // Retain the returned handle even if bind throws or close wins the race.
            createdVirtualDisplay?.bind(owner, capabilities, authorization)
            createdCommands = commandFactory?.let { factory ->
                createResource { factory(owner, authorization) }
            }
            ordinary = createResource { ordinaryFactory?.invoke(owner, capabilities, authorization).orEmpty() }
            val publish = synchronized(closeLock) {
                commands = createdCommands
                // Retain late resources even after close so their physical drain is observed.
                background = createdBackground
                generation = createdGeneration
                virtualDisplay = createdVirtualDisplay
                if (closed.get()) false else {
                    browserTools = boundBrowserTools
                    boundAuthorization = authorization
                    this.owner = owner // Publish only the complete binding.
                    true
                }
            }
            check(publish) { "run_closed" }
        } catch (failure: Exception) {
            synchronized(closeLock) {
                background = createdBackground
                generation = createdGeneration
                virtualDisplay = createdVirtualDisplay
                commands = createdCommands
            }
            close()
            throw failure
        } finally {
            synchronized(closeLock) { binding = false }
            endCreation()
        }
    }

    override val definitions: List<app.daiyu.android.domain.ToolDefinition> get() = initialized().definitions

    private fun initialized(): CatalogModelBridge {
        var creating = false
        try {
            return synchronized(setupLock) {
                check(!closed.get()) { "run_closed" }
                bridge?.let { return@synchronized it }
                val bound = checkNotNull(owner) { "tool_owner_not_bound" }
                val authorization = checkNotNull(boundAuthorization) { "tool_owner_not_bound" }
                beginCreation(); creating = true
                val scope = capture(capabilities, authorization)
                val publish = synchronized(closeLock) {
                    if (closed.get()) false else { skills = scope; true }
                }
                if (!publish) {
                    stopResources(listOf(scope))
                    error("run_closed")
                }
                ScopedSkillTools.create(scope, authorization).forEach { registry.register(it) }
                memoryContext?.let { context ->
                    ScopedAssistantMemoryTools.create(context) {
                        synchronized(closeLock) {
                            !closed.get() && owner === bound && boundAuthorization === authorization
                        }
                    }.forEach { registry.register(it) }
                }
                childTaskAccess?.tools()?.forEach { registry.register(it) }
                children?.let { registry.register(it.tool()) }
                background?.let { registry.register(it.tool()); registry.register(it.commandTool()) }
                shizuku?.let { registry.register(it.commands.tool()) }
                platformAction?.tools()?.forEach { registry.register(it) }
                terminalLeaf?.registeredTools?.forEach { registry.register(it) }
                terminalJobs?.tools(capabilities)?.forEach { registry.register(it) }
                commands?.tools()?.forEach { registry.register(it) }
                browserTools.forEach { registry.register(it) }
                ordinary.flatMap { it.tools }.forEach { registry.register(it) }
                gui?.let { GuiScreenToolFactory.create(it).forEach { tool -> registry.register(tool) } }
                mcp?.registeredTools()?.forEach { registry.register(it) }
                generation?.registeredTools?.forEach { registry.register(it) }
                virtualDisplay?.registeredTools?.forEach { registry.register(it) }
                retainedVirtualDisplaySession?.registeredTools(bound, capabilities, authorization)?.forEach { registry.register(it) }
                val snapshot = registry.snapshot().let { catalog -> allowedToolNames?.let(catalog::restrictedTo) ?: catalog }
                // Exact, short-lived screen confirmations never suspend through a user barrier.
                // A suspended receipt completes outside this catalog's final-result boundary.
                // Fail closed instead of allowing that completion to bypass the GUI Unknown fence.
                require(gui == null || snapshot.available(capabilities).none { it.effect == ToolEffect.USER_BARRIER }) {
                    "gui_user_barrier_not_supported"
                }
                val result = CatalogModelBridge(snapshot, capabilities, ledger, conversationId, runId, authorization) {
                    val latest = liveMcp?.let { snapshot.withAdditional(it.registeredTools()) } ?: snapshot
                    allowedToolNames?.let(latest::restrictedTo) ?: latest
                }
                result.bindOwner(bound)
                synchronized(closeLock) {
                    check(!closed.get()) { "run_closed" }
                    bridge = result
                }
                onCatalogReady?.invoke(bound, capabilities, authorization)
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
    override fun execute(message: Message, invocations: List<ToolInvocation>, cancelled: () -> Boolean): List<ToolDispatchResult> =
        if (commandFactory == null) executeBatch(message, invocations, cancelled)
        else synchronized(commandDispatchLock) { executeBatch(message, invocations, cancelled) }

    private fun executeBatch(message: Message, invocations: List<ToolInvocation>, cancelled: () -> Boolean): List<ToolDispatchResult> {
        validateOwner(message)
        val batch = CatalogBatch(invocations.toList())
        // A closed catalog cannot open a leaf ticket: leaf revocation is already final.
        if (!beginDispatch()) return batch.invocations.map { ToolDispatchResult(it.id, DispatchOutcome.NotExecuted("run_closed")) }
        try {
            batch.begin()
            return checkNotNull(bridge) { "catalog_not_prepared" }.execute(message, batch.invocations) { closed.get() || cancelled() }
                .also(batch::finish)
        } catch (failure: Throwable) {
            batch.unconfirmed()
            throw failure
        } finally { endDispatch() }
    }
    override fun start(message: Message, invocations: List<ToolInvocation>, cancelled: () -> Boolean): ReceiptBatchStart =
        if (commandFactory == null) startBatch(message, invocations, cancelled)
        else synchronized(commandDispatchLock) { startBatch(message, invocations, cancelled) }

    private fun startBatch(message: Message, invocations: List<ToolInvocation>, cancelled: () -> Boolean): ReceiptBatchStart {
        validateOwner(message)
        val batch = CatalogBatch(invocations.toList())
        if (!beginDispatch()) return ReceiptBatchStart.Completed(
            batch.invocations.map { ToolDispatchResult(it.id, DispatchOutcome.NotExecuted("run_closed")) })
        try {
            batch.begin()
            return checkNotNull(bridge) { "catalog_not_prepared" }.start(message, batch.invocations) { closed.get() || cancelled() }
                .also { result ->
                    when (result) {
                        is ReceiptBatchStart.Completed -> batch.finish(result.receipts)
                        // The central dispatcher only suspends a singleton USER_BARRIER.
                        // Any R08 + barrier batch is rejected whole before execution.
                        // An unexpected suspension must leave its ticket pending/Unknown,
                        // never masquerade as a final result or an empty successful batch.
                        is ReceiptBatchStart.Suspended -> check(!batch.hasTicket) { "virtual_display_unexpected_suspension" }
                    }
                }
        } catch (failure: Throwable) {
            batch.unconfirmed()
            throw failure
        } finally { endDispatch() }
    }

    /** A relevant batch holds the immutable ORIGINAL WHOLE batch, not a filtered R08 subset.
     * Pure ask_user can suspend/resume independently: it owns no virtual-display operations.
     * All leaf/journal calls run outside closeLock. Missing/throwing feedback is never success. */
    private inner class CatalogBatch(val invocations: List<ToolInvocation>) {
        private val relevant = invocations.any {
            it.name == R08VirtualDisplayCatalogLeaf.WRITE_TOOL || it.name == R08VirtualDisplayCatalogLeaf.READ_TOOL
        }
        private val leaf = virtualDisplay?.takeIf { relevant }
        private val session = retainedVirtualDisplaySession?.takeIf { relevant }
        private var ticket: R08VirtualDisplayCatalogLeaf.BatchTicket? = null
        private var sessionTicket: ControllerVirtualDisplaySession.BatchTicket? = null
        private var finalForwarded = false
        val hasTicket: Boolean get() = ticket != null || sessionTicket != null
        fun begin() {
            // Mutually exclusive routes. The Session already owns its leaf: never bind another.
            if (session != null) sessionTicket = session.beginBatch(invocations)
            else ticket = leaf?.beginBatch(invocations)
        }
        fun finish(results: List<ToolDispatchResult>) {
            check(!finalForwarded) { "catalog_batch_already_finished" }
            finalForwarded = true // Consume before external feedback, even if feedback throws.
            try { observeOutcomes(results) }
            finally {
                if (session != null) sessionTicket?.let { session.finishBatch(it, results) }
                else ticket?.let { checkNotNull(leaf).finishBatch(it, results) }
            }
        }
        fun unconfirmed() {
            ordinary.forEach { it.markUnknown() }
            commands?.markUnknown()
            gui?.onCentralUnknown() // missing final central feedback also fails closed, with no retry
            mcp?.markUnknown()
            liveMcp?.close()
            platformAction?.markUnknown()
            if (leaf != null || session != null) synchronized(closeLock) { recordCloseFailure("virtual_display_batch_unconfirmed") }
            // Do not invent results or finish a ticket when no final central result exists.
        }
    }
    private fun observeOutcomes(results: List<ToolDispatchResult>) {
        // Includes ledger reservation conflicts and persistence failure AFTER a confirmed adapter reply.
        // Use the entire central result, never a second leaf dispatcher or adapter-only receipts.
        val unknown = results.firstNotNullOfOrNull { it.outcome as? DispatchOutcome.Unknown }
        if (unknown != null) ordinary.forEach { it.markUnknown() }
        if (unknown != null) gui?.onCentralUnknown() // before any external feedback can throw
        terminalLeaf?.observeOutcomes(results)
        terminalJobs?.observeOutcomes(results)
        commands?.observeOutcomes(results)
        unknown?.let {
            mcp?.markUnknown()
            liveMcp?.close()
            shizuku?.session?.markOutcomeUnknown(it.code.ifBlank { "dispatcher_outcome_unknown" })
            platformAction?.markUnknown()
        }
    }
    private fun beginDispatch(): Boolean = synchronized(closeLock) {
        if (closed.get()) false else { activeDispatches++; true }
    }
    private fun endDispatch() {
        synchronized(closeLock) { activeDispatches-- }
        advanceClose()
    }
    // Fence publication under the same short lock as creation admission. Never wait for a factory,
    // capture, dispatch or drain callback on the UI/control thread.
    override fun close() {
        val resources = synchronized(closeLock) {
            closed.set(true)
            generation?.revokeAdmission()
            // Fence the terminal before stopping other resources; join is observed separately.
            // R08 close is revocation only. Never synthesize a release/shutdown operation.
            claimStops(listOfNotNull(mcp, liveMcp, gui, browser, generation, virtualDisplay, retainedVirtualDisplaySession, terminalLeaf, terminalJobs, commands, shizuku, platformAction, background, children, deviceResources, skills, referenceReader) + ordinary)
        }
        browser?.revoke()
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

    /** Same retained object for the accepted-run record/UI/explicit maintenance; never a new owner.
     * Factories MUST retain it in that record before returning (including failed/late binding).
     * Do not clear that record on close, cancellation, Unknown or a Runner stop request. */
    internal fun virtualDisplaySessionForRetention(): ControllerVirtualDisplaySession? = retainedVirtualDisplaySession

    /** Caller holds closeLock. This is LOCAL drain, not physical owner exit and not the final
     * catalog Drained callback (which deliberately waits for this session). Never observe the
     * session to probe this gate: cleanup must be possible while its initialized owner is alive.
     * Closed admission + zero creations/dispatches includes final whole-batch forwarding.
     */
    private fun localDrainReadyLocked(): Boolean =
        closed.get() && observingClose && observationsStarted && !registeringObservations &&
            activeCreations == 0 && activeDispatches == 0 && pendingStops == 0 &&
            remainingObservations == 0 && closeFailure == null

    /** Pure identity/local-state check. Parent Runner/retention/UI evidence is ADDITIONAL;
     * passing that evidence never bypasses the catalog's private lifecycle accounting. */
    internal fun virtualDisplayCleanupLocallyDrained(original: ControllerVirtualDisplaySession): Boolean =
        synchronized(closeLock) { retainedVirtualDisplaySession === original && localDrainReadyLocked() }

    /** Explicit non-releasing preparation. No observe, lease release or owner I/O. */
    internal fun prepareVirtualDisplayCleanupOnWorker(original: ControllerVirtualDisplaySession,
        parentDrained: Boolean): R08CleanupApprovalRequest? {
        check(android.os.Looper.getMainLooper()?.thread !== Thread.currentThread()) { "virtual_display_worker_required" }
        if (!parentDrained || !virtualDisplayCleanupLocallyDrained(original)) return null
        return original.prepareInitializedCleanupOnWorker(parentDrained = true)
    }

    internal fun shutdownVirtualDisplayUninitializedOnWorker(original: ControllerVirtualDisplaySession,
        parentDrained: Boolean, admissionStillValid: () -> Boolean = { true }): Boolean {
        check(android.os.Looper.getMainLooper()?.thread !== Thread.currentThread()) { "virtual_display_worker_required" }
        if (!parentDrained || !virtualDisplayCleanupLocallyDrained(original)) return false
        return original.shutdownUninitializedOnceOnWorker(parentDrained = true,
            admissionStillValid = admissionStillValid)
    }

    internal fun observeVirtualDisplayTasksOnWorker(original: ControllerVirtualDisplaySession,
        parentDrained: Boolean): ControllerVirtualDisplaySession.TasksResult {
        check(android.os.Looper.getMainLooper()?.thread !== Thread.currentThread()) { "virtual_display_worker_required" }
        if (!parentDrained || !virtualDisplayCleanupLocallyDrained(original))
            return ControllerVirtualDisplaySession.TasksResult.Unavailable
        return original.observeTasksOnceOnWorker(true)
    }

    internal fun prepareVirtualDisplayTaskHandoffOnWorker(original: ControllerVirtualDisplaySession,
        tasks: ControllerVirtualDisplaySession.RetainedTasks, selected: List<Int>, parentDrained: Boolean,
        admissionStillValid: () -> Boolean): R08CleanupApprovalRequest? {
        check(android.os.Looper.getMainLooper()?.thread !== Thread.currentThread()) { "virtual_display_worker_required" }
        if (!parentDrained || !virtualDisplayCleanupLocallyDrained(original)) return null
        return original.prepareTaskHandoffOnWorker(tasks, selected, true, admissionStillValid)
    }

    /** Recheck the SAME gate at send admission; an earlier preparation/approval is not drain
     * evidence. The session still owns approval, fixed journal intent and one-attempt semantics.
     * No external/session code runs under closeLock. Closed local admission cannot reopen.
     */
    internal fun releaseVirtualDisplayCleanupOnWorker(original: ControllerVirtualDisplaySession,
        request: R08CleanupApprovalRequest, parentDrained: Boolean,
        admissionStillValid: () -> Boolean = { true }): ControllerVirtualDisplaySession.CleanupResult {
        check(android.os.Looper.getMainLooper()?.thread !== Thread.currentThread()) { "virtual_display_worker_required" }
        if (!parentDrained || !virtualDisplayCleanupLocallyDrained(original))
            return ControllerVirtualDisplaySession.CleanupResult.UNAVAILABLE
        return original.releaseInitializedOnceOnWorker(request, parentDrained = true,
            admissionStillValid = admissionStillValid)
    }

    /** Explicit WORKER maintenance after closeWithResult has begun and the parent's Runner has
     * actually returned/joined. A stop request is not parentDrained. Local creation, dispatch,
     * final forwarding, stop actions and other resource observations must also have finished.
     * No owner I/O or lease release runs on the UI/control lane or under closeLock.
     * Unknown is an observation, not a retry of any write: retain and observe physical exit later.
     * Only Drained permits removing the accepted-run/session retention record.
     * Uninitialized shutdown, if needed, remains a separate explicit session worker operation. */
    internal fun observeVirtualDisplayCloseOnWorker(parentDrained: Boolean): RunCatalogCloseResult {
        check(android.os.Looper.getMainLooper()?.thread !== Thread.currentThread()) { "virtual_display_worker_required" }
        val session = synchronized(closeLock) {
            if (!parentDrained || !localDrainReadyLocked())
                return RunCatalogCloseResult.Unknown("virtual_display_parent_pending")
            // Read the retained handle atomically with creation-drain evidence: a late factory
            // must not be mistaken for the never-created case.
            retainedVirtualDisplaySession
        }
        // No session after a successful local drain means its factory was never entered.
        // A throwing/unfinished factory cannot pass localDrained (failure/creation accounting).
        val result = session?.observeCloseOnWorker(parentDrained = true)
        if (result != null && result != ControllerVirtualDisplaySession.CloseResult.Drained) {
            return RunCatalogCloseResult.Unknown("virtual_display_close_unconfirmed")
        }
        synchronized(closeLock) { virtualDisplaySessionDrained = true }
        notifyClosed()
        return RunCatalogCloseResult.Drained
    }

    /** Legacy callback means DRAINED only. External bindings must use closeWithResult instead. */
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
            // The terminal leaf's own dispatcher is not used by this catalog. Its worker join
            // must therefore start only after OUR final receipt persistence/observation settles.
            if (!closed.get() || !observingClose || observationsStarted || activeCreations != 0 || pendingStops != 0 ||
                activeDispatches != 0) false
            else {
                observationsStarted = true
                registeringObservations = true
                remainingObservations = 13 + ordinary.size
                true
            }
        }
        if (begin) {
            ordinary.forEach { scope -> observeResourceClose(scope, scope::observeClose) }
            // Independent registrations: one broken resource must not suppress another's drain.
            observeResourceClose(gui) { done ->
                if (gui == null) done(RunCatalogCloseResult.Drained)
                else observeGuiClose(gui, done)
            }
            observeResourceClose(liveMcp) { done ->
                if (liveMcp == null) done(RunCatalogCloseResult.Drained)
                else liveMcp.observeDrained { done(RunCatalogCloseResult.Drained) }
            }
            observeResourceClose(mcp) { done ->
                if (mcp == null) done(RunCatalogCloseResult.Drained)
                else mcp.observeDrained { done(RunCatalogCloseResult.Drained) }
            }
            observeResourceClose(children) { done ->
                if (children == null) done(RunCatalogCloseResult.Drained)
                else children.closeWhenIdle { done(RunCatalogCloseResult.Drained) }
            }
            observeResourceClose(deviceResources) { done ->
                if (deviceResources == null) done(RunCatalogCloseResult.Drained)
                else deviceResources.closeWhenIdle { done(RunCatalogCloseResult.Drained) }
            }
            observeResourceClose(platformAction) { done ->
                if (platformAction == null) done(RunCatalogCloseResult.Drained)
                else platformAction.observeClose(done)
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
            observeResourceClose(terminalLeaf) { done ->
                if (terminalLeaf == null) done(RunCatalogCloseResult.Drained)
                else terminalLeaf.observeClose(done)
            }
            observeResourceClose(commands) { done ->
                if (commands == null) done(RunCatalogCloseResult.Drained)
                else commands!!.observeClose(done)
            }
            observeResourceClose(terminalJobs) { done ->
                if (terminalJobs == null) done(RunCatalogCloseResult.Drained)
                else terminalJobs.observeClose(done)
            }
            observeResourceClose(browser) { done ->
                if (browser == null) done(RunCatalogCloseResult.Drained)
                else observeBrowserClose(browser, done)
            }
            observeResourceClose(generation) { done ->
                if (generation == null) done(RunCatalogCloseResult.Drained)
                else generation!!.observeClose(done)
            }
            observeResourceClose(virtualDisplay) { done ->
                val leaf = virtualDisplay
                if (leaf == null) done(RunCatalogCloseResult.Drained)
                else leaf.observeClose { result ->
                    done(when (result) {
                        R08VirtualDisplayCloseResult.Drained -> RunCatalogCloseResult.Drained
                        is R08VirtualDisplayCloseResult.Unknown -> RunCatalogCloseResult.Unknown(result.code)
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
    private var workspaceReleaseStarted = false
    private var workspaceReleaseFinished = false
    private fun notifyClosed() {
        val release = synchronized(closeLock) {
            if (localDrainReadyLocked() && (!usesVirtualDisplaySession || virtualDisplaySessionDrained) &&
                workspacePin != null && !workspaceReleaseStarted) {
                workspaceReleaseStarted = true
                true
            } else false
        }
        if (release) {
            val failure = runCatching { workspacePin?.close() }.exceptionOrNull()
            synchronized(closeLock) {
                workspaceReleaseFinished = true
                if (failure != null) recordCloseFailure("workspace_close_unconfirmed")
            }
        }
        val notification = synchronized(closeLock) {
            if (closeResult == null && observationsStarted && !registeringObservations &&
                activeCreations == 0 && pendingStops == 0 && activeDispatches == 0 &&
                (closeFailure != null || (remainingObservations == 0 &&
                    (!usesVirtualDisplaySession || virtualDisplaySessionDrained) &&
                    (workspacePin == null || workspaceReleaseFinished)))) {
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

/** A close request or consumer timeout is not a GUI release receipt. Pending captures and
 * attached overlays retain the real lease. Unknown keeps the parent's retention record pinned. */
internal fun observeGuiDrain(session: GuiRunSession, complete: (RunCatalogCloseResult) -> Unit) {
    val deadline = android.os.SystemClock.uptimeMillis() + 30_000L
    val handler = android.os.Handler(android.os.Looper.getMainLooper())
    val observe = object : Runnable {
        override fun run() {
            when {
                session.releaseConfirmed -> complete(RunCatalogCloseResult.Drained)
                android.os.SystemClock.uptimeMillis() >= deadline ->
                    complete(RunCatalogCloseResult.Unknown("gui_close_unconfirmed"))
                !handler.postDelayed(this, 50L) ->
                    complete(RunCatalogCloseResult.Unknown("gui_close_observation_unavailable"))
            }
        }
    }
    observe.run()
}

/** Native browser close is nonblocking; retain its owner until observed, not merely requested. */
internal fun observeBrowserDrain(browser: CatalogBrowserBinding, complete: (RunCatalogCloseResult) -> Unit) {
    val deadline = android.os.SystemClock.uptimeMillis() + 30_000L
    val handler = android.os.Handler(android.os.Looper.getMainLooper())
    val observe = object : Runnable {
        override fun run() {
            val result = try { browser.closeResult() } catch (_: Exception) {
                RunCatalogCloseResult.Unknown("browser_close_observation_failed")
            }
            when {
                result != null -> complete(result)
                android.os.SystemClock.uptimeMillis() >= deadline ->
                    complete(RunCatalogCloseResult.Unknown("browser_close_timeout"))
                !handler.postDelayed(this, 50L) ->
                    complete(RunCatalogCloseResult.Unknown("browser_close_observation_unavailable"))
            }
        }
    }
    observe.run()
}
