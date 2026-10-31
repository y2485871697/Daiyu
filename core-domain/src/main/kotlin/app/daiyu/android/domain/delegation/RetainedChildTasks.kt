package app.daiyu.android.domain.delegation

import app.daiyu.android.domain.ToolInvocation
import app.daiyu.android.domain.runtime.RunExecutionOwner
import app.daiyu.android.domain.tools.SessionCapabilities
import app.daiyu.android.domain.tools.TaskAuthorization
import app.daiyu.android.domain.tools.ToolExecutionContext
import app.daiyu.android.domain.usage.ChildUsageRecorder
import java.lang.ref.WeakReference
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Collections
import java.util.UUID
import java.util.WeakHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

sealed interface ChildTaskAdmission {
    data class Accepted(val tasks: List<ChildTaskRecord>) : ChildTaskAdmission
    data class Rejected(val code: String) : ChildTaskAdmission
    data class Unconfirmed(val code: String) : ChildTaskAdmission
}

enum class ChildTaskStop { REQUESTED, ALREADY_SETTLED, NOT_FOUND }

data class RetainedChildTask(
    val record: ChildTaskRecord,
    val stopRequested: Boolean,
    val settled: Boolean,
    val journalUnconfirmed: Boolean,
    val pauseState: ChildTaskPauseState = ChildTaskPauseState.ACTIVE,
    val queueState: ChildTaskQueueState = ChildTaskQueueState.ACTIVE,
    val contextLimited: Boolean = false,
)

internal class ChildTaskResources(
    val session: SessionCapabilities,
    val authorization: TaskAuthorization,
    var models: Map<String, ChildModelDefinition>,
    val onChanged: () -> Unit,
    val toolAssembly: ChildToolAssemblyFactory?
) {
    val poolKeys = models.values.map { it.poolKey }.toMutableSet()
}

/** A digest is a replay tombstone, not a retained prompt or permission to run again. */
internal class ChildTaskRequest(val fingerprint: ByteArray, var remaining: Int) {
    var admission: ChildTaskAdmission? = null
}

/** A bounded call tombstone; attempted calls may only query durable receipts, never enqueue again. */
internal class ChildTaskCommandRequest(val fingerprint: String) {
    var attempted = false
    var receipt: ChildTaskCommandReceipt? = null
}

/** One process-local pause epoch; never a durable restart permission. */
class ChildTaskPauseGroup internal constructor(internal val binding: ChildTaskBinding) {
    internal val members = linkedSetOf<ChildRunOwner>()
    internal val manual = mutableSetOf<ChildRunOwner>()
    internal var consumed = false
}

/** Host-owned capability. Finishing its parent does not revoke accepted child work. */
class ChildTaskBinding internal constructor(
    internal val supervisor: RetainedChildTasks,
    internal val owner: RunExecutionOwner,
    internal var resources: ChildTaskResources?
) {
    // All mutable fields are protected by the supervisor gate. Once sealed and drained,
    // only this lightweight handle/history stays with its host, never with the supervisor.
    internal val authorization = WeakReference(checkNotNull(resources).authorization)
    internal var accepting = true
    internal var pauseGroup: ChildTaskPauseGroup? = null
    internal var outstanding = 0
    internal val requests = mutableMapOf<String, ChildTaskRequest>()
    internal val commandRequests = mutableMapOf<String, ChildTaskCommandRequest>()
    internal val recent = linkedMapOf<String, RetainedChildTask>()
}

/** Controller-lifetime execution; durable records remain owned by the existing runtime store.
 * maxRetainedTasks bounds live reservations (including draining transports), not lifetime admissions.
 * Terminal snapshots are a bounded UI cache per handle; complete history belongs to ChildTaskStore.
 * maxRequestsPerBinding (1..4096, default 1024) independently bounds replay tombstones.
 * They are never evicted to admit work: new calls fail with child_request_budget_exhausted at the limit.
 */
class RetainedChildTasks(private val store: ChildTaskStore, private val timeoutMillis: Long = 120_000,
    private val maxRetainedTasks: Int = 64, private val usageRecorder: ChildUsageRecorder? = null,
    private val maxRequestsPerBinding: Int = 1024) : AutoCloseable {
    private class Pool(var limit: Int) {
        val capacity = ChildModelPool(limit)
        var bindings = 0
    }
    private class Task(val binding: ChildTaskBinding, val request: ChildTaskRequest,
        val initial: ChildTaskRecord, val invocation: ToolInvocation, val deadline: Long,
        val pause: ChildTaskPauseControl, val source: ChildTaskRecord? = null,
        val definition: ChildModelDefinition, val toolPin: AutoCloseable? = null) {
        var record = initial
        var stopRequested = false
        var settled = false
        var journalUnconfirmed = false
        var batch: ChildModelBatch? = null
        var admitting = false
        var queueState = ChildTaskQueueState.ACTIVE
    }
    private val gate = Any()
    private val pools = mutableMapOf<String, Pool>()
    private val bindings = java.util.IdentityHashMap<RunExecutionOwner, ChildTaskBinding>()
    // RunExecutionOwner has identity equality. A reachable old owner cannot be rebound after
    // its resources are released; dead owners need not be retained for the controller lifetime.
    private val seenOwners = WeakHashMap<RunExecutionOwner, Unit>()
    private val tasks = linkedMapOf<String, Task>()
    // In-flight only, across bindings: a reentrant store must see pending, never join its own I/O.
    private val pendingCommands = mutableMapOf<String, ChildTaskCommandRequest>()
    private val progress = linkedMapOf<ChildRunOwner, String>()
    private val workers = Executors.newCachedThreadPool() { action ->
        Thread(action, "retained-child-task").apply { isDaemon = true }
    }
    private val observers = mutableListOf<() -> Unit>()
    private var generation = 0L
    private var outstanding = 0
    private var closed = false

    init {
        require(timeoutMillis in 1..120_000); require(maxRetainedTasks in 1..256)
        require(maxRequestsPerBinding in 1..4096) { "invalid_child_request_budget" }
    }

    fun bind(owner: RunExecutionOwner, session: SessionCapabilities, authorization: TaskAuthorization,
        models: Map<String, ChildModelDefinition>, onChanged: () -> Unit = {}): ChildTaskBinding =
        bind(owner, session, authorization, models, null, onChanged)

    fun bind(owner: RunExecutionOwner, session: SessionCapabilities, authorization: TaskAuthorization,
        models: Map<String, ChildModelDefinition>, toolAssembly: ChildToolAssemblyFactory?,
        onChanged: () -> Unit = {}): ChildTaskBinding = synchronized(gate) {
        check(!closed) { "child_supervisor_closed" }
        require(authorization.sessionId == session.sessionId && authorization.taskId == owner.runId.value)
        check(!seenOwners.containsKey(owner)) { "child_parent_already_bound" }
        val frozen = models.toMap()
        require(frozen.keys.all { it.isNotBlank() && it.length <= 200 })
        val limits = frozen.values.groupBy { it.poolKey }.mapValues { (_, definitions) ->
            require(definitions.map { it.parallelLimit }.distinct().size == 1) { "child_pool_limit_conflict" }
            definitions.first().parallelLimit
        }
        require(limits.all { (key, limit) -> pools[key]?.limit?.let { it == limit } != false }) { "child_pool_limit_conflict" }
        limits.forEach { (key, limit) -> pools.getOrPut(key) { Pool(limit) }.bindings++ }
        ChildTaskBinding(this, owner, ChildTaskResources(session, authorization,
            Collections.unmodifiableMap(frozen), onChanged, toolAssembly)).also {
            bindings[owner] = it
            seenOwners[owner] = Unit
        }
    }

    fun submit(binding: ChildTaskBinding, context: ToolExecutionContext, input: List<ChildModelTask>): ChildTaskAdmission {
        requireBinding(binding)
        require(context.owner === binding.owner && context.authorization === binding.authorization.get()) {
            "child_parent_owner_mismatch"
        }
        // Bound retained map keys as well as their count; rejected ids consume no reservation.
        val callId = context.invocation.id.value
        if (callId.isBlank() || callId.length > 200) return ChildTaskAdmission.Rejected("invalid_child_call_id")
        val inputSnapshot = input.toList()
        if (inputSnapshot.isEmpty() || inputSnapshot.any {
            it.profileId.isBlank() || it.profileId.length > 200 || it.prompt.isBlank() ||
                it.prompt.length > 4096 || it.prompt.toByteArray(Charsets.UTF_8).size > 16 * 1024
        }) return ChildTaskAdmission.Rejected("invalid_child_tasks")
        val fingerprint = fingerprint(inputSnapshot)
        val cancelled = context.cancelled()
        val reserved: List<Task>
        val request: ChildTaskRequest
        synchronized(gate) {
            binding.requests[callId]?.let { previous ->
                return if (!previous.fingerprint.contentEquals(fingerprint)) ChildTaskAdmission.Rejected("child_request_conflict")
                else previous.admission ?: ChildTaskAdmission.Unconfirmed("child_admission_pending")
            }
            val resources = binding.resources ?: return ChildTaskAdmission.Rejected("child_binding_closed")
            if (closed || !binding.accepting) return ChildTaskAdmission.Rejected("child_binding_closed")
            if (inputSnapshot.any { it.profileId !in resources.models }) return ChildTaskAdmission.Rejected("invalid_child_tasks")
            if (inputSnapshot.any { !resources.models.getValue(it.profileId).acceptsNewTask() })
                return ChildTaskAdmission.Rejected("child_model_deleted")
            if (cancelled) return ChildTaskAdmission.Rejected("cancelled_before_admission")
            // Old calls are resolved above even at this limit. Never drop a sent-call tombstone
            // to make room: that would turn a replay into a second provider request.
            if (binding.requests.size + binding.commandRequests.size >= maxRequestsPerBinding) return ChildTaskAdmission.Rejected("child_request_budget_exhausted")
            if (tasks.size + inputSnapshot.size > maxRetainedTasks) return ChildTaskAdmission.Rejected("child_retention_limit")
            val group = childTaskCallGroup(binding.owner.conversationId.value, binding.owner.runId.value,
                binding.owner.assistantMessageId.value, context.invocation.name, context.invocation.id.value)
            val epoch = Math.incrementExact(generation).also { generation = it }
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
            request = ChildTaskRequest(fingerprint, inputSnapshot.size)
            val staged = mutableListOf<Task>()
            try { inputSnapshot.forEachIndexed { index, inputTask ->
                val owner = binding.owner
                val record = ChildTaskRecord(ChildRunOwner(owner.conversationId.value, owner.runId.value,
                    group, epoch, UUID.randomUUID().toString(), UUID.randomUUID().toString()),
                    owner.assistantMessageId.value, owner.ownerId, index, inputTask.profileId, inputTask.prompt,
                    display = resources.models.getValue(inputTask.profileId).display,
                    access = if (resources.models.getValue(inputTask.profileId).role == ChildModelRole.IMPLEMENTATION)
                        checkNotNull(resources.toolAssembly) { "child_execution_tools_unavailable" }.restrict(resources.session, inputTask.access) else ChildToolAccess.NONE)
                Task(binding, request, record, context.invocation, deadline,
                    ChildTaskPauseControl { notifyChanged(binding) }, definition = resources.models.getValue(inputTask.profileId),
                    toolPin = if (resources.models.getValue(inputTask.profileId).role == ChildModelRole.IMPLEMENTATION) resources.toolAssembly?.retain() else null).also(staged::add)
            } } catch (_: Exception) {
                staged.forEach { runCatching { it.toolPin?.close() } }
                return ChildTaskAdmission.Rejected("child_tool_admission_unavailable")
            }
            reserved = staged.toList()
            binding.requests[callId] = request
            reserved.forEach { tasks[it.initial.owner.childRunId] = it; inheritPauseGroup(it) }
            outstanding += reserved.size
            binding.outstanding += reserved.size
        }
        try {
            store.enqueue(binding.owner, reserved.map { it.initial })
        } catch (_: Exception) {
            val result = ChildTaskAdmission.Unconfirmed("child_admission_unconfirmed")
            synchronized(gate) {
                binding.accepting = false
                request.admission = result
                reserved.forEach { it.journalUnconfirmed = true }
            }
            reserved.forEach(::settled)
            return result
        }
        val result = ChildTaskAdmission.Accepted(Collections.unmodifiableList(reserved.map { it.initial }))
        synchronized(gate) { request.admission = result }
        notifyChanged(binding)
        reserved.forEach { task ->
            try { workers.execute { execute(task) } }
            catch (_: java.util.concurrent.RejectedExecutionException) {
                finishUnsent(task, "child_not_submitted")
                settled(task)
            }
        }
        return result
    }

    fun source(owner: ChildRunOwner): ChildTaskRecord? = store.get(owner)
    fun progress(owner: ChildRunOwner): String = synchronized(gate) { progress[owner].orEmpty() }

    private fun progress(task: Task, text: String) {
        synchronized(gate) {
            if (closed || task.stopRequested || task.settled) return
            progress[task.initial.owner] = text
            while (progress.size > maxRetainedTasks) {
                val oldest = progress.keys.firstOrNull { tasks[it.childRunId]?.initial?.owner != it } ?: break
                progress.remove(oldest)
            }
        }
        notifyChanged(task.binding)
    }

    /** New admissions use the current group; accepted tasks retain their own definitions. */
    fun configure(binding: ChildTaskBinding, models: Map<String, ChildModelDefinition>): Boolean {
        requireBinding(binding)
        val frozen = models.toMap()
        require(frozen.keys.all { it.isNotBlank() && it.length <= 200 })
        val limits = frozen.values.groupBy { it.poolKey }.mapValues { (_, definitions) ->
            require(definitions.map { it.parallelLimit }.distinct().size == 1) { "child_pool_limit_conflict" }
            definitions.first().parallelLimit
        }
        return synchronized(gate) {
            val resources = binding.resources ?: return@synchronized false
            if (closed || !binding.accepting) return@synchronized false
            limits.forEach { (key, limit) ->
                if (resources.poolKeys.add(key)) pools.getOrPut(key) { Pool(limit) }.bindings++
            }
            resources.models = Collections.unmodifiableMap(frozen)
            true
        }
    }

    /** Durable configuration event, independent of parent admission or user pause state. */
    fun reconcile(conversationId: String, limit: (String) -> Int?) {
        val candidates = synchronized(gate) {
            tasks.values.filter { it.initial.owner.conversationId == conversationId }
        }
        // Settings lookup and stop/transport cleanup stay outside the supervisor gate.
        val changes = synchronized(gate) {
            bindings.values.filter { it.owner.conversationId.value == conversationId }
                .flatMap { it.resources?.poolKeys.orEmpty() }.distinct()
        }.mapNotNull { key -> limit(key)?.let { key to it } }
        changes.forEach { (key, size) ->
            val pool = synchronized(gate) { pools[key]?.also { it.limit = size } }
            pool?.capacity?.resize(size)
        }
        candidates.filter { !runCatching { it.definition.isValid() }.getOrDefault(true) }
            .forEach { stop(it.binding, it.initial.owner) }
        candidates.map { it.binding }.distinct().forEach(::notifyChanged)
    }

    fun command(binding: ChildTaskBinding, context: ToolExecutionContext, command: ChildTaskCommand): ChildTaskCommandReceipt {
        requireBinding(binding)
        require(context.owner === binding.owner && context.authorization === binding.authorization.get()) { "child_parent_owner_mismatch" }
        if (context.cancelled()) return ChildTaskCommandReceipt.Rejected("cancelled_before_admission")
        if (command.source.owner.parentRunId != binding.owner.runId.value || command.source.parentOwnerId != binding.owner.ownerId)
            return ChildTaskCommandReceipt.Rejected("child_source_owner_mismatch")
        return commandInternal(binding, context.invocation, command, modelCall = true, cancelled = context.cancelled)
    }

    /** Trusted host entry; a sealed binding is never reopened, including for historical sources. */
    fun command(binding: ChildTaskBinding, invocation: ToolInvocation, command: ChildTaskCommand,
        prepareAdmission: () -> Boolean = { true }): ChildTaskCommandReceipt =
        commandInternal(binding, invocation, command, prepareAdmission = prepareAdmission)

    private fun commandInternal(binding: ChildTaskBinding, invocation: ToolInvocation, command: ChildTaskCommand,
        modelCall: Boolean = false, cancelled: () -> Boolean = { false },
        prepareAdmission: () -> Boolean = { true }): ChildTaskCommandReceipt {
        requireBinding(binding)
        val fingerprint = command.fingerprint()
        val request: ChildTaskCommandRequest
        val remembered: Boolean
        synchronized(gate) {
            // Never wait for another invocation: stores can synchronously reenter this capability.
            pendingCommands[command.callId]?.let {
                return if (it.fingerprint == fingerprint) ChildTaskCommandReceipt.Unconfirmed("child_command_pending")
                else ChildTaskCommandReceipt.Rejected("child_request_conflict")
            }
            val previous = binding.commandRequests[command.callId]
            if (previous != null && previous.fingerprint != fingerprint)
                return ChildTaskCommandReceipt.Rejected("child_request_conflict")
            request = previous ?: ChildTaskCommandRequest(fingerprint)
            // Even a closed/full binding may query an existing durable tombstone. Only a new
            // admission needs a budget slot; the operation pin prevents premature resource release.
            remembered = previous != null
            pendingCommands[command.callId] = request
            outstanding++; binding.outstanding++
        }
        var reserved: Task? = null
        var handedOff = false
        try {
            store.commandReceipt(command)?.let { return it }
            synchronized(gate) {
                if (request.attempted) return request.receipt
                    ?: ChildTaskCommandReceipt.Unconfirmed("child_command_unconfirmed")
            }
            if (!prepareAdmission()) return ChildTaskCommandReceipt.Rejected("child_model_configuration_unavailable")
            synchronized(gate) {
                val resources = binding.resources ?: return ChildTaskCommandReceipt.Rejected("child_binding_closed")
                if (closed || !binding.accepting) return ChildTaskCommandReceipt.Rejected("child_binding_closed")
                if (command.source.owner.conversationId != binding.owner.conversationId.value || command.profileId !in resources.models)
                    return ChildTaskCommandReceipt.Rejected("child_source_owner_mismatch")
                if (!remembered && binding.requests.size + binding.commandRequests.size >= maxRequestsPerBinding)
                    return ChildTaskCommandReceipt.Rejected("child_request_budget_exhausted")
                binding.commandRequests[command.callId] = request
            }
            if (cancelled()) return ChildTaskCommandReceipt.Rejected("cancelled_before_admission")
            val source = store.get(command.source.owner) ?: return ChildTaskCommandReceipt.Rejected("child_source_stale")
            if (modelCall) {
                if (snapshots(binding).none { it.record.owner == command.source.owner })
                    return ChildTaskCommandReceipt.Rejected("child_source_not_controlled")
                // Model arguments are not the records UI's one-shot human confirmation.
                if (command.acknowledgePossibleExecution || source.status == ChildTaskStatus.UNKNOWN ||
                    (command.kind == ChildTaskCommandKind.REPLACE && source.status in setOf(ChildTaskStatus.QUEUED, ChildTaskStatus.RUNNING)))
                    return ChildTaskCommandReceipt.Rejected("child_ui_confirmation_required")
            }
            command.validateSource(source)?.let { return ChildTaskCommandReceipt.Rejected(it) }
            val wasCancelled = cancelled() // external callback, like all store calls, stays outside gate
            val task = synchronized(gate) {
                if (closed || !binding.accepting) return ChildTaskCommandReceipt.Rejected("child_binding_closed")
                if (wasCancelled) return ChildTaskCommandReceipt.Rejected("cancelled_before_admission")
                if (tasks.size >= maxRetainedTasks) return ChildTaskCommandReceipt.Rejected("child_retention_limit")
                if (source.status in setOf(ChildTaskStatus.QUEUED, ChildTaskStatus.RUNNING) && tasks[source.owner.childRunId] == null)
                    return ChildTaskCommandReceipt.Rejected("child_source_not_controlled")
                val definition = binding.resources?.models?.get(command.profileId)
                    ?: return ChildTaskCommandReceipt.Rejected("child_model_configuration_unavailable")
                if (!definition.acceptsNewTask()) return ChildTaskCommandReceipt.Rejected("child_model_deleted")
                val parent = binding.owner
                val epoch = Math.incrementExact(generation).also { generation = it }
                val record = ChildTaskRecord(ChildRunOwner(parent.conversationId.value, parent.runId.value,
                    childTaskCallGroup(parent.conversationId.value, parent.runId.value, parent.assistantMessageId.value,
                        invocation.name, invocation.id.value), epoch, UUID.randomUUID().toString(), UUID.randomUUID().toString()),
                    parent.assistantMessageId.value, parent.ownerId, 0, command.profileId, command.instruction,
                    sourceTaskId = source.owner.childRunId, commandKind = command.kind,
                    display = binding.resources?.models?.get(command.profileId)?.display,
                    access = if (definition.role == ChildModelRole.IMPLEMENTATION)
                        checkNotNull(binding.resources?.toolAssembly) { "child_execution_tools_unavailable" }
                            .restrict(checkNotNull(binding.resources).session, source.access) else ChildToolAccess.NONE)
                Task(binding, ChildTaskRequest(fingerprint.toByteArray(), 1), record, invocation,
                    System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis),
                    ChildTaskPauseControl { notifyChanged(binding) }, source,
                    definition, if (definition.role == ChildModelRole.IMPLEMENTATION) binding.resources?.toolAssembly?.retain() else null).also {
                    it.admitting = true
                    reserved = it
                    tasks[record.owner.childRunId] = it
                    inheritPauseGroup(it)
                    outstanding++; binding.outstanding++
                    // From here on even an exception/null future lookup must never retry this write.
                    request.attempted = true
                }
            }
            val receipt = store.enqueueCommand(binding.owner, command, task.initial)
            if (receipt !is ChildTaskCommandReceipt.Accepted || receipt.task.owner != task.initial.owner ||
                receipt.task.status != ChildTaskStatus.QUEUED) {
                synchronized(gate) { request.receipt = receipt.takeUnless { it is ChildTaskCommandReceipt.Accepted } }
                return receipt // A durable replay is observation, never permission to open it again.
            }
            val stopBatch = synchronized(gate) {
                task.admitting = false
                task.record = receipt.task
                tasks[source.owner.childRunId]?.let { old ->
                    old.record = old.record.copy(revision = maxOf(old.record.revision, source.revision + 1))
                    if (command.kind == ChildTaskCommandKind.REPLACE) {
                        old.stopRequested = true; old.pause.terminate(); old.batch
                    } else null
                }
            }
            // close/revoke/stop during enqueue already marked the reservation. execute also checks
            // those intents; no accepted row is dropped just because the worker pool shut down.
            handedOff = true
            stopBatch?.let { runCatching { it.close() } }
            notifyChanged(binding)
            if (runCatching(cancelled).getOrDefault(true)) synchronized(gate) { task.stopRequested = true; task.pause.terminate() }
            try { workers.execute { execute(task) } }
            catch (_: java.util.concurrent.RejectedExecutionException) {
                finishUnsent(task, "child_not_submitted"); settled(task)
            }
            return receipt
        } catch (_: Exception) {
            val result = ChildTaskCommandReceipt.Unconfirmed("child_command_unconfirmed")
            synchronized(gate) { request.receipt = result }
            // An unknown write stays unknown. Do not finish its possible row or auto-enqueue it.
            return result
        } finally {
            val callback = synchronized(gate) {
                if (!handedOff) reserved?.let { task ->
                    tasks.remove(task.initial.owner.childRunId)
                    outstanding--; binding.outstanding--
                }
                pendingCommands.remove(command.callId)
                if (!request.attempted) binding.commandRequests.remove(command.callId)
                outstanding--; binding.outstanding--
                binding.resources?.onChanged.also { releaseBindingIfIdle(binding) }
            }
            if (!handedOff) runCatching { reserved?.toolPin?.close() }
            callback?.let { runCatching(it) }
            notifyIfIdle()
        }
    }

    fun finishParent(binding: ChildTaskBinding) {
        requireBinding(binding)
        val callback = synchronized(gate) {
            binding.accepting = false
            binding.resources?.onChanged.also { releaseBindingIfIdle(binding) }
        }
        callback?.let { runCatching(it) }
    }

    /** True only once admission is sealed AND every reserved task has really drained.
     * Hosts may then remove their handle; durable history is still in ChildTaskStore.
     */
    fun isReleased(binding: ChildTaskBinding): Boolean {
        requireBinding(binding)
        return synchronized(gate) { binding.resources == null }
    }

    // Called under gate before a reserved child can enter its first provider boundary.
    private fun inheritPauseGroup(task: Task) {
        val group = task.binding.pauseGroup ?: return
        if (!group.consumed && task.initial.owner !in group.manual && task.pause.snapshot() == ChildTaskPauseState.ACTIVE &&
            !task.stopRequested && !task.journalUnconfirmed && !task.settled &&
            task.record.status in setOf(ChildTaskStatus.QUEUED, ChildTaskStatus.RUNNING)) {
            if (task.pause.pause() != ChildTaskControlReceipt.STALE) group.members.add(task.initial.owner)
        }
    }

    fun pauseGroup(binding: ChildTaskBinding): ChildTaskPauseGroup {
        requireBinding(binding)
        val group = synchronized(gate) {
            val group = binding.pauseGroup ?: ChildTaskPauseGroup(binding).also { binding.pauseGroup = it }
            tasks.values.filter { it.binding === binding }.forEach(::inheritPauseGroup)
            group
        }
        notifyChanged(binding)
        return group
    }

    fun hasCaptured(group: ChildTaskPauseGroup): Boolean {
        requireBinding(group.binding)
        return synchronized(gate) { !group.consumed && group.members.isNotEmpty() }
    }

    fun resumeGroup(group: ChildTaskPauseGroup): Boolean {
        val binding = group.binding
        requireBinding(binding)
        synchronized(gate) {
            if (group.consumed || binding.pauseGroup !== group) return false
            group.consumed = true; binding.pauseGroup = null
            group.members.forEach { owner ->
                tasks[owner.childRunId]?.takeIf { it.binding === binding && it.initial.owner == owner &&
                    !it.stopRequested && !it.journalUnconfirmed && !it.settled &&
                    it.record.status in setOf(ChildTaskStatus.QUEUED, ChildTaskStatus.RUNNING) }?.pause?.resume()
            }
        }
        notifyChanged(binding)
        return true
    }

    /** Consumes the captured choice once; dismissing it preserves all pause intents. */
    fun finishGroup(group: ChildTaskPauseGroup, stop: Boolean): Boolean {
        val binding = group.binding
        requireBinding(binding)
        val batches = synchronized(gate) {
            if (group.consumed || binding.pauseGroup !== group) return false
            group.consumed = true; binding.pauseGroup = null
            if (!stop) emptyList() else group.members.mapNotNull { owner ->
                tasks[owner.childRunId]?.takeIf { it.binding === binding && it.initial.owner == owner &&
                    !it.settled && !it.journalUnconfirmed &&
                    it.record.status in setOf(ChildTaskStatus.QUEUED, ChildTaskStatus.RUNNING) }?.let {
                    it.stopRequested = true; it.pause.terminate(); it.batch
                }
            }
        }
        batches.forEach { it.close() }
        notifyChanged(binding)
        return true
    }

    /** The binding is the trusted host capability; every field of the child identity must match.
     * Parent admission may be sealed without revoking control of its accepted children. */
    fun pause(binding: ChildTaskBinding, owner: ChildRunOwner): ChildTaskControlReceipt =
        control(binding, owner, resume = false)

    /** Resumes only this live child, never a persisted row or a replacement request. */
    fun resume(binding: ChildTaskBinding, owner: ChildRunOwner): ChildTaskControlReceipt =
        control(binding, owner, resume = true)

    private fun control(binding: ChildTaskBinding, owner: ChildRunOwner, resume: Boolean): ChildTaskControlReceipt {
        requireBinding(binding)
        val receipt = synchronized(gate) {
            val task = tasks[owner.childRunId]?.takeIf { it.binding === binding && it.initial.owner == owner }
                ?: return ChildTaskControlReceipt.STALE
            if (closed || task.admitting || task.stopRequested || task.journalUnconfirmed || task.record.status !in setOf(ChildTaskStatus.QUEUED, ChildTaskStatus.RUNNING))
                return ChildTaskControlReceipt.STALE
            binding.pauseGroup?.let { it.members.remove(owner); it.manual.add(owner) }
            if (resume) task.pause.resume() else task.pause.pause()
        }
        notifyChanged(binding)
        return receipt
    }

    fun stop(binding: ChildTaskBinding, owner: ChildRunOwner): ChildTaskStop {
        requireBinding(binding)
        val batch: ChildModelBatch?
        synchronized(gate) {
            val task = tasks[owner.childRunId]?.takeIf { it.binding === binding && it.initial.owner == owner }
            if (task == null) return if (binding.recent[owner.childRunId]?.record?.owner == owner)
                ChildTaskStop.ALREADY_SETTLED else ChildTaskStop.NOT_FOUND
            if (task.record.status !in setOf(ChildTaskStatus.QUEUED, ChildTaskStatus.RUNNING))
                return ChildTaskStop.ALREADY_SETTLED
            binding.pauseGroup?.let { it.members.remove(owner); it.manual.add(owner) }
            task.stopRequested = true
            task.pause.terminate()
            batch = task.batch
        }
        batch?.close()
        notifyChanged(binding)
        return ChildTaskStop.REQUESTED
    }

    fun revoke(binding: ChildTaskBinding) {
        requireBinding(binding)
        val callback: (() -> Unit)?
        val active = synchronized(gate) {
            binding.accepting = false
            callback = binding.resources?.onChanged
            tasks.values.filter { it.binding === binding }.onEach { it.stopRequested = true; it.pause.terminate() }
                .mapNotNull { it.batch }.also { releaseBindingIfIdle(binding) }
        }
        active.forEach { it.close() }
        callback?.let { runCatching(it) }
    }

    /** Includes queued, paused and physically draining tasks across every parent binding. */
    fun liveProfiles(): Set<Pair<String, String>> = synchronized(gate) {
        tasks.values.map { it.initial.owner.conversationId to it.initial.profileId }.toSet()
    }

    fun snapshots(binding: ChildTaskBinding): List<RetainedChildTask> {
        requireBinding(binding)
        return synchronized(gate) {
            (binding.recent.values + tasks.values.filter { it.binding === binding }.map {
                RetainedChildTask(it.record, it.stopRequested, false, it.journalUnconfirmed, it.pause.snapshot(), it.queueState)
            }).sortedWith(compareBy({ it.record.owner.generation }, { it.record.index }))
        }
    }

    override fun close() {
        val active = synchronized(gate) {
            closed = true
            bindings.values.toList().forEach { it.accepting = false; releaseBindingIfIdle(it) }
            tasks.values.onEach { it.stopRequested = true; it.pause.terminate() }.mapNotNull { it.batch }
        }
        active.forEach { it.close() }
        workers.shutdown()
        notifyIfIdle()
    }

    fun closeWhenIdle(onClosed: () -> Unit) {
        synchronized(gate) { observers.add(onClosed) }
        close()
    }

    private fun requireBinding(binding: ChildTaskBinding) {
        require(binding.supervisor === this) { "foreign_child_binding" }
    }

    private fun stopped(task: Task) = synchronized(gate) { closed || task.stopRequested }

    private fun execute(task: Task) {
        // Resources and pool references stay pinned until this task's real batch drain.
        val resources = synchronized(gate) { checkNotNull(task.binding.resources) }
        val definition = task.definition
        var batch: ChildModelBatch? = null
        try {
            // A terminal row or stop intent is NOT a transport drain. No successor opens early.
            val source = task.source
            if (source != null) {
                while (synchronized(gate) { tasks.containsKey(source.owner.childRunId) } &&
                    !stopped(task) && System.nanoTime() < task.deadline) Thread.sleep(25)
                if (stopped(task) || System.nanoTime() >= task.deadline) {
                    finishUnsent(task, "child_source_not_drained"); return
                }
            }
            val preceding = source?.let { store.get(it.owner) }
            if (source != null && preceding == null) {
                finishUnsent(task, "child_source_missing"); return
            }
            if (task.initial.commandKind == ChildTaskCommandKind.APPEND && preceding?.status != ChildTaskStatus.COMPLETED) {
                finishUnsent(task, "child_append_source_unconfirmed"); return
            }
            if (stopped(task) || System.nanoTime() >= task.deadline) {
                finishUnsent(task, "child_not_sent")
                return
            }
            val remaining = TimeUnit.NANOSECONDS.toMillis(task.deadline - System.nanoTime()).coerceAtLeast(1)
            batch = ChildModelBatch(mapOf(task.initial.profileId to definition), remaining, journal(task), usageRecorder,
                onProgress = { _, text -> progress(task, text) }, toolAssembly = resources.toolAssembly,
                sharedPool = { selected -> synchronized(gate) {
                    check(!task.settled && task.binding.resources === resources)
                    val pool = pools.getOrPut(selected.poolKey) { Pool(selected.parallelLimit) }
                    if (resources.poolKeys.add(selected.poolKey)) pool.bindings++
                    pool.capacity.lease { waiting ->
                        synchronized(gate) { task.queueState = if (waiting) ChildTaskQueueState.PENDING else ChildTaskQueueState.ACTIVE }
                        notifyChanged(task.binding)
                    }
                } })
            batch.bind(task.binding.owner, resources.session, resources.authorization)
            synchronized(gate) { task.batch = batch }
            if (stopped(task)) {
                finishUnsent(task, "child_not_sent")
            } else {
                val context = ToolExecutionContext(task.binding.owner, task.invocation, resources.authorization) { stopped(task) }
                batch.executeQueued(context, listOf(task.initial), task.pause,
                    preceding.takeIf { task.initial.commandKind == ChildTaskCommandKind.APPEND })
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            finishUnsent(task, "child_wait_interrupted")
        } catch (_: Exception) {
            synchronized(gate) { task.journalUnconfirmed = true }
        } finally {
            val release = {
                if (synchronized(gate) { task.record.status == ChildTaskStatus.QUEUED && !task.journalUnconfirmed })
                    finishUnsent(task, "child_not_sent")
                settled(task)
            }
            if (batch == null) release() else batch.closeWhenIdle(release)
        }
    }

    private fun journal(state: Task): ChildTaskStore = object : ChildTaskStore {
        override fun enqueue(parent: RunExecutionOwner, tasks: List<ChildTaskRecord>) = error("child_already_queued")
        override fun started(task: ChildTaskRecord) {
            try {
                store.started(task)
                synchronized(gate) { state.record = task.copy(status = ChildTaskStatus.RUNNING, revision = state.record.revision) }
            } catch (failure: Exception) {
                synchronized(gate) { state.journalUnconfirmed = true }
                throw failure
            }
            notifyChanged(state.binding)
        }
        override fun selected(task: ChildTaskRecord, display: ChildTaskDisplay?) {
            store.selected(task, display)
            synchronized(gate) { state.record = state.record.copy(display = display) }
            notifyChanged(state.binding)
        }
        override fun finished(task: ChildTaskRecord, status: ChildTaskStatus, text: String, detail: String?) {
            try {
                store.finished(task, status, text, detail)
                synchronized(gate) {
                    state.record = task.copy(status = status, text = text, detail = detail, revision = state.record.revision)
                    state.journalUnconfirmed = false
                }
            } catch (failure: Exception) {
                synchronized(gate) { state.journalUnconfirmed = true }
                throw failure
            }
            notifyChanged(state.binding)
        }
        override fun list(conversationId: String, limit: Int): List<ChildTaskRecord> = store.list(conversationId, limit)
    }

    private fun finishUnsent(task: Task, detail: String) {
        try { journal(task).finished(task.initial, ChildTaskStatus.NOT_EXECUTED, detail = detail) }
        catch (_: Exception) { /* Keep the last confirmed row, never turn failed persistence into success. */ }
    }

    private fun settled(task: Task) {
        val callback = synchronized(gate) {
            check(!task.settled)
            task.settled = true
            task.pause.terminate()
            task.batch = null
            tasks.remove(task.initial.owner.childRunId)
            val binding = task.binding
            binding.recent[task.initial.owner.childRunId] =
                RetainedChildTask(task.record, task.stopRequested, true, task.journalUnconfirmed)
            while (binding.recent.size > maxRetainedTasks) binding.recent.remove(binding.recent.keys.first())
            task.request.remaining--
            if (task.request.remaining == 0 && task.request.admission is ChildTaskAdmission.Accepted)
                task.request.admission = ChildTaskAdmission.Rejected("child_request_settled")
            outstanding--
            binding.outstanding--
            binding.resources?.onChanged.also { releaseBindingIfIdle(binding) }
        }
        runCatching { task.toolPin?.close() }
        callback?.let { runCatching(it) }
        notifyIfIdle()
    }

    /** Under gate only. Never called as a substitute for waiting for model/session exit. */
    private fun releaseBindingIfIdle(binding: ChildTaskBinding) {
        if (binding.accepting || binding.outstanding != 0) return
        val resources = binding.resources ?: return
        resources.poolKeys.forEach { key ->
            val pool = pools.getValue(key)
            pool.bindings--
            if (pool.bindings == 0) pools.remove(key)
        }
        binding.resources = null
        // Only a failed enqueue needs its small, stable Unknown receipt after sealing.
        // All other calls are denied by the sealed handle; seenOwners forbids rebinding.
        binding.requests.entries.removeAll { it.value.admission !is ChildTaskAdmission.Unconfirmed }
        binding.commandRequests.entries.removeAll { it.value.receipt !is ChildTaskCommandReceipt.Unconfirmed }
        bindings.remove(binding.owner)
    }

    private fun notifyChanged(binding: ChildTaskBinding) {
        val callback = synchronized(gate) { binding.resources?.onChanged }
        callback?.let { runCatching(it) }
    }

    private fun notifyIfIdle() {
        val callbacks = synchronized(gate) {
            if (!closed || outstanding != 0) emptyList() else observers.toList().also { observers.clear() }
        }
        callbacks.forEach { callback -> runCatching(callback) }
    }

    private fun fingerprint(tasks: List<ChildModelTask>): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        tasks.forEach { task ->
            listOf(task.profileId, task.prompt, task.access.toJson()).forEach { text ->
                val bytes = text.toByteArray(Charsets.UTF_8)
                digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
                digest.update(bytes)
            }
        }
        return digest.digest()
    }

}
