package app.daiyu.android.domain.question

data class QuestionRecord(val callId: String, val question: UserQuestion, val answer: QuestionAnswer? = null, val interrupted: Boolean = false) {
    /** Suitable for a persistent transcript/card, including when no dialog is attached. */
    fun summary(): String = buildString {
        append(question.title).append('\n').append(question.question)
        question.options.forEach { append('\n').append(it.id).append(". ").append(it.label); if (it.description.isNotEmpty()) append(" — ").append(it.description) }
        when (val value = answer) {
            is QuestionAnswer.Selected -> append("\n已选择：").append(question.options.firstOrNull { it.id == value.optionId }?.label ?: value.optionId)
            is QuestionAnswer.Other -> append("\n其他：").append(value.text)
            is QuestionAnswer.Delegate -> append("\n用户授权：由你决定")
            null -> append(if (interrupted) "\n提问已中断，未收到答案；不会自动恢复执行" else "\n等待用户回答")
        }
        answer?.note?.let { append("\n补充：").append(it) }
    }
}

/** Implementations must durably commit before returning; create/answer are atomic.
 * A thrown answer must not commit. Scoped interruption must be idempotent so failures can be retried. */
interface QuestionStore {
    fun get(callId: String): QuestionRecord?
    fun records(): List<QuestionRecord>
    fun pending(): List<QuestionRecord> = records().filter { it.answer == null && !it.interrupted }
    fun create(record: QuestionRecord): Boolean
    fun answer(callId: String, answer: QuestionAnswer): Boolean
    fun interruptPending()
    /** Scoped implementations should atomically mark only unresolved rows with this owner prefix.
     * Default refuses nonempty scopes, avoiding accidental global interruption of another owner. */
    fun interruptPending(ownerPrefix: String) {
        require(ownerPrefix.isEmpty()) { "QuestionStore must implement scoped interruption" }
        interruptPending()
    }
}

enum class QuestionFailure { INVALID_ARGUMENTS, CALL_ID_CONFLICT, BATCH_CONFLICT, DUPLICATE_ANSWER, INVALID_ANSWER, UNKNOWN_QUESTION, EXPIRED_QUESTION, CLOSED }
class QuestionException(val failure: QuestionFailure) : IllegalStateException(failure.name)
/** The answer is already durable; only display notification failed. Never retry a different answer. */
class QuestionRefreshException(cause: Exception) : IllegalStateException("question_refresh_failed", cause)

/** Blocking execution is for the runner thread, never Android main. Detach retains the question.
 * Exactly one live barrier/registry per store owner is required: monitors are not shared across instances.
 * On process recovery/close, unresolved records become interrupted, never automatically executed.
 */
class QuestionBarrier(private val store: QuestionStore, private val ownerPrefix: String = "") : AutoCloseable {
    private fun ownedPending(): List<QuestionRecord> = store.pending().filter { it.callId.startsWith(ownerPrefix) && it.callId !in expiredInMemory }
    private val monitor = Object()
    private var display: ((QuestionRecord?) -> Unit)? = null
    private var closed = false
    // Memory cancellation is immediate; a failed durable interruption must remain retryable by close().
    private var closeCommitted = false
    private val expiredInMemory = mutableSetOf<String>()
    private val waiters = mutableMapOf<String, Int>()

    // Notifications are coalesced and serialized, never invoked under monitor. A notification is
    // considered started when the drainer claims it under monitor. Changes during that callback
    // replace the next notification, so an in-flight publish is always followed by the latest
    // state (including close's clear), never by a stale clear of a newer question. Detach/attach
    // discard notifications not yet started; they cannot revoke an already running callback.
    // Concurrent/reentrant callers enqueue without waiting for the active callback. Callback
    // failures are reported to the active drainer, after it has delivered any newer notification.
    private var notification: Pair<(QuestionRecord?) -> Unit, QuestionRecord?>? = null
    private var notifying = false

    private fun enqueueDisplay(record: QuestionRecord?) {
        display?.let { notification = it to record }
    }

    private fun drainNotifications() {
        synchronized(monitor) {
            if (notifying) return
            notifying = true
        }
        var failure: Throwable? = null
        while (true) {
            val next = synchronized(monitor) {
                val value = notification
                notification = null
                if (value == null) notifying = false
                value
            } ?: break
            try { next.first(next.second) }
            catch (error: Throwable) {
                next.second?.let { record ->
                    synchronized(monitor) { cleanupUnansweredLocked(record.callId) }?.let {
                        if (it !== error) error.addSuppressed(it)
                    }
                }
                if (failure == null) failure = error else if (failure !== error) failure.addSuppressed(error)
            }
        }
        failure?.let { throw it }
    }

    fun attach(display: (QuestionRecord?) -> Unit) {
        synchronized(monitor) {
            notification = null
            if (!closed) this.display = display
            notification = display to if (closed) null else ownedPending().firstOrNull()
        }
        drainNotifications()
    }
    fun detach() { synchronized(monitor) { display = null; notification = null } }
    fun pending(): QuestionRecord? = synchronized(monitor) { if (closed) null else ownedPending().firstOrNull() }
    fun records(): List<QuestionRecord> = synchronized(monitor) { store.records().filter { it.callId.startsWith(ownerPrefix) } }

    /** Register the entire tool batch before executing anything in it. */
    fun register(callId: String, question: UserQuestion, batchToolNames: List<String> = listOf(ASK_USER_TOOL_NAME)): QuestionRecord {
        val record = synchronized(monitor) { registerLocked(callId, question, batchToolNames) }
        try { drainNotifications() }
        catch (failure: Throwable) {
            // A register-only caller must not leave an orphan answerable after publish fails.
            // An existing execute waiter, or an answer committed by the callback, is safe.
            cleanupUnanswered(callId)?.let { if (it !== failure) failure.addSuppressed(it) }
            val answered = synchronized(monitor) { store.get(callId)?.takeIf { it.answer != null } }
            if (answered != null) return answered
            throw failure
        }
        return record
    }

    // Called under monitor; publication is deferred until execute has installed its cleanup guard.
    private fun registerLocked(callId: String, question: UserQuestion, batchToolNames: List<String>): QuestionRecord {
        if (batchToolNames != listOf(ASK_USER_TOOL_NAME)) throw QuestionException(QuestionFailure.BATCH_CONFLICT)
        try { require(callId.isNotBlank() && callId.length <= 1200 && callId.startsWith(ownerPrefix)); question.validate() }
        catch (_: IllegalArgumentException) { throw QuestionException(QuestionFailure.INVALID_ARGUMENTS) }
        if (closed) throw QuestionException(QuestionFailure.CLOSED)
        if (callId in expiredInMemory) throw QuestionException(QuestionFailure.EXPIRED_QUESTION)
        val existing = store.get(callId)
        if (existing != null) {
            if (existing.question != question) throw QuestionException(QuestionFailure.CALL_ID_CONFLICT)
            if (existing.interrupted) throw QuestionException(QuestionFailure.EXPIRED_QUESTION)
            return existing
        }
        if (ownedPending().isNotEmpty()) throw QuestionException(QuestionFailure.BATCH_CONFLICT)
        val record = QuestionRecord(callId, question.copy(options = question.options.toList()))
        if (!store.create(record)) throw QuestionException(QuestionFailure.CALL_ID_CONFLICT)
        enqueueDisplay(record)
        return record
    }

    // Caller has removed its waiter. Keep durable answers and other live waiters intact.
    private fun cleanupUnansweredLocked(callId: String): Throwable? {
        if (closed || waiters.containsKey(callId) || callId in expiredInMemory) return null
        var failure: Throwable? = null
        val answered = try { store.get(callId)?.answer != null }
        catch (error: Throwable) { failure = error; false }
        if (answered) return failure
        expiredInMemory.add(callId)
        enqueueDisplay(null)
        display = null
        try { store.interruptPending(ownerPrefix) }
        catch (error: Throwable) { if (failure == null) failure = error else if (failure !== error) failure?.addSuppressed(error) }
        finally { monitor.notifyAll() }
        return failure
    }

    private fun cleanupUnanswered(callId: String): Throwable? {
        var shouldDrain = false
        var failure = synchronized(monitor) {
            val wasExpired = callId in expiredInMemory
            val error = cleanupUnansweredLocked(callId)
            shouldDrain = !wasExpired && callId in expiredInMemory
            error
        }
        if (shouldDrain) {
            try { drainNotifications() }
            catch (error: Throwable) { if (failure == null) failure = error else if (failure !== error) failure?.addSuppressed(error) }
        }
        return failure
    }

    @Throws(InterruptedException::class)
    fun execute(callId: String, question: UserQuestion, batchToolNames: List<String> = listOf(ASK_USER_TOOL_NAME)): QuestionAnswer {
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
        synchronized(monitor) {
            registerLocked(callId, question, batchToolNames)
            waiters[callId] = (waiters[callId] ?: 0) + 1
        }
        var primary: Throwable? = null
        var answered = false
        try {
            try { drainNotifications() }
            catch (failure: Throwable) {
                // A callback can answer and then fail. The durable answer wins.
                synchronized(monitor) { store.get(callId)?.answer }?.let { answered = true; return it }
                throw failure
            }
            synchronized(monitor) {
                while (true) {
                    val record = store.get(callId) ?: throw QuestionException(QuestionFailure.UNKNOWN_QUESTION)
                    record.answer?.let { answered = true; return it }
                    if (Thread.currentThread().isInterrupted || closed || callId in expiredInMemory || record.interrupted) throw InterruptedException()
                    monitor.wait()
                }
            }
        } catch (failure: Throwable) {
            primary = failure
            throw failure
        } finally {
            synchronized(monitor) {
                val count = (waiters[callId] ?: 1) - 1
                if (count == 0) waiters.remove(callId) else waiters[callId] = count
            }
            // A known durable answer needs no unanswered cleanup or second fallible read.
            if (!answered) cleanupUnanswered(callId)?.let {
                if (primary == null) throw it else if (primary !== it) primary?.addSuppressed(it)
            }
        }
    }

    /** Exact replay is idempotent; a second, different answer or an expired question is rejected. */
    fun submit(callId: String, answer: QuestionAnswer) {
        var refreshFailure: Exception? = null
        synchronized(monitor) {
            if (closed) throw QuestionException(QuestionFailure.CLOSED)
            if (!callId.startsWith(ownerPrefix)) throw QuestionException(QuestionFailure.UNKNOWN_QUESTION)
            if (callId in expiredInMemory) throw QuestionException(QuestionFailure.EXPIRED_QUESTION)
            val record = store.get(callId) ?: throw QuestionException(QuestionFailure.UNKNOWN_QUESTION)
            if (record.interrupted) throw QuestionException(QuestionFailure.EXPIRED_QUESTION)
            if (record.answer != null) {
                if (record.answer == answer) return
                throw QuestionException(QuestionFailure.DUPLICATE_ANSWER)
            }
            if (!record.question.accepts(answer)) throw QuestionException(QuestionFailure.INVALID_ANSWER)
            if (!store.answer(callId, answer)) throw QuestionException(QuestionFailure.DUPLICATE_ANSWER)
            monitor.notifyAll()
            try { enqueueDisplay(ownedPending().firstOrNull()) }
            catch (failure: Exception) {
                // The write is durable even if reading the refresh state fails. Invalidate any
                // queued publish of the now-answered record instead of later displaying it.
                notification = null
                enqueueDisplay(null)
                refreshFailure = failure
            }
        }
        try { drainNotifications() }
        catch (failure: Exception) {
            if (refreshFailure == null) refreshFailure = failure
            else if (refreshFailure !== failure) refreshFailure?.addSuppressed(failure)
        }
        refreshFailure?.let { throw QuestionRefreshException(it) }
    }

    /** Memory shutdown always completes. If durable interruption fails, report it and retry on close.
     * The store must remain open until this method succeeds. */
    override fun close() {
        var failure: Throwable? = null
        synchronized(monitor) {
            closed = true
            enqueueDisplay(null)
            display = null
            try {
                if (!closeCommitted) {
                    store.interruptPending(ownerPrefix)
                    closeCommitted = true
                }
            } catch (error: Throwable) { failure = error }
            finally { monitor.notifyAll() }
        }
        try { drainNotifications() }
        catch (error: Throwable) { if (failure == null) failure = error else if (failure !== error) failure?.addSuppressed(error) }
        failure?.let { throw it }
    }
}
