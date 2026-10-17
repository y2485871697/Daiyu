package app.daiyu.android

import android.content.Context
import android.os.Looper
import app.daiyu.android.domain.ToolInvocation
import app.daiyu.android.domain.runtime.RunExecutionOwner
import app.daiyu.android.domain.tools.*
import app.daiyu.android.integration.tools.*
import app.daiyu.android.vd.AndroidVirtualDisplayOwnerHost
import app.daiyu.android.vd.OwnerRunBinding
import app.daiyu.android.vd.OwnerReply
import app.daiyu.android.vdapproval.VirtualDisplayApproval
import app.daiyu.android.vdapproval.VirtualDisplayApprovalRequest
import app.daiyu.android.vdapproval.R08CleanupApprovalRequest
import java.util.concurrent.CountDownLatch
import org.json.JSONObject

/** Parent-owned integration seam, not a Runner, dispatcher, database or automatic cleanup service.
 * Create ONLY for an already durably accepted send. Keep in the parent's retention record until
 * observeCloseOnWorker returns Drained. Closing/denying does not undo acceptance or resend text.
 */
internal class ControllerVirtualDisplaySession internal constructor(
    private val capabilities: SessionCapabilities,
    writes: List<R08VirtualDisplayWrite>,
    reads: List<R08VirtualDisplayRead>,
    private val parent: Parent,
    private val ownerFactory: () -> OwnerHandle
) : AutoCloseable {
    /** Implement with the existing durable journal/lease store, never an in-memory production map.
     * reserve must persist+fsync a fresh generation and exclusive lease before returning. A lost
     * receipt throws; the parent retains this session for reconciliation, never reserves again.
     */
    interface Parent {
        fun reserve(owner: RunExecutionOwner, capabilities: SessionCapabilities): Lease
        /** Publish on the parent's UI lane. Do not wait for that lane; no root start here. */
        fun showApproval(request: VirtualDisplayApprovalRequest)
        /** Union with OTHER explicitly approved effects; return the ONE actual catalog auth object. */
        fun combinedAuthorization(owner: RunExecutionOwner, capabilities: SessionCapabilities,
                                  approvedEffects: Set<WriteEffect>): TaskAuthorization
        /** Stop only this accepted run. No draft restoration, rejection receipt or automatic resend. */
        fun stopAcceptedRun(owner: RunExecutionOwner)
        /** Pure feedback registration on this exact retained leaf; not dispatch. In the batch
         * candidate: val ticket = leaf.beginBatch(originalWholeBatch); return BatchCompletion {
         * results -> leaf.finishBatch(ticket, results) }. Capture that original ticket, not a
         * reconstructed batch. The seam avoids a direct dependency on the candidate's ticket type.
         */
        fun beginBatch(leaf: R08VirtualDisplayCatalogLeaf, originalWholeBatch: List<ToolInvocation>): BatchCompletion
    }

    fun interface BatchCompletion {
        fun finish(finalResults: List<ToolDispatchResult>)
    }
    /** Identity is additionally validated against this session's live ticket set. */
    class BatchTicket internal constructor(internal val completion: BatchCompletion,
                                           internal val originalWholeBatch: List<ToolInvocation>)

    interface Lease {
        val generation: String
        val journal: R08VirtualDisplayJournal
        /** Must be true only for durable exclusive storage, including the journal's atomic claims. */
        val durable: Boolean
        /** Persist release once. An exception is UNKNOWN, never grounds to retry this write. */
        fun releaseAfterDrain()
    }

    /** Existing host/client only. This seam also permits tests without su, sockets or Android I/O. */
    internal class OwnerHandle(
        val endpoint: R08VirtualDisplayEndpoint,
        val observePhysical: () -> AndroidVirtualDisplayOwnerHost.Observation,
        val uninitializedShutdown: UninitializedShutdown? = null,
        // Keep the real host strongly referenced even when a transport is poisoned.
        val retainedHost: AndroidVirtualDisplayOwnerHost? = null
    )

    /** Optional trusted host adapter for versions that implement uninitialized shutdown. Base
     * 1d923481 has no such wire operation: leave absent, retain UNKNOWN, never invent a release.
     * lookupOnly must authenticate THIS generation+binding and send receipt queries only.
     * shutdownOnce must use the existing trusted owner API, never initialize, shell/kill or replay.
     */
    interface UninitializedShutdown {
        fun lookupOnly(generation: String, binding: OwnerRunBinding): UninitializedEvidence
        fun shutdownOnce(generation: String, binding: OwnerRunBinding)
    }
    enum class UninitializedEvidence { PROVEN_UNINITIALIZED, UNKNOWN }
    sealed interface CloseResult {
        data object Drained : CloseResult
        data object Unknown : CloseResult
    }

    private val frozenWrites = writes.toList()
    private val frozenReads = reads.toList()
    private val lock = Any()
    private val answered = CountDownLatch(1)
    private var owner: RunExecutionOwner? = null
    private var lease: Lease? = null
    private var plan: R08VirtualDisplayPlan? = null
    private var request: VirtualDisplayApprovalRequest? = null
    private var approval: VirtualDisplayApproval? = null
    private var authorization: TaskAuthorization? = null
    private var handle: OwnerHandle? = null
    private var leaf: R08VirtualDisplayCatalogLeaf? = null
    private var binding = false
    private var closed = false
    private var uncertain = false
    private var decision: Boolean? = null
    private var stopClaimed = false
    private var releasing = false
    private var released = false
    private var shutdownClaimed = false
    private var shutdownBusy = false
    // Only the original authenticated business reply can prove Handoff. Central replay alone
    // is not authority to send an additional Release; final feedback may downgrade this evidence.
    private var handoffConfirmed = false
    private var originalHandoffAttempted = false
    private var tasksObservationAttempted = false
    private var tasksObservation: RetainedTasks? = null
    private var cleanupRequest: R08CleanupApprovalRequest? = null
    private var cleanupApproval: VirtualDisplayApproval? = null
    private var cleanupDecision: Boolean? = null
    private var cleanupAttempted = false
    private var cleanupBusy = false
    private var batchCallbacks = 0
    private val batchTickets = java.util.Collections.newSetFromMap(
        java.util.IdentityHashMap<BatchTicket, Boolean>())
    // Retain evidence/closures even after an uncertain final callback; never replay them.
    private val completedBatchTickets = mutableListOf<BatchTicket>()

    init {
        require(R08VirtualDisplayCatalogLeaf.CAPABILITY in capabilities.enabled)
        require(frozenWrites.size <= 64 && frozenReads.size <= 64)
    }

    /** Supply as RunCatalogTools.authorizationFactory after DB acceptance. Runner supplies its
     * real token here before model.open. Blocking wait is interruptible and close wakes it without
     * polling. Parent must call close on run cancellation, navigation or controller disposal.
     * Every external call, journal operation and wait is OUTSIDE the short state lock.
     */
    fun authorizationFactory(actualOwner: RunExecutionOwner): TaskAuthorization {
        worker()
        synchronized(lock) {
            check(owner == null && !closed) { "virtual_display_session_consumed" }
            owner = actualOwner
            binding = true
        }
        try {
            val reserved = parent.reserve(actualOwner, capabilities)
            synchronized(lock) { lease = reserved }
            check(reserved.durable) { "virtual_display_durable_lease_required" }
            val journal = reserved.journal
            val prepared = R08VirtualDisplayPlan.prepare(actualOwner, capabilities, reserved.generation,
                frozenWrites, frozenReads)
            val review = VirtualDisplayApprovalRequest(prepared)
            val publish = synchronized(lock) {
                plan = prepared
                if (closed) false else { request = review; true }
            }
            if (!publish) throw AcceptedRunStopped()
            parent.showApproval(review)
            answered.await()
            val grant = synchronized(lock) {
                if (closed || decision != true) null else approval
            } ?: throw AcceptedRunStopped()
            val auth = parent.combinedAuthorization(actualOwner, capabilities, grant.requiredEffects)
            require(auth.sessionId == capabilities.sessionId && auth.taskId == actualOwner.runId.value &&
                auth.allowedWrites.containsAll(grant.requiredEffects)) { "virtual_display_authorization_mismatch" }
            // Owner construction is non-launching. Retain BEFORE exposing any executable tools.
            val retained = ownerFactory()
            synchronized(lock) { handle = retained }
            val guarded = object : R08VirtualDisplayEndpoint by retained.endpoint {
                override fun execute(id: String, operation: String, parameters: JSONObject): OwnerReply {
                    worker()
                    // A model cannot skip the approved Handoff and send Release first. This is
                    // NOT permission to insert/retry Handoff or replace the frozen parameters.
                    if (operation == "release" && prepared.writes.any { it.operation == "handoff" } &&
                        !synchronized(lock) { handoffConfirmed }) return OwnerReply(OwnerReply.Outcome.UNKNOWN)
                    if (operation == "handoff") synchronized(lock) { originalHandoffAttempted = true }
                    val reply = retained.endpoint.execute(id, operation, parameters)
                    if (operation == "handoff") {
                        val body = reply.payloadCopy()
                        val confirmed = reply.outcome == OwnerReply.Outcome.REPLIED && body != null &&
                            body.opt("ok") == true && body.opt("receiptOnly") != true &&
                            body.opt("mutationUncertain") != true && body.opt("handedOff") == true &&
                            body.opt("sourceEmpty") == true &&
                            body.optJSONObject("receipt")?.optString("state") == "SUCCEEDED"
                        synchronized(lock) { handoffConfirmed = confirmed }
                    }
                    return reply
                }

                override fun start(generation: String, binding: OwnerRunBinding): Boolean {
                    worker()
                    val admitted = synchronized(lock) {
                        !closed && !uncertain && decision == true && approval === grant &&
                            lease === reserved && handle === retained && leaf != null &&
                            generation == prepared.generation && binding === prepared.binding
                    }
                    // Admission linearizes before close; host I/O is never under our lock.
                    return admitted && retained.endpoint.start(generation, binding)
                }
            }
            val bound = grant.bind(actualOwner, capabilities, auth, guarded, journal)
            val publishLeaf = synchronized(lock) {
                authorization = auth
                leaf = bound
                !closed
            }
            if (!publishLeaf) { bound.close(); throw AcceptedRunStopped() }
            return auth
        } catch (failure: Exception) {
            if (failure is InterruptedException) Thread.currentThread().interrupt()
            synchronized(lock) { if (failure !is AcceptedRunStopped) uncertain = true }
            close()
            stopAcceptedOnce(actualOwner)
            // Never leak store/host exceptions, identities or possible credentials through Runner.
            throw AcceptedRunStopped()
        } finally { synchronized(lock) { binding = false } }
    }

    /** UI callbacks carry the ORIGINAL request, not an ID reconstructed from a newer screen.
     * First answer wins. Old confirm/reject/dispose callbacks cannot approve or cancel another run.
     */
    fun answerFromUi(original: VirtualDisplayApprovalRequest, accepted: Boolean): Boolean {
        val acceptedCallback = synchronized(lock) {
            if (closed || request !== original || decision != null) false else {
                original.answerFromUi(accepted) { approval = it }
                decision = accepted
                request = null
                true
            }
        }
        if (acceptedCallback) answered.countDown()
        return acceptedCallback
    }

    fun pendingApproval(): VirtualDisplayApprovalRequest? = synchronized(lock) { request }

    /** In the same factory, register these in the parent's existing registry BEFORE its snapshot.
     * Never create a second dispatcher/ledger. The SAME auth/capabilities references are checked.
     */
    fun registeredTools(actualOwner: RunExecutionOwner, actualCapabilities: SessionCapabilities,
                        actualAuthorization: TaskAuthorization): List<RegisteredTool> = synchronized(lock) {
        check(!closed && owner === actualOwner && capabilities === actualCapabilities &&
            authorization === actualAuthorization) { "virtual_display_binding_mismatch" }
        checkNotNull(leaf).registeredTools
    }

    /** Before the parent's central batch call, register the ORIGINAL WHOLE batch exactly once.
     * No filtering into VD-only calls, no execution and no second dispatcher/ledger here. The
     * parent adapter captures leaf.beginBatch's actual ticket. A deferred barrier retains this
     * ticket until actual final CENTRAL results are available, including cancellation/errors.
     */
    fun beginBatch(originalWholeBatch: List<ToolInvocation>): BatchTicket {
        worker()
        val current = synchronized(lock) {
            check(!closed && !uncertain && leaf != null) { "virtual_display_not_bound" }
            check(batchCallbacks == 0 && batchTickets.isEmpty()) { "virtual_display_batch_pending" }
            batchCallbacks++
            checkNotNull(leaf)
        }
        try {
            val completion = parent.beginBatch(current, originalWholeBatch)
            return BatchTicket(completion, originalWholeBatch.toList()).also { ticket ->
                synchronized(lock) { batchTickets.add(ticket) }
            }
        } catch (_: Exception) {
            synchronized(lock) { uncertain = true }
            throw IllegalStateException("virtual_display_batch_registration_unknown")
        } finally { synchronized(lock) { batchCallbacks-- } }
    }

    /** May finish after close. Never substitutes local adapter results or a new batch/ticket. */
    fun finishBatch(ticket: BatchTicket, finalResults: List<ToolDispatchResult>) {
        worker()
        synchronized(lock) {
            check(batchTickets.remove(ticket)) { "virtual_display_batch_ticket_unavailable" }
            completedBatchTickets.add(ticket)
            batchCallbacks++
        }
        try {
            ticket.completion.finish(finalResults)
            ticket.originalWholeBatch.zip(finalResults).forEach { (call, result) ->
                val stepId = try { JSONObject(call.arguments).optString("step_id") } catch (_: Exception) { "" }
                if (call.name == R08VirtualDisplayCatalogLeaf.WRITE_TOOL &&
                    synchronized(lock) { plan?.writes?.any { it.id == stepId && it.operation == "handoff" } == true }) {
                    synchronized(lock) {
                        if (result.callId == call.id && result.outcome is DispatchOutcome.Confirmed)
                            originalHandoffAttempted = true
                        else handoffConfirmed = false
                    }
                }
            }
        }
        catch (_: Exception) {
            synchronized(lock) { uncertain = true }
            throw IllegalStateException("virtual_display_final_batch_unknown")
        } finally { synchronized(lock) { batchCallbacks-- } }
    }

    /** Pure revocation and wakeup. Does not release lease, disconnect, initialize, shutdown or kill. */
    override fun close() {
        val revoke = synchronized(lock) { closed = true; request = null; approval to leaf }
        revoke.first?.revoke()
        revoke.second?.close()
        answered.countDown()
    }

    /** One explicit worker observation, no timer or busy loop. parentDrained includes the parent
     * Runner, all dispatches AND final-result forwarding. UNKNOWN keeps every strong reference.
     */
    fun observeCloseOnWorker(parentDrained: Boolean): CloseResult {
        worker()
        val snapshot = synchronized(lock) {
            if (released) return CloseResult.Drained
            if (!closed || binding || shutdownBusy || cleanupBusy || uncertain || releasing || batchCallbacks != 0 ||
                batchTickets.isNotEmpty() || !parentDrained)
                return CloseResult.Unknown
            Triple(lease, handle, leaf)
        }
        val state = snapshot.third?.heldState()
        if (state != null && (!state.revoked || state.activeExecutions != 0 ||
                state.pendingFinalOutcomes != 0 || state.uncertain)) return CloseResult.Unknown
        val physical = try { snapshot.second?.observePhysical?.invoke() }
        catch (_: Exception) { return CloseResult.Unknown }
        val drained = if (physical == null) state == null else when (physical.lifecycle) {
            AndroidVirtualDisplayOwnerHost.Lifecycle.NOT_STARTED -> state?.startAttempted != true
            AndroidVirtualDisplayOwnerHost.Lifecycle.DRAINED -> physical.stopped && physical.launcherExit == 0 &&
                physical.ownerPid != null && physical.ownerExitObserved
            AndroidVirtualDisplayOwnerHost.Lifecycle.UNKNOWN -> false
        }
        if (!drained) return CloseResult.Unknown
        synchronized(lock) {
            if (uncertain || releasing || shutdownBusy || cleanupBusy) return CloseResult.Unknown
            if (released) return CloseResult.Drained
            releasing = true
        }
        return try {
            snapshot.first?.releaseAfterDrain()
            synchronized(lock) { released = true }
            CloseResult.Drained
        } catch (_: Exception) {
            synchronized(lock) { uncertain = true }
            CloseResult.Unknown
        } finally { synchronized(lock) { releasing = false } }
    }

    /** Explicit trusted maintenance action, separate from close and from tool grants. Consumes
     * the attempt BEFORE lookup, so UNKNOWN/exception is never retried. Does not release the lease;
     * only a subsequent physical observation can do so. Local initialized=false is NOT proof:
     * the authenticated lookup must independently establish that initialization never happened.
     */
    fun shutdownUninitializedOnceOnWorker(): Boolean {
        worker()
        val target = synchronized(lock) {
            if (!closed || binding || releasing || released || shutdownClaimed || cleanupBusy || batchCallbacks != 0 ||
                batchTickets.isNotEmpty()) return false
            val current = handle ?: return false
            val prepared = plan ?: return false
            val state = leaf?.heldState() ?: return false
            if (!state.startAttempted || state.activeExecutions != 0 || state.pendingFinalOutcomes != 0 ||
                state.initialized) return false
            val shutdown = current.uninitializedShutdown ?: return false
            shutdownClaimed = true
            shutdownBusy = true
            prepared to shutdown
        }
        return try {
            if (target.second.lookupOnly(target.first.generation, target.first.binding) !=
                UninitializedEvidence.PROVEN_UNINITIALIZED) false
            else { target.second.shutdownOnce(target.first.generation, target.first.binding); true }
        } catch (_: Exception) { false }
        finally { synchronized(lock) { shutdownBusy = false } }
    }

    enum class CleanupResult { NOT_APPROVED, UNAVAILABLE, CONFIRMED, UNKNOWN, DELIVERED_AND_RELEASED, DELIVERED_RETAINED }

    internal class RetainedTasks internal constructor(val displayId: Int, candidateIds: List<Int>) {
        val candidateTaskIds: List<Int> = java.util.Collections.unmodifiableList(candidateIds.toList())
    }
    sealed interface TasksResult {
        data class Observed(val tasks: RetainedTasks) : TasksResult
        data object Unavailable : TasksResult
        data object Unknown : TasksResult
    }

    fun observeTasksOnceOnWorker(parentDrained: Boolean): TasksResult {
        worker()
        val target = synchronized(lock) {
            if (cleanupDecision != null || cleanupAttempted || !taskHandoffAvailable(parentDrained))
                return TasksResult.Unavailable
            tasksObservation?.let { return TasksResult.Observed(it) }
            if (tasksObservationAttempted) return TasksResult.Unavailable
            tasksObservationAttempted = true
            cleanupBusy = true
            val original = checkNotNull(plan)
            val statusPlan = R08VirtualDisplayPlan.prepare(original.owner, capabilities, original.generation,
                emptyList(), listOf(R08VirtualDisplayRead("host_tasks_status")))
            Triple(checkNotNull(lease).journal, checkNotNull(handle).endpoint,
                statusPlan.intent("host-tasks-observe-v1", statusPlan.reads.single()))
        }
        var recorded = false
        try {
            val intent = target.third
            if (target.first.claim(intent) != R08VirtualDisplayPermit.EXECUTE_ONCE) return TasksResult.Unknown
            val reply = target.second.execute(intent.operationId, intent.operation, JSONObject(intent.canonicalParameters))
            val body = reply.payloadCopy()
            val tasks = if (reply.outcome == OwnerReply.Outcome.REPLIED && body != null &&
                body.opt("ok") == true && body.opt("receiptOnly") != true &&
                body.optJSONObject("receipt")?.optString("state") == "SUCCEEDED" && body.opt("ready") == true &&
                body.opt("session") == "active" && body.opt("mutationUncertain") == false &&
                body.opt("finishing") == false && body.opt("handoffComplete") == false &&
                body.opt("releaseAttempted") == false && body.opt("sourceState") in setOf("empty", "occupied")) {
                fun ids(key: String): List<Int> {
                    val array = body.getJSONArray(key)
                    require(array.length() <= 8192)
                    return (0 until array.length()).map {
                        val value = array.get(it)
                        require((value is Int || value is Long) && (value as Number).toLong() in 1..Int.MAX_VALUE.toLong())
                        (value as Number).toInt()
                    }.also { require(it.distinct().size == it.size) }
                }
                val retained = ids("retainedTaskIds").toSet()
                val live = ids("liveTaskIds")
                val gone = ids("goneTaskIds").toSet()
                require(live.toSet().intersect(gone).isEmpty() && live.toSet() + gone == retained)
                val displayId = body.getInt("displayId"); require(displayId > 0)
                RetainedTasks(displayId, live)
            } else null
            target.first.complete(intent, if (tasks == null) R08VirtualDisplayEvidence.UNKNOWN else R08VirtualDisplayEvidence.CONFIRMED)
            recorded = true
            if (tasks != null) {
                synchronized(lock) { tasksObservation = tasks }
                return TasksResult.Observed(tasks)
            }
        } catch (_: Exception) {
            // Retain the original claim; no second observation or replacement ID.
        } finally { synchronized(lock) { if (!recorded) uncertain = true; cleanupBusy = false } }
        return TasksResult.Unknown
    }

    fun prepareTaskHandoffOnWorker(originalTasks: RetainedTasks, selectedTaskIds: List<Int>,
        parentDrained: Boolean, admissionStillValid: () -> Boolean = { true }): R08CleanupApprovalRequest? {
        worker()
        val selected = selectedTaskIds.toList()
        return synchronized(lock) {
            if (tasksObservation !== originalTasks || cleanupDecision != null ||
                cleanupAttempted || !taskHandoffAvailable(parentDrained) || !admissionStillValid()) return null
            cleanupRequest?.let { return if (it.deliversTasks && it.selectedTaskIds == selected) it else null }
            if (selected.isEmpty() || selected.distinct().size != selected.size ||
                selected.size > 256 || selected.any { it !in originalTasks.candidateTaskIds }) return null
            val original = checkNotNull(plan)
            val prepared = R08VirtualDisplayPlan.prepare(original.owner, capabilities, original.generation,
                listOf(R08VirtualDisplayWrite("host_handoff_tasks", R08VirtualDisplayAction.HandoffTasks(selected)),
                    R08VirtualDisplayWrite("host_cleanup_release", R08VirtualDisplayAction.Release)))
            R08CleanupApprovalRequest(prepared).also { cleanupRequest = it }
        }
    }

    /** Explicit host action AFTER Stop + parent drain, never called by close/observe/timers.
     * Freezes an additional Release-only request on the SAME owner, capabilities and durable
     * generation. It never adds Handoff: plans that may own tasks require the original approved
     * Handoff's known success first. A previous Release (successful or uncertain) is never replaced.
     * No store, IPC, reserve, catalog registration or combined tool authorization happens here.
     */
    fun prepareInitializedCleanupOnWorker(parentDrained: Boolean): R08CleanupApprovalRequest? {
        worker()
        return synchronized(lock) {
            if (cleanupDecision != null || cleanupAttempted || !cleanupAvailable(parentDrained) ||
                cleanupRequest?.deliversTasks == true) return null
            cleanupRequest ?: run {
                val original = checkNotNull(plan)
                val releasePlan = R08VirtualDisplayPlan.prepare(original.owner, capabilities, original.generation,
                    listOf(R08VirtualDisplayWrite("host_cleanup_release", R08VirtualDisplayAction.Release)))
                R08CleanupApprovalRequest(releasePlan).also { cleanupRequest = it }
            }
        }
    }

    /** Identity-bound, first answer wins. Back/disposal deny this request only; neither grants
     * nor executes a Release. The original revoked approval remains revoked forever.
     */
    fun answerCleanupFromUi(original: R08CleanupApprovalRequest, accepted: Boolean): Boolean = synchronized(lock) {
        if (cleanupRequest !== original || cleanupDecision != null || cleanupAttempted || released) false
        else {
            original.review.answerFromUi(accepted) { cleanupApproval = it }
            cleanupDecision = accepted
            true
        }
    }

    /** One explicitly requested worker action, not a Runner/tool dispatcher or automatic cleanup.
     * Claims the deterministic host intent in the EXISTING durable journal BEFORE sending on the
     * retained endpoint. Any existing claim/exception/ambiguous business result is UNKNOWN, never
     * grounds for a new ID or retry. This path does not release the lease or clear prior UNKNOWN.
     */
    fun releaseInitializedOnceOnWorker(original: R08CleanupApprovalRequest,
                                       parentDrained: Boolean,
                                       admissionStillValid: () -> Boolean = { true }): CleanupResult {
        worker()
        val target = synchronized(lock) {
            if (cleanupRequest !== original || cleanupDecision != true || cleanupApproval == null)
                return CleanupResult.NOT_APPROVED
            // Adapter supplies only an atomic UI-visit validity read here: no host locks or I/O.
            // Invalidation before this admission does not consume the journal/send attempt;
            // after admission the in-flight operation cannot be undone or safely replayed.
            if (cleanupAttempted || !(if (original.deliversTasks) taskHandoffAvailable(parentDrained)
                    else cleanupAvailable(parentDrained)) || !admissionStillValid())
                return CleanupResult.UNAVAILABLE
            val prepared = original.review.plan
            val actual = checkNotNull(plan)
            check(prepared.owner === actual.owner && prepared.capabilities === capabilities &&
                prepared.generation == actual.generation && prepared.bindingKey == actual.bindingKey)
            cleanupAttempted = true // consumed BEFORE claim/storage/IPC, even if the receipt is lost
            cleanupBusy = true
            Triple(checkNotNull(lease).journal, checkNotNull(handle).endpoint, prepared)
        }
        var outcomeRecorded = false
        var delivered = false
        var releaseClaimed = false
        try {
            for (step in target.third.writes) {
                if (!admissionStillValid()) {
                    outcomeRecorded = true
                    return if (delivered) CleanupResult.DELIVERED_RETAINED else CleanupResult.UNAVAILABLE
                }
                val invocation = if (step.operation == "handoff") "host-handoff-tasks-v1" else "host-cleanup-release-v1"
                val intent = target.third.intent(invocation, step)
                outcomeRecorded = false
                var confirmed = false
                if (step.operation == "release") releaseClaimed = true
                if (target.first.claim(intent) == R08VirtualDisplayPermit.EXECUTE_ONCE) {
                    if (!admissionStillValid()) {
                        target.first.complete(intent, R08VirtualDisplayEvidence.NOT_EXECUTED)
                        outcomeRecorded = true
                        return if (delivered) CleanupResult.DELIVERED_RETAINED else CleanupResult.UNAVAILABLE
                    }
                    val reply = target.second.execute(intent.operationId, intent.operation, JSONObject(intent.canonicalParameters))
                    val body = reply.payloadCopy()
                    val success = reply.outcome == OwnerReply.Outcome.REPLIED && body != null &&
                        body.opt("ok") == true && body.opt("receiptOnly") != true &&
                        body.opt("mutationUncertain") != true &&
                        body.optJSONObject("receipt")?.optString("state") == "SUCCEEDED"
                    confirmed = success && if (step.operation == "release") body!!.opt("released") == true
                    else body!!.opt("handedOff") == true && body.opt("sourceEmpty") == true &&
                        body.opt("cleanupOnly") == false &&
                        body.optJSONArray("keptTaskIds")?.toString() == step.parameters().getJSONArray("taskIds").toString()
                }
                target.first.complete(intent, if (confirmed) R08VirtualDisplayEvidence.CONFIRMED else R08VirtualDisplayEvidence.UNKNOWN)
                outcomeRecorded = true
                if (!confirmed) return if (delivered) CleanupResult.DELIVERED_RETAINED else CleanupResult.UNKNOWN
                if (step.operation == "handoff") {
                    delivered = true
                    synchronized(lock) { handoffConfirmed = true }
                }
            }
            return if (delivered) CleanupResult.DELIVERED_AND_RELEASED else CleanupResult.CONFIRMED
        } catch (_: Exception) {
            // A lost claim or completion is never replayed, even after a successful wire reply.
        } finally {
            synchronized(lock) {
                if (!outcomeRecorded) uncertain = true
                if (delivered && !releaseClaimed && outcomeRecorded) {
                    cleanupRequest = null
                    cleanupApproval = null
                    cleanupDecision = null
                    cleanupAttempted = false
                }
                cleanupBusy = false
            }
        }
        return if (delivered) CleanupResult.DELIVERED_RETAINED else CleanupResult.UNKNOWN
    }

    private fun maintenanceAvailable(parentDrained: Boolean): Boolean {
        if (!closed || !parentDrained || binding || uncertain || releasing || released || shutdownClaimed ||
            shutdownBusy || cleanupBusy || batchCallbacks != 0 || batchTickets.isNotEmpty() || lease == null ||
            handle == null || plan == null) return false
        val state = leaf?.heldState() ?: return false
        return state.revoked && state.startAttempted && state.initialized && !state.uncertain &&
            !state.releaseAttempted && state.activeExecutions == 0 && state.pendingFinalOutcomes == 0
    }

    private fun cleanupAvailable(parentDrained: Boolean): Boolean = maintenanceAvailable(parentDrained) &&
        (plan!!.writes.none { it.operation == "launch" || it.operation == "handoff" } || handoffConfirmed)

    private fun taskHandoffAvailable(parentDrained: Boolean): Boolean = maintenanceAvailable(parentDrained) &&
        !originalHandoffAttempted && !handoffConfirmed

    private fun stopAcceptedOnce(actualOwner: RunExecutionOwner) {
        val stop = synchronized(lock) { if (stopClaimed) false else { stopClaimed = true; true } }
        if (stop) try { parent.stopAcceptedRun(actualOwner) }
        catch (_: Exception) { synchronized(lock) { uncertain = true } }
    }

    private fun worker() {
        check(Looper.getMainLooper()?.thread !== Thread.currentThread()) { "virtual_display_worker_required" }
    }

    internal class AcceptedRunStopped : IllegalStateException("virtual_display_accepted_run_stopped")

    companion object {
        /** No I/O, process start or permissions at construction time. */
        fun production(context: Context, capabilities: SessionCapabilities,
                       writes: List<R08VirtualDisplayWrite>, reads: List<R08VirtualDisplayRead> = emptyList(),
                       parent: Parent,
                       uninitializedShutdown: ((AndroidVirtualDisplayOwnerHost) -> UninitializedShutdown)? = null
        ): ControllerVirtualDisplaySession {
            val application = context.applicationContext
            return ControllerVirtualDisplaySession(capabilities, writes, reads, parent) {
                val host = AndroidVirtualDisplayOwnerHost(application)
                OwnerHandle(R08AndroidVirtualDisplayEndpoint(host), host::observe,
                    uninitializedShutdown?.invoke(host), retainedHost = host)
            }
        }
    }
}
