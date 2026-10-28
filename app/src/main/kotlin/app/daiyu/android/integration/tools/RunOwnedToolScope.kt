package app.daiyu.android.integration.tools

import app.daiyu.android.domain.runtime.RunExecutionOwner
import app.daiyu.android.domain.tools.*

/** Ordinary-run leaves share Runner's owner, dispatcher and durable ledger. Construction is I/O free. */
internal abstract class RunOwnedToolScope(
    protected val owner: RunExecutionOwner,
    protected val authorization: TaskAuthorization,
) : AutoCloseable {
    abstract val tools: List<RegisteredTool>
    @Volatile private var closed = false
    @Volatile private var unknown = false
    private data class Resource(val closeable: AutoCloseable,
        val observe: ((RunCatalogCloseResult) -> Unit) -> Unit)
    private val resources = mutableListOf<Resource>()
    private var creations = 0
    private var closeFailed = false
    private val creationWaiters = mutableListOf<() -> Unit>()
    protected fun beginCreation() = synchronized(resources) { check(!closed); creations++ }
    protected fun endCreation() {
        val waiters = synchronized(resources) {
            check(creations > 0); creations--
            if (creations == 0) creationWaiters.toList().also { creationWaiters.clear() } else emptyList()
        }
        waiters.forEach { it() }
    }
    open fun beginBatch(invocations: List<app.daiyu.android.domain.ToolInvocation>) = Unit
    open fun observeOutcomes(results: List<ToolDispatchResult>) = Unit

    protected fun open(): Boolean = !closed
    protected fun live(): Boolean = !closed && !unknown
    protected fun denial(context: ToolExecutionContext, original: Boolean = true): ExecutionReceipt.NotExecuted? = when {
        !live() -> ExecutionReceipt.NotExecuted(if (unknown) "run_tool_outcome_unknown_no_retry" else "run_closed")
        context.owner !== owner || (original && context.authorization !== authorization) ->
            ExecutionReceipt.NotExecuted("tool_run_owner_mismatch")
        context.cancelled() -> ExecutionReceipt.NotExecuted("cancelled_before_execution")
        else -> null
    }
    protected fun read(execute: (ToolExecutionContext) -> ExecutionReceipt): PreparedTool =
        PreparedTool.contextual(admit = { denial(it) }) { context -> denial(context) ?: execute(context) }
    protected fun diagnosticRead(execute: (ToolExecutionContext) -> ExecutionReceipt): PreparedTool {
        fun deny(context: ToolExecutionContext): ExecutionReceipt.NotExecuted? = when {
            !open() -> ExecutionReceipt.NotExecuted("run_closed")
            context.owner !== owner || context.authorization !== authorization -> ExecutionReceipt.NotExecuted("tool_run_owner_mismatch")
            context.cancelled() -> ExecutionReceipt.NotExecuted("cancelled_before_execution")
            else -> null
        }
        return PreparedTool.contextual(admit = ::deny) { deny(it) ?: execute(it) }
    }
    protected fun write(effect: WriteEffect, execute: (ToolExecutionContext) -> ExecutionReceipt): PreparedTool =
        PreparedTool.approvedWrite(effect, approve = { context ->
            if (denial(context) != null) null else TaskAuthorization(authorization.sessionId, authorization.taskId, setOf(effect))
        }, admit = { denial(it) }) { context ->
            denial(context, original = false) ?: if (context.authorization.sessionId != authorization.sessionId ||
                context.authorization.taskId != authorization.taskId || context.authorization.allowedWrites != setOf(effect))
                ExecutionReceipt.NotExecuted("task_write_not_authorized") else execute(context)
        }
    protected fun <T : AutoCloseable> retain(resource: T,
        observe: ((RunCatalogCloseResult) -> Unit) -> Unit = { it(RunCatalogCloseResult.Drained) }): T {
        val stop = synchronized(resources) {
            resources.add(Resource(resource, observe))
            closed.also { if (it) creations++ }
        }
        if (stop) try { resource.close() }
        catch (_: Exception) { synchronized(resources) { closeFailed = true } }
        finally { endCreation() }
        return resource
    }
    open fun markUnknown() { unknown = true }
    override fun close() {
        val stop = synchronized(resources) { if (closed) return; closed = true; creations++; resources.toList() }
        try {
            stop.forEach { resource ->
                try { resource.closeable.close() }
                catch (_: Exception) { synchronized(resources) { closeFailed = true } }
            }
        } finally { endCreation() }
    }
    fun observeClose(done: (RunCatalogCloseResult) -> Unit) {
        close()
        val retained = synchronized(resources) {
            if (creations != 0) { creationWaiters.add { observeClose(done) }; return }
            resources.toList()
        }
        if (synchronized(resources) { closeFailed }) { done(RunCatalogCloseResult.Unknown("run_tool_close_unconfirmed")); return }
        if (retained.isEmpty()) { done(RunCatalogCloseResult.Drained); return }
        val lock = Any()
        var pending = retained.size
        var failure: RunCatalogCloseResult.Unknown? = null
        retained.forEach { resource ->
            val once = java.util.concurrent.atomic.AtomicBoolean()
            val deliver: (RunCatalogCloseResult) -> Unit = { result ->
                if (once.compareAndSet(false, true)) {
                    val final = synchronized(lock) {
                        if (result is RunCatalogCloseResult.Unknown) failure = result
                        pending--
                        if (pending == 0) failure ?: RunCatalogCloseResult.Drained else null
                    }
                    if (final != null) done(final)
                }
            }
            try { resource.observe(deliver) }
            catch (_: Exception) { deliver(RunCatalogCloseResult.Unknown("run_tool_close_unconfirmed")) }
        }
    }
}
