package app.daiyu.android.domain.tools

import app.daiyu.android.domain.ToolCallId
import app.daiyu.android.domain.ToolInvocation
import app.daiyu.android.domain.runtime.PendingToolCheckpoint
import app.daiyu.android.domain.runtime.RunExecutionOwner

sealed interface DispatchOutcome {
    data class NotExecuted(val code: String) : DispatchOutcome
    data class Confirmed(val payload: String, val isError: Boolean, val replayed: Boolean = false) : DispatchOutcome
    /** This invocation OR a semantically equivalent earlier effect may have executed. Never auto-retry. */
    data class Unknown(val code: String) : DispatchOutcome
}
data class ToolDispatchResult(val callId: ToolCallId, val outcome: DispatchOutcome)

class ToolDispatcher(
    private val catalog: ToolCatalog,
    private val session: SessionCapabilities,
    private val ledger: ToolLedger
) {
    /** Only call after the model's whole batch has been received and its protocol terminal confirmed.
     * This class does not implement a stream runner or change existing ModelPort/BatteryTool. */
    fun dispatch(
        task: TaskAuthorization,
        batch: List<ToolInvocation>,
        cancelled: () -> Boolean = { false }
    ): List<ToolDispatchResult> = (dispatchInternal(task, batch, null, cancelled, allowBarrier = false) as ReceiptBatchStart.Completed).receipts

    fun dispatchOwned(
        task: TaskAuthorization,
        batch: List<ToolInvocation>,
        owner: RunExecutionOwner,
        cancelled: () -> Boolean = { false }
    ): List<ToolDispatchResult> = (dispatchInternal(task, batch, owner, cancelled, allowBarrier = false) as ReceiptBatchStart.Completed).receipts

    fun start(task: TaskAuthorization, batch: List<ToolInvocation>, owner: RunExecutionOwner,
        cancelled: () -> Boolean = { false }): ReceiptBatchStart =
        dispatchInternal(task, batch, owner, cancelled, allowBarrier = true)

    private fun dispatchInternal(
        task: TaskAuthorization,
        batch: List<ToolInvocation>,
        owner: RunExecutionOwner?,
        cancelled: () -> Boolean,
        allowBarrier: Boolean
    ): ReceiptBatchStart {
        val invocations = batch.toList()
        fun rejected(code: String) = ReceiptBatchStart.Completed(invocations.map { ToolDispatchResult(it.id, DispatchOutcome.NotExecuted(code)) })
        if (invocations.isEmpty()) return ReceiptBatchStart.Completed(emptyList())
        if (invocations.size > 64) return rejected("batch_too_large")
        if (task.sessionId != session.sessionId) return rejected("task_session_mismatch")
        if (owner != null && task.taskId != owner.runId.value) return rejected("tool_run_owner_mismatch")
        if (invocations.map { it.id }.distinct().size != invocations.size) return rejected("duplicate_call_id")
        val definitions = invocations.map { catalog.find(it.name) ?: return rejected("unknown_tool") }
        if (invocations.size != 1 && definitions.any { it.first.effect == ToolEffect.USER_BARRIER })
            return rejected("ask_user_batch_barrier")
        if (definitions.any { it.first.effect == ToolEffect.USER_BARRIER && it.first.name != "ask_user" })
            return rejected("effect_contract_violation")
        if (!allowBarrier && definitions.any { it.first.effect == ToolEffect.USER_BARRIER })
            return rejected("user_barrier_requires_start")
        // Every definition, argument and authorization is checked before the first execution/reservation.
        val plans = mutableListOf<PreparedTool>()
        val requests = mutableListOf<LedgerRequest>()
        for ((index, invocation) in invocations.withIndex()) {
            val (definition, tool) = definitions[index]
            if (definition.capability !in session.enabled) return rejected("capability_disabled")
            val arguments = try { definition.schema.parse(invocation.arguments) }
                catch (_: RuntimeException) { return rejected("invalid_arguments") }
            val plan = try { tool.prepare(arguments) }
                catch (_: Exception) { return rejected("preparation_failed") }
            if (plan.requiresContext && owner == null) return rejected("tool_execution_context_required")
            if ((definition.effect == ToolEffect.USER_BARRIER) != plan.isUserBarrier)
                return rejected("effect_contract_violation")
            if ((definition.effect == ToolEffect.WRITE) != (plan.writeEffect != null))
                return rejected("effect_contract_violation")
            if (plan.writeEffect != null && plan.writeEffect !in task.allowedWrites)
                return rejected("task_write_not_authorized")
            plans.add(plan)
            requests.add(LedgerRequest(invocation.id.value, invocationKey(invocation, definition, arguments), plan.writeEffect?.key()))
        }
        val effects = requests.mapNotNull { it.effectKey }
        if (effects.distinct().size != effects.size) return rejected("duplicate_batch_effect")
        val contexts = invocations.map { call -> owner?.let { ToolExecutionContext(it, call, task, cancelled) } }
        // Admission covers both fresh execution and receipt replay, before ANY ledger access.
        for ((index, plan) in plans.withIndex()) {
            val denial = try { plan.admit(contexts[index]) }
                catch (_: Exception) { return rejected("context_admission_failed") }
            if (denial != null) return rejected(denial.code)
        }
        if (cancelled()) return rejected("cancelled_before_execution")
        val reservation = try { ledger.reserve(session.sessionId, task.taskId, requests) }
            catch (_: Exception) { return rejected("ledger_unavailable") }
        if (reservation is Reservation.Rejected) return ReceiptBatchStart.Completed(invocations.mapIndexed { index, call ->
            ToolDispatchResult(call.id, if (index in reservation.unknownIndices) DispatchOutcome.Unknown(reservation.code)
                else DispatchOutcome.NotExecuted(reservation.code))
        })
        reservation as Reservation.Accepted
        var stop = false
        return ReceiptBatchStart.Completed(invocations.mapIndexed { index, call ->
            val entry = reservation.entries[index]
            if (entry.state == LedgerState.CONFIRMED) {
                // An earlier call in this batch may have revoked access since whole-batch admission.
                // Fence delivery without executing the tool or rewriting the original receipt.
                val denial = if (stop || cancelled()) ExecutionReceipt.NotExecuted("batch_stopped_before_execution")
                    else try { plans[index].admit(contexts[index]) }
                    catch (_: Exception) { ExecutionReceipt.NotExecuted("context_admission_failed") }
                return@mapIndexed ToolDispatchResult(call.id, if (denial != null)
                    DispatchOutcome.NotExecuted(denial.code)
                else DispatchOutcome.Confirmed(entry.payload, entry.isError, replayed = true))
            }
            if (entry.state == LedgerState.NOT_EXECUTED) return@mapIndexed ToolDispatchResult(call.id,
                DispatchOutcome.NotExecuted(entry.code))
            val receipt = if (stop || cancelled()) ExecutionReceipt.NotExecuted("batch_stopped_before_execution") else if (plans[index].isUserBarrier) {
                val context = checkNotNull(contexts[index])
                return ReceiptBatchStart.Suspended(BarrierOffer(context, PendingToolCheckpoint(call.id)) { receipt ->
                    val outcome = try {
                        ledger.finish(entry, receipt)
                        when (receipt) {
                            is ExecutionReceipt.Confirmed -> DispatchOutcome.Confirmed(receipt.payload, receipt.isError)
                            is ExecutionReceipt.NotExecuted -> DispatchOutcome.NotExecuted(receipt.code)
                            is ExecutionReceipt.Unknown -> DispatchOutcome.Unknown(receipt.code)
                        }
                    } catch (_: Exception) { DispatchOutcome.Unknown("receipt_persistence_failed") }
                    ToolDispatchResult(call.id, outcome)
                })
            } else {
                try { plans[index].execute(contexts[index]) }
                // Once execution starts, exceptions (including interruption) do NOT prove non-execution.
                catch (_: Exception) { ExecutionReceipt.Unknown("adapter_result_unconfirmed") }
            }
            val persisted = try { ledger.finish(entry, receipt); true } catch (_: Exception) { false }
            val outcome = when {
                !persisted && receipt !is ExecutionReceipt.NotExecuted -> DispatchOutcome.Unknown("receipt_persistence_failed")
                receipt is ExecutionReceipt.NotExecuted -> DispatchOutcome.NotExecuted(receipt.code)
                receipt is ExecutionReceipt.Confirmed -> DispatchOutcome.Confirmed(receipt.payload, receipt.isError)
                receipt is ExecutionReceipt.Unknown -> DispatchOutcome.Unknown(receipt.code)
                else -> error("unreachable")
            }
            if (outcome is DispatchOutcome.Unknown) stop = true
            ToolDispatchResult(call.id, outcome)
        })
    }
}
