package app.daiyu.android.gui

import android.os.Looper
import androidx.annotation.AnyThread
import androidx.annotation.MainThread
import app.daiyu.android.domain.runtime.RunExecutionOwner
import app.daiyu.android.domain.tools.TaskAuthorization
import app.daiyu.android.domain.tools.WriteEffect
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** User-facing lease, not a model-created permission. Never persist or restore it after process death. */
internal class GuiRunSession internal constructor(internal val lease: GuiLeaseGate.Lease) : AutoCloseable {
    val identity: GuiOwner get() = lease.owner
    private val closed = AtomicBoolean(false)
    private val ioFence = GuiIoFence()
    /** Parent MUST forward every final central Unknown, including receipt_persistence_failed.
     * Pure synchronous fence; safe on any thread. Never retry using a new call ID. */
    fun onCentralUnknown() { ioFence.unknown() }
    fun preparationAvailable(): Boolean = !closed.get() && ioFence.rejection() == null && lease.valid && frozenWrites == null
    /** Main/user-only entry; parent retains this session before showing the non-focus overlay. */
    fun prepareExactActionFromUser(operation: GuiOperation, text: String? = null,
        ownerCurrent: () -> Boolean,
        onNotExecuted: (String) -> Unit = {},
        onRevoked: () -> Unit = {},
        onConfirmed: (GuiConfirmedDelivery) -> Boolean): GuiResult<Unit> {
        editable()
        return GuiRuntime.showConfirmation(this, operation, text, ownerCurrent, onNotExecuted, onRevoked, onConfirmed)
    }
    fun validateExactActionFromUser(action: GuiAction): GuiResult<Unit> {
        editable(); return GuiRuntime.validateExactTarget(lease, action)
    }
    private val released = AtomicBoolean(false)
    /** Admission is already closed; true additionally proves main-loop lease release completed. */
    val releaseConfirmed: Boolean get() = released.get()
    @Volatile private var boundOwner: RunExecutionOwner? = null
    @Volatile private var boundAuthorization: TaskAuthorization? = null
    private var frozenWrites: Set<WriteEffect>? = null
    private fun onMain() { check(Looper.myLooper() === Looper.getMainLooper()) { "gui_main_thread_required" } }
    private fun editable() { onMain(); check(!closed.get() && ioFence.rejection() == null && frozenWrites == null && boundOwner == null) { "gui_approval_frozen" } }

    /** These methods belong exclusively to trusted, explicit UI callbacks. Tools cannot call them. */
    fun approveReadsFromUser(approval: GuiReadApproval): GuiResult<Unit> {
        editable(); return GuiRuntime.approveReads(lease, approval)
    }
    fun previewFromUser(): GuiResult<GuiObservation> { editable(); return GuiRuntime.observe(lease) }
    fun approveExactActionFromUser(action: GuiAction): GuiResult<Unit> {
        editable(); return GuiRuntime.approveAction(lease, action)
    }
    /** Main-thread freeze after confirmation, before building the parent's one TaskAuthorization. */
    fun freezeAuthorizationFromUser(): Set<WriteEffect> {
        onMain(); check(!closed.get() && ioFence.rejection() == null) { "gui_session_closed" }
        return frozenWrites ?: Collections.unmodifiableSet(GuiRuntime.approvedActions(lease)
            .map { it.effect(identity) }.toSet()).also { frozenWrites = it }
    }
    /** No I/O. Parent passes Runner's original opaque owner and SAME final union authorization. */
    @Synchronized fun bind(owner: RunExecutionOwner, authorization: TaskAuthorization) {
        check(!closed.get() && ioFence.rejection() == null) { "gui_session_closed" }
        val approved = checkNotNull(frozenWrites) { "gui_explicit_approval_snapshot_required" }
        require(owner.runId.value == identity.runId && authorization.taskId == identity.runId &&
            authorization.sessionId == identity.sessionId) { "gui_run_owner_mismatch" }
        require(authorization.allowedWrites.containsAll(approved)) { "gui_task_authorization_mismatch" }
        check(boundOwner == null || boundOwner === owner) { "gui_owner_already_bound" }
        check(boundAuthorization == null || boundAuthorization === authorization) { "gui_authorization_already_bound" }
        boundAuthorization = authorization
        boundOwner = owner
    }
    fun admission(owner: RunExecutionOwner, authorization: TaskAuthorization): GuiResult.Rejected? = when {
        closed.get() -> GuiResult.Rejected("GUI_AUTHORIZATION_REVOKED")
        !lease.valid -> GuiResult.Rejected("GUI_LEASE_REVOKED_SERVICE_OR_OWNER_LOST")
        ioFence.rejection() != null -> ioFence.rejection()
        boundOwner !== owner || boundAuthorization !== authorization -> GuiResult.Rejected("GUI_RUN_OWNER_MISMATCH")
        else -> null
    }
    fun observe(cancelled: () -> Boolean): GuiResult<GuiObservation> = onWorker(cancelled) { GuiRuntime.observe(lease) }
    fun action(action: GuiAction, cancelled: () -> Boolean): GuiResult<String> = onWorker(cancelled) {
        if (action.effect(identity) !in frozenWrites.orEmpty()) GuiResult.Rejected("EXACT_ACTION_NOT_AUTHORIZED")
        else GuiRuntime.action(lease, action)
    }.also { if (it is GuiResult.Unknown) ioFence.unknown() }
    fun screenshot(observationId: String, cancelled: () -> Boolean): GuiResult<GuiScreenshot> {
        val started = onWorker(cancelled) { GuiResult.Value(GuiRuntime.screenshot(lease, observationId)) }
        if (started !is GuiResult.Value) return when (started) {
            is GuiResult.Rejected -> started
            is GuiResult.Unknown -> started
            else -> error("unreachable")
        }
        return try {
            val value = started.value.get(5, TimeUnit.SECONDS)
            if (closed.get() || cancelled() || ioFence.rejection() != null) GuiResult.Rejected("SCREENSHOT_RESULT_REVOKED") else value
        } catch (failure: Exception) {
            if (failure is InterruptedException) Thread.currentThread().interrupt()
            // Capture is read-only; discard any late image. Never issue a fallback capture.
            GuiResult.Rejected("SCREENSHOT_RESULT_UNAVAILABLE")
        }
    }
    private fun <T> onWorker(cancelled: () -> Boolean, block: () -> GuiResult<T>): GuiResult<T> {
        ioFence.rejection()?.let { return it } // Unknown never even posts work or touches Android
        if (Looper.myLooper() === Looper.getMainLooper()) return GuiResult.Rejected("GUI_WORKER_REQUIRED")
        if (closed.get() || cancelled()) return GuiResult.Rejected("CANCELLED_NOT_EXECUTED")
        val state = AtomicInteger(0) // queued=0, started=1, abandoned=2, completed=3
        val future = CompletableFuture<GuiResult<T>>()
        val posted = GuiRuntime.main.post {
            if (!state.compareAndSet(0, 1)) return@post
            if (closed.get() || cancelled()) future.complete(GuiResult.Rejected("CANCELLED_NOT_EXECUTED"))
            else if (ioFence.rejection() != null) future.complete(ioFence.rejection()!!)
            else try {
                val result = block()
                if (result is GuiResult.Unknown) ioFence.unknown()
                future.complete(result)
            } catch (_: Exception) {
                ioFence.unknown()
                future.complete(GuiResult.Unknown("GUI_OPERATION_UNCONFIRMED"))
            }
            finally { state.set(3) }
        }
        if (!posted) return GuiResult.Rejected("GUI_HOST_UNAVAILABLE")
        return try { future.get(5, TimeUnit.SECONDS) }
        catch (failure: Exception) {
            if (failure is InterruptedException) Thread.currentThread().interrupt()
            if (state.compareAndSet(0, 2)) GuiResult.Rejected("CANCELLED_BEFORE_GUI_EXECUTION")
            else {
                ioFence.unknown()
                GuiResult.Unknown("GUI_OPERATION_UNCONFIRMED")
            }
        }
    }
    /** Synchronously closes admission; main-loop cleanup releases only this lease, never another owner. */
    @AnyThread
    override fun close() {
        ioFence.close()
        closed.set(true)
        if (Looper.myLooper() === Looper.getMainLooper()) closeOnMain()
        else try { GuiRuntime.main.post { closeOnMain() } }
        catch (_: Exception) { /* false/exception is NOT a drain receipt */ }
    }
    @MainThread
    private fun closeOnMain() {
        try { GuiRuntime.close(lease) { released.set(true) } }
        catch (_: Exception) { /* keep retention; cleanup failure is never a release receipt */ }
    }
}
