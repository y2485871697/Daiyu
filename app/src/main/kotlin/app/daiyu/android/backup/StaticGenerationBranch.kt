package app.daiyu.android.backup

import android.database.sqlite.SQLiteDatabase
import app.daiyu.android.context.ArchiveCodec
import app.daiyu.android.domain.Message
import app.daiyu.android.media.StaticGenerationAssets
import app.daiyu.android.media.StaticGenerationOriginalReader
import app.daiyu.android.media.StaticGenerationSettlement
import app.daiyu.android.storage.*
import org.json.JSONArray
import java.io.ByteArrayInputStream

/** Host-owned adapter. Scalar capture grants no read authority: every source is opened through
 * its existing reader after T1 closes. No URL/path fallback and no live destination registration. */
internal class StaticGenerationBranch(
    private val assets: StaticGenerationAssets,
    private val account: () -> String,
    private val liveOriginal: (GenerationOutputReference) -> ByteArray,
    private val historicalOriginal: (HistoricalGenerationOutput) -> ByteArray,
    private val lookup: (String) -> StaticGenerationOutput?,
    private val cancelled: () -> Boolean = { false },
) {
    internal data class Source(val provenance: StaticGenerationProvenance,
        val live: GenerationOutputReference? = null, val historical: HistoricalGenerationOutput? = null,
        val static: StaticGenerationOutput? = null)
    internal class Capture(val conversation: String, val generation: String, val historicalKeys: Set<String>,
        val staticRows: List<StaticGenerationOutput>, val sources: List<Source>, val outputs: List<StaticGenerationOutput>) {
        val leases = mutableListOf<StaticGenerationAssets.Lease>()
    }
    fun freezeAccount(db: SQLiteDatabase): Result<String> {
        check(!db.inTransaction())
        return runCatching { account() }
    }
    fun verifyAccountAndPrepareSchema(db: SQLiteDatabase, capture: Capture) {
        check(!db.inTransaction())
        if (capture.outputs.isEmpty()) return
        val scope = account()
        check(capture.outputs.all { it.destination.accountScope == scope }) { "生成账户范围已变化" }
        if (!BackupHistoryRows.exists(db, "neutral_backup_history")) BackupHistoryRows.install(db)
    }
    fun capture(db: SQLiteDatabase, original: List<Message>, mapped: List<Message>, accountScope: Result<String>): Capture {
        val conversation = original.first().conversationId.value
        val generation = BackupGenerationCodec.export(db, conversation)
        // The full physical history (not a UI page or only the copied prefix) authorizes every
        // selected tool/run binding, including imported historical members with no live reader.
        BackupGenerationCodec.validate(generation, staticMessageRows(app.daiyu.android.context.ContextService.read(db,
            app.daiyu.android.domain.ConversationId(conversation))))
        val historical = BackupHistoryRows.history(db, "generation_output", conversation)
        val static = StaticGenerationCodec.read(db, conversation)
        val owners = original.map { it.id.value }.toSet()
        val sources = BackupHistoryRows.rows(generation.getJSONArray("outputs")).filter {
            it.getString("assistant_id") in owners
        }.map { row ->
            val reference = BackupGenerationCodec.reference(row)
            if (BackupGenerationCodec.key(row) in historical) Source(StaticGenerationProvenance.capture(reference),
                historical = HistoricalGenerationOutput(reference))
            else Source(StaticGenerationProvenance.capture(reference), live = reference)
        } + static.filter { it.destination.assistantMessageId in owners }.map { Source(it.source, static = it) }
        val byOwner = original.zip(mapped).associate { it.first.id.value to it.second }
        val scope = if (sources.isEmpty()) null else accountScope.getOrThrow()
        val outputs = sources.map { selected ->
            val oldOwner = selected.static?.destination?.assistantMessageId ?: selected.provenance.assistantMessageId
            val owner = byOwner.getValue(oldOwner)
            val call = selected.static?.destination?.toolCallId ?: selected.provenance.toolCallId
            StaticGenerationOutput(selected.provenance, StaticGenerationDestination(checkNotNull(scope),
                owner.conversationId.value, owner.id.value, owner.runId.value, call, selected.provenance.toolName))
        }
        StaticGenerationCodec.validateBatch(outputs)
        outputs.forEach { StaticGenerationCodec.validateDestination(it, staticMessageRows(mapped)) }
        return Capture(conversation, generation.toString(), historical.keys.toSet(), static, sources, outputs)
    }
    fun verifySource(db: SQLiteDatabase, capture: Capture) {
        check(BackupGenerationCodec.export(db, capture.conversation).toString() == capture.generation) { "生成来源集合已变化" }
        check(BackupHistoryRows.history(db, "generation_output", capture.conversation).keys == capture.historicalKeys)
        check(StaticGenerationCodec.read(db, capture.conversation) == capture.staticRows) { "静态来源集合已变化" }
    }
    fun prepareAndPublish(capture: Capture) {
        capture.sources.zip(capture.outputs).forEach { (selected, output) ->
            capture.leases += assets.prepare(output, StaticGenerationOriginalReader { source ->
                check(source == selected.provenance)
                when {
                    selected.live != null -> ByteArrayInputStream(liveOriginal(selected.live))
                    selected.historical != null -> ByteArrayInputStream(historicalOriginal(selected.historical))
                    else -> assets.openVerified(checkNotNull(selected.static), lookup, cancelled)
                }
            }, cancelled)
        }
        capture.leases.forEach { assets.publish(it) }
    }
    fun save(db: SQLiteDatabase, capture: Capture, messages: List<Message>) {
        check(db.inTransaction())
        if (capture.outputs.isNotEmpty()) check(BackupHistoryRows.exists(db, "neutral_backup_history"))
        capture.outputs.forEach { StaticGenerationCodec.saveInTransaction(db, it, staticMessageRows(messages)) }
    }
    fun verifyDestination(db: SQLiteDatabase, capture: Capture, messages: List<Message>) {
        check(StaticGenerationCodec.read(db, messages.first().conversationId.value).toSet() == capture.outputs.toSet())
        capture.outputs.forEach { StaticGenerationCodec.verifyInTransaction(db, it, staticMessageRows(messages)) }
        for (table in listOf("generation_outputs", "generation_attempts")) check(!db.rawQuery(
            "SELECT 1 FROM $table WHERE conversation_id=? LIMIT 1", arrayOf(messages.first().conversationId.value))
            .use { it.moveToFirst() }) { "历史分支不能制造活动生成回执或尝试" }
    }
    fun settle(capture: Capture, outcome: StaticGenerationSettlement) {
        // Cleanup errors cannot turn a confirmed COMMIT into a retryable failure. The store
        // preserves its reservation on cleanup error; UNKNOWN never grants deletion authority.
        capture.leases.forEach { lease -> runCatching { assets.settle(lease, outcome) } }
    }
}

internal fun staticMessageRows(messages: List<Message>): JSONArray = JSONArray(messages.map { message ->
    org.json.JSONObject().put("id", message.id.value).put("conversation_id", message.conversationId.value)
        .put("run_id", message.runId.value).put("role", message.role).put("blocks", ArchiveCodec.blocks(message.blocks))
        .put("history_segments", MessageHistoryCodec.encode(message.historySegments))
})
