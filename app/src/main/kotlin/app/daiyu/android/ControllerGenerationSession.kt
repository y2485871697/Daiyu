package app.daiyu.android

import app.daiyu.android.domain.Conversation
import app.daiyu.android.domain.runtime.RunExecutionOwner
import app.daiyu.android.domain.tools.SessionCapabilities
import app.daiyu.android.domain.tools.TaskAuthorization
import app.daiyu.android.domain.tools.WriteEffect
import app.daiyu.android.integration.tools.GenerationDownloadAuthorization
import app.daiyu.android.integration.tools.GenerationToolsCloseResult
import app.daiyu.android.integration.tools.RunGenerationTools
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Only the parent's completed durable admission transaction may issue these receipts.
 * Stop/cancel/worker scheduling are NOT persistence receipts. Unknown must not be retried.
 */
internal sealed interface GenerationPersistenceReceipt {
    class Accepted(val conversation: Conversation) : GenerationPersistenceReceipt {
        override fun toString() = "GenerationPersistenceReceipt.Accepted(<redacted>)"
    }
    data object Rejected : GenerationPersistenceReceipt
    data object Unknown : GenerationPersistenceReceipt
}

/** App-private one-shot lifetime; retain in the parent's reserved slot, not in composition.
 * No Runner/dispatcher/DB is created here. All methods except cleanup are nonblocking local gates.
 * The token, leaf AND resources remain held even on Unknown; this class never retries paid work.
 */
internal class ControllerGenerationSession(
    val token: ConfirmedGenerationPreparation,
    private val parentStop: () -> Unit,
    private val parentDrain: (timeoutMillis: Long) -> GenerationToolsCloseResult,
) {
    /** Keep both (token, drain) and token + trailing drain lambda source-compatible.
     * Parents with a Runner must supply parentStop; omission preserves the existing no-op.
     */
    constructor(
        token: ConfirmedGenerationPreparation,
        parentDrain: (timeoutMillis: Long) -> GenerationToolsCloseResult,
    ) : this(token, {}, parentDrain)

    val identity get() = token.preview.identity
    private var owner: RunExecutionOwner? = null
    private var authorization: TaskAuthorization? = null
    private var resources: ControllerGenerationResources? = null
    private var leaf: RunGenerationTools? = null
    private var stopped = false
    private var ownerValidated = false
    private var persistence: GenerationPersistenceReceipt? = null
    private var receiptConsumed = false
    private var cleanupPending = false
    private var cleanupRunning = false
    private var cleanupResult: GenerationToolsCloseResult? = null

    /** Truth about DB admission, including Accepted-before-bind and Accepted-after-stop.
     * This accessor NEVER authorizes editor consumption; use consumeAccepted instead.
     */
    val persistenceReceipt get() = synchronized(this) { persistence }
    val cleanupRequested get() = synchronized(this) { cleanupPending }
    val closeResult get() = synchronized(this) { cleanupResult }
    val canRetire get() = synchronized(this) {
        cleanupResult == GenerationToolsCloseResult.Drained &&
            (persistence === GenerationPersistenceReceipt.Rejected ||
                persistence is GenerationPersistenceReceipt.Accepted && receiptConsumed)
    }

    /** Exactly one task for the entire mixed catalog. Invoke from authorizationFactory, with
     * snapshots taken under the parent's control lock. Same owner/task objects are reused.
     * A stopped session may validate a late owner for its receipt but grants no paid writes.
     */
    @Synchronized fun bindOwner(
        realOwner: RunExecutionOwner,
        currentEditor: GenerationEditorSnapshot,
        currentIdentity: GenerationPreparationIdentity,
        sessionId: String,
        otherApprovedWrites: Set<WriteEffect> = emptySet(),
    ): TaskAuthorization {
        check(!stopped && cleanupResult == null) { "generation_session_stopped" }
        checkReceiptOwner(realOwner, currentEditor, currentIdentity)
        authorization?.let {
            require(it.sessionId == sessionId && it.allowedWrites ==
                otherApprovedWrites + token.paidWrites(realOwner, currentEditor, currentIdentity)) {
                "generation_task_changed"
            }
            return it
        }
        val writes = token.paidWrites(realOwner, currentEditor, currentIdentity)
        return TaskAuthorization(sessionId, realOwner.runId.value, otherApprovedWrites + writes)
            .also { authorization = it; owner = realOwner; ownerValidated = true }
    }

    /** Receipt-only late binding is legal after stop; it cannot create a task or resurrect a leaf.
     * Do not fabricate an owner if Runner never issued one. In that case keep Accepted retained
     * and show saved history separately, without consuming the original editor automatically.
     */
    @Synchronized fun validateOwnerForReceipt(
        realOwner: RunExecutionOwner,
        currentEditor: GenerationEditorSnapshot,
        currentIdentity: GenerationPreparationIdentity,
    ) {
        check(stopped) { "generation_receipt_only_binding_requires_stop" }
        checkReceiptOwner(realOwner, currentEditor, currentIdentity)
        owner = realOwner
        ownerValidated = true
    }

    private fun checkReceiptOwner(
        realOwner: RunExecutionOwner,
        currentEditor: GenerationEditorSnapshot,
        currentIdentity: GenerationPreparationIdentity,
    ) {
        require(identity == currentIdentity && token.preview.editor.matches(currentEditor)) {
            "generation_draft_changed"
        }
        require(realOwner.conversationId == identity.conversationId && realOwner.runId == identity.runId &&
            realOwner.assistantMessageId == identity.assistantMessageId &&
            (owner == null || owner === realOwner)) { "generation_owner_mismatch" }
    }

    /** Matches RunCatalogTools.generationFactory exactly. Pass the task returned by bindOwner.
     * Resources are retained BEFORE opening, including if construction throws. No I/O here.
     */
    @Synchronized fun openLeaf(
        realOwner: RunExecutionOwner,
        capabilities: SessionCapabilities,
        task: TaskAuthorization,
        retainedResources: ControllerGenerationResources,
        isCurrentOwner: () -> Boolean,
        networkPolicy: GenerationDownloadAuthorization = ControllerGenerationResources.DENY_DOWNLOADS,
    ): RunGenerationTools {
        check(!stopped && leaf == null && cleanupResult == null) { "generation_session_stopped_or_opened" }
        require(owner === realOwner && authorization === task && capabilities.sessionId == task.sessionId &&
            retainedResources.accountScope == identity.accountScope) { "generation_binding_mismatch" }
        check(resources == null) { "generation_resources_already_bound" }
        resources = retainedResources
        return token.openLeaf(realOwner, task, retainedResources::database,
            retainedResources::assets, retainedResources::validateOwner, networkPolicy, isCurrentOwner)
            .also { leaf = it }
    }

    /** Single terminal transaction result. Neither stop nor a binding failure can replace this
     * with Rejected. Report Unknown if the transaction's success could not be established.
     */
    @Synchronized fun recordPersistence(
        receiptIdentity: GenerationPreparationIdentity,
        receipt: GenerationPersistenceReceipt,
    ) {
        require(receiptIdentity == identity) { "generation_receipt_identity_mismatch" }
        check(persistence == null) { "generation_receipt_already_recorded" }
        if (receipt is GenerationPersistenceReceipt.Accepted)
            require(receipt.conversation.id == identity.conversationId) { "generation_receipt_identity_mismatch" }
        persistence = receipt
    }

    /** Call on UI under the SAME parent lock as the matching editor CAS/removal. The returned
     * receipt authorizes only the frozen revision/text/ordered attachments, never a newer draft.
     */
    @Synchronized fun consumeAccepted(
        currentEditor: GenerationEditorSnapshot,
        currentIdentity: GenerationPreparationIdentity,
    ): GenerationPersistenceReceipt.Accepted? {
        val accepted = persistence as? GenerationPersistenceReceipt.Accepted ?: return null
        if (!ownerValidated || receiptConsumed || currentIdentity != identity ||
            !token.preview.editor.matches(currentEditor)) return null
        receiptConsumed = true
        return accepted
    }

    /** Synchronous cheap revocation first. Schedule cleanup separately; never close under UI or
     * controller locks. Queue failure leaves this session visibly pending and strongly retained.
     */
    fun revokeAdmission() {
        synchronized(this) { stopped = true; cleanupPending = true }
        token.revokeAdmission()
    }

    /** Worker-only, not on the send/HTTP executor. Request stop of the original parent Runner
     * before token close; neither callback runs under this session's monitor. Even if stop fails,
     * attempt token close, but never claim Drained. Both drains must succeed before retirement.
     * Unknown is sticky and intentionally non-retriable. ParentDrain must include catalog,
     * runner AND receipt-persistence completion, not merely an HTTP cancellation acknowledgement.
     */
    internal fun cleanupOnWorker(timeoutMillis: Long) {
        require(timeoutMillis >= 0)
        revokeAdmission()
        synchronized(this) {
            if (cleanupRunning || cleanupResult != null) return
            cleanupRunning = true
        }
        var result: GenerationToolsCloseResult = GenerationToolsCloseResult.Unknown("generation_cleanup_failed")
        try {
            result = try {
                try { parentStop() }
                finally { token.close() }
                val own = token.awaitClose(timeoutMillis)
                val parent = parentDrain(timeoutMillis)
                when {
                    own is GenerationToolsCloseResult.Unknown -> own
                    parent is GenerationToolsCloseResult.Unknown -> parent
                    else -> GenerationToolsCloseResult.Drained
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                GenerationToolsCloseResult.Unknown("generation_cleanup_interrupted")
            } catch (_: Exception) {
                GenerationToolsCloseResult.Unknown("generation_cleanup_failed")
            }
        } finally {
            // Even a fatal worker failure publishes a terminal observation before notification.
            synchronized(this) { cleanupResult = result; cleanupRunning = false }
        }
    }
}

/** Dedicated bounded cleanup lane, NEVER shared with paid HTTP or the send executor.
 * AbortPolicy means saturation cannot run blocking close on the caller. Failed offers retain
 * session.cleanupRequested; the parent MUST keep its slot and retry notification later.
 * close only stops accepting work: queued/active cleanup is NOT discarded or force-interrupted.
 */
internal class ControllerGenerationCleanupQueue(
    workers: Int = 2,
    capacity: Int = 8,
    private val timeoutMillis: Long = 5_000,
) : AutoCloseable {
    init { require(workers in 1..4 && capacity in 1..64 && timeoutMillis >= 0) }
    private val queued = mutableSetOf<ControllerGenerationSession>()
    private val ids = AtomicInteger()
    private val executor = ThreadPoolExecutor(workers, workers, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue<Runnable>(capacity), { task ->
            Thread(task, "generation-cleanup-${ids.incrementAndGet()}").apply { isDaemon = true }
        }, ThreadPoolExecutor.AbortPolicy())

    /** Only the first accepted offer owns a completion notification. Duplicate/already-terminal
     * offers return true without scheduling or notifying; rejected offers return false. The
     * worker removes its queued marker before notifying, outside all queue/session locks. Parent
     * observers may recheck retirement or retry other pending slots, but must not block this lane.
     */
    fun offer(session: ControllerGenerationSession, onFinished: () -> Unit = {}): Boolean {
        session.revokeAdmission()
        synchronized(queued) {
            if (session.closeResult != null || !queued.add(session)) return true
            try {
                executor.execute {
                    try { session.cleanupOnWorker(timeoutMillis) }
                    finally {
                        synchronized(queued) { queued.remove(session) }
                        // Notification belongs to this worker, never offer's caller. A failing
                        // parent observer must not kill the worker or strand other cleanup.
                        try { onFinished() } catch (_: Throwable) { }
                    }
                }
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                queued.remove(session)
                return false
            }
        }
        return true
    }

    override fun close() { executor.shutdown() }
}
