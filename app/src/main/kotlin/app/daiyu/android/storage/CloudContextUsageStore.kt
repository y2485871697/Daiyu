package app.daiyu.android.storage

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import app.daiyu.android.domain.ConversationId
import app.daiyu.android.domain.runtime.RunCheckpoint
import app.daiyu.android.domain.runtime.UsageRequestSource
import app.daiyu.android.ui.model.CloudContextUsageUi
import app.daiyu.android.ui.model.ContextUsageModel
import app.daiyu.android.ui.model.acceptedContextInput
import org.json.JSONObject

/** Scoped UI receipts, not a reconstruction from billed totals or local compression estimates.
 * Kept separately from imported billing history: a backup cannot manufacture an actual-input receipt. */
internal object CloudContextUsageStore {
    fun installSchema(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS context_ui_state(conversation_id TEXT PRIMARY KEY REFERENCES conversations(id) ON DELETE CASCADE,payload TEXT NOT NULL)")
    }
    private data class Receipt(val model: ContextUsageModel, val epoch: Long, val input: Long, val requestOwner: String, val requestId: String)
    private data class State(val epoch: Long = 0, val run: String = "", val compactions: Int = 0,
        val blockedMainCount: Int = 0, val receipt: Receipt? = null)

    fun read(db: SQLiteDatabase, conversation: ConversationId, model: ContextUsageModel): CloudContextUsageUi {
        val state = load(db, conversation)
        val receipt = state.receipt?.takeIf { it.epoch == state.epoch && model.accepts(it.model) }
        return CloudContextUsageUi(receipt?.input, model.windowTokens)
    }

    /** Invoked only inside the same accepted checkpoint transaction. */
    fun record(db: SQLiteDatabase, checkpoint: RunCheckpoint, model: ContextUsageModel): Boolean {
        check(db.inTransaction())
        val conversation = checkpoint.message.conversationId
        val previous = load(db, conversation)
        val run = checkpoint.message.runId.value
        var next = if (previous.run == run) previous else previous.copy(run = run, compactions = 0, blockedMainCount = 0)
        val main = checkpoint.usage.filter { it.source == UsageRequestSource.MAIN }
        val admitted = checkpoint.usageRequests.ifEmpty { main.map { it.request } }
        val compactions = checkpoint.context?.compactions ?: 0
        if (compactions > next.compactions) {
            next = next.copy(epoch = Math.addExact(next.epoch, 1), compactions = compactions,
                blockedMainCount = admitted.size, receipt = null)
        }
        if (admitted.size > next.blockedMainCount) {
            val request = admitted.last()
            val observation = main.lastOrNull { it.request.owner == request.owner && it.request.requestId == request.requestId }
            val input = observation?.snapshot?.let { acceptedContextInput(it, model.windowTokens) }
            if (input != null) next = next.copy(receipt = Receipt(model, next.epoch, input, request.owner, request.requestId))
        }
        if (next == previous) return false
        save(db, conversation, next)
        return true
    }

    /** History edits and a successfully published summary revoke the previous actual input. */
    fun invalidate(db: SQLiteDatabase, conversation: ConversationId) {
        check(db.inTransaction())
        val old = load(db, conversation)
        save(db, conversation, old.copy(epoch = Math.addExact(old.epoch, 1), receipt = null))
    }

    private fun load(db: SQLiteDatabase, conversation: ConversationId): State {
        val raw = db.rawQuery("SELECT payload FROM context_ui_state WHERE conversation_id=?", arrayOf(conversation.value))
            .use { if (it.moveToFirst()) it.getString(0) else null } ?: return State()
        return try {
            val json = JSONObject(raw)
            require(json.getInt("version") == 1 && json.getString("source") == "cloud_actual" &&
                json.getString("conversation") == conversation.value)
            val epoch = json.getLong("epoch").also { require(it >= 0) }
            val receipt = if (json.isNull("receipt")) null else json.getJSONObject("receipt").let { r ->
                val model = ContextUsageModel(r.getString("provider"), r.getString("model"),
                    r.getLong("configuration"), if (r.isNull("window")) null else r.getLong("window"), r.getLong("registry"),
                    if (!r.has("identity") || r.isNull("identity")) null else r.getString("identity").also {
                        require(it.matches(Regex("[0-9a-f]{64}")))
                    })
                require(model.provider.isNotBlank() && model.model.isNotBlank() && model.configuration >= 0 &&
                    (model.windowTokens == null || model.windowTokens > 0))
                Receipt(model, r.getLong("epoch"), r.getLong("input"), r.getString("requestOwner"), r.getString("requestId")).also {
                    require(it.epoch == epoch && it.input >= 0 && it.requestOwner.isNotBlank() && it.requestId.isNotBlank())
                }
            }
            State(epoch, json.getString("run"), json.getInt("compactions").also { require(it >= 0) },
                json.getInt("blockedMainCount").also { require(it >= 0) }, receipt)
        } catch (_: org.json.JSONException) { State() }
        catch (_: IllegalArgumentException) { State() }
    }

    private fun save(db: SQLiteDatabase, conversation: ConversationId, state: State) {
        val receipt = state.receipt?.let { r -> JSONObject().put("provider", r.model.provider)
            .put("model", r.model.model).put("configuration", r.model.configuration)
            .put("window", r.model.windowTokens ?: JSONObject.NULL).put("registry", r.model.registryRevision)
            .put("identity", r.model.receiptIdentity ?: JSONObject.NULL)
            .put("epoch", r.epoch).put("input", r.input).put("requestOwner", r.requestOwner).put("requestId", r.requestId) }
        val json = JSONObject().put("version", 1).put("source", "cloud_actual")
            .put("conversation", conversation.value).put("epoch", state.epoch).put("run", state.run)
            .put("compactions", state.compactions).put("blockedMainCount", state.blockedMainCount)
            .put("receipt", receipt ?: JSONObject.NULL)
        db.insertWithOnConflict("context_ui_state", null, ContentValues().apply {
            put("conversation_id", conversation.value); put("payload", json.toString())
        }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) }
    }
}
