package app.daiyu.android.domain.delegation

import app.daiyu.android.domain.*
import app.daiyu.android.domain.runtime.*
import app.daiyu.android.domain.tools.ReceiptBatchStart
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Typed host port. Domain never knows about Android, native hosts or browser tokens.
 * The resource owner MUST retain this port before prepareOnWorker can allocate anything.
 * receipts.bindOwner is invoked by the real child Runner before tools.definitions, never by this owner.
 */
interface ChildToolAssembly : AutoCloseable {
    val scope: ChildRunScope
    val tools: CatalogTool
    val receipts: ReceiptBatchToolExecutor
    fun prepareOnWorker(): Boolean
    fun observeClose(observer: (ChildToolDrain) -> Unit)
}

sealed interface ChildToolDrain {
    data object Drained : ChildToolDrain
    data class Unknown(val code: String) : ChildToolDrain
}

/** Explicit trusted-UI admission, separate from (and never enabling) text-only ChildModelBatch.
 * One-shot lifetime: no model reopen, no constructor replay and no recovery admission.
 * Capacity is returned only after worker exit, transport cancel return AND physical tool drain.
 */
class ToolEnabledChildRun(
    val scope: ChildRunScope,
    private val record: ChildTaskRecord,
    private val parent: RunExecutionOwner,
    private val assembly: ChildToolAssembly,
    private val model: () -> ModelPort,
    private val store: ChildTaskStore,
    private val capacity: Semaphore,
    private val onChanged: () -> Unit = {},
) : AutoCloseable {
    private val gate = Any()
    private var attempted = false
    private var cancelled = false
    private var workerExited = false
    private var acquired = false
    private var transportDrained = true
    private var observing = false
    private var toolDrain: ChildToolDrain? = null
    private var unknown = false
    private var released = false
    private var runner: ChatRunner? = null
    @Volatile private var observed = record
    @Volatile var progress: String = ""
        private set
    val snapshot: RetainedChildTask get() = synchronized(gate) {
        RetainedChildTask(observed, cancelled && observed.status != ChildTaskStatus.COMPLETED,
            released, unknown, pauseControl.snapshot())
    }
    private val pauseControl = ChildTaskPauseControl(::changed)
    val pauseState: ChildTaskPauseState get() = pauseControl.snapshot()
    fun pause(): ChildTaskControlReceipt = synchronized(gate) {
        if (cancelled || workerExited) ChildTaskControlReceipt.STALE else pauseControl.pause()
    }
    fun resume(): ChildTaskControlReceipt = synchronized(gate) {
        if (cancelled || workerExited) ChildTaskControlReceipt.STALE else pauseControl.resume()
    }
    val drained: Boolean get() = synchronized(gate) { released }
    val cleanupUnknown: Boolean get() = synchronized(gate) { unknown }

    init {
        require(assembly.scope === scope) { "child_assembly_scope_replaced" }
        require(record.owner == scope.owner && record.owner.parentRunId == parent.runId.value &&
            record.owner.conversationId == parent.conversationId.value &&
            record.parentOwnerId == parent.ownerId && record.parentMessageId == parent.assistantMessageId.value)
    }

    fun start(): Boolean {
        synchronized(gate) {
            if (attempted || cancelled) return false
            attempted = true
        }
        // The journal is before host construction, model creation or provider open.
        try { store.enqueue(parent, listOf(record)) }
        catch (_: Exception) {
            synchronized(gate) { workerExited = true; unknown = true }
            close()
            return false
        }
        Thread({ runOnWorker() }, "approved-tool-child").apply { isDaemon = true; start() }
        return true
    }

    private fun stopped() = synchronized(gate) { cancelled }
    private fun runOnWorker() {
        var sent = false
        var completed: Message? = null
        try {
            while (!stopped()) {
                if (capacity.tryAcquire(25, TimeUnit.MILLISECONDS)) {
                    synchronized(gate) { acquired = true }
                    break
                }
            }
            check(!stopped() && pauseControl.awaitBoundary())
            check(assembly.prepareOnWorker() && !stopped())
            val initial = Message(MessageId(scope.owner.assistantMessageId), ConversationId(scope.owner.conversationId),
                RunId(scope.owner.childRunId), Message.ASSISTANT_ROLE, emptyList(), RunStatus.RUNNING)
            val user = Message(MessageId(java.util.UUID.randomUUID().toString()), initial.conversationId, initial.runId,
                Message.USER_ROLE, listOf(MessageBlock.Text("task", record.prompt)), RunStatus.COMPLETED)
            val memory = object : ConversationStore {
                override fun save(message: Message) = scope.requireOwner(message)
            }
            val selected = object : ModelPort {
                override fun open(history: List<Message>): ModelSession = error("child_schema_required")
                override fun open(history: List<Message>, definitions: List<ToolDefinition>): ModelSession {
                    check(!stopped() && definitions.isNotEmpty())
                    store.started(record)
                    observed = record.copy(status = ChildTaskStatus.RUNNING)
                    check(!stopped())
                    check(pauseControl.awaitBoundary() && !stopped())
                    val port = model()
                    check(!stopped() && pauseControl.awaitBoundary())
                    sent = true
                    val transport = port.open(history, definitions)
                    synchronized(gate) { transportDrained = false }
                    val once = AtomicBoolean(false)
                    return object : ModelSession {
                        override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                            check(pauseControl.awaitBoundary() && !stopped())
                            transport.next(outputs, sink)
                        }
                        override fun cancel() {
                            if (!once.compareAndSet(false, true)) return
                            try { transport.cancel() }
                            catch (_: Exception) { synchronized(gate) { unknown = true } }
                            finally {
                                synchronized(gate) { transportDrained = true }
                                releaseIfReady()
                            }
                        }
                    }
                }
            }
            val receipts = object : ReceiptBatchToolExecutor by assembly.receipts {
                override fun execute(message: Message, invocations: List<ToolInvocation>, cancelled: () -> Boolean): List<app.daiyu.android.domain.tools.ToolDispatchResult> {
                    check(pauseControl.awaitBoundary() && !stopped() && !cancelled())
                    return assembly.receipts.execute(message, invocations, cancelled)
                }
                override fun start(message: Message, invocations: List<ToolInvocation>, cancelled: () -> Boolean): ReceiptBatchStart {
                    check(pauseControl.awaitBoundary() && !stopped() && !cancelled())
                    return assembly.receipts.start(message, invocations, cancelled)
                }
            }
            val childRunner = ChatRunner(selected, memory, assembly.tools,
                RunnerOptions(receiptBatchExecutor = receipts)) { message ->
                    scope.requireOwner(message)
                    progress = message.blocks.filterIsInstance<MessageBlock.Text>().joinToString("\n") { it.text }.take(16 * 1024)
                    changed()
                }
            synchronized(gate) { runner = childRunner }
            if (stopped()) childRunner.stop() else {
                val result = childRunner.run(initial, listOf(user))
                scope.requireOwner(result)
                val text = result.blocks.filterIsInstance<MessageBlock.Text>().joinToString("\n") { it.text }
                if (result.status == RunStatus.COMPLETED && !stopped() && text.isNotBlank() &&
                    text.toByteArray(Charsets.UTF_8).size <= 64 * 1024) completed = result
            }
        } catch (_: Exception) { /* Never reopen a sent or uncertain operation. */ }
        finally {
            try {
                val result = completed
                store.finished(record, if (result != null) ChildTaskStatus.COMPLETED else if (sent)
                    ChildTaskStatus.UNKNOWN else ChildTaskStatus.NOT_EXECUTED,
                    text = result?.blocks?.filterIsInstance<MessageBlock.Text>()?.joinToString("\n") { it.text }.orEmpty(),
                    detail = if (result != null) null else if (sent) "child_result_unconfirmed" else "child_not_sent")
                observed = record.copy(status = if (result != null) ChildTaskStatus.COMPLETED else if (sent)
                    ChildTaskStatus.UNKNOWN else ChildTaskStatus.NOT_EXECUTED,
                    text = result?.blocks?.filterIsInstance<MessageBlock.Text>()?.joinToString("\n") { it.text }.orEmpty(),
                    detail = if (result != null) null else if (sent) "child_result_unconfirmed" else "child_not_sent")
            } catch (_: Exception) { synchronized(gate) { unknown = true } }
            close()
            synchronized(gate) { workerExited = true }
            releaseIfReady()
        }
    }

    override fun close() {
        val active = synchronized(gate) {
            cancelled = true
            pauseControl.terminate()
            if (!attempted) { attempted = true; workerExited = true }
            runner
        }
        active?.stop()
        try { assembly.close() }
        catch (_: Exception) { synchronized(gate) { unknown = true } }
        val observe = synchronized(gate) { if (observing) false else { observing = true; true } }
        if (observe) try {
            assembly.observeClose { result ->
                synchronized(gate) {
                    if (result is ChildToolDrain.Unknown) unknown = true
                    toolDrain = result
                }
                releaseIfReady()
                changed()
            }
        } catch (_: Exception) { synchronized(gate) { unknown = true }; changed() }
        releaseIfReady()
    }

    private fun releaseIfReady() {
        synchronized(gate) {
            if (!released && !unknown && workerExited && transportDrained && toolDrain == ChildToolDrain.Drained) {
                // Semaphore.release is nonblocking and invokes no host code. Publish drained
                // only AFTER the real release, so observers cannot retire a half-released owner.
                if (acquired) capacity.release()
                released = true
            }
        }
        changed()
    }
    private fun changed() { try { onChanged() } catch (_: Exception) { } }
}
