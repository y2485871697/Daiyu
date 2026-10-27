package app.daiyu.android

import app.daiyu.android.domain.Message
import app.daiyu.android.domain.MessageHistory
import app.daiyu.android.media.video.MessageVideoOwner
import app.daiyu.android.media.video.MessageVideoReference
import app.daiyu.android.model.images.FrozenMessageImagePlan
import app.daiyu.android.model.videos.*
import app.daiyu.android.storage.ModelSettings
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.coroutines.resume

/** A main-thread, authoritative view, not a UI projection. The parent obtains this only AFTER
 * checking its existing VideoUploadApprovalController.run/capability on that controller's thread.
 * Epochs must be monotonic even for change-and-restore; runIdentity is an object identity token.
 * history is the full model input projection (including retained segments), not just the last row.
 * This value does not itself grant a POST and must never be persisted or logged.
 */
internal class ControllerVideoPostAuthority(
    internal val runIdentity: Any,
    internal val runEpoch: Long,
    internal val configurationEpoch: Long,
    internal val settings: ModelSettings,
    history: List<Message>,
    inputs: List<MessageVideoInput>,
) {
    internal val history = postHistoryCopy(history)
    internal val references = orderedPostReferences(history, inputs)
    init { require(runEpoch >= 0 && configurationEpoch >= 0) }
    override fun toString() = "ControllerVideoPostAuthority(<private run snapshot>)"
}

/** Implement using the parent's existing main dispatcher, state/DB authority and cancellation.
 * snapshotOnMain may do DB work, but is NEVER called with the adapter gate held. Return null if
 * the run controller no longer authorizes this run. Do not call that controller from a worker.
 * showOnMain defaults to trusted permission for this freshly offered, already-requested POST.
 * It must never reuse an old decision; authority is re-read before issuing each exact permit.
 * cancelRun must be thread safe and signal the existing run/session, not wait for worker drainage.
 */
internal interface ControllerVideoPostApprovalHost {
    fun postToMain(action: () -> Unit)
    fun snapshotOnMain(): ControllerVideoPostAuthority?
    fun showOnMain(prompt: ControllerVideoPostPrompt, decision: (Boolean) -> Unit) = decision(true)
    fun cancelRun()
}

/** No API key, original bytes or data URLs are exposed to the dialog. */
internal class ControllerVideoPostPrompt internal constructor(
    request: VideoPostRequest,
    references: List<MessageVideoReference>,
) {
    val references: List<MessageVideoReference> = Collections.unmodifiableList(references.toList())
    val endpoint = request.endpoint
    val model = request.model
    val postNumber = request.postNumber
    val requestByteCount = request.requestByteCount
    val requestSha256 = request.requestSha256
    val totalVideoBytes = references.fold(0L) { total, ref -> Math.addExact(total, ref.bytes) }
    val disclosure: String get() = "本次 POST #$postNumber 将向 $endpoint（$model）上传 " +
        "${references.size} 个原视频，共 $totalVideoBytes 字节。" +
        "本次请求为 $requestByteCount 字节；默认许可仅签发此 POST 的一次性令牌，工具续轮重新核验并签发。"
    override fun toString() = "ControllerVideoPostPrompt(<one private POST>)"
}

/** One instance per already-authorized parent run; construct on the Android main thread.
 *
 * Workers register the ACTUAL plan produced by wrap and suspend in approve. Main obtains fresh
 * authority before offering and again on that exact offer's decision (default or custom). A private,
 * one-shot permit is consumed on the worker immediately before ResponsesModel connects.
 *
 * The short shared gate contains only in-memory identity/epoch/state operations. No dispatcher,
 * dialog, authority read, run-controller call, DB, transport, continuation resume or cancellation
 * runs under it. Parent MUST call revoke BEFORE mutating any relevant authority (including
 * configuration restore, history/sidecar edits, run/owner/lifecycle changes), and cancel the run.
 * This synchronous fence is what allows worker consumption without blocking on the main thread.
 */
internal class ControllerVideoPostApproval(
    private val host: ControllerVideoPostApprovalHost,
    private val initial: ControllerVideoPostAuthority,
) : VideoPostApprovalProvider {
    private val mainThread = Thread.currentThread()
    private val gate = Any()
    private var closed = false
    private var generation = 0L
    private var plan: Plan? = null
    private var pending: Pending? = null
    private val seenPlans = Collections.newSetFromMap(IdentityHashMap<FrozenMessageVideoPlan, Boolean>())

    private class Plan(val videos: FrozenMessageVideoPlan, val history: List<Message>,
        val references: List<MessageVideoReference>) {
        var historyIdentity: Any? = null
        var lastPost = 0
    }
    private class Pending(val request: VideoPostRequest, val plan: Plan, val generation: Long,
        var continuation: CancellableContinuation<VideoPostApproval?>?) {
        // These are the immutable exact-POST binding, never recomputed from the current dialog.
        val historyIdentity = request.historyIdentity
        val postNumber = request.postNumber
        val sha256 = request.requestSha256
        val byteCount = request.requestByteCount
        var deciding = false
        var authority: ControllerVideoPostAuthority? = null
        var spent = false
    }

    /** Called on EVERY actual wrap freeze, before opening ResponsesModel. Preflight plans are
     * not reusable here. New plan identity retires all outstanding old-plan dialogs/permits.
     */
    fun registerFrozenPlan(actual: ControllerVideoModelPlan) {
        val videos = requireNotNull(actual.videos) { "video_post_plan_required" }
        val next = Plan(videos, postHistoryCopy(actual.history), orderedPostReferences(actual.history, actual.inputs))
        val wake = synchronized(gate) {
            check(!closed) { "video_post_run_revoked" }
            check(seenPlans.add(videos)) { "video_post_plan_reused" }
            generation++
            val previous = pending?.continuation
            pending?.continuation = null
            pending = null
            plan = next
            previous
        }
        wake?.resume(null)
    }

    override suspend fun approve(request: VideoPostRequest): VideoPostApproval? = suspendCancellableCoroutine { wait ->
        // All potentially linear history/configuration comparisons happen outside the short gate.
        val registered = synchronized(gate) { if (closed) null else plan }
        if (registered == null || request.videoPlan !== registered.videos ||
            request.history != registered.history || !request.matchesConfiguration(initial.settings) ||
            request.postNumber <= 0 || request.requestByteCount <= 0 ||
            !request.requestSha256.matches(Regex("[0-9a-f]{64}"))) {
            wait.resume(null)
            return@suspendCancellableCoroutine
        }
        val offered = synchronized(gate) {
            if (closed || plan !== registered || pending != null ||
                request.postNumber != registered.lastPost + 1 ||
                (registered.historyIdentity != null && registered.historyIdentity !== request.historyIdentity)) null
            else Pending(request, registered, generation, wait).also {
                registered.historyIdentity = request.historyIdentity
                registered.lastPost = request.postNumber
                pending = it
            }
        }
        if (offered == null) {
            wait.resume(null)
            return@suspendCancellableCoroutine
        }
        wait.invokeOnCancellation { cancelPending(offered) }
        try {
            host.postToMain { present(offered) }
        } catch (_: Exception) {
            revokeIfCurrent(offered)
        }
    }

    private fun assertMain() = check(Thread.currentThread() === mainThread) { "video_post_requires_main_thread" }
    private fun current(offered: Pending): Boolean = !closed && pending === offered &&
        plan === offered.plan && generation == offered.generation && !offered.spent

    private fun matches(authority: ControllerVideoPostAuthority?, offered: Pending): Boolean =
        authority != null && authority.runIdentity === initial.runIdentity &&
            authority.runEpoch == initial.runEpoch && authority.configurationEpoch == initial.configurationEpoch &&
            authority.settings == initial.settings && offered.request.matchesConfiguration(authority.settings) &&
            authority.history == offered.plan.history && authority.references == offered.plan.references

    private fun present(offered: Pending) {
        assertMain()
        if (!synchronized(gate) { current(offered) && !offered.deciding }) return
        try {
            val authority = host.snapshotOnMain()
            if (!matches(authority, offered)) { revokeIfCurrent(offered); return }
            if (!synchronized(gate) { current(offered) && !offered.deciding }) return
            // Captures THIS Pending/THIS original VideoPostRequest, never a mutable current request.
            host.showOnMain(ControllerVideoPostPrompt(offered.request, offered.plan.references)) { accepted ->
                decide(offered, accepted)
            }
        } catch (_: Exception) {
            revokeIfCurrent(offered)
        }
    }

    private fun decide(offered: Pending, accepted: Boolean) {
        assertMain()
        val eligible = synchronized(gate) {
            if (!current(offered) || offered.deciding) false else { offered.deciding = true; true }
        }
        if (!eligible) return // A late callback must not cancel/approve a newer request either.
        if (!accepted) { revokeIfCurrent(offered); return }
        try {
            val authority = host.snapshotOnMain()
            if (!matches(authority, offered)) { revokeIfCurrent(offered); return }
            val approved = synchronized(gate) {
                if (!current(offered)) false else { offered.authority = authority; true }
            }
            if (!approved) return
            // Binding the original request can race cancellation. No request monitor is held by gate.
            val handoff = offered.request.bindPermitConsumption { consume(offered) }
            val wake = synchronized(gate) {
                if (!current(offered)) null else offered.continuation.also { offered.continuation = null }
            }
            wake?.resume(handoff)
        } catch (_: Exception) {
            // Only invalidate this run if this is still its current request.
            revokeIfCurrent(offered)
        }
    }

    private fun consume(offered: Pending) = synchronized(gate) {
        check(current(offered) && offered.deciding && offered.authority != null &&
            offered.continuation == null && offered.request.videoPlan === offered.plan.videos &&
            offered.request.historyIdentity === offered.historyIdentity &&
            offered.request.postNumber == offered.postNumber && offered.request.requestSha256 == offered.sha256 &&
            offered.request.requestByteCount == offered.byteCount) { "video_post_permit_stale_or_spent" }
        offered.spent = true
        pending = null
    }

    private fun cancelPending(offered: Pending) = revokeIfCurrent(offered)

    /** Thread-safe, idempotent signal, not a worker drain. Also wakes an outstanding dialog wait.
     * Call this synchronously BEFORE publishing a state change; dismissing the UI alone is not it.
     */
    fun revoke() = revokeIfCurrent(null)

    private fun revokeIfCurrent(expected: Pending?) {
        val result = synchronized(gate) {
            if (closed || (expected != null && !current(expected))) null else {
                closed = true
                generation++
                plan = null
                val wake = pending?.continuation
                pending?.continuation = null
                pending = null
                seenPlans.clear()
                Pair(wake, true)
            }
        } ?: return
        result.first?.resume(null)
        host.cancelRun()
    }
}

private fun postHistoryCopy(history: List<Message>): List<Message> =
    physicalVideoHistoryCopy(FrozenMessageImagePlan.snapshot(history))

/** Same order as ResponsesRequest: expanded history order, then each row's reference order.
 * Input-sidecar storage order must not silently become wire inventory order.
 */
private fun orderedPostReferences(history: List<Message>, inputs: List<MessageVideoInput>): List<MessageVideoReference> {
    validateVideoHistory(history, inputs)
    val byOwner = inputs.associate { it.owner to it.references }
    return Collections.unmodifiableList(MessageHistory.expand(history).flatMap { row ->
        byOwner[MessageVideoOwner(row.conversationId.value, row.id.value)].orEmpty()
    })
}
