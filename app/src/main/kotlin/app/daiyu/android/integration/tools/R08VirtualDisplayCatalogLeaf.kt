package app.daiyu.android.integration.tools

import app.daiyu.android.domain.ToolInvocation
import app.daiyu.android.domain.runtime.RunExecutionOwner
import app.daiyu.android.domain.tools.*
import app.daiyu.android.vd.AndroidVirtualDisplayOwnerHost
import app.daiyu.android.vd.OwnerReply
import app.daiyu.android.vd.OwnerRunBinding
import app.daiyu.android.vdapproval.VirtualDisplayApproval
import org.json.JSONObject

/** Small seam around the EXISTING client; no alternate dispatcher, transport or owner runtime. */
internal interface R08VirtualDisplayEndpoint {
    fun start(generation: String, binding: OwnerRunBinding): Boolean
    fun initialize(): OwnerReply
    fun execute(id: String, operation: String, parameters: JSONObject): OwnerReply
    fun lookup(id: String, operation: String, parameters: JSONObject): OwnerReply
    fun lookupGeneration(): OwnerReply
    fun observe(): AndroidVirtualDisplayOwnerHost.Lifecycle
}
internal class R08AndroidVirtualDisplayEndpoint(private val host: AndroidVirtualDisplayOwnerHost) : R08VirtualDisplayEndpoint {
    override fun start(generation: String, binding: OwnerRunBinding) =
        host.start(generation, binding) == AndroidVirtualDisplayOwnerHost.Start.READY
    override fun initialize() = checkNotNull(host.client).initialize()
    override fun execute(id: String, operation: String, parameters: JSONObject) =
        checkNotNull(host.client).execute(id, operation, parameters)
    override fun lookup(id: String, operation: String, parameters: JSONObject) =
        checkNotNull(host.client).lookup(id, operation, parameters)
    override fun lookupGeneration() = checkNotNull(host.client).lookupGeneration()
    override fun observe() = host.observe().lifecycle
}

internal sealed interface R08VirtualDisplayCloseResult {
    data object Drained : R08VirtualDisplayCloseResult
    data class Unknown(val code: String = "virtual_display_owner_retained") : R08VirtualDisplayCloseResult
}
internal data class R08VirtualDisplayHeldState(val revoked: Boolean, val activeExecutions: Int,
    val pendingFinalOutcomes: Int, val startAttempted: Boolean, val initialized: Boolean, val uncertain: Boolean)

/** Leaf registration only. Parent owns the one ToolRegistry/ToolDispatcher/ToolLedger and must
 * beginBatch BEFORE dispatch and finishBatch with FINAL outcomes, including ledger short-circuits.
 * Nothing starts on construction,
 * bind, definitions, prepare, admission, approval, settings display, or close.
 */
internal class R08VirtualDisplayCatalogLeaf internal constructor(
    private val approval: VirtualDisplayApproval,
    private val endpoint: R08VirtualDisplayEndpoint,
    private val journal: R08VirtualDisplayJournal
) : AutoCloseable {
    private val plan = approval.plan
    private val stateLock = Any()
    private val executionLock = Any()
    private var owner: RunExecutionOwner? = null
    private var capabilities: SessionCapabilities? = null
    private var authorization: TaskAuthorization? = null
    private var revoked = false
    private var active = 0
    // Identity-only capability. Constructing another ticket cannot release this leaf's batches.
    internal class BatchTicket internal constructor()
    private class BatchState(val wholeBatch: List<ToolInvocation>) {
        val relevant = wholeBatch.filter { it.name == WRITE_TOOL || it.name == READ_TOOL }
        val executed = mutableSetOf<String>()
        var activeExecutions = 0
        var finishing = false
    }
    private val batches = mutableMapOf<BatchTicket, BatchState>()
    // Keep local execute accounting until FINAL central feedback, not just the adapter return.
    private val pendingFinal = mutableSetOf<String>()

    /** In-memory only. Register the ORIGINAL WHOLE batch before any dispatcher/ledger access.
     * Missing callbacks (including exceptions) leave the ticket pending. No timeout releases it.
     * Concurrent batches may not reuse an outstanding R08 call ID.
     */
    fun beginBatch(originalWholeBatch: List<ToolInvocation>): BatchTicket = synchronized(stateLock) {
        check(!revoked && approval.isActive) { "virtual_display_revoked" }
        val state = BatchState(originalWholeBatch.toList())
        val outstanding = batches.values.flatMap { it.relevant }.map { it.id.value }.toSet() + pendingFinal
        require(state.relevant.none { it.id.value in outstanding }) { "virtual_display_batch_call_pending" }
        BatchTicket().also { batches[it] = state }
    }
    private var startAttempted = false
    private var transportReady = false
    private var initialized = false
    private var uncertain = false

    /** Exact object identity, not equivalent reconstructed owner/authorization/capabilities. */
    fun bind(owner: RunExecutionOwner, capabilities: SessionCapabilities, authorization: TaskAuthorization) {
        synchronized(stateLock) {
            check(!revoked && approval.isActive) { "virtual_display_revoked" }
            require(approval.accepts(owner, capabilities, authorization)) { "virtual_display_owner_mismatch" }
            require(authorization.sessionId == capabilities.sessionId && authorization.taskId == owner.runId.value) {
                "virtual_display_authorization_mismatch"
            }
            require(authorization.allowedWrites.containsAll(plan.effects)) { "virtual_display_approval_missing_effect" }
            if (this.owner != null) {
                require(this.owner === owner && this.capabilities === capabilities && this.authorization === authorization) {
                    "virtual_display_binding_changed"
                }
            } else {
                this.owner = owner; this.capabilities = capabilities; this.authorization = authorization
            }
        }
    }

    val registeredTools: List<RegisteredTool> = listOfNotNull(
        if (plan.writes.isEmpty()) null else tool(WRITE_TOOL, "step_id", plan.writes, ToolEffect.WRITE),
        if (plan.reads.isEmpty()) null else tool(READ_TOOL, "read_id", plan.reads, ToolEffect.READ_ONLY)
    )

    private fun tool(name: String, field: String, operations: List<R08VirtualDisplayOperation>, effect: ToolEffect): RegisteredTool =
        object : RegisteredTool {
            override val definition = ToolDefinition(name, 1,
                if (effect == ToolEffect.WRITE) "Execute one explicitly approved virtual-display step; never use a main-display fallback."
                else "Read only this run's already approved and initialized virtual display; never start or initialize it.",
                CAPABILITY, effect, ToolSchema(listOf(ArgumentField(field,
                    ArgumentType.Text(64, operations.map { it.id }.toSet(), 1)))))
            override fun prepare(arguments: ToolArguments): PreparedTool {
                val operation = operations.single { it.id == arguments.text(field) }
                return PreparedTool.contextual(operation.effect,
                    admit = { admission(it, operation) }, execute = { execute(it, operation) })
            }
        }

    /** Pure in-memory admission, including for central-ledger replay. */
    private fun admission(context: ToolExecutionContext, operation: R08VirtualDisplayOperation): ExecutionReceipt.NotExecuted? =
        synchronized(stateLock) {
            val code = when {
                revoked || !approval.isActive -> "virtual_display_revoked"
                owner == null -> "virtual_display_not_bound"
                context.owner !== owner || context.authorization !== authorization -> "virtual_display_owner_mismatch"
                capabilities !== plan.capabilities || CAPABILITY !in plan.capabilities.enabled -> "virtual_display_capability_disabled"
                context.authorization.sessionId != plan.capabilities.sessionId ||
                    context.authorization.taskId != plan.owner.runId.value -> "virtual_display_authorization_mismatch"
                operation.effect != null && operation.effect !in context.authorization.allowedWrites -> "virtual_display_effect_not_approved"
                operation.effect == null && !initialized -> "virtual_display_not_initialized"
                else -> null
            }
            code?.let { ExecutionReceipt.NotExecuted(it) }
        }

    private fun execute(context: ToolExecutionContext, operation: R08VirtualDisplayOperation): ExecutionReceipt {
        val batch = synchronized(stateLock) {
            val registered = batches.values.singleOrNull { !it.finishing && context.invocation in it.relevant }
            if (registered == null) {
                pendingFinal.add(context.invocation.id.value)
                return unknown("virtual_display_batch_not_registered")
            }
            admission(context, operation)?.let { return it }
            if (uncertain) return ExecutionReceipt.Unknown("virtual_display_owner_uncertain")
            if (context.cancelled()) return ExecutionReceipt.NotExecuted("cancelled_before_execution")
            pendingFinal.add(context.invocation.id.value)
            registered.executed.add(context.invocation.id.value)
            registered.activeExecutions++
            active++
            registered
        }
        try {
            // Serialize workers, never lifecycle/close. Recheck revocation after any wait.
            return synchronized(executionLock) {
                admission(context, operation)?.let { return@synchronized it }
                if (synchronized(stateLock) { uncertain }) return@synchronized ExecutionReceipt.Unknown("virtual_display_owner_uncertain")
                if (context.cancelled()) return@synchronized ExecutionReceipt.NotExecuted("cancelled_before_execution")
                val unavailable = synchronized(stateLock) {
                    when (operation.operation) {
                        "start" -> if (startAttempted) "virtual_display_start_already_attempted" else null
                        "initialize" -> if (!transportReady || initialized) "virtual_display_initialize_unavailable" else null
                        else -> if (!initialized) "virtual_display_not_initialized" else null
                    }
                }
                if (unavailable != null) return@synchronized ExecutionReceipt.NotExecuted(unavailable)
                val intent = plan.intent(context.invocation.id.value, operation)
                val permit = try { journal.claim(intent) } catch (_: Exception) { R08VirtualDisplayPermit.UNKNOWN }
                if (permit == R08VirtualDisplayPermit.UNKNOWN) return@synchronized unknown("virtual_display_journal_uncertain")
                // Claim persistence can race close/cancel. No owner I/O until this final check.
                admission(context, operation)?.let { return@synchronized finish(intent, it) }
                if (synchronized(stateLock) { uncertain })
                    return@synchronized finish(intent, ExecutionReceipt.Unknown("virtual_display_owner_uncertain"))
                if (context.cancelled()) return@synchronized finish(intent, ExecutionReceipt.NotExecuted("cancelled_before_execution"))
                val result = try {
                    if (permit == R08VirtualDisplayPermit.LOOKUP_ONLY) {
                        // Never replay start or initialize. Existing receipts are evidence only.
                        when (operation.operation) {
                            "start" -> Unit
                            "initialize" -> endpoint.lookupGeneration()
                            else -> endpoint.lookup(intent.operationId, operation.operation, operation.parameters())
                        }
                        unknown("virtual_display_receipt_only")
                    } else when (operation.operation) {
                        "start" -> {
                            synchronized(stateLock) { startAttempted = true }
                            if (endpoint.start(plan.generation, plan.binding)) {
                                synchronized(stateLock) { transportReady = true }
                                ExecutionReceipt.Confirmed("{\"ok\":true,\"ownerTransportReady\":true,\"displayInitialized\":false}")
                            } else unknown("virtual_display_start_unconfirmed")
                        }
                        "initialize" -> accept(endpoint.initialize(), operation)
                        else -> accept(endpoint.execute(intent.operationId, operation.operation, operation.parameters()), operation)
                    }
                } catch (_: Exception) {
                    // Invocation has begun: exception/EOF/disconnection NEVER proves NotExecuted.
                    unknown("virtual_display_execution_unconfirmed")
                }
                finish(intent, result)
            }
        } finally { synchronized(stateLock) { active--; batch.activeExecutions-- } }
    }

    private fun finish(intent: R08VirtualDisplayIntent, receipt: ExecutionReceipt): ExecutionReceipt = try {
        journal.complete(intent, when (receipt) {
            is ExecutionReceipt.Confirmed -> R08VirtualDisplayEvidence.CONFIRMED
            is ExecutionReceipt.NotExecuted -> R08VirtualDisplayEvidence.NOT_EXECUTED
            is ExecutionReceipt.Unknown -> R08VirtualDisplayEvidence.UNKNOWN
        })
        receipt
    } catch (_: Exception) { unknown("virtual_display_journal_completion_uncertain") }

    private fun accept(reply: OwnerReply, operation: R08VirtualDisplayOperation): ExecutionReceipt {
        if (reply.outcome != OwnerReply.Outcome.REPLIED) return unknown("virtual_display_reply_unconfirmed")
        val body = reply.payloadCopy() ?: return unknown("virtual_display_reply_unconfirmed")
        // A valid envelope is NOT proof of business success. receiptOnly includes historical success.
        if (body.optBoolean("receiptOnly", false) || !body.optBoolean("ok", false) || body.optBoolean("mutationUncertain", false))
            return unknown("virtual_display_business_unconfirmed")
        val state = body.optJSONObject("receipt")?.optString("state")
        val proven = when (operation.operation) {
            "initialize" -> state == "STARTED" && body.optBoolean("initialized") && body.optInt("displayId") > 0
            "input" -> state == "SUCCEEDED" && body.optBoolean("executed")
            "launch" -> state == "SUCCEEDED" && body.optBoolean("launched")
            "release" -> state == "SUCCEEDED" && body.optBoolean("released")
            "status" -> state == "SUCCEEDED" && body.optBoolean("ready") && body.optString("session") == "active"
            "snapshot" -> state == "SUCCEEDED" && body.optString("format") == "png"
            else -> false
        }
        if (!proven) return unknown("virtual_display_business_unconfirmed")
        synchronized(stateLock) {
            if (operation.operation == "initialize") initialized = true
            if (operation.operation == "release") initialized = false
        }
        // Allowlist business data only; never return raw receipt, identity, bootstrap or envelopes.
        val result = JSONObject().put("ok", true).put("operation", operation.operation)
        val fields = when (operation.operation) {
            "snapshot" -> listOf("format", "width", "height", "frameCount", "timestampNs", "data", "bytes")
            "status" -> listOf("width", "height", "hasFrame", "frameCount", "sourceState", "sourceEmpty")
            else -> emptyList()
        }
        fields.filter { body.has(it) }.forEach { result.put(it, body.get(it)) }
        return ExecutionReceipt.Confirmed(result.toString())
    }

    private fun unknown(code: String): ExecutionReceipt.Unknown {
        synchronized(stateLock) { uncertain = true }
        return ExecutionReceipt.Unknown(code)
    }

    /** MUST receive the actual complete final central results, in original dispatcher order.
     * The identity ticket owns its immutable original whole batch; no caller-supplied replacement
     * batch can release it. Bad/missing/duplicate feedback latches UNKNOWN and leaves it pending.
     * Only R08 evidence is journaled, but coverage of the WHOLE batch is required. No second ledger.
     * Call on a worker: journal I/O runs outside stateLock, with the ticket still held throughout.
     */
    fun finishBatch(ticket: BatchTicket, finalResults: List<ToolDispatchResult>) {
        val results = finalResults.toList()
        val batch = synchronized(stateLock) {
            val registered = batches[ticket]
            if (registered == null || registered.finishing || registered.activeExecutions != 0) {
                uncertain = true
                throw IllegalStateException("virtual_display_invalid_batch_ticket")
            }
            if (results.map { it.callId } != registered.wholeBatch.map { it.id }) {
                uncertain = true
                throw IllegalArgumentException("virtual_display_incomplete_final_batch")
            }
            registered.finishing = true
            registered
        }
        var completed = false
        try {
            batch.wholeBatch.zip(results).forEach { (call, result) ->
                if (call.name != WRITE_TOOL && call.name != READ_TOOL) return@forEach
                val evidence = when (result.outcome) {
                    is DispatchOutcome.Unknown -> R08VirtualDisplayEvidence.UNKNOWN
                    is DispatchOutcome.NotExecuted -> R08VirtualDisplayEvidence.NOT_EXECUTED
                    is DispatchOutcome.Confirmed -> R08VirtualDisplayEvidence.CONFIRMED
                }
                if (evidence == R08VirtualDisplayEvidence.UNKNOWN) unknown("virtual_display_central_ledger_unknown")
                journal.finalOutcome(plan.bindingKey, plan.generation, result.callId.value, evidence)
            }
            completed = true
        } catch (_: Exception) {
            unknown("virtual_display_final_journal_uncertain")
        } finally {
            synchronized(stateLock) {
                // No duplicate finish may enter the journal or release a different ticket's calls.
                // Even an uncaught journal failure must not clear the held accounting.
                if (completed) {
                    pendingFinal.removeAll(batch.executed)
                    batches.remove(ticket)
                } else uncertain = true
            }
        }
    }

    fun heldState(): R08VirtualDisplayHeldState = synchronized(stateLock) {
        val registeredIds = batches.values.flatMap { it.relevant }.map { it.id.value }.toSet()
        val pending = batches.values.sumOf { it.relevant.size } + pendingFinal.count { it !in registeredIds }
        R08VirtualDisplayHeldState(revoked || !approval.isActive, active, pending, startAttempted, initialized, uncertain)
    }

    /** Pure revocation only: no socket close, wait/join, release, handoff, reconnect or kill. */
    override fun close() { synchronized(stateLock) { revoked = true }; approval.revoke() }

    /** Nonblocking one-shot observation. Parent calls AFTER dispatch + final-ledger callbacks drain.
     * UNKNOWN retains this leaf and its endpoint/host. Later explicit observation is allowed; no
     * polling thread, deadline loop or implicit cleanup. Physical drain cannot clear ledger UNKNOWN. */
    fun observeClose(done: (R08VirtualDisplayCloseResult) -> Unit) {
        Thread({
            val state = heldState()
            val result = try {
                if (!state.revoked || state.activeExecutions != 0 || state.pendingFinalOutcomes != 0 || state.uncertain)
                    R08VirtualDisplayCloseResult.Unknown()
                else when (endpoint.observe()) {
                    AndroidVirtualDisplayOwnerHost.Lifecycle.NOT_STARTED ->
                        if (!state.startAttempted) R08VirtualDisplayCloseResult.Drained else R08VirtualDisplayCloseResult.Unknown()
                    AndroidVirtualDisplayOwnerHost.Lifecycle.DRAINED -> R08VirtualDisplayCloseResult.Drained
                    AndroidVirtualDisplayOwnerHost.Lifecycle.UNKNOWN -> R08VirtualDisplayCloseResult.Unknown()
                }
            } catch (_: Exception) { R08VirtualDisplayCloseResult.Unknown() }
            // Host observation is I/O outside stateLock. Recheck after it so concurrent bad
            // feedback cannot turn a newly latched UNKNOWN into a stale Drained observation.
            val latest = heldState()
            done(if (result == R08VirtualDisplayCloseResult.Drained &&
                (!latest.revoked || latest.activeExecutions != 0 || latest.pendingFinalOutcomes != 0 || latest.uncertain))
                R08VirtualDisplayCloseResult.Unknown() else result)
        }, "r08-virtual-display-observe").apply { isDaemon = true; start() }
    }

    companion object {
        const val CAPABILITY = "r08.virtual_display"
        const val WRITE_TOOL = "r08_virtual_display_step"
        const val READ_TOOL = "r08_virtual_display_read"
    }
}
