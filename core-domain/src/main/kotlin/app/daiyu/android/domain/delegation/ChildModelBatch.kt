package app.daiyu.android.domain.delegation

import app.daiyu.android.domain.*
import app.daiyu.android.domain.runtime.RunExecutionOwner
import app.daiyu.android.domain.runtime.RunnerOptions
import app.daiyu.android.domain.runtime.RunCheckpoint
import app.daiyu.android.domain.runtime.RuntimeStore
import app.daiyu.android.domain.usage.ChildUsageRecorder
import app.daiyu.android.domain.tools.*
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean


data class ChildModelTask(val profileId: String, val prompt: String, val access: ChildToolAccess = ChildToolAccess.NONE)

/** An exact host-frozen selection. Zero means no extra model-pool limit. */
class ChildModelDefinition(val poolKey: String, val parallelLimit: Int, val display: ChildTaskDisplay?, val create: () -> ModelPort) {
    private var contextFactory: ((Message) -> app.daiyu.android.domain.runtime.RunContextRuntime?)? = null
    fun withContext(factory: (Message) -> app.daiyu.android.domain.runtime.RunContextRuntime?): ChildModelDefinition = apply {
        contextFactory = factory
    }
    internal val hasContext: Boolean get() = contextFactory != null
    fun contextFor(initial: Message) = contextFactory?.invoke(initial)
    var role: ChildModelRole = ChildModelRole.REVIEW
        private set
    var requestModel: ChildRequestModel? = null
        private set
    fun routed(providerId: String, modelId: String): ChildModelDefinition = apply { requestModel = ChildRequestModel(providerId, modelId) }
    var profileId: String? = null
        private set
    var modelIdentity: String? = null
        private set
    var groupToken: String? = null
        private set
    fun identified(profileId: String, modelIdentity: String, groupToken: String?): ChildModelDefinition = apply {
        this.profileId = profileId
        this.modelIdentity = modelIdentity
        this.groupToken = groupToken
    }
    private var taskFactory: (() -> ChildModelDefinition)? = null
    private var candidates: (() -> List<ChildModelDefinition>)? = null
    private var choose: ((ChildModelDefinition) -> Boolean)? = null
    fun taskScoped(factory: () -> ChildModelDefinition): ChildModelDefinition = apply { taskFactory = factory }
    fun forTask(): ChildModelDefinition = taskFactory?.invoke() ?: this
    fun scheduling(candidates: () -> List<ChildModelDefinition>,
        select: (ChildModelDefinition) -> Boolean): ChildModelDefinition = apply {
        this.candidates = candidates; this.choose = select
    }
    fun currentCandidates(): List<ChildModelDefinition> = candidates?.invoke() ?: listOf(current())
    fun select(candidate: ChildModelDefinition): Boolean = choose?.invoke(candidate) ?: candidate.sameSelection(current())
    private var latest: (() -> ChildModelDefinition)? = null
    private var selection: Any? = null
    private var valid: () -> Boolean = { true }
    private var admissionAllowed: () -> Boolean = { true }
    constructor(poolKey: String, parallelLimit: Int, display: ChildTaskDisplay?,
        latest: (() -> ChildModelDefinition)?, selection: Any, valid: () -> Boolean = { true },
        admissionAllowed: () -> Boolean = { true }, role: ChildModelRole = ChildModelRole.REVIEW, create: () -> ModelPort) : this(poolKey, parallelLimit, display, create) {
        this.latest = latest
        this.selection = selection
        this.valid = valid
        this.admissionAllowed = admissionAllowed
        this.role = role
    }
    fun isValid(): Boolean = valid()
    fun acceptsNewTask(): Boolean = admissionAllowed()
    fun current(): ChildModelDefinition {
        check(isValid()) { "child_profile_revoked" }
        val selected = latest?.invoke() ?: this
        check(isValid()) { "child_profile_revoked" }
        return selected
    }
    fun sameRequest(other: ChildModelDefinition): Boolean = role == other.role &&
        (selection ?: this) == (other.selection ?: other)
    fun sameSelection(other: ChildModelDefinition): Boolean = poolKey == other.poolKey && parallelLimit == other.parallelLimit &&
        role == other.role && display == other.display && (selection ?: this) == (other.selection ?: other)
    constructor(poolKey: String, parallelLimit: Int, create: () -> ModelPort) : this(poolKey, parallelLimit, null, create)
    constructor(poolKey: String, parallelLimit: Int, role: ChildModelRole, create: () -> ModelPort) :
        this(poolKey, parallelLimit, null, create) { this.role = role }
    init { require(poolKey.isNotBlank()); require(parallelLimit >= 0) }
}

/** Synchronous parent tool, internally parallel, with optional durable per-child observation. */
class ChildModelBatch(models: Map<String, ChildModelDefinition>, private val timeoutMillis: Long = 120_000,
    private val store: ChildTaskStore? = null,
    private val usageRecorder: ChildUsageRecorder? = null,
    private val currentModels: (() -> Map<String, ChildModelDefinition>)? = null,
    private val onProgress: (ChildRunOwner, String) -> Unit = { _, _ -> },
    private val sharedPool: ((ChildModelDefinition) -> ChildPoolLease)? = null,
    private val onProfilesChanged: () -> Unit = {},
    private val toolAssembly: ChildToolAssemblyFactory? = null,
    private val diagnostics: ChildTaskDiagnostics? = null,
    private val onPoolWaiting: (Boolean) -> Unit = {}) : AutoCloseable {
    private val models = models.toMap()
    private fun limits(models: Map<String, ChildModelDefinition>) = models.values.groupBy { it.poolKey }.mapValues { (_, entries) ->
        require(entries.map { it.parallelLimit }.distinct().size == 1) { "child_pool_limit_conflict" }
        ChildModelPool(entries.first().parallelLimit)
    }
    private val localPools = limits(this.models).toMutableMap()
    fun configurationChanged(limit: (String) -> Int?) {
        val updates = synchronized(gate) { localPools.toMap() }
        updates.forEach { (key, pool) -> limit(key)?.let { pool.resize(it) } }
        val invalid = synchronized(gate) { runnerDefinitions.toList() }
            .filter { !runCatching { it.second.isValid() }.getOrDefault(false) }
        invalid.forEach { (runner, _) ->
            val child = synchronized(gate) { activeChildren.values.firstOrNull { it.runner === runner } }
            child?.let { pauseForConfiguration(it.record.owner) }
        }
    }
    private val runnerDefinitions = mutableMapOf<ChatRunner, ChildModelDefinition>()
    private class ActiveChild(var record: ChildTaskRecord, val assignment: ChildModelAssignment,
        val pause: ChildTaskPauseControl) {
        var runner: ChatRunner? = null
        var partial = ""
        var selected: ChildModelDefinition? = null
        var request: app.daiyu.android.domain.runtime.RequestCheckpoint? = null
        val requestIds = linkedSetOf<String>()
    }
    private val activeChildren = mutableMapOf<ChildRunOwner, ActiveChild>()

    private fun trace(child: ActiveChild, phase: ChildTaskDiagnosticPhase) {
        val event = synchronized(gate) { ChildTaskDiagnosticEvent(child.record.owner, child.record.assignedProfileId,
            child.record.originalRole, phase, child.selected?.requestModel, child.request, child.requestIds.size) }
        runCatching { diagnostics?.record(event) }
    }
    fun contains(owner: ChildRunOwner): Boolean = synchronized(gate) { owner in activeChildren }
    fun progress(owner: ChildRunOwner): String = synchronized(gate) { activeChildren[owner]?.partial.orEmpty() }
    fun snapshots(): List<RetainedChildTask> = synchronized(gate) {
        activeChildren.values.map { child -> RetainedChildTask(child.record, false, false, false,
            child.pause.snapshot(), ChildTaskQueueState.ACTIVE, configurationPaused = child.pause.isConfigurationBlocked()) }
    }
    fun pauseForConfiguration(owner: ChildRunOwner) {
        val child = synchronized(gate) { activeChildren[owner] } ?: return
        val alreadyBlocked = child.pause.isConfigurationBlocked()
        child.pause.pauseForConfiguration()
        if (!alreadyBlocked) trace(child, ChildTaskDiagnosticPhase.CONFIGURATION_PAUSE_REQUESTED)
    }
    fun resumeWithAssignment(owner: ChildRunOwner, assigned: ChildTaskRecord? = null): ChildTaskControlReceipt {
        val child = synchronized(gate) { activeChildren[owner] } ?: return ChildTaskControlReceipt.STALE
        if (assigned != null) {
            synchronized(gate) { child.record = assigned }
            trace(child, ChildTaskDiagnosticPhase.TAKEOVER)
        }
        val result = child.pause.resumeWithAssignment()
        child.runner?.resume()
        return result
    }
    fun takeOver(source: ChildTaskRecord, profileId: String, next: ChildModelDefinition): ChildTaskControlReceipt {
        val child = synchronized(gate) { activeChildren[source.owner] } ?: return ChildTaskControlReceipt.STALE
        if (source.originalRole == null || source.originalRole != next.role) return ChildTaskControlReceipt.ROLE_MISMATCH
        if (child.pause.snapshot() != ChildTaskPauseState.PAUSED) return ChildTaskControlReceipt.PENDING
        if (!next.isValid() || !next.acceptsNewTask()) return ChildTaskControlReceipt.CONFIGURATION_REQUIRED
        val written = store?.reassigned(source, profileId, source.display) ?: return ChildTaskControlReceipt.STALE
        child.assignment.reassign(profileId, next)
        synchronized(gate) { child.record = written }
        trace(child, ChildTaskDiagnosticPhase.TAKEOVER)
        return resumeWithAssignment(source.owner)
    }
    private val workers = Executors.newCachedThreadPool() { task -> Thread(task, "child-model").apply { isDaemon = true } }
    private val gate = Any()
    private var owner: RunExecutionOwner? = null
    private var session: SessionCapabilities? = null
    private var authorization: TaskAuthorization? = null
    private var closed = false
    private var busy = false
    private var outstanding = 0
    private val profileReservations = mutableMapOf<Any, String>()
    fun liveProfiles(): Set<String> = synchronized(gate) { profileReservations.values.toSet() }
    // Registered before exposing a transport to ChatRunner, whose cancel is asynchronous.
    // Worker exit alone does not prove provider cleanup has returned.
    private var cleanupPins = 0
    private var generation = 0L
    private val runners = mutableSetOf<ChatRunner>()
    private val assemblies = mutableSetOf<ChildToolAssembly>()
    private var pauseControl: ChildTaskPauseControl? = null
    private val observers = mutableListOf<() -> Unit>()

    init {
        require(timeoutMillis in 1..120_000); require(this.models.keys.all { it.isNotBlank() && it.length <= 200 })
        require(this.models.values.groupBy { it.poolKey }.values.all { entries ->
            entries.map { it.parallelLimit }.distinct().size == 1
        }) { "child_pool_limit_conflict" }
    }

    fun bind(owner: RunExecutionOwner, session: SessionCapabilities, authorization: TaskAuthorization) = synchronized(gate) {
        check(!closed) { "child_batch_closed" }
        require(authorization.taskId == owner.runId.value && authorization.sessionId == session.sessionId)
        check(this.owner == null || (this.owner === owner && this.session === session && this.authorization === authorization))
        this.owner = owner; this.session = session; this.authorization = authorization
    }

    private data class Completion(val index: Int, val child: ChildRunOwner, val message: Message, val text: String,
        val status: ChildTaskStatus = ChildTaskStatus.COMPLETED, val detail: String? = null)

    fun execute(context: ToolExecutionContext, tasks: List<ChildModelTask>): ExecutionReceipt =
        executeBatch(context, tasks, null, null, null)

    internal fun executeQueued(context: ToolExecutionContext, records: List<ChildTaskRecord>,
        pause: ChildTaskPauseControl? = null, preceding: ChildTaskRecord? = null): ExecutionReceipt {
        require(pause == null || records.size == 1) { "pause_requires_exact_child" }
        require(preceding == null || (records.size == 1 && preceding.status == ChildTaskStatus.COMPLETED &&
            records.single().sourceTaskId == preceding.owner.childRunId && records.single().commandKind == ChildTaskCommandKind.APPEND))
        return executeBatch(context, records.map { ChildModelTask(it.profileId, it.prompt, it.access) }, records, pause, preceding)
    }

    private fun executeBatch(context: ToolExecutionContext, tasks: List<ChildModelTask>,
        queued: List<ChildTaskRecord>?, pause: ChildTaskPauseControl?, preceding: ChildTaskRecord?): ExecutionReceipt {
        val models = try { currentModels?.invoke()?.toMap() ?: this.models }
            catch (_: Exception) { return ExecutionReceipt.NotExecuted("child_model_configuration_unavailable") }
        synchronized(gate) {
            models.values.forEach { selected ->
                localPools.getOrPut(selected.poolKey) { ChildModelPool(selected.parallelLimit) }
                    .also { it.resize(selected.parallelLimit) }
            }
        }
        val snapshot = tasks.toList()
        if (snapshot.isEmpty() || snapshot.any {
            it.profileId !in models || it.prompt.isBlank() || it.prompt.length > 4096 || bytes(it.prompt) > 16 * 1024
        }) return ExecutionReceipt.NotExecuted("invalid_child_tasks")
        val textOnlyBatch = snapshot.all { models.getValue(it.profileId).role == ChildModelRole.REVIEW }
        val parent: RunExecutionOwner
        val parentSession: SessionCapabilities
        val parentAuthorization: TaskAuthorization
        val current: Long
        val reservations = snapshot.map { Any() }
        var submitted = 0
        if (context.cancelled()) return ExecutionReceipt.NotExecuted("cancelled_before_execution")
        synchronized(gate) {
            parent = checkNotNull(owner); parentSession = checkNotNull(session); parentAuthorization = checkNotNull(authorization)
            require(context.owner === parent && context.authorization === parentAuthorization) { "child_parent_owner_mismatch" }
            if (queued != null) {
                require(queued.size == snapshot.size && queued.map { it.owner.childRunId }.distinct().size == queued.size)
                require(queued.map { it.owner.assistantMessageId }.distinct().size == queued.size)
                val first = queued.first().owner
                require(queued.all { record ->
                    record.parentOwnerId == parent.ownerId && record.parentMessageId == parent.assistantMessageId.value &&
                        record.owner.conversationId == parent.conversationId.value && record.owner.parentRunId == parent.runId.value &&
                        record.owner.taskGroupId == first.taskGroupId && record.owner.generation == first.generation &&
                        record.status == ChildTaskStatus.QUEUED && record.text.isEmpty() && record.detail == null
                }) { "queued_child_owner_mismatch" }
            }
            if (closed || busy || outstanding != 0 || cleanupPins != 0) return ExecutionReceipt.NotExecuted("child_batch_unavailable")
            if (queued == null && snapshot.any { !models.getValue(it.profileId).acceptsNewTask() })
                return ExecutionReceipt.NotExecuted("child_model_deleted")
            reservations.zip(snapshot).forEach { (reservation, task) -> profileReservations[reservation] = task.profileId }
            busy = true; current = ++generation
            pauseControl = pause
        }
        val group = queued?.first()?.owner?.taskGroupId ?: childTaskCallGroup(parent.conversationId.value,
            parent.runId.value, parent.assistantMessageId.value, context.invocation.name, context.invocation.id.value)
        val recordGeneration = queued?.first()?.owner?.generation ?: current
        val started = AtomicBoolean(false)
        val completions = LinkedBlockingQueue<Pair<Int, Completion?>>()
        val failures = AtomicBoolean(false)
        val childPauses = snapshot.map { pause ?: ChildTaskPauseControl(onProfilesChanged) }
        val pausedAtStart = childPauses.maxOf { it.excludedNanos() }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        fun beforeDeadline(): Boolean = System.nanoTime() - (childPauses.maxOf { it.excludedNanos() } - pausedAtStart) < deadline
        fun cancelled() = synchronized(gate) { closed || generation != current } || context.cancelled()
        fun uncertain(code: String): ExecutionReceipt {
            close()
            return if (started.get()) ExecutionReceipt.Unknown(code) else ExecutionReceipt.NotExecuted(code)
        }
        try {
            val records = queued ?: snapshot.mapIndexed { index, task ->
                ChildTaskRecord(ChildRunOwner(parent.conversationId.value, parent.runId.value, group, current,
                    UUID.randomUUID().toString(), UUID.randomUUID().toString()), parent.assistantMessageId.value,
                    parent.ownerId, index, task.profileId, task.prompt, display = models.getValue(task.profileId).display,
                    originalRole = models.getValue(task.profileId).role,
                    access = if (models.getValue(task.profileId).role == ChildModelRole.IMPLEMENTATION)
                        checkNotNull(toolAssembly) { "child_execution_tools_unavailable" }.restrict(parentSession, task.access) else ChildToolAccess.NONE)
            }
            if (queued == null) store?.enqueue(parent, records)
            snapshot.forEachIndexed { index, task ->
                val admitted = synchronized(gate) { if (closed) false else { outstanding++; true } }
                if (!admitted) {
                    records.drop(index).forEach { runCatching { store?.finished(it, ChildTaskStatus.NOT_EXECUTED, detail = "child_not_submitted") } }
                    return uncertain("child_batch_cancelled")
                }
                try {
                    workers.execute {
                        var completion: Completion? = null
                        var record = records[index]
                        var requestStarted = false
                        var requestFailure: ModelRequestException? = null
                        var failureStage = ChildFailureStage.PREPARE
                        var executionFailure: Exception? = null
                        val diagnostics = ChildFailureDiagnostics()
                        val childPause = childPauses[index]
                        val assignment = ChildModelAssignment(record.assignedProfileId, models.getValue(task.profileId))
                        val activeChild = ActiveChild(record, assignment, childPause)
                        synchronized(gate) { activeChildren[record.owner] = activeChild }
                        childPause.observePause { trace(activeChild, ChildTaskDiagnosticPhase.PAUSED) }
                        trace(activeChild, ChildTaskDiagnosticPhase.ADMITTED)
                        val waitingForPool = AtomicBoolean(false)
                        fun poolWaiting(waiting: Boolean) {
                            childPause.schedulingBoundary(waiting)
                            if (waitingForPool.getAndSet(waiting) != waiting) {
                                onPoolWaiting(waiting)
                                trace(activeChild, if (waiting) ChildTaskDiagnosticPhase.QUEUE_WAIT else ChildTaskDiagnosticPhase.QUEUE_READY)
                            }
                        }
                        val definition = assignment.definition
                        val excludedAtStart = childPause.excludedNanos()
                        fun beforeDeadline(): Boolean = System.nanoTime() - (childPause.excludedNanos() - excludedAtStart) < deadline
                        var previousCleanup: java.util.concurrent.CompletableFuture<Unit>? = null
                        fun awaitTransportCleanup() {
                            val previous = previousCleanup ?: return
                            while (!previous.isDone) {
                                check(!cancelled() && beforeDeadline())
                                Thread.sleep(10)
                            }
                            previous.join()
                        }
                        fun currentDefinition(honorUserPause: Boolean = true): ChildModelDefinition {
                            while (true) {
                                if (!runCatching { definition.isValid() }.getOrDefault(false)) {
                                    pauseForConfiguration(record.owner)
                                }
                                if (honorUserPause || childPause.isConfigurationBlocked()) check(childPause.awaitBoundary())
                                check(!cancelled())
                                try {
                                    val selected = definition.current()
                                    if (selected.role != definition.role) { pauseForConfiguration(record.owner); continue }
                                    return selected
                                }
                                catch (failure: Exception) {
                                    if (definition.isValid()) throw failure
                                }
                            }
                        }
                        var lease: ChildPoolLease? = null
                        val waitingLeases = linkedMapOf<String, Pair<ChildModelDefinition, ChildPoolLease>>()
                        var journalStarted = false
                        // A permit belongs to this item until BOTH its worker and transport drain.
                        // Keep outstanding pinned through the lock-free release as well, so idle
                        // observers cannot retire the batch while a release is still in flight.
                        var assembly: ChildToolAssembly? = null
                        var toolsDrained = true
                        var assemblyDrain: ChildToolDrain? = null
                        var poolSelection: ChildModelDefinition? = null
                        var workerFinished = false
                        var cleanupFinished = true // No transport exists on pre-open failures.
                        var itemReleased = false
                        fun releaseItemIfReady() {
                            val release = synchronized(gate) {
                                if (!workerFinished || !cleanupFinished || !toolsDrained || itemReleased) false
                                else { itemReleased = true; true }
                            }
                            if (!release) return
                            waitingLeases.values.forEach { it.second.close() }; waitingLeases.clear()
                            lease?.close()
                            synchronized(gate) { outstanding--; profileReservations.remove(reservations[index]) }
                            onProfilesChanged()
                            notifyIfIdle()
                        }
                        fun closeWaitingLeases() {
                            waitingLeases.values.forEach { it.second.close() }
                            waitingLeases.clear()
                        }
                        var recordedSelection: ChildModelDefinition? = null
                        fun recordAssignment(selected: ChildModelDefinition) {
                            val profileId = selected.profileId ?: assignment.profileId
                            if (record.assignedProfileId == profileId) return
                            record = if (store == null) record.copy(assignedProfileId = profileId)
                            else checkNotNull(store.reassigned(record, profileId, selected.display)) {
                                "child_assignment_unconfirmed"
                            }.copy(status = ChildTaskStatus.QUEUED, text = "", detail = null)
                            synchronized(gate) { activeChild.record = record }
                        }
                        fun recordSelection(selected: ChildModelDefinition) {
                            val profileId = selected.profileId ?: assignment.profileId
                            if (recordedSelection?.sameSelection(selected) == true && record.assignedProfileId == profileId &&
                                recordedSelection?.requestModel == selected.requestModel) return
                            recordAssignment(selected)
                            store?.selected(record, selected.display)
                            record = record.copy(display = selected.display)
                            synchronized(gate) { activeChild.record = record; activeChild.selected = selected }
                            recordedSelection = selected
                        }
                        fun selectionReady(selected: ChildModelDefinition): Boolean {
                            check(!cancelled() && beforeDeadline())
                            if (childPause.snapshot() != ChildTaskPauseState.ACTIVE) {
                                lease?.close(); lease = null; poolSelection = null
                                closeWaitingLeases(); poolWaiting(false)
                                return false
                            }
                            return selected.sameSelection(currentDefinition()) && lease?.leaveBoundary() != false
                        }
                        fun awaitSelection(): ChildModelDefinition {
                            while (true) {
                                check(!cancelled() && beforeDeadline())
                                check(childPause.awaitBoundary(boundary = { lease?.enterBoundary() }))
                                val current = currentDefinition()
                                val choices = definition.currentCandidates().filter { it.role == definition.role }.distinctBy { it.poolKey }
                                if (choices.isEmpty()) continue
                                if (poolSelection?.sameSelection(current) != true) {
                                    lease?.close()
                                    lease = null; poolSelection = null
                                }
                                if (lease != null && checkNotNull(lease).tryAcquire()) {
                                    if (definition.select(current) && current.sameSelection(currentDefinition()) &&
                                        checkNotNull(lease).leaveBoundary()) {
                                        poolWaiting(false)
                                        if (!journalStarted) { store?.started(record); journalStarted = true }
                                        recordSelection(current)
                                        if (!selectionReady(current)) continue
                                        return current
                                    }
                                    continue
                                }
                                // Probe the existing shared pools, never a second app-side capacity counter.
                                if (lease != null && poolSelection != null) {
                                    checkNotNull(lease).observeWaiting {}
                                    waitingLeases.putIfAbsent(current.poolKey, current to checkNotNull(lease))
                                }
                                waitingLeases.entries.removeAll { (key, entry) ->
                                    if (entry.second.closed || choices.none { it.poolKey == key && it.sameSelection(entry.first) }) {
                                        entry.second.close(); true
                                    } else false
                                }
                                choices.forEach { candidate ->
                                    waitingLeases.getOrPut(candidate.poolKey) {
                                        candidate to (sharedPool?.invoke(candidate) ?: synchronized(gate) {
                                            localPools.getOrPut(candidate.poolKey) { ChildModelPool(candidate.parallelLimit) }.lease()
                                        })
                                    }
                                }
                                val ready = ChildPoolLease.acquireBalanced(waitingLeases.values.toList())
                                if (ready == null) {
                                    // Binding is accepted even while all compatible capacity is busy.
                                    if (definition.select(current)) recordAssignment(current)
                                    poolWaiting(true); Thread.sleep(25); continue
                                }
                                val (selected, selectedLease) = ready
                                waitingLeases.remove(selected.poolKey)
                                closeWaitingLeases()
                                if (lease !== selectedLease) lease?.close()
                                lease = selectedLease; poolSelection = selected
                                selectedLease.observeWaiting(::poolWaiting)
                                if (!definition.select(selected)) continue
                                check(!cancelled() && beforeDeadline())
                                if (childPause.snapshot() != ChildTaskPauseState.ACTIVE) continue
                                if (!selected.sameSelection(currentDefinition())) continue
                                if (!selectedLease.leaveBoundary()) { poolWaiting(true); continue }
                                poolWaiting(false)
                                if (!journalStarted) { store?.started(record); journalStarted = true }
                                recordSelection(selected)
                                if (!selectionReady(selected)) continue
                                return selected
                            }
                        }
                        fun requireCurrentRole() {
                            check(currentDefinition().role == definition.role) { "child_role_changed" }
                        }
                        fun awaitRequestBoundary() {
                            requireCurrentRole()
                            check(!cancelled() && beforeDeadline())
                            lease?.enterBoundary()
                            check(childPause.awaitBoundary(boundary = { lease?.enterBoundary() }))
                            while (lease != null) {
                                check(!cancelled() && beforeDeadline())
                                check(childPause.awaitBoundary(boundary = { lease?.enterBoundary() }))
                                if (checkNotNull(lease).tryAcquire() && checkNotNull(lease).leaveBoundary()) { poolWaiting(false); break }
                                poolWaiting(true); Thread.sleep(25)
                            }
                            check(!cancelled() && beforeDeadline())
                            requireCurrentRole()
                        }
                        try {
                            if (!cancelled() && beforeDeadline()) {
                                val child = record.owner
                                if (definition.role == ChildModelRole.IMPLEMENTATION) {
                                    awaitSelection()
                                    val factory = checkNotNull(toolAssembly) { "child_execution_tools_unavailable" }
                                    assembly = factory.create(child, parentSession, parentAuthorization, record.access)
                                    synchronized(gate) { toolsDrained = false; cleanupPins++; assemblies.add(checkNotNull(assembly)) }
                                    check(assembly!!.scope.owner == child) { "child_assembly_owner_mismatch" }
                                    check(!cancelled() && assembly!!.prepareOnWorker() && !cancelled())
                                }
                                val scope = assembly?.scope ?: ChildRunScope.capture(child, parentSession, parentAuthorization, emptySet())
                                val initial = Message(MessageId(child.assistantMessageId), parent.conversationId, RunId(child.childRunId),
                                    Message.ASSISTANT_ROLE, emptyList(), RunStatus.RUNNING)
                                val user = Message(MessageId(UUID.randomUUID().toString()), initial.conversationId, initial.runId,
                                    Message.USER_ROLE, listOf(MessageBlock.Text("task", task.prompt)), RunStatus.COMPLETED)
                                val memory = object : ConversationStore {
                                    override fun save(message: Message) { scope.requireOwner(message) }
                                }
                                val noTools = object : CatalogTool {
                                    override val definitions = emptyList<app.daiyu.android.domain.ToolDefinition>()
                                    override fun execute(invocation: ToolInvocation): String = error("child_tools_disabled")
                                }
                                val tools = assembly?.tools ?: noTools
                                val childContext = if (definition.hasContext) ChildModelContextRuntime(initial) else null
                                var preparedModel: ChildModelDefinition? = null
                                val selected = object : ModelPort {
                                    override fun prepareRequest() {
                                        val candidate = awaitSelection()
                                        childContext?.prepare(candidate)
                                        preparedModel = candidate
                                    }
                                    override fun open(history: List<Message>): ModelSession = open(history, tools.definitions)
                                    override fun open(history: List<Message>, definitions: List<app.daiyu.android.domain.ToolDefinition>): ModelSession {
                                        awaitTransportCleanup()
                                        check(assembly != null || definitions.isEmpty())
                                        check(!cancelled() && beforeDeadline())
                                        check(childPause.awaitBoundary())
                                        check(!cancelled() && beforeDeadline())
                                        store?.get(record.owner)?.let { saved ->
                                            record = saved.copy(status = ChildTaskStatus.QUEUED, text = "", detail = null)
                                        }
                                        val selectedModel = checkNotNull(preparedModel)
                                        if (childPause.snapshot() != ChildTaskPauseState.ACTIVE ||
                                            !selectedModel.sameSelection(currentDefinition()) || lease?.leaveBoundary() == false)
                                            throw ModelPreparationChangedException()
                                        val port = selectedModel.create()
                                        if (!selectedModel.sameSelection(currentDefinition())) throw ModelPreparationChangedException()
                                        recordSelection(selectedModel)
                                        val revoked = context.cancelled()
                                        synchronized(gate) {
                                            check(!closed && generation == current && !revoked && beforeDeadline())
                                            requestStarted = true
                                            started.set(true)
                                        }
                                        failureStage = ChildFailureStage.OPEN
                                        trace(activeChild, ChildTaskDiagnosticPhase.REQUEST_OPEN)
                                        val transport = try { port.open(history, definitions) }
                                        catch (failure: Exception) {
                                            trace(activeChild, ChildTaskDiagnosticPhase.REQUEST_FAILED)
                                            executionFailure = failure
                                            requestFailure = failure as? ModelRequestException
                                            throw failure
                                        }
                                        val cleanup = java.util.concurrent.CompletableFuture<Unit>()
                                        previousCleanup = cleanup
                                        val cancelStarted = AtomicBoolean(false)
                                        synchronized(gate) { cleanupFinished = false; cleanupPins++ }
                                        return object : RequestRebindingModelSession {
                                            private var firstNext = true
                                            override fun needsRebind(): Boolean = !selectedModel.sameRequest(currentDefinition())
                                            override fun updateToolDefinitions(definitions: List<app.daiyu.android.domain.ToolDefinition>) = transport.updateToolDefinitions(definitions)
                                            override fun cancel() {
                                                if (!cancelStarted.compareAndSet(false, true)) return
                                                var confirmed = false
                                                try { transport.cancel(); confirmed = true }
                                                finally {
                                                    synchronized(gate) {
                                                        cleanupFinished = confirmed
                                                        if (cleanupFinished) cleanupPins--
                                                    }
                                                    releaseItemIfReady()
                                                    trace(activeChild, if (confirmed) ChildTaskDiagnosticPhase.CLEANUP_COMPLETED else ChildTaskDiagnosticPhase.CLEANUP_FAILED)
                                                    if (confirmed) cleanup.complete(Unit)
                                                    else cleanup.completeExceptionally(IllegalStateException("child_transport_cleanup_unconfirmed"))
                                                }
                                            }
                                            override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                                                val first = firstNext
                                                firstNext = false
                                                if (!first && assembly != null) awaitRequestBoundary()
                                                if (!first && (!selectedModel.sameRequest(checkNotNull(preparedModel)) ||
                                                    !selectedModel.sameRequest(currentDefinition()))) throw ModelPreparationChangedException()
                                                trace(activeChild, ChildTaskDiagnosticPhase.REQUEST_STARTED)
                                                var firstEvent = true
                                                val texts = mutableMapOf<String, Int>()
                                                val progress = linkedMapOf<String, String>()
                                                var rejected: Exception? = null
                                                val checkedSink: (ModelEvent) -> Unit = checkedSink@ { event ->
                                                    if (cancelled()) return@checkedSink
                                                    if (firstEvent) { firstEvent = false; trace(activeChild, ChildTaskDiagnosticPhase.FIRST_EVENT) }
                                                    rejected?.let { throw it }
                                                    try { when (event) {
                                                        is ModelEvent.TextDelta, is ModelEvent.TextFinal -> {
                                                            val id: String; val text: String; val final: Boolean
                                                            if (event is ModelEvent.TextDelta) { id=event.itemId; text=event.text; final=false }
                                                            else { event as ModelEvent.TextFinal; id=event.itemId; text=event.text; final=true }
                                                            require(text.length <= MAX_OUTPUT && id.length <= 200)
                                                            val size = bytes(text).toLong() + if (final) 0 else (texts[id] ?: 0)
                                                            require(size <= MAX_OUTPUT && texts.size <= 256)
                                                            texts[id] = size.toInt()
                                                            require(texts.values.sumOf { it.toLong() } <= MAX_OUTPUT)
                                                            sink(event)
                                                            progress[id] = if (final) text else progress[id].orEmpty() + text
                                                            val partial = progress.values.joinToString("")
                                                            synchronized(gate) { activeChild.partial = partial }
                                                            onProgress(child, partial)
                                                        }
                                                        is ModelEvent.ToolReady -> if (assembly == null) throw ModelRequestException(ModelFailure.PROTOCOL) else sink(event)
                                                        is ModelEvent.Usage -> sink(event)
                                                        else -> if (assembly != null) sink(event)
                                                    } } catch (failure: Exception) { rejected = failure; throw failure }
                                                }
                                                var continuing = false
                                                while (true) {
                                                    try {
                                                        val delivery = ProviderEventDeliveryGate()
                                                        try {
                                                            val attemptSink: (ModelEvent) -> Unit = { event ->
                                                                delivery.deliver { checkedSink(event) }
                                                            }
                                                            if (continuing) (transport as ResumableModelSession).resume(attemptSink)
                                                            else { failureStage = ChildFailureStage.RESPONSE; transport.next(outputs, attemptSink) }
                                                        } finally {
                                                            // Fence late callbacks and drain acknowledged delivery
                                                            // before publishing a resumable pause boundary.
                                                            delivery.close()
                                                        }
                                                        break
                                                    } catch (failure: Exception) {
                                                        // A blocked read is never interrupted for pause. Only a
                                                        // transport-reported safe continuation may park here.
                                                        trace(activeChild, ChildTaskDiagnosticPhase.REQUEST_FAILED)
                                                        executionFailure = failure
                                                        if (failure is ModelRequestException) {
                                                            try { store?.diagnosed(record,
                                                                failure.detailedMessage.take(ChildTaskRecord.MAX_DETAIL_CHARS)) }
                                                            catch (_: Exception) { /* Final result persistence remains authoritative. */ }
                                                        }
                                                        val resumable = transport as? ResumableModelSession
                                                        val kind = (failure as? ModelRequestException)?.failure
                                                        if (rejected == null && failure is ModelRequestException) requestFailure = failure
                                                        if (rejected != null || cancelled() || !beforeDeadline() ||
                                                            kind !in setOf(ModelFailure.NETWORK, ModelFailure.TIMEOUT) ||
                                                            resumable?.canResume != true) throw failure
                                                        // Claim intent and boundary together: retracting an intent
                                                        // before this point must not silently enable reconnect.
                                                        val yielding = lease?.yieldRequested == true
                                                        if (!yielding && childPause.snapshot() != ChildTaskPauseState.PENDING) throw failure
                                                        lease?.enterBoundary()
                                                        poolWaiting(lease?.yieldRequested == true)
                                                        if (!childPause.awaitBoundary(requireRequest = !yielding,
                                                                boundary = { lease?.enterBoundary() })) throw failure
                                                        while (lease != null) {
                                                            check(!cancelled() && beforeDeadline())
                                                            check(childPause.awaitBoundary(boundary = { lease?.enterBoundary() }))
                                                            if (checkNotNull(lease).tryAcquire() && checkNotNull(lease).leaveBoundary()) { poolWaiting(false); break }
                                                            poolWaiting(true)
                                                            Thread.sleep(25)
                                                        }
                                                        check(!cancelled() && beforeDeadline())
                                                        // Never call open or next(outputs) again for this request.
                                                        continuing = true
                                                    }
                                                }
                                                rejected?.let {
                                                    trace(activeChild, ChildTaskDiagnosticPhase.REQUEST_FAILED)
                                                    throw it
                                                }
                                            }
                                        }
                                    }
                                }
                                val receipts = assembly?.receipts?.let { delegate -> object : app.daiyu.android.domain.runtime.ReceiptBatchToolExecutor by delegate {
                                    override fun execute(message: Message, invocations: List<ToolInvocation>, cancelled: () -> Boolean): List<ToolDispatchResult> {
                                        check(childPause.awaitBoundary() && !cancelled())
                                        requireCurrentRole()
                                        trace(activeChild, ChildTaskDiagnosticPhase.TOOL_STARTED)
                                        return delegate.execute(message, invocations, cancelled).also { trace(activeChild, ChildTaskDiagnosticPhase.TOOL_COMPLETED) }
                                    }
                                    override fun start(message: Message, invocations: List<ToolInvocation>, cancelled: () -> Boolean): ReceiptBatchStart {
                                        check(childPause.awaitBoundary() && !cancelled())
                                        requireCurrentRole()
                                        trace(activeChild, ChildTaskDiagnosticPhase.TOOL_STARTED)
                                        return delegate.start(message, invocations, cancelled).also {
                                            if (it is ReceiptBatchStart.Completed) trace(activeChild, ChildTaskDiagnosticPhase.TOOL_COMPLETED)
                                        }
                                    }
                                } }
                                val runner = ChatRunner(selected, memory, tools, RunnerOptions(
                                    receiptBatchExecutor = receipts, diagnostics = diagnostics, contextRuntime = childContext,
                                    onRequestCompletedBoundary = {
                                        trace(activeChild, ChildTaskDiagnosticPhase.REQUEST_COMPLETED)
                                        lease?.enterBoundary(); currentDefinition(honorUserPause = false)
                                    },
                                    runtimeStore = object : RuntimeStore {
                                        override fun save(checkpoint: RunCheckpoint) {
                                            synchronized(gate) {
                                                activeChild.request = checkpoint.request
                                                checkpoint.usageRequests.forEach { activeChild.requestIds.add(it.requestId) }
                                            }
                                            store?.get(record.owner)?.let { record = it.copy(status = ChildTaskStatus.QUEUED, text = "", detail = null) }
                                            usageRecorder?.record(record, checkpoint, activeChild.selected?.requestModel)
                                        }
                                    },
                                    validateBatch = { require(assembly != null || it.isEmpty()) { "child_tools_disabled" } })) { scope.requireOwner(it) }
                                val attached = synchronized(gate) { if (closed) false else {
                                    runners.add(runner); runnerDefinitions[runner] = definition; activeChild.runner = runner; true
                                } }
                                try {
                                    if (attached && !cancelled()) {
                                        val previous = preceding?.let { source -> listOf(
                                            Message(MessageId(UUID.randomUUID().toString()), initial.conversationId, initial.runId,
                                                Message.USER_ROLE, listOf(MessageBlock.Text("source-input", source.prompt)), RunStatus.COMPLETED),
                                            Message(MessageId(UUID.randomUUID().toString()), initial.conversationId, initial.runId,
                                                Message.ASSISTANT_ROLE, listOf(MessageBlock.Text("source-result", source.text)), RunStatus.COMPLETED)
                                        ) } ?: emptyList()
                                        val result = runner.run(initial, previous + user)
                                        trace(activeChild, if (result.status == RunStatus.COMPLETED) ChildTaskDiagnosticPhase.COMPLETED else ChildTaskDiagnosticPhase.FAILED)
                                        if (executionFailure == null && result.status == RunStatus.COMPLETED) failureStage = ChildFailureStage.VALIDATE
                                        scope.requireOwner(result)
                                        check(!cancelled())
                                        val failure = requestFailure
                                        if (result.status == RunStatus.FAILED && definition.role == ChildModelRole.REVIEW &&
                                            assembly == null && failure != null && !failure.transportInterrupted &&
                                            failure.failure in CONFIRMED_MODEL_FAILURES && result.error == failure.detailedMessage) {
                                            completion = Completion(index, child, result, "", ChildTaskStatus.FAILED,
                                                failure.detailedMessage.take(ChildTaskRecord.MAX_DETAIL_CHARS))
                                        } else {
                                            check(result.status == RunStatus.COMPLETED)
                                            val text = result.blocks.filterIsInstance<MessageBlock.Text>().joinToString("\n") { it.text }
                                            check(text.isNotBlank() && bytes(text) <= MAX_OUTPUT)
                                            completion = Completion(index, child, result, text)
                                        }
                                    } else runner.stop()
                                } finally { synchronized(gate) { runners.remove(runner); runnerDefinitions.remove(runner); activeChildren.remove(record.owner) } }
                            }
                        } catch (failure: Exception) {
                            if (executionFailure == null) executionFailure = failure
                        }
                        finally {
                            assembly?.let { resource ->
                                try { resource.close() }
                                catch (_: Exception) { synchronized(gate) { assemblyDrain = ChildToolDrain.Unknown("child_tool_close_failed") } }
                                try { resource.observeClose { result ->
                                    val drained = synchronized(gate) {
                                        if (assemblyDrain != null) false else {
                                            assemblyDrain = result
                                            if (result == ChildToolDrain.Drained) {
                                                toolsDrained = true; cleanupPins--; assemblies.remove(resource); true
                                            } else false
                                        }
                                    }
                                    if (drained) releaseItemIfReady()
                                } } catch (_: Exception) {
                                    synchronized(gate) { if (assemblyDrain == null) assemblyDrain = ChildToolDrain.Unknown("child_tool_drain_unconfirmed") }
                                }
                            }
                            childPause.terminate()
                            synchronized(gate) { activeChildren.remove(record.owner) }
                            try {
                                val result = completion
                                if (result != null) store?.finished(record, result.status, result.text, result.detail)
                                else store?.finished(record,
                                    if (requestStarted) ChildTaskStatus.UNKNOWN else ChildTaskStatus.NOT_EXECUTED,
                                    detail = diagnostics.detail(if (requestStarted) requestFailure?.userMessage ?: "child_result_unconfirmed" else "child_not_sent",
                                        failureStage, executionFailure))
                            } catch (_: Exception) {
                                completion = null
                            } finally {
                                if (completion == null && runCatching { definition.isValid() }.getOrDefault(true)) failures.set(true)
                                completions.offer(index to completion)
                                poolWaiting(false)
                                synchronized(gate) { workerFinished = true }
                                releaseItemIfReady()
                            }
                        }
                    }
                    submitted++
                } catch (_: Exception) {
                    synchronized(gate) { outstanding-- }
                    records.drop(index).forEach { runCatching { store?.finished(it, ChildTaskStatus.NOT_EXECUTED, detail = "child_not_submitted") } }
                    return uncertain("child_submission_unconfirmed")
                }
            }
            val results = arrayOfNulls<Completion>(snapshot.size)
            var remaining = snapshot.size
            while (remaining > 0) {
                if (cancelled()) return uncertain("child_batch_cancelled")
                if (!beforeDeadline()) return uncertain("child_batch_timeout")
                if (failures.get() && !textOnlyBatch) return uncertain("child_result_unconfirmed")
                val (index, result) = completions.poll(25, TimeUnit.MILLISECONDS) ?: continue
                if (result == null) { remaining--; continue }
                check(result.index == index)
                check(result.child.taskGroupId == group && result.child.generation == recordGeneration &&
                    result.child.parentRunId == parent.runId.value && result.child.conversationId == parent.conversationId.value)
                check(results[result.index] == null)
                results[result.index] = result; remaining--
            }
            if (failures.get()) return uncertain("child_result_unconfirmed")
            if (results.any { it == null }) return uncertain("child_profile_revoked")
            val output = results.map { checkNotNull(it) }
            if (output.sumOf { bytes(it.text).toLong() } > 256 * 1024) return uncertain("child_batch_output_too_large")
            val allSucceeded = output.all { it.status == ChildTaskStatus.COMPLETED }
            val payload = output.joinToString(",", "{\"ok\":$allSucceeded,\"task_group_id\":${jsonQuote(group)},\"generation\":$recordGeneration,\"results\":[", "]}") {
                "{\"index\":${it.index},\"profile_id\":${jsonQuote(snapshot[it.index].profileId)}," +
                    "\"child_run_id\":${jsonQuote(it.child.childRunId)},\"assistant_message_id\":${jsonQuote(it.message.id.value)}," +
                    "\"status\":${jsonQuote(it.status.name)},\"text\":${jsonQuote(it.text)}" +
                    (it.detail?.let { detail -> ",\"detail\":${jsonQuote(detail)},\"request_may_have_executed\":true,\"automatic_retry\":false" } ?: "") + "}"
            }
            if (bytes(payload) > 1024 * 1024) return uncertain("child_receipt_too_large")
            if (cancelled()) return uncertain("child_batch_cancelled")
            return ExecutionReceipt.Confirmed(payload, isError = !allSucceeded)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt(); return uncertain("child_wait_interrupted")
        } catch (_: Exception) { return uncertain("child_batch_unconfirmed") }
        finally {
            synchronized(gate) { busy = false; reservations.drop(submitted).forEach(profileReservations::remove) }
            onProfilesChanged()
            notifyIfIdle()
        }
    }

    override fun close() {
        val active = synchronized(gate) {
            closed = true
            pauseControl?.terminate()
            activeChildren.values.forEach { it.pause.terminate() }
            runners.toList() to assemblies.toList()
        }
        active.first.forEach { it.stop() }
        active.second.forEach { runCatching { it.close() } }
        workers.shutdown()
        notifyIfIdle()
    }

    fun closeWhenIdle(onClosed: () -> Unit) {
        synchronized(gate) { observers.add(onClosed) }
        close()
    }

    private fun notifyIfIdle() {
        val callbacks = synchronized(gate) {
            if (!closed || busy || outstanding != 0 || cleanupPins != 0) emptyList()
            else observers.toList().also { observers.clear() }
        }
        callbacks.forEach { it() }
    }

    companion object {
        private const val MAX_OUTPUT = 64 * 1024
        private val CONFIRMED_MODEL_FAILURES = setOf(ModelFailure.AUTHENTICATION, ModelFailure.PERMISSION,
            ModelFailure.ENDPOINT, ModelFailure.REDIRECT, ModelFailure.RATE_LIMIT, ModelFailure.SERVER,
            ModelFailure.CONFIGURATION, ModelFailure.REQUEST, ModelFailure.CONTEXT_LIMIT)
        private fun bytes(text: String) = text.toByteArray(Charsets.UTF_8).size
    }
}
