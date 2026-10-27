package app.daiyu.android

import android.os.Looper
import app.daiyu.android.integration.tools.RunCatalogTools
import app.daiyu.android.vdapproval.R08CleanupApprovalRequest
import app.daiyu.android.vdapproval.R08CleanupUiOwner
import java.util.concurrent.atomic.AtomicBoolean

/** Explicit host-only cleanup seam; no executor, launcher, dispatcher or automatic observation.
 * Capture ORIGINAL entry/catalog/session in parentIsRetainedAndDrained, checking under the
 * Controller monitor the unchanged observeVirtualDisplayClose outer gate (including Runner
 * return), plus storage retention and original session identity. This is NOT the catalog's local drain;
 * the catalog rechecks its private gate at both preparation and send.
 *
 * Each visit has its own UI owner, independent of the text-send editor. currentUiOwner must
 * read the host's current cleanup visit safely. On replacement, synchronously finish old
 * adapter.close() BEFORE publishing a new visit: owner.close() alone cannot deny an already
 * published request, which a later visit may otherwise inherit. Callbacks capture this adapter.
 * Host predicates run outside adapter/session/catalog locks and must be pure, nonblocking reads.
 * Queued work is invalidated, not already admitted IPC: never retry an in-flight/UNKNOWN result.
 */
internal class ControllerVirtualCleanupAdapter(
    private val catalog: RunCatalogTools,
    private val session: ControllerVirtualDisplaySession,
    val uiOwner: R08CleanupUiOwner,
    private val parentIsRetainedAndDrained: () -> Boolean,
    private val currentUiOwner: () -> R08CleanupUiOwner?
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val lock = Any()
    private var prepared: R08CleanupApprovalRequest? = null
    private var observed: ControllerVirtualDisplaySession.RetainedTasks? = null

    private fun visitActive(): Boolean = !closed.get() && uiOwner.isActive
    private fun eligible(): Boolean = visitActive() && currentUiOwner() === uiOwner &&
        parentIsRetainedAndDrained() && catalog.virtualDisplayCleanupLocallyDrained(session)

    /** Only from a user-requested worker task. Creating/showing the adapter does not prepare. */
    fun prepareOnWorker(): R08CleanupApprovalRequest? {
        worker()
        if (!eligible()) return null
        val request = catalog.prepareVirtualDisplayCleanupOnWorker(session, parentDrained = true) ?: return null
        // A worker completing after navigation must not publish an approval to the new visit.
        // Do not answer an unpublished request from a stale worker. A later valid visit may
        // still review that same frozen session request; this worker owns no UI decision.
        if (!eligible()) return null
        return synchronized(lock) {
            if (!visitActive()) null else { prepared = request; request }
        }
    }

    fun observeTasksOnWorker(): ControllerVirtualDisplaySession.TasksResult {
        worker()
        if (!eligible()) return ControllerVirtualDisplaySession.TasksResult.Unavailable
        val result = catalog.observeVirtualDisplayTasksOnWorker(session, true)
        if (!eligible()) return ControllerVirtualDisplaySession.TasksResult.Unavailable
        if (result is ControllerVirtualDisplaySession.TasksResult.Observed) synchronized(lock) {
            if (!visitActive()) return ControllerVirtualDisplaySession.TasksResult.Unavailable
            observed = result.tasks
        }
        return result
    }

    fun canPublishTasks(original: ControllerVirtualDisplaySession.RetainedTasks): Boolean =
        eligible() && synchronized(lock) { visitActive() && observed === original }

    fun prepareTaskHandoffOnWorker(original: ControllerVirtualDisplaySession.RetainedTasks,
        selected: List<Int>): R08CleanupApprovalRequest? {
        worker()
        if (!canPublishTasks(original)) return null
        val request = catalog.prepareVirtualDisplayTaskHandoffOnWorker(session, original, selected.toList(), true,
            ::visitActive) ?: return null
        if (!eligible()) return null
        return synchronized(lock) { if (!visitActive()) null else { prepared = request; request } }
    }

    fun shutdownUninitializedOnWorker(): Boolean {
        worker()
        if (!eligible()) return false
        return catalog.shutdownVirtualDisplayUninitializedOnWorker(session, parentDrained = true,
            admissionStillValid = ::visitActive)
    }

    /** Check AGAIN on the UI lane before publishing a worker result. No physical observation. */
    fun canPublish(original: R08CleanupApprovalRequest): Boolean =
        eligible() && synchronized(lock) { visitActive() && prepared === original }

    /** Grant only. The host schedules releaseOnWorker ONLY after an explicit accepted answer.
     * A stale callback cannot grant or deny a different visit/session's request. */
    fun answerFromUi(original: R08CleanupApprovalRequest, accepted: Boolean): Boolean {
        if (!eligible()) return false
        return synchronized(lock) {
            visitActive() && prepared === original && session.answerCleanupFromUi(original, accepted)
        }
    }

    /** Call from the host's original cleanup/selection event, never by auto-clicking a dialog.
     * Authorization alone performs no release and cannot revive the stopped run's grant. */
    fun authorizeWithProductDefaults(original: R08CleanupApprovalRequest): Boolean {
        if (!eligible()) return false
        return synchronized(lock) {
            visitActive() && prepared === original && session.authorizeCleanupWithProductDefaults(
                original, parentDrained = true, admissionStillValid = ::visitActive)
        }
    }

    fun releaseOnWorker(original: R08CleanupApprovalRequest): ControllerVirtualDisplaySession.CleanupResult {
        worker()
        if (!eligible() || !synchronized(lock) { visitActive() && prepared === original })
            return ControllerVirtualDisplaySession.CleanupResult.UNAVAILABLE
        return catalog.releaseVirtualDisplayCleanupOnWorker(session, original, parentDrained = true,
            // Only atomic reads under the session lock, at the actual one-attempt admission.
            admissionStillValid = ::visitActive)
    }

    /** Back/removal/replacement synchronously finishes denying only this visit's unanswered
     * original request, including on repeated close. Finish BEFORE publishing a replacement.
     * Does not cancel the accepted run or clear retention. Accepted but queued sends are fenced. */
    override fun close() {
        // Fence queued sends immediately. Even a second close MUST wait for the first denial
        // to finish: returning early could let the host publish a new visit to the same request
        // while the first closer is still about to deny it.
        closed.set(true)
        uiOwner.close()
        synchronized(lock) {
            prepared?.let { session.answerCleanupFromUi(it, false) }
            prepared = null
            observed = null
        }
    }

    private fun worker() {
        check(Looper.getMainLooper()?.thread !== Thread.currentThread()) { "virtual_display_worker_required" }
    }
}
