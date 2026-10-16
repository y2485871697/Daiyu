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
    val pauseState: ChildTaskPauseState = ChildTaskPauseState.ACTIVE
)

internal class ChildTaskResources(
    val session: SessionCapabilities,
    val authorization: TaskAuthorization,
    val models: Map<String, ChildModelDefinition>,
    val onChanged: () -> Unit
)

/** A digest is a replay tombstone, not a retained prompt or permission to run again. */
internal class ChildTaskRequest(val fingerprint: ByteArray, var remaining: Int) {
    var admission: ChildTaskAdmission? = null
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
    internal var outstanding = 0
    internal val requests = mutableMapOf<String, ChildTaskRequest>()
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
    private class Pool(val limit: Int) {
        val permits = Semaphore(if (limit == 0) MAX_WORKERS else minOf(limit, MAX_WORKERS), true)
        var bindings = 0
    }
    private class Task(val binding: ChildTaskBinding, val request: ChildTaskRequest,
        val initial: ChildTaskRecord, val invocation: ToolInvocation, val deadline: Long,
        val pause: ChildTaskPauseControl) {
        var record = initial
        var stopRequested = false
        var settled = false
        var journalUnconfirmed = false
        var batch: ChildModelBatch? = null
    }
    private val gate = Any()
    private val pools = mutableMapOf<String, Pool>()
    private val bindings = java.util.IdentityHashMap<RunExecutionOwner, ChildTaskBinding>()
    // RunExecutionOwner has identity equality. A reachable old owner cannot be rebound after
    // its resources are released; dead owners need not be retained for the controller lifetime.
    private val seenOwners = WeakHashMap<RunExecutionOwner, Unit>()
    private val tasks = linkedMapOf<String, Task>()
    private val workers = Executors.newFixedThreadPool(MAX_WORKERS) { action ->
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
        models: Map<String, ChildModelDefinition>, onChanged: () -> Unit = {}): ChildTaskBinding = synchronized(gate) {
        check(!closed) { "child_supervisor_closed" }
        require(authorization.sessionId == session.sessionId && authorization.taskId == owner.runId.value)
        check(!seenOwners.containsKey(owner)) { "child_parent_already_bound" }
        val frozen = models.toMap()
        require(frozen.isNotEmpty() && frozen.keys.all { it.isNotBlank() && it.length <= 200 })
        val limits = frozen.values.groupBy { it.poolKey }.mapValues { (_, definitions) ->
            require(definitions.map { it.parallelLimit }.distinct().size == 1) { "child_pool_limit_conflict" }
            definitions.first().parallelLimit
        }
        require(limits.all { (key, limit) -> pools[key]?.limit?.let { it == limit } != false }) { "child_pool_limit_conflict" }
        limits.forEach { (key, limit) -> pools.getOrPut(key) { Pool(limit) }.bindings++ }
        ChildTaskBinding(this, owner, ChildTaskResources(session, authorization,
            Collections.unmodifiableMap(frozen), onChanged)).also {
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
        if (inputSnapshot.size !in 1..8 || inputSnapshot.any {
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
            if (cancelled) return ChildTaskAdmission.Rejected("cancelled_before_admission")
            // Old calls are resolved above even at this limit. Never drop a sent-call tombstone
            // to make room: that would turn a replay into a second provider request.
            if (binding.requests.size >= maxRequestsPerBinding) return ChildTaskAdmission.Rejected("child_request_budget_exhausted")
            if (tasks.size + inputSnapshot.size > maxRetainedTasks) return ChildTaskAdmission.Rejected("child_retention_limit")
            val group = UUID.randomUUID().toString()
            val epoch = Math.incrementExact(generation).also { generation = it }
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
            request = ChildTaskRequest(fingerprint, inputSnapshot.size)
            binding.requests[callId] = request
            reserved = inputSnapshot.mapIndexed { index, inputTask ->
                val owner = binding.owner
                val record = ChildTaskRecord(ChildRunOwner(owner.conversationId.value, owner.runId.value,
                    group, epoch, UUID.randomUUID().toString(), UUID.randomUUID().toString()),
                    owner.assistantMessageId.value, owner.ownerId, index, inputTask.profileId, inputTask.prompt)
                Task(binding, request, record, context.invocation, deadline,
                    ChildTaskPauseControl { notifyChanged(binding) }).also { tasks[record.owner.childRunId] = it }
            }
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
            if (closed || task.stopRequested || task.journalUnconfirmed || task.record.status !in setOf(ChildTaskStatus.QUEUED, ChildTaskStatus.RUNNING))
                return ChildTaskControlReceipt.STALE
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

    fun snapshots(binding: ChildTaskBinding): List<RetainedChildTask> {
        requireBinding(binding)
        return synchronized(gate) {
            (binding.recent.values + tasks.values.filter { it.binding === binding }.map {
                RetainedChildTask(it.record, it.stopRequested, false, it.journalUnconfirmed, it.pause.snapshot())
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
        val definition = resources.models.getValue(task.initial.profileId)
        val pool = synchronized(gate) { pools.getValue(definition.poolKey) }
        var acquired = false
        var batch: ChildModelBatch? = null
        try {
            while (!acquired && !stopped(task) && System.nanoTime() < task.deadline)
                acquired = pool.permits.tryAcquire(25, TimeUnit.MILLISECONDS)
            if (!acquired || stopped(task) || System.nanoTime() >= task.deadline) {
                finishUnsent(task, "child_not_sent")
                return
            }
            val remaining = TimeUnit.NANOSECONDS.toMillis(task.deadline - System.nanoTime()).coerceAtLeast(1)
            batch = ChildModelBatch(mapOf(task.initial.profileId to definition), remaining, journal(task), usageRecorder)
            batch.bind(task.binding.owner, resources.session, resources.authorization)
            synchronized(gate) { task.batch = batch }
            if (stopped(task)) {
                finishUnsent(task, "child_not_sent")
            } else {
                val context = ToolExecutionContext(task.binding.owner, task.invocation, resources.authorization) { stopped(task) }
                batch.executeQueued(context, listOf(task.initial), task.pause)
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
                if (acquired) pool.permits.release()
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
                synchronized(gate) { state.record = task.copy(status = ChildTaskStatus.RUNNING) }
            } catch (failure: Exception) {
                synchronized(gate) { state.journalUnconfirmed = true }
                throw failure
            }
            notifyChanged(state.binding)
        }
        override fun finished(task: ChildTaskRecord, status: ChildTaskStatus, text: String, detail: String?) {
            try {
                store.finished(task, status, text, detail)
                synchronized(gate) {
                    state.record = task.copy(status = status, text = text, detail = detail)
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
        callback?.let { runCatching(it) }
        notifyIfIdle()
    }

    /** Under gate only. Never called as a substitute for waiting for model/session exit. */
    private fun releaseBindingIfIdle(binding: ChildTaskBinding) {
        if (binding.accepting || binding.outstanding != 0) return
        val resources = binding.resources ?: return
        resources.models.values.map { it.poolKey }.toSet().forEach { key ->
            val pool = pools.getValue(key)
            pool.bindings--
            if (pool.bindings == 0) pools.remove(key)
        }
        binding.resources = null
        // Only a failed enqueue needs its small, stable Unknown receipt after sealing.
        // All other calls are denied by the sealed handle; seenOwners forbids rebinding.
        binding.requests.entries.removeAll { it.value.admission !is ChildTaskAdmission.Unconfirmed }
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
            listOf(task.profileId, task.prompt).forEach { text ->
                val bytes = text.toByteArray(Charsets.UTF_8)
                digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
                digest.update(bytes)
            }
        }
        return digest.digest()
    }

    private companion object { const val MAX_WORKERS = 8 }
}
