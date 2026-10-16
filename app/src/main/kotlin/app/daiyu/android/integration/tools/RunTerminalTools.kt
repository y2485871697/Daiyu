package app.daiyu.android.integration.tools

import app.daiyu.android.domain.CatalogTool
import app.daiyu.android.domain.Message
import app.daiyu.android.domain.RunId
import app.daiyu.android.domain.ToolInvocation
import app.daiyu.android.domain.runtime.ReceiptBatchToolExecutor
import app.daiyu.android.domain.runtime.RunExecutionOwner
import app.daiyu.android.domain.tools.*
import java.util.Collections

internal sealed interface TerminalToolsCloseResult {
    data object Drained : TerminalToolsCloseResult
    data class Unknown(val reason: String) : TerminalToolsCloseResult
}

/**
 * Optional central-catalog leaf. Only an approved, immutable finite plan can create it.
 * For a combined catalog register registeredTools once, bind the real Runner owner, and feed
 * whole-batch outcomes to observeOutcomes. The standalone bridge uses the SAME ToolLedger;
 * it is not an alternative command dispatcher and does not mint authorization or call IDs.
 */
internal class RunTerminalTools(
    private val binding: CatalogTerminalBinding,
    capabilities: SessionCapabilities,
    ledger: ToolLedger,
) : CatalogTool, ReceiptBatchToolExecutor, AutoCloseable {
    init { require(capabilities.sessionId == binding.authorization.sessionId) { "tool_run_owner_mismatch" } }
    private val capabilities = capabilities
    val registeredTools: List<RegisteredTool> = Collections.unmodifiableList(
        if (CAPABILITY in capabilities.enabled) buildList {
            add(stepTool())
            if (binding.plan.reads.isNotEmpty()) add(readTool())
        } else emptyList()
    )
    private val bridge = CatalogModelBridge(
        ToolRegistry().also { registry -> registeredTools.forEach(registry::register) }.snapshot(),
        capabilities, ledger, binding.plan.conversationId, RunId(binding.plan.runId), binding.authorization,
    )
    override fun bindOwner(owner: RunExecutionOwner) {
        binding.bind(owner, capabilities)
        bridge.bindOwner(owner)
    }
    private val lifecycle = Any()
    private var closed = false
    private var activeDispatches = 0
    override val definitions get() = bridge.definitions
    override fun execute(invocation: ToolInvocation): String = error("whole_batch_dispatch_required")
    private fun beginDispatch(): Boolean = synchronized(lifecycle) {
        if (closed) false else { activeDispatches++; true }
    }
    private fun closedReceipts(invocations: List<ToolInvocation>) = invocations.map {
        ToolDispatchResult(it.id, DispatchOutcome.NotExecuted("terminal_run_closed"))
    }
    override fun execute(message: Message, invocations: List<ToolInvocation>, cancelled: () -> Boolean): List<ToolDispatchResult> {
        if (!beginDispatch()) return closedReceipts(invocations)
        return try { bridge.execute(message, invocations, cancelled).also(::observeOutcomes) }
        finally { synchronized(lifecycle) { activeDispatches-- } }
    }
    override fun start(message: Message, invocations: List<ToolInvocation>, cancelled: () -> Boolean): ReceiptBatchStart {
        if (!beginDispatch()) return ReceiptBatchStart.Completed(closedReceipts(invocations))
        return try {
            bridge.start(message, invocations, cancelled).also { if (it is ReceiptBatchStart.Completed) observeOutcomes(it.receipts) }
        } finally { synchronized(lifecycle) { activeDispatches-- } }
    }

    /** Include ledger reservation/persistence UNKNOWN, not just the transport's result. */
    fun observeOutcomes(results: List<ToolDispatchResult>) {
        if (results.any { it.outcome is DispatchOutcome.Unknown }) binding.markOutcomeUnknown()
    }

    private fun stepTool(): RegisteredTool {
        val byId = binding.plan.steps.associateBy { it.id }
        val schema = ToolSchema(listOf(ArgumentField("step_id", ArgumentType.Text(128, byId.keys, 1))))
        return object : RegisteredTool {
            override val definition = ToolDefinition(STEP_TOOL, 1,
                "仅执行本次用户精确批准的持久PTY计划步骤（含启动）。不可传入命令、路径、身份或序号。输入送达及命令完成未知时不重发。不是每命令独立shell。",
                CAPABILITY, ToolEffect.WRITE, schema)
            override fun prepare(arguments: ToolArguments): PreparedTool {
                val step = byId.getValue(arguments.text("step_id"))
                return PreparedTool.contextual(binding.plan.effects.getValue(step.id),
                    admit = { context -> binding.admit(context, STEP_TOOL, schema, arguments) },
                    execute = { context ->
                        binding.admit(context, STEP_TOOL, schema, arguments)
                            ?: binding.execute(step, context)
                    })
            }
        }
    }
    private fun readTool(): RegisteredTool {
        val byId = binding.plan.reads.associateBy { it.id }
        val schema = ToolSchema(listOf(ArgumentField("read_id", ArgumentType.Text(128, byId.keys, 1))))
        return object : RegisteredTool {
            override val definition = ToolDefinition(READ_TOOL, 1,
                "读取本次PTY计划预先指定的有界输出页；输出和退出帧不证明命令成功，也不授权重发。返回值是该调用的历史快照。",
                CAPABILITY, ToolEffect.READ_ONLY, schema)
            override fun prepare(arguments: ToolArguments): PreparedTool {
                val read = byId.getValue(arguments.text("read_id"))
                return PreparedTool.contextual(
                    admit = { context -> binding.admit(context, READ_TOOL, schema, arguments) },
                    execute = { context ->
                        binding.admit(context, READ_TOOL, schema, arguments)
                            ?: binding.read(read, context)
                    })
            }
        }
    }

    /** Non-waiting fence. Keep this object, including after a failed or late open. */
    override fun close() {
        synchronized(lifecycle) { closed = true }
        binding.close()
    }

    /** Worker-only and explicitly retryable. Includes central receipt persistence, not only PTY I/O. */
    fun awaitClose(timeoutMillis: Long): TerminalToolsCloseResult {
        require(timeoutMillis in 1..30000)
        if (synchronized(lifecycle) { !closed || activeDispatches != 0 })
            return TerminalToolsCloseResult.Unknown("terminal_dispatch_not_drained")
        return if (binding.awaitTermination(timeoutMillis)) TerminalToolsCloseResult.Drained
        else TerminalToolsCloseResult.Unknown("terminal_termination_unconfirmed")
    }

    companion object {
        const val CAPABILITY = "terminal.pty.approved_plan"
        const val STEP_TOOL = "terminal_pty_step"
        const val READ_TOOL = "terminal_pty_read"
    }
}
