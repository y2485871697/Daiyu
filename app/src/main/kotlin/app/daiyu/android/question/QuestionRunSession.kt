package app.daiyu.android.question

import android.os.Handler
import android.os.Looper
import app.daiyu.android.domain.ToolInvocation
import app.daiyu.android.domain.question.*
import app.daiyu.android.domain.runtime.RunExecutionOwner
import app.daiyu.android.domain.tools.BarrierOffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

sealed interface QuestionRunResult {
    val identity: QuestionIdentity
    data class Answered(override val identity: QuestionIdentity, val answer: QuestionAnswer) : QuestionRunResult
    data class Stopped(override val identity: QuestionIdentity) : QuestionRunResult
    data class Rejected(override val identity: QuestionIdentity, val reason: QuestionFailure) : QuestionRunResult
    data class Unconfirmed(override val identity: QuestionIdentity) : QuestionRunResult
}

/** Run-retained adapter, not a central ledger or Runner continuation authority.
 * writer is serial; completionWorker must be separate from writer and the Runner execution slot.
 * Callbacks run on main. A true begin accepts local scheduling only, not durable admission.
 */
class QuestionRunSession(
    private val registry: QuestionRegistry,
    private val owner: QuestionIdentity,
    private val writer: Executor,
    private val completionWorker: Executor,
    private val ownerIsLive: () -> Boolean,
    private val onFailure: (QuestionIdentity, Exception) -> Unit,
    private val executionOwner: RunExecutionOwner?,
    private val currentOffer: () -> BarrierOffer?
) {
    constructor(registry: QuestionRegistry, owner: QuestionIdentity, writer: Executor,
        completionWorker: Executor, ownerIsLive: () -> Boolean,
        onFailure: (QuestionIdentity, Exception) -> Unit) :
        this(registry, owner, writer, completionWorker, ownerIsLive, onFailure, null, { null })

    private class Work(val identity: QuestionIdentity, val onResult: (QuestionRunResult) -> Unit,
        val offer: BarrierOffer?) {
        val delivered = AtomicBoolean()
        val waiterDone = CompletableFuture<Unit>()
    }
    private val main = Handler(Looper.getMainLooper())
    private val fenced = AtomicBoolean()
    private val monitor = Any()
    private val seen = mutableSetOf<String>()
    private var active: Work? = null
    private val outstanding = mutableListOf<Work>()
    val binding = QuestionCardBinding(registry, owner, writer) {
        val work = synchronized(monitor) { active }
        if (work == null) live() else live(work)
    }
    init {
        require(registry.ownerPrefix == owner.ownerPrefix) { "question_owner_mismatch" }
        require(writer !== completionWorker) { "question_writer_must_not_wait_for_answer" }
    }
    private fun live() = !fenced.get() && ownerIsLive()
    private fun live(work: Work) = live() && (work.offer?.let { ownsCurrentOffer(it) } ?: true)

    private fun ownsCurrentOffer(offer: BarrierOffer): Boolean {
        val expected = executionOwner ?: return false
        return try {
            val context = offer.context
            offer.waitingCommitted && context.owner === expected &&
                expected.conversationId.value == owner.conversationId &&
                expected.runId.value == owner.runId && expected.ownerId == owner.ownerId &&
                context.invocation.id == offer.pending.callId && context.invocation.name == ASK_USER_TOOL_NAME &&
                context.authorization.taskId == expected.runId.value && !context.cancelled() && currentOffer() === offer
        } catch (_: Exception) { false }
    }

    /** Only the original, durably waiting offer. Never finishes the ledger or resumes Runner. */
    fun begin(offer: BarrierOffer, onPending: (QuestionRecord) -> Unit,
        onResult: (QuestionRunResult) -> Unit): Boolean {
        if (!live() || !ownsCurrentOffer(offer)) return false
        val identity = try { owner.copy(callId = offer.context.invocation.id.value) }
            catch (_: IllegalArgumentException) { return false }
        return beginInternal(identity, offer.context.invocation, listOf(ASK_USER_TOOL_NAME), onPending, onResult, offer)
    }
    private fun workerOnly() = check(Looper.myLooper() != Looper.getMainLooper()) { "background_thread_required" }

    /** Uses the provider invocation unchanged. Duplicate calls never install another consumer. */
    fun begin(identity: QuestionIdentity, invocation: ToolInvocation,
        batchToolNames: List<String> = listOf(ASK_USER_TOOL_NAME),
        onPending: (QuestionRecord) -> Unit, onResult: (QuestionRunResult) -> Unit): Boolean =
        beginInternal(identity, invocation, batchToolNames, onPending, onResult, null)

    private fun beginInternal(identity: QuestionIdentity, invocation: ToolInvocation,
        batchToolNames: List<String>, onPending: (QuestionRecord) -> Unit,
        onResult: (QuestionRunResult) -> Unit, offer: BarrierOffer?): Boolean {
        if (identity.ownerPrefix != owner.ownerPrefix || identity.callId != invocation.id.value ||
            invocation.name != ASK_USER_TOOL_NAME || batchToolNames != listOf(ASK_USER_TOOL_NAME)) return false
        val question = try { AskUserContract.parse(invocation.arguments) }
        catch (_: IllegalArgumentException) { return false }
        val work = Work(identity, onResult, offer)
        synchronized(monitor) {
            if (!live(work) || active != null || !seen.add(identity.storageKey)) return false
            active = work
            outstanding.removeAll { it.waiterDone.isDone }
            outstanding.add(work)
        }
        try {
            writer.execute {
                try {
                    workerOnly()
                    if (!live(work)) { work.waiterDone.complete(Unit); deliver(work, QuestionRunResult.Stopped(identity)); return@execute }
                    val record = registry.barrier.register(identity.storageKey, question, listOf(ASK_USER_TOOL_NAME))
                    main.post {
                        if (!work.delivered.get()) {
                            if (!live(work)) {
                                fenceAndCleanup(work)
                                deliver(work, QuestionRunResult.Stopped(identity))
                            } else {
                                try { onPending(record) }
                                catch (_: Exception) { abandon(work) }
                            }
                        }
                    }
                    try {
                        completionWorker.execute {
                            try {
                                workerOnly()
                                if (!live(work)) {
                                    fenceAndCleanup(work)
                                    deliver(work, QuestionRunResult.Stopped(identity))
                                } else deliver(work, QuestionRunResult.Answered(identity, registry.awaitAnswer(identity, question)))
                            } catch (_: InterruptedException) {
                                fenceAndCleanup(work)
                                deliver(work, QuestionRunResult.Stopped(identity))
                            } catch (failure: QuestionException) {
                                deliver(work, QuestionRunResult.Rejected(identity, failure.failure))
                            } catch (_: Exception) {
                                abandon(work)
                            } finally { work.waiterDone.complete(Unit) }
                        }
                    } catch (_: Exception) { work.waiterDone.complete(Unit); abandon(work) }
                } catch (failure: QuestionException) {
                    work.waiterDone.complete(Unit)
                    deliver(work, QuestionRunResult.Rejected(identity, failure.failure))
                } catch (_: Exception) { work.waiterDone.complete(Unit); abandon(work) }
            }
        } catch (_: Exception) { work.waiterDone.complete(Unit); abandon(work) }
        return true
    }

    private fun deliver(work: Work, result: QuestionRunResult) {
        main.post {
            if (!work.delivered.compareAndSet(false, true)) return@post
            synchronized(monitor) { if (active === work) active = null }
            val value = if (result is QuestionRunResult.Answered && !live(work))
                QuestionRunResult.Stopped(work.identity) else result
            // A callback failure is not authority to replay a question or model continuation.
            try { work.onResult(value) } catch (failure: Exception) {
                fenceAndCleanup(work)
                onFailure(work.identity, failure)
            }
        }
    }
    private fun fenceAndCleanup(work: Work) {
        fenced.set(true)
        binding.stop { committed ->
            if (!committed) onFailure(work.identity, IllegalStateException("question_cleanup_unconfirmed"))
        }
    }
    private fun abandon(work: Work) {
        fenceAndCleanup(work)
        deliver(work, QuestionRunResult.Unconfirmed(work.identity))
    }

    /** Fences begin/answers immediately. Success means durable interruption AND waiter exit.
     * Retain the injected store/executors after false; stop can be retried without reopening.
     */
    fun stop(done: (Boolean) -> Unit) {
        fenced.set(true)
        val work = synchronized(monitor) { outstanding.toList() }
        work.forEach { deliver(it, QuestionRunResult.Stopped(it.identity)) }
        binding.stop { committed ->
            if (!committed) done(false)
            else CompletableFuture.allOf(*work.map { it.waiterDone }.toTypedArray())
                .whenComplete { _, _ -> main.post { done(true) } }
        }
    }
}
