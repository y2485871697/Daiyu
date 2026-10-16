package app.daiyu.android.shizuku

import app.daiyu.android.domain.tools.ExecutionReceipt
import app.daiyu.android.domain.tools.WriteEffect
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One host-reserved run, never a preference granting future runs shell access. */
internal enum class ShizukuApprovalStatus { DRAFT, APPROVED, OPENING, READY, REVOKED, CLOSED }

internal data class ShizukuRunState(
    val sessionId: String,
    val runId: String,
    val selectedUid: Int? = null,
    val script: String = "",
    val timeoutMillis: Long = 30_000,
    val status: ShizukuApprovalStatus = ShizukuApprovalStatus.DRAFT,
    val identity: ShizukuIdentity? = null,
    val receipt: ExecutionReceipt? = null,
    val cleanup: ShizukuCloseResult? = null
)

/**
 * Product approval for a single, host-reserved session/run. Construction, edit, approve and bind
 * perform no external I/O. Only explicit open() connects the existing Shizuku backend; it never
 * executes a probe. A consumed approval cannot be reopened, even after a failed/unknown open.
 * Keep this instance with the run, not with the Activity. Do not persist approvals across death.
 */
internal class ShizukuRunSession(val sessionId: String, val runId: String) : AutoCloseable {
    private val lock = Any()
    private val updates = MutableStateFlow(ShizukuRunState(sessionId, runId))
    val states: StateFlow<ShizukuRunState> = updates.asStateFlow()
    private var value: ShizukuRunState
        get() = updates.value
        set(next) { updates.value = next }
    private var approvedEffect: WriteEffect? = null
    private var ticket: String? = null
    private var started = false
    private var opening = false
    private var closing = false
    private var commands: RunShizukuCommands? = null
    private var observingClose = false
    private val observers = mutableListOf<(ShizukuCloseResult) -> Unit>()

    init { require(sessionId.isNotBlank() && runId.isNotBlank()) }
    val state: ShizukuRunState get() = synchronized(lock) { value }

    /** Snapshot only. The host must put these into the SAME TaskAuthorization used by Dispatcher. */
    val allowedWrites: Set<WriteEffect> get() = synchronized(lock) {
        if (closing) emptySet() else approvedEffect?.let { setOf(it) } ?: emptySet()
    }

    /** Changing any displayed field invalidates the previous approval, including identity changes. */
    fun edit(uid: Int?, script: String, timeoutMillis: Long) = synchronized(lock) {
        check(!started && !closing) { "shizuku_approval_consumed" }
        require(uid == null || uid == 0 || uid == 2000)
        approvedEffect = null
        ticket = null
        value = value.copy(selectedUid = uid, script = script, timeoutMillis = timeoutMillis,
            status = ShizukuApprovalStatus.DRAFT)
    }

    /** Call only for an explicit user confirmation of all fields in state, including this run ID. */
    fun approve(displayed: ShizukuRunState) = synchronized(lock) {
        check(!started && !closing) { "shizuku_approval_consumed" }
        check(value == displayed) { "shizuku_approval_changed" }
        val uid = checkNotNull(value.selectedUid) { "shizuku_identity_required" }
        approvedEffect = RunShizukuCommands.writeEffect(uid, value.script, value.timeoutMillis)
        ticket = UUID.randomUUID().toString()
        value = value.copy(status = ShizukuApprovalStatus.APPROVED)
    }

    /** Explicit worker-thread operation. Authorization itself is not command approval. */
    fun open(authorization: ShizukuAuthorization): RunShizukuCommands = open { uid, scope ->
        val connection = authorization.connect(uid)
        try { connection.openBackend(scope) }
        catch (failure: Exception) { connection.close(); throw failure }
    }

    /** Transport seam for deterministic tests; production uses only ShizukuAuthorization above. */
    internal fun open(connect: (uid: Int, scope: String) -> ShizukuBackend): RunShizukuCommands {
        val selection = synchronized(lock) {
            check(!closing && !started && approvedEffect != null) { "shizuku_command_not_approved" }
            val id = checkNotNull(ticket)
            started = true
            opening = true
            value = value.copy(status = ShizukuApprovalStatus.OPENING)
            checkNotNull(value.selectedUid) to id
        }
        var resource: RunShizukuCommands? = null
        try {
            val backend = connect(selection.first, "run-${selection.second}")
            val created = RunShizukuCommands(backend,
                admission = { synchronized(lock) { !closing && ticket == selection.second } },
                commandApproved = { effect -> synchronized(lock) {
                    !closing && ticket == selection.second && approvedEffect == effect
                } },
                onReceipt = ::recordReceipt, expectedSessionId = sessionId, expectedRunId = runId)
            resource = created
            // Never silently adopt the service identity instead of the user's choice.
            check(backend.identity.uid == selection.first) { "shizuku_identity_mismatch" }
            val publish = synchronized(lock) {
                commands = created
                opening = false
                if (!closing) value = value.copy(status = ShizukuApprovalStatus.READY, identity = backend.identity)
                !closing
            }
            if (!publish) {
                beginCleanup()
                error("shizuku_approval_revoked")
            }
            return created
        } catch (failure: Exception) {
            synchronized(lock) {
                if (commands == null) commands = resource
                opening = false
                closing = true
                approvedEffect = null
                ticket = null
                if (value.status != ShizukuApprovalStatus.REVOKED) {
                    value = value.copy(status = ShizukuApprovalStatus.CLOSED)
                }
                if (resource == null) value = value.copy(cleanup =
                    ShizukuCloseResult.Unknown("shizuku_connection_cleanup_unconfirmed"))
            }
            beginCleanup()
            throw failure
        }
    }

    private fun recordReceipt(receipt: ExecutionReceipt) {
        synchronized(lock) {
            if (value.receipt !is ExecutionReceipt.Unknown) value = value.copy(receipt = receipt)
        }
        // Unknown is sticky: no new approval, connection, call ID or automatic retry on this run.
        if (receipt is ExecutionReceipt.Unknown) close()
    }

    /** The outer ledger/runner may know the final receipt is unknown even after an adapter reply. */
    fun markOutcomeUnknown(reason: String) {
        require(reason.isNotBlank())
        recordReceipt(ExecutionReceipt.Unknown(reason))
    }

    /** Fences new execution immediately; does not claim reversal of already dispatched effects. */
    fun revoke() = finish(ShizukuApprovalStatus.REVOKED)
    override fun close() = finish(ShizukuApprovalStatus.CLOSED)

    private fun finish(status: ShizukuApprovalStatus) {
        synchronized(lock) {
            if (!closing) value = value.copy(status = status)
            closing = true
            approvedEffect = null
            ticket = null
            if (!started) value = value.copy(cleanup = ShizukuCloseResult.Drained)
        }
        beginCleanup()
    }

    /** Callback means owned execution drained OR explicitly unknown, never "everything released". */
    fun closeWithResult(onClosed: (ShizukuCloseResult) -> Unit) {
        synchronized(lock) { observers.add(onClosed) }
        close()
    }

    private fun beginCleanup() {
        val target = synchronized(lock) {
            if (!closing || opening || observingClose) null
            else commands?.also { observingClose = true }
        }
        if (target != null) {
            try {
                target.closeWithResult { result ->
                    synchronized(lock) { value = value.copy(cleanup = result) }
                    notifyObservers()
                }
            } catch (_: Exception) {
                synchronized(lock) {
                    if (value.cleanup == null) value = value.copy(cleanup =
                        ShizukuCloseResult.Unknown("shizuku_close_observation_unconfirmed"))
                }
            }
        }
        notifyObservers()
    }

    private fun notifyObservers() {
        val notification = synchronized(lock) {
            val result = value.cleanup ?: return
            if (opening) return
            val callbacks = observers.toList()
            observers.clear()
            result to callbacks
        }
        notification.second.forEach { callback -> runCatching { callback(notification.first) } }
    }
}
