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
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean


data class ChildModelTask(val profileId: String, val prompt: String)

/** An exact host-frozen selection. Zero means no extra model-pool limit. */
class ChildModelDefinition(val poolKey: String, val parallelLimit: Int, val create: () -> ModelPort) {
    init { require(poolKey.isNotBlank()); require(parallelLimit >= 0) }
}

/** Synchronous parent tool, internally parallel, with optional durable per-child observation. */
class ChildModelBatch(models: Map<String, ChildModelDefinition>, private val timeoutMillis: Long = 120_000,
    private val store: ChildTaskStore? = null,
    private val usageRecorder: ChildUsageRecorder? = null) : AutoCloseable {
    private val models = models.toMap()
    private val limits = this.models.values.groupBy { it.poolKey }.mapValues { (_, entries) ->
        require(entries.map { it.parallelLimit }.distinct().size == 1) { "child_pool_limit_conflict" }
        Semaphore(entries.first().parallelLimit.let { if (it == 0) MAX_TASKS else minOf(it, MAX_TASKS) }, true)
    }
    private val workers = Executors.newFixedThreadPool(MAX_TASKS) { task -> Thread(task, "child-model").apply { isDaemon = true } }
    private val gate = Any()
    private var owner: RunExecutionOwner? = null
    private var session: SessionCapabilities? = null
    private var authorization: TaskAuthorization? = null
    private var closed = false
    private var busy = false
    private var outstanding = 0
    // Registered before exposing a transport to ChatRunner, whose cancel is asynchronous.
    // Worker exit alone does not prove provider cleanup has returned.
    private var cleanupPins = 0
    private var generation = 0L
    private val runners = mutableSetOf<ChatRunner>()
    private var pauseControl: ChildTaskPauseControl? = null
    private val observers = mutableListOf<() -> Unit>()

    init { require(timeoutMillis in 1..120_000); require(this.models.keys.all { it.isNotBlank() && it.length <= 200 }) }

    fun bind(owner: RunExecutionOwner, session: SessionCapabilities, authorization: TaskAuthorization) = synchronized(gate) {
        check(!closed) { "child_batch_closed" }
        require(authorization.taskId == owner.runId.value && authorization.sessionId == session.sessionId)
        check(this.owner == null || (this.owner === owner && this.session === session && this.authorization === authorization))
        this.owner = owner; this.session = session; this.authorization = authorization
    }

    private data class Completion(val index: Int, val child: ChildRunOwner, val message: Message, val text: String)

    fun execute(context: ToolExecutionContext, tasks: List<ChildModelTask>): ExecutionReceipt =
        executeBatch(context, tasks, null, null, null)

    internal fun executeQueued(context: ToolExecutionContext, records: List<ChildTaskRecord>,
        pause: ChildTaskPauseControl? = null, preceding: ChildTaskRecord? = null): ExecutionReceipt {
        require(pause == null || records.size == 1) { "pause_requires_exact_child" }
        require(preceding == null || (records.size == 1 && preceding.status == ChildTaskStatus.COMPLETED &&
            records.single().sourceTaskId == preceding.owner.childRunId && records.single().commandKind == ChildTaskCommandKind.APPEND))
        return executeBatch(context, records.map { ChildModelTask(it.profileId, it.prompt) }, records, pause, preceding)
    }

    private fun executeBatch(context: ToolExecutionContext, tasks: List<ChildModelTask>,
        queued: List<ChildTaskRecord>?, pause: ChildTaskPauseControl?, preceding: ChildTaskRecord?): ExecutionReceipt {
        val snapshot = tasks.toList()
        if (snapshot.size !in 1..MAX_TASKS || snapshot.any {
            it.profileId !in models || it.prompt.isBlank() || it.prompt.length > 4096 || bytes(it.prompt) > 16 * 1024
        }) return ExecutionReceipt.NotExecuted("invalid_child_tasks")
        val parent: RunExecutionOwner
        val parentSession: SessionCapabilities
        val parentAuthorization: TaskAuthorization
        val current: Long
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
            busy = true; current = ++generation
            pauseControl = pause
        }
        val group = queued?.first()?.owner?.taskGroupId ?: UUID.randomUUID().toString()
        val recordGeneration = queued?.first()?.owner?.generation ?: current
        val started = AtomicBoolean(false)
        val completions = LinkedBlockingQueue<Completion>()
        val failures = AtomicBoolean(false)
        val pausedAtStart = pause?.pausedNanos() ?: 0L
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        fun beforeDeadline(): Boolean = System.nanoTime() - ((pause?.pausedNanos() ?: 0L) - pausedAtStart) < deadline
        fun cancelled() = synchronized(gate) { closed || generation != current } || context.cancelled()
        fun uncertain(code: String): ExecutionReceipt {
            close()
            return if (started.get()) ExecutionReceipt.Unknown(code) else ExecutionReceipt.NotExecuted(code)
        }
        try {
            val records = queued ?: snapshot.mapIndexed { index, task ->
                ChildTaskRecord(ChildRunOwner(parent.conversationId.value, parent.runId.value, group, current,
                    UUID.randomUUID().toString(), UUID.randomUUID().toString()), parent.assistantMessageId.value,
                    parent.ownerId, index, task.profileId, task.prompt)
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
                        val record = records[index]
                        var requestStarted = false
                        val definition = models.getValue(task.profileId)
                        val pool = limits.getValue(definition.poolKey)
                        var acquired = false
                        // A permit belongs to this item until BOTH its worker and transport drain.
                        // Keep outstanding pinned through the lock-free release as well, so idle
                        // observers cannot retire the batch while a release is still in flight.
                        var workerFinished = false
                        var cleanupFinished = true // No transport exists on pre-open failures.
                        var itemReleased = false
                        fun releaseItemIfReady() {
                            val release = synchronized(gate) {
                                if (!workerFinished || !cleanupFinished || itemReleased) false
                                else { itemReleased = true; true }
                            }
                            if (!release) return
                            if (acquired) pool.release()
                            synchronized(gate) { outstanding-- }
                            notifyIfIdle()
                        }
                        try {
                            while (!acquired && !cancelled() && beforeDeadline())
                                acquired = pool.tryAcquire(25, TimeUnit.MILLISECONDS)
                            if (acquired && !cancelled() && beforeDeadline()) {
                                val child = record.owner
                                val scope = ChildRunScope.capture(child, parentSession, parentAuthorization, emptySet())
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
                                val selected = object : ModelPort {
                                    override fun open(history: List<Message>): ModelSession = open(history, noTools.definitions)
                                    override fun open(history: List<Message>, definitions: List<app.daiyu.android.domain.ToolDefinition>): ModelSession {
                                        check(definitions.isEmpty())
                                        check(!cancelled() && beforeDeadline())
                                        check(pause?.awaitBoundary() != false)
                                        check(!cancelled() && beforeDeadline())
                                        val port = definition.create()
                                        store?.started(record)
                                        // Persistence/factory callbacks may have raced a new pause intent.
                                        check(pause?.awaitBoundary() != false)
                                        val revoked = context.cancelled()
                                        synchronized(gate) {
                                            check(!closed && generation == current && !revoked && beforeDeadline())
                                            requestStarted = true
                                            started.set(true)
                                        }
                                        val transport = port.open(history, definitions)
                                        val cancelStarted = AtomicBoolean(false)
                                        synchronized(gate) { cleanupFinished = false; cleanupPins++ }
                                        return object : ModelSession {
                                            override fun cancel() {
                                                if (!cancelStarted.compareAndSet(false, true)) return
                                                try { transport.cancel() }
                                                finally {
                                                    synchronized(gate) { cleanupFinished = true; cleanupPins-- }
                                                    releaseItemIfReady()
                                                }
                                            }
                                            override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                                                val texts = mutableMapOf<String, Int>()
                                                var rejected: Exception? = null
                                                val checkedSink: (ModelEvent) -> Unit = checkedSink@ { event ->
                                                    if (cancelled()) return@checkedSink
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
                                                        }
                                                        is ModelEvent.ToolReady -> throw ModelRequestException(ModelFailure.PROTOCOL)
                                                        is ModelEvent.Usage -> sink(event)
                                                        // No continuation or reasoning projection in this first stage.
                                                        else -> Unit
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
                                                            else transport.next(outputs, attemptSink)
                                                        } finally {
                                                            // Fence late callbacks and drain acknowledged delivery
                                                            // before publishing a resumable pause boundary.
                                                            delivery.close()
                                                        }
                                                        break
                                                    } catch (failure: Exception) {
                                                        // A blocked read is never interrupted for pause. Only a
                                                        // transport-reported safe continuation may park here.
                                                        val resumable = transport as? ResumableModelSession
                                                        val kind = (failure as? ModelRequestException)?.failure
                                                        if (rejected != null || cancelled() || !beforeDeadline() ||
                                                            kind !in setOf(ModelFailure.NETWORK, ModelFailure.TIMEOUT) ||
                                                            resumable?.canResume != true) throw failure
                                                        // Claim intent and boundary together: retracting an intent
                                                        // before this point must not silently enable reconnect.
                                                        if (pause == null || !pause.awaitBoundary(requireRequest = true)) throw failure
                                                        check(!cancelled() && beforeDeadline())
                                                        // Never call open or next(outputs) again for this request.
                                                        continuing = true
                                                    }
                                                }
                                                rejected?.let { throw it }
                                            }
                                        }
                                    }
                                }
                                val runner = ChatRunner(selected, memory, noTools, RunnerOptions(maxRounds = 1,
                                    runtimeStore = usageRecorder?.let { recorder -> object : RuntimeStore {
                                        override fun save(checkpoint: RunCheckpoint) = recorder.record(record, checkpoint)
                                    } },
                                    validateBatch = { require(it.isEmpty()) { "child_tools_disabled" } })) { scope.requireOwner(it) }
                                val attached = synchronized(gate) { if (closed) false else { runners.add(runner); true } }
                                try {
                                    if (attached && !cancelled()) {
                                        val previous = preceding?.let { source -> listOf(
                                            Message(MessageId(UUID.randomUUID().toString()), initial.conversationId, initial.runId,
                                                Message.USER_ROLE, listOf(MessageBlock.Text("source-input", source.prompt)), RunStatus.COMPLETED),
                                            Message(MessageId(UUID.randomUUID().toString()), initial.conversationId, initial.runId,
                                                Message.ASSISTANT_ROLE, listOf(MessageBlock.Text("source-result", source.text)), RunStatus.COMPLETED)
                                        ) } ?: emptyList()
                                        val result = runner.run(initial, previous + user)
                                        scope.requireOwner(result)
                                        check(result.status == RunStatus.COMPLETED && !cancelled())
                                        val text = result.blocks.filterIsInstance<MessageBlock.Text>().joinToString("\n") { it.text }
                                        check(text.isNotBlank() && bytes(text) <= MAX_OUTPUT)
                                        completion = Completion(index, child, result, text)
                                    } else runner.stop()
                                } finally { synchronized(gate) { runners.remove(runner) } }
                            }
                        } catch (_: Exception) { /* A sent request is uncertain; never reopen it. */ }
                        finally {
                            pause?.terminate()
                            try {
                                val result = completion
                                if (result != null) store?.finished(record, ChildTaskStatus.COMPLETED, result.text)
                                else store?.finished(record,
                                    if (requestStarted) ChildTaskStatus.UNKNOWN else ChildTaskStatus.NOT_EXECUTED,
                                    detail = if (requestStarted) "child_result_unconfirmed" else "child_not_sent")
                            } catch (_: Exception) {
                                completion = null
                            } finally {
                                if (completion == null) failures.set(true) else completions.offer(completion)
                                synchronized(gate) { workerFinished = true }
                                releaseItemIfReady()
                            }
                        }
                    }
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
                if (failures.get()) return uncertain("child_result_unconfirmed")
                val result = completions.poll(25, TimeUnit.MILLISECONDS) ?: continue
                check(result.child.taskGroupId == group && result.child.generation == recordGeneration &&
                    result.child.parentRunId == parent.runId.value && result.child.conversationId == parent.conversationId.value)
                check(results[result.index] == null)
                results[result.index] = result; remaining--
            }
            val output = results.map { checkNotNull(it) }
            if (output.sumOf { bytes(it.text).toLong() } > 256 * 1024) return uncertain("child_batch_output_too_large")
            val payload = output.joinToString(",", "{\"ok\":true,\"task_group_id\":${jsonQuote(group)},\"generation\":$recordGeneration,\"results\":[", "]}") {
                "{\"index\":${it.index},\"profile_id\":${jsonQuote(snapshot[it.index].profileId)}," +
                    "\"child_run_id\":${jsonQuote(it.child.childRunId)},\"assistant_message_id\":${jsonQuote(it.message.id.value)},\"text\":${jsonQuote(it.text)}}"
            }
            if (bytes(payload) > 1024 * 1024) return uncertain("child_receipt_too_large")
            if (cancelled()) return uncertain("child_batch_cancelled")
            return ExecutionReceipt.Confirmed(payload)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt(); return uncertain("child_wait_interrupted")
        } catch (_: Exception) { return uncertain("child_batch_unconfirmed") }
        finally { synchronized(gate) { busy = false }; notifyIfIdle() }
    }

    override fun close() {
        val active = synchronized(gate) {
            closed = true
            pauseControl?.terminate()
            runners.toList()
        }
        active.forEach { it.stop() }
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
        private const val MAX_TASKS = 8
        private const val MAX_OUTPUT = 64 * 1024
        private fun bytes(text: String) = text.toByteArray(Charsets.UTF_8).size
    }
}
