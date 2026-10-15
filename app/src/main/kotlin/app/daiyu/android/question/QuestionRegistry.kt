package app.daiyu.android.question

import android.content.Context
import android.os.Looper
import app.daiyu.android.domain.ToolInvocation
import app.daiyu.android.domain.question.*
import org.json.JSONArray
import org.json.JSONObject

/** Production tool entry. Keep one registry for the runner lifetime, across Activity detach/attach.
 * Exactly one live registry per durable store + owner scope is a caller invariant (including recovery).
 * A second registry has a separate monitor and cannot wake the first one's waiters. Close the previous
 * registry successfully before replacing it; on close failure retain the store and retry close.
 * Call-ID overload is required for production replay. No-ID entry rejects rather than inventing an
 * identity: unified dispatch must bind the provider's call ID before invoking this stateful tool.
 */
class QuestionRegistry private constructor(
    private val store: QuestionStore,
    internal val ownerPrefix: String,
    recoverOwner: Boolean,
    private val ownsStore: Boolean
) : AutoCloseable {
    /** Independent fallback backend. Construct once, not once per Activity. */
    constructor(context: Context) : this(PrivateQuestionStore(context), "", true, true)
    /** Production journal adapter: scoped recovery is explicit; caller retains database ownership.
     * owner supplies conversation/run/owner; its callId is ignored for scope selection. */
    constructor(store: QuestionStore, owner: QuestionIdentity, recoverOwner: Boolean = false) :
        this(store, owner.ownerPrefix, recoverOwner, false)
    val barrier: QuestionBarrier
    val schemas: JSONArray get() = JSONArray().put(JSONObject().put("name", ASK_USER_TOOL_NAME)
        .put("description", AskUserContract.description).put("parameters", JSONObject(AskUserContract.parametersJson))
        .put("sideEffect", "user_interaction"))
    init {
        // Reconstructing a registry is process/runner recovery, not permission to resume execution.
        if (recoverOwner) store.interruptPending(ownerPrefix)
        barrier = QuestionBarrier(store, ownerPrefix)
    }
    fun records(): List<QuestionRecord> = barrier.records()
    fun register(identity: QuestionIdentity, arguments: JSONObject, batchToolNames: List<String> = listOf(ASK_USER_TOOL_NAME)): QuestionRecord =
        register(identity.storageKey, arguments, batchToolNames)
    @Throws(InterruptedException::class)
    fun execute(identity: QuestionIdentity, name: String, arguments: JSONObject, batchToolNames: List<String> = listOf(ASK_USER_TOOL_NAME)): JSONObject =
        execute(identity.storageKey, name, arguments, batchToolNames)
    fun submit(identity: QuestionIdentity, answer: QuestionAnswer) = submit(identity.storageKey, answer)
    /** Cancel the owner without fabricating an answer; also unblocks any waiting execute. */
    fun interrupt() = barrier.close()
    fun register(callId: String, arguments: JSONObject, batchToolNames: List<String> = listOf(ASK_USER_TOOL_NAME)): QuestionRecord =
        barrier.register(callId, AskUserContract.parse(arguments.toString()), batchToolNames)

    fun execute(name: String, arguments: JSONObject): JSONObject = error(if (name == ASK_USER_TOOL_NAME) "call_id_required" else "unknown_tool")

    @Throws(InterruptedException::class)
    fun execute(callId: String, name: String, arguments: JSONObject, batchToolNames: List<String> = listOf(ASK_USER_TOOL_NAME)): JSONObject =
        executeRaw(callId, name, arguments.toString(), batchToolNames)

    @Throws(InterruptedException::class)
    fun execute(invocation: ToolInvocation, batchToolNames: List<String> = listOf(ASK_USER_TOOL_NAME)): String =
        executeRaw(invocation.id.value, invocation.name, invocation.arguments, batchToolNames).toString()

    private fun executeRaw(callId: String, name: String, arguments: String, batchToolNames: List<String>): JSONObject {
        if (name != ASK_USER_TOOL_NAME) return error("unknown_tool")
        if (Looper.myLooper() == Looper.getMainLooper()) return error("background_thread_required")
        val question = try { AskUserContract.parse(arguments) } catch (_: IllegalArgumentException) { return error("invalid_arguments") }
        return try {
            val answer = barrier.execute(callId, question, batchToolNames)
            JSONObject().put("ok", true).put("call_id", callId).put("answer", answer.json())
        } catch (failure: QuestionException) { error(failure.failure.name.lowercase()) }
        // InterruptedException intentionally propagates so a cancelled runner never continues model output.
    }
    fun submit(callId: String, answer: QuestionAnswer) = barrier.submit(callId, answer)
    override fun close() { barrier.close(); if (ownsStore) (store as? AutoCloseable)?.close() }
    private fun error(code: String): JSONObject = JSONObject().put("ok", false).put("error", code)
}
