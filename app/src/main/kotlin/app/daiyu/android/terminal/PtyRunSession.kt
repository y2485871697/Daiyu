package app.daiyu.android.terminal

import app.daiyu.android.domain.ConversationId
import app.daiyu.android.domain.tools.TaskAuthorization
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Trusted host values, never deserialized from model arguments. */
class PtyRunOwner(
    val conversationId: ConversationId,
    val authorization: TaskAuthorization,
    val environmentId: String,
    val generation: String,
) {
    init {
        require(environmentId.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        require(generation.matches(Regex("[A-Za-z0-9_-]{1,128}")))
    }

    internal fun binding(storage: File): JSONObject = JSONObject()
        .put("conversation_id", conversationId.value)
        .put("session_id", authorization.sessionId).put("run_id", authorization.taskId)
        .put("environment_id", environmentId).put("generation", generation)
        .put("storage_domain", storage.canonicalPath)
}

/** One run, one host-created authorization object, one durable PTY scope.
 * Central Dispatcher still owns write approval and its authoritative tool ledger.
 * requestId and sequence come from the trusted host, not model JSON.
 */
class PtyRunSession private constructor(private val owner: PtyRunOwner, private val session: PtySession) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val lifecycle = Any()

    private fun execute(caller: TaskAuthorization, operation: () -> JSONObject): JSONObject {
        if (caller !== owner.authorization) return OperationJournal.failure("pty_run_owner_mismatch")
        if (closed.get()) return OperationJournal.failure("pty_run_closed")
        return operation()
    }

    fun read(caller: TaskAuthorization, offset: Int = 0, limit: Int = 16384): JSONObject =
        synchronized(lifecycle) { execute(caller) { session.read(offset, limit) } }

    fun write(caller: TaskAuthorization, requestId: String, inputSequence: Long, bytes: ByteArray): JSONObject =
        execute(caller) { session.write(requestId, bytes, inputSequence) }

    fun resize(caller: TaskAuthorization, requestId: String, controlSequence: Long, rows: Int, columns: Int): JSONObject =
        execute(caller) { session.resize(requestId, rows, columns, controlSequence) }

    fun interrupt(caller: TaskAuthorization, requestId: String, controlSequence: Long): JSONObject =
        execute(caller) { session.interrupt(requestId, controlSequence) }

    override fun close() {
        val stop = synchronized(lifecycle) { closed.compareAndSet(false, true) }
        if (stop) session.close()
    }

    /** Host/worker only; retain the selected Linux lease when this returns false. */
    fun awaitTermination(timeoutMillis: Long): Boolean = session.awaitTermination(timeoutMillis)

    companion object {
        /** Call on worker after freezing launch/selection and obtaining central authorization. */
        fun open(owner: PtyRunOwner, helper: File, argv: List<String>, environment: Map<String, String>, cwd: File,
                 journalDirectory: File, sessionScope: String, rows: Int = 24, columns: Int = 80): PtyRunSession =
            PtyRunSession(owner, PtySession.openBound(helper, argv, environment, cwd, journalDirectory, sessionScope,
                rows, columns, owner.binding(journalDirectory)))
    }
}
