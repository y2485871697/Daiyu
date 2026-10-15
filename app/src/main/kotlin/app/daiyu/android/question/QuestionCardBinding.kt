package app.daiyu.android.question

import android.os.Handler
import android.os.Looper
import app.daiyu.android.domain.question.QuestionAnswer
import app.daiyu.android.domain.question.QuestionException
import app.daiyu.android.domain.question.QuestionIdentity
import app.daiyu.android.domain.question.QuestionRecord
import app.daiyu.android.domain.question.QuestionRefreshException
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** A UI adapter, not a tool executor. writer must be serial and separate from any blocking Runner. */
class QuestionCardBinding(
    private val registry: QuestionRegistry,
    private val owner: QuestionIdentity,
    private val writer: Executor,
    private val ownerIsLive: () -> Boolean
) {
    private val main = Handler(Looper.getMainLooper())
    private val fenced = AtomicBoolean(false)
    private val attachment = AtomicLong()
    init { require(registry.ownerPrefix == owner.ownerPrefix) { "question_owner_mismatch" } }

    fun identity(callId: String): QuestionIdentity = owner.copy(callId = callId)
    private fun live(): Boolean = !fenced.get() && ownerIsLive()
    private fun workerOnly() { check(Looper.myLooper() != Looper.getMainLooper()) { "background_thread_required" } }

    fun attach(onRecords: (List<QuestionRecord>) -> Unit, onUnavailable: () -> Unit) {
        check(Looper.myLooper() == Looper.getMainLooper())
        val token = attachment.incrementAndGet()
        fun refresh() {
            if (attachment.get() != token) return
            try {
                writer.execute {
                    workerOnly()
                    if (attachment.get() != token) return@execute
                    val result = runCatching { registry.records().toList() }
                    main.post {
                        if (attachment.get() == token) result.fold(onRecords) { onUnavailable() }
                    }
                }
            } catch (_: Exception) { main.post { if (attachment.get() == token) onUnavailable() } }
        }
        try {
            writer.execute {
                workerOnly()
                if (attachment.get() != token) return@execute
                try { registry.barrier.attach { refresh() } }
                catch (_: Exception) { main.post { if (attachment.get() == token) onUnavailable() } }
            }
        } catch (_: Exception) { main.post { if (attachment.get() == token) onUnavailable() } }
    }

    fun detach(done: (Boolean) -> Unit = {}) {
        check(Looper.myLooper() == Looper.getMainLooper())
        attachment.incrementAndGet()
        try {
            writer.execute {
                workerOnly()
                registry.barrier.detach()
                main.post { done(true) }
            }
        } catch (_: Exception) { main.post { done(false) } }
    }

    fun submit(identity: QuestionIdentity, answer: QuestionAnswer, done: (QuestionCardView.Reply) -> Unit) {
        fun deliver(reply: QuestionCardView.Reply) { main.post { done(reply) } }
        if (identity.ownerPrefix != owner.ownerPrefix) { deliver(QuestionCardView.Reply.REJECTED); return }
        try {
            writer.execute {
                workerOnly()
                val reply = try {
                    if (!live()) QuestionCardView.Reply.STOPPED else {
                        registry.submit(identity, answer)
                        QuestionCardView.Reply.SAVED
                    }
                } catch (_: QuestionRefreshException) { QuestionCardView.Reply.SAVED_REFRESH_FAILED }
                catch (_: QuestionException) { QuestionCardView.Reply.REJECTED }
                catch (_: Exception) { QuestionCardView.Reply.UNCONFIRMED }
                deliver(reply)
            }
        } catch (_: Exception) { deliver(QuestionCardView.Reply.UNCONFIRMED) }
    }

    /** Fence new answers now; interrupt the same barrier off the control thread. Failures are retryable. */
    fun stop(done: (Boolean) -> Unit) {
        fenced.set(true)
        try {
            writer.execute {
                workerOnly()
                val committed = runCatching { registry.interrupt() }.isSuccess
                main.post { done(committed) }
            }
        } catch (_: Exception) { main.post { done(false) } }
    }
}
