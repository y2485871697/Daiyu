package app.daiyu.android.backup

import android.database.sqlite.SQLiteDatabase
import app.daiyu.android.context.PrivateBlobs
import app.daiyu.android.model.generation.*
import app.daiyu.android.storage.GenerationAttemptBinding
import app.daiyu.android.storage.GenerationOutputReference
import app.daiyu.android.storage.MessageHistoryCodec
import app.daiyu.android.domain.MessageBlock
import org.json.JSONArray
import org.json.JSONObject

/** Read authority only; NEVER a live generation authorization or a restore-to-live capability.
 * Host must use its existing persisted-owner gate and common backup acceptance lock. */
fun interface BackupGenerationAssets {
    fun read(reference: GenerationOutputReference): ByteArray
}

/** Deliberately NOT a registered GenerationOutputReference or a GenerationAssetReceipt.
 * Original identities describe provenance only; no method can convert this into live admission. */
data class HistoricalGenerationOutput internal constructor(internal val source: GenerationOutputReference) {
    val historicalOnly: Boolean get() = true
    val identity: GenerationIdentity get() = source.identity
    val outputIndex: Int get() = source.outputIndex
    val conversationId: String get() = source.conversationId
    val assistantMessageId: String get() = source.assistantMessageId
    val runId: String get() = source.runId
    val toolCallId: String get() = source.toolCallId
    val kind: GenerationKind get() = source.kind
    val mimeType: String get() = source.mimeType
    val bytes: Long get() = source.bytes
    val sha256: String get() = source.sha256
    val width: Int get() = source.width
    val height: Int get() = source.height
    val durationMs: Long get() = source.durationMs
    override fun toString() = "HistoricalGenerationOutput(kind=$kind, historicalOnly=true, payload=<redacted>)"
}

/** Versioned scalar projection. outcome, endpoint, headers, handles and request bodies are absent.
 * Imported references live ONLY in neutral_backup_history; live generation_outputs stays empty. */
internal object BackupGenerationCodec {
    private val attemptsColumns = listOf("operation_id", "item_id", "conversation_id", "assistant_id", "run_id",
        "tool_call_id", "tool_name", "fingerprint", "protocol")
    private val outputColumns = listOf("operation_id", "item_id", "output_index", "fingerprint", "protocol", "account_scope",
        "conversation_id", "assistant_id", "run_id", "tool_call_id", "tool_name", "receipt", "kind", "mime_type",
        "byte_count", "sha256", "width", "height", "duration_ms")
    data class Validated(val attempts: List<JSONObject>, val outputs: List<JSONObject>) {
        val references get() = outputs.map(::reference)
    }
    fun export(db: SQLiteDatabase, conversation: String?): JSONObject {
        require(BackupHistoryRows.exists(db, "generation_attempts") && BackupHistoryRows.exists(db, "generation_outputs")) {
            "生成历史备份 schema 未接入，不能声明完整导出"
        }
        val priorHistory = BackupHistoryRows.history(db, "generation_output", conversation)
        priorHistory.values.forEach { row ->
            BackupHistoryRows.verify(db, "generation_attempts", JSONObject().apply {
                attemptsColumns.forEach { put(it, row.get(it)) }; put("outcome", JSONObject.NULL)
            })
            require(!db.rawQuery("SELECT 1 FROM generation_outputs WHERE operation_id=? AND item_id=? AND output_index=?",
                arrayOf(row.getString("operation_id"), row.getString("item_id"), row.get("output_index").toString())).use { it.moveToFirst() }) {
                "恢复的生成历史不能重新绑定活动输出"
            }
        }
        val where = if (conversation == null) "" else " WHERE conversation_id=?"
        val args = conversation?.let { arrayOf(it) }
        val attempts = BackupHistoryRows.read(db, "generation_attempts", attemptsColumns, where, args)
        val outputs = linkedMapOf<String, JSONObject>()
        BackupHistoryRows.read(db, "generation_outputs", outputColumns, where, args).forEach { outputs[key(it)] = it }
        priorHistory.forEach { (id, row) ->
            if (conversation == null || row.getString("conversation_id") == conversation) {
                require(id == key(row))
                val existing = outputs.put(id, row)
                require(existing == null || BackupHistoryRows.same(existing, row)) { "生成历史与运行引用冲突" }
            }
        }
        return JSONObject().put("version", 1).put("attempts", JSONArray(attempts)).put("outputs", JSONArray(outputs.toSortedMap().values.toList()))
    }
    fun validate(value: JSONObject?, messages: JSONArray, contents: Map<String, ByteArray>? = null): Validated {
        if (value == null) return Validated(emptyList(), emptyList())
        with(BackupHistoryRows) {
            fields(value, listOf("version", "attempts", "outputs")); require(number(value, "version") == 1L)
            val owners = messageMap(messages)
            val attempts = rows(value.getJSONArray("attempts")); val outputs = rows(value.getJSONArray("outputs"))
            val byAttempt = linkedMapOf<Pair<String, String>, JSONObject>()
            attempts.forEach { row ->
                fields(row, attemptsColumns); validateIdentity(row)
                require(byAttempt.put(attemptKey(row), row) == null) { "重复生成防重身份" }
                // A cut can retire a call inside a surviving owner. Its identity remains a
                // tombstone, never an execution capability; output-bearing attempts stay exact.
                val owner = owners[id(row, "assistant_id")]
                if (owner != null && hasOwnerCall(row, owner))
                    validateOwner(row, owners)
            }
            val slots = mutableSetOf<String>(); val receipts = mutableSetOf<String>()
            outputs.forEach { row ->
                fields(row, outputColumns); validateIdentity(row); validateOwner(row, owners)
                require(slots.add(key(row)) && receipts.add(text(row, "receipt")))
                val attempt = byAttempt[attemptKey(row)] ?: error("生成引用缺少防重墓碑")
                attemptsColumns.forEach { require(row.get(it) == attempt.get(it)) { "生成引用与原尝试不一致" } }
                val r = reference(row)
                require(r.accountScope.matches(Regex("[A-Za-z0-9_.:-]{1,200}"))) { "账户范围不是本地中性标识" }
                require(r.outputIndex in 0..31 && r.bytes in 1..NeutralBackupService.MAX_BYTES &&
                    r.width > 0 && r.height > 0 && r.sha256.matches(Regex("[0-9a-f]{64}")))
                require(r.receipt.assetId.matches(Regex("genasset-v1-[0-9a-f]{64}-[0-9a-f]{64}")) && r.receipt.assetId.endsWith("-${r.sha256}"))
                when (r.kind) {
                    GenerationKind.IMAGE -> require(r.protocol != GenerationProtocol.OPENAI_VIDEO_MULTIPART &&
                        r.mimeType in setOf("image/png", "image/jpeg", "image/webp") && r.durationMs == 0L)
                    GenerationKind.VIDEO -> require(r.protocol == GenerationProtocol.OPENAI_VIDEO_MULTIPART &&
                        r.mimeType in setOf("video/mp4", "video/webm") && r.durationMs > 0L)
                }
                if (contents != null) verify(r, contents[r.sha256] ?: error("缺少生成原资产"))
            }
            historyBudget("generation_output", outputs, ::key)
            return Validated(attempts, outputs)
        }
    }
    private fun validateIdentity(row: JSONObject) = with(BackupHistoryRows) {
        val binding = GenerationAttemptBinding(id(row, "conversation_id"), id(row, "assistant_id"), id(row, "run_id"),
            id(row, "tool_call_id"), id(row, "tool_name"))
        require(text(row, "operation_id") == binding.operationId)
        binding.identity(text(row, "item_id"))
        // Production fingerprints are SHA-256; reject custom opaque/private fingerprints explicitly.
        hash(row, "fingerprint"); GenerationProtocol.valueOf(text(row, "protocol"))
    }
    private fun hasOwnerCall(row: JSONObject, message: JSONObject): Boolean {
        val history = if (message.has("history_segments")) MessageHistoryCodec.decode(message.getJSONArray("history_segments")) else emptyList()
        return if (history.isNotEmpty()) history.filter { it.role == "assistant" }.any { segment ->
            segment.blocks.filterIsInstance<MessageBlock.Tool>().any { it.invocation.id.value == row.getString("tool_call_id") }
        } else message.getJSONArray("blocks").let { blocks -> (0 until blocks.length()).any {
            blocks.getJSONObject(it).optString("type") == "tool" && blocks.getJSONObject(it).optString("id") == row.getString("tool_call_id")
        } }
    }
    private fun validateOwner(row: JSONObject, messages: Map<String, JSONObject>) = with(BackupHistoryRows) {
        owner(row, messages, "conversation_id", "assistant_id", "run_id")
        val message = messages.getValue(text(row, "assistant_id"))
        val history = if (message.has("history_segments")) MessageHistoryCodec.decode(message.getJSONArray("history_segments")) else emptyList()
        val names = if (history.isNotEmpty()) history.filter { it.role == "assistant" }.flatMap {
            require(it.messageId.value == text(row, "assistant_id")); it.blocks.filterIsInstance<MessageBlock.Tool>()
        }.filter { it.invocation.id.value == text(row, "tool_call_id") }.map { it.invocation.name }
        else {
            val blocks = message.getJSONArray("blocks")
            (0 until blocks.length()).map { blocks.getJSONObject(it) }.filter {
                it.getString("type") == "tool" && it.getString("id") == text(row, "tool_call_id")
            }.map { it.getString("name") }
        }
        require(names == listOf(text(row, "tool_name"))) { "生成历史缺少准确工具来源" }
    }
    private fun attemptKey(row: JSONObject) = row.getString("operation_id") to row.getString("item_id")
    internal fun key(row: JSONObject): String = JSONArray(listOf(row.getString("operation_id"), row.getString("item_id"), row.get("output_index"))).toString()
    fun reference(row: JSONObject): GenerationOutputReference = with(BackupHistoryRows) {
        fun int(key: String): Int = number(row, key).also { require(it in 0..Int.MAX_VALUE.toLong()) }.toInt()
        GenerationOutputReference(GenerationIdentity(text(row, "operation_id"), text(row, "item_id")), int("output_index"),
            text(row, "fingerprint"), GenerationProtocol.valueOf(text(row, "protocol")), text(row, "account_scope"),
            text(row, "conversation_id"), text(row, "assistant_id"), text(row, "run_id"), text(row, "tool_call_id"), text(row, "tool_name"),
            GenerationAssetReceipt(text(row, "receipt")), GenerationKind.valueOf(text(row, "kind")), text(row, "mime_type"),
            number(row, "byte_count"), text(row, "sha256"), int("width"), int("height"), number(row, "duration_ms"))
    }
    fun verify(reference: GenerationOutputReference, bytes: ByteArray) {
        require(bytes.size.toLong() == reference.bytes && PrivateBlobs.digest(bytes) == reference.sha256) { "生成原资产损坏或缺失" }
    }
    fun restore(db: SQLiteDatabase, value: Validated) {
        value.attempts.forEach { row ->
            // Null outcome means ALREADY_ATTEMPTED, never Started. Never restore provider handles.
            BackupHistoryRows.insert(db, "generation_attempts", row)
        }
        value.outputs.forEach { BackupHistoryRows.saveHistory(db, "generation_output", key(it), it) }
    }
    fun verifyRestored(db: SQLiteDatabase, value: Validated) {
        value.attempts.forEach { row ->
            BackupHistoryRows.verify(db, "generation_attempts", row)
            check(db.rawQuery("SELECT 1 FROM generation_attempts WHERE operation_id=? AND item_id=? AND outcome IS NULL",
                arrayOf(row.getString("operation_id"), row.getString("item_id"))).use { it.moveToFirst() }) { "导入墓碑不可携带运行授权结果" }
        }
        value.outputs.forEach { row ->
            BackupHistoryRows.verifyHistory(db, "generation_output", key(row), row)
            check(!db.rawQuery("SELECT 1 FROM generation_outputs WHERE operation_id=? AND item_id=? AND output_index=?",
                arrayOf(row.getString("operation_id"), row.getString("item_id"), row.get("output_index").toString())).use { it.moveToFirst() }) {
                "恢复的生成引用不能冒充活动授权输出"
            }
        }
    }
}
