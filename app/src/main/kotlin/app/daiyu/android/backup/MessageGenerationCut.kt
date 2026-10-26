package app.daiyu.android.backup

import android.database.sqlite.SQLiteDatabase
import app.daiyu.android.domain.*
import app.daiyu.android.storage.StaticGenerationOutput
import org.json.JSONObject

/** Retire references cut out of authoritative history. Original assets and attempt tombstones stay. */
internal object MessageGenerationCut {
    data class Call(val conversation: String, val owner: String, val run: String, val id: String, val name: String)

    fun calls(history: List<Message>): Set<Call> = history.filter { it.role == Message.ASSISTANT_ROLE }.flatMap { owner ->
        val blocks = if (owner.historySegments.isEmpty()) owner.blocks else
            owner.historySegments.filter { it.role == Message.ASSISTANT_ROLE }.flatMap { it.blocks }
        blocks.filterIsInstance<MessageBlock.Tool>().map { tool ->
            Call(owner.conversationId.value, owner.id.value, owner.runId.value, tool.invocation.id.value, tool.invocation.name)
        }
    }.toSet()

    fun call(row: JSONObject) = Call(row.getString("conversation_id"), row.getString("assistant_id"),
        row.getString("run_id"), row.getString("tool_call_id"), row.getString("tool_name"))
    fun call(output: StaticGenerationOutput) = output.destination.let {
        Call(it.conversationId, it.assistantMessageId, it.runId, it.toolCallId, it.toolName)
    }

    fun retain(db: SQLiteDatabase, id: ConversationId, before: List<Message>, after: List<Message>) {
        check(db.inTransaction())
        val generation = BackupGenerationCodec.export(db, id.value)
        BackupGenerationCodec.validate(generation, staticMessageRows(before))
        val static = StaticGenerationCodec.read(db, id.value)
        static.forEach { StaticGenerationCodec.validateDestination(it, staticMessageRows(before)) }
        val retained = calls(after)
        val historical = BackupHistoryRows.history(db, "generation_output", id.value)
        val expected = BackupHistoryRows.rows(generation.getJSONArray("outputs")).filter { call(it) in retained }
        BackupHistoryRows.rows(generation.getJSONArray("outputs")).filter { call(it) !in retained }.forEach { row ->
            val args = arrayOf(row.getString("operation_id"), row.getString("item_id"), row.get("output_index").toString())
            val deleted = if (BackupGenerationCodec.key(row) in historical)
                db.delete("neutral_backup_history", "kind='generation_output' AND identity=? AND owner_message_id=?",
                    arrayOf(BackupGenerationCodec.key(row), row.getString("assistant_id")))
            else db.delete("generation_outputs", "operation_id=? AND item_id=? AND output_index=?", args)
            check(deleted == 1) { "生成引用退役回执不匹配" }
        }
        static.filter { call(it) !in retained }.forEach {
            check(db.delete("neutral_backup_history", "kind=? AND identity=?",
                arrayOf(StaticGenerationCodec.KIND, it.identity)) == 1)
        }
        val actual = BackupGenerationCodec.export(db, id.value)
        check(BackupHistoryRows.rows(actual.getJSONArray("outputs")).map { it.toString() } == expected.map { it.toString() })
        check(actual.getJSONArray("attempts").toString() == generation.getJSONArray("attempts").toString())
        check(StaticGenerationCodec.read(db, id.value) == static.filter { call(it) in retained })
        BackupGenerationCodec.validate(actual, staticMessageRows(after))
    }

    fun verify(db: SQLiteDatabase, id: ConversationId, after: List<Message>) {
        BackupGenerationCodec.validate(BackupGenerationCodec.export(db, id.value), staticMessageRows(after))
        StaticGenerationCodec.read(db, id.value)
    }
}
