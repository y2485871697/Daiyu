package app.daiyu.android.context

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import app.daiyu.android.backup.*
import app.daiyu.android.domain.*
import app.daiyu.android.domain.context.ContextRevision
import app.daiyu.android.domain.context.isHistoryClosedPrefix
import app.daiyu.android.media.StaticGenerationPublicationUnknown
import app.daiyu.android.media.StaticGenerationSettlement
import app.daiyu.android.model.videos.MessageVideoInput
import app.daiyu.android.storage.*
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.CancellationException

internal data class BranchLocator(val conversationId: ConversationId, val staticIdentities: List<String>)
internal class BranchRolledBack(cause: Throwable) : IllegalStateException("分支已确认未提交；原会话保留", cause)
internal class BranchOutcomeUnknown(val locator: BranchLocator, cause: Throwable) :
    RuntimeException("分支提交或原件发布未确认；仅刷新核实，不重试", cause)

/** The production path has no enclosing SQL transaction. T1 captures a complete scalar snapshot;
 * all original reads/copies occur after it ends; T2 does only CAS and inserts/readback. The caller
 * retains the Controller acceptance/deletion reservation, never its monitor or a reader fence. */
internal class ProductionConversationBranch(private val store: ConversationDatabase, private val blobs: PrivateBlobs,
    private val transactions: DatabaseTransactions, private val videos: BackupVideoAssets?,
    private val generation: StaticGenerationBranch?, private val cancelled: () -> Boolean) {
    private fun checkCancelled() { if (cancelled() || Thread.currentThread().isInterrupted) throw CancellationException() }
    fun branch(id: ConversationId, through: MessageId, authorize: (SQLiteDatabase) -> Unit,
        copyImages: (List<Message>, List<Message>) -> List<Message>): BranchLocator {
        val db = transactions.database()
        check(!db.inTransaction()) { "分支原件读取不能在外层SQL事务内" }
        val copy = ConversationId(UUID.randomUUID().toString())
        data class Snapshot(val history: List<Message>, val prefix: List<Message>, val mapped: List<Message>,
            val sourceValues: List<JSONObject>, val attachments: List<JSONObject>, val archives: List<JSONObject>,
            val inputs: List<List<MessageVideoInput>>, val generation: StaticGenerationBranch.Capture?)
        fun sourceValues(sql: SQLiteDatabase) = BackupHistoryRows.read(sql, "conversations",
            listOf("id", "title", "folder", "assistant_id", "pinned", "draft", "updated"), " WHERE id=?", arrayOf(id.value))
        fun attachments(sql: SQLiteDatabase) = BackupHistoryRows.read(sql, "neutral_assets",
            listOf("id", "message_id", "kind", "mime", "bytes", "blob_hash"),
            " WHERE message_id IN (SELECT id FROM messages WHERE conversation_id=?)", arrayOf(id.value))
        fun archives(sql: SQLiteDatabase) = BackupHistoryRows.read(sql, "context_archives",
            listOf("id", "conversation_id", "prefix_count", "revision", "summary", "blob_hash", "created", "active"),
            " WHERE conversation_id=?", arrayOf(id.value))
        val snapshot = transactions.run { sql ->
            checkCancelled(); authorize(sql)
            val original = ContextService.read(sql, id)
            require(original.none { it.status == RunStatus.RUNNING })
            val end = original.indexOfFirst { it.id == through } + 1
            require(end > 0 && isHistoryClosedPrefix(original, end)) { "不能分离工具批次或补充消息" }
            val prefix = original.take(end)
            require(prefix.all { it.status == RunStatus.COMPLETED }) { "只能复制已完成的历史基线" }
            require(MessageHistory.expand(prefix).all { m -> m.blocks.none { it is MessageBlock.Tool && it.result == null } })
            val runs = mutableMapOf<RunId, RunId>()
            val ids = mutableMapOf<Triple<ConversationId, RunId, MessageId>, MessageId>()
            fun remap(m: Message, old: MessageId) = ids.getOrPut(Triple(m.conversationId, m.runId, old)) { MessageId(UUID.randomUUID().toString()) }
            val mapped = prefix.map { m -> m.copy(id = remap(m, m.id), conversationId = copy,
                runId = runs.getOrPut(m.runId) { RunId(UUID.randomUUID().toString()) },
                historySegments = m.historySegments.map { it.copy(messageId = remap(m, it.messageId)) }) }
            val capture = generation?.capture(sql, prefix, mapped)
            if (generation == null) prefix.forEach { m ->
                require(!sql.rawQuery("SELECT 1 FROM generation_outputs WHERE assistant_id=?", arrayOf(m.id.value)).use { it.moveToFirst() })
                require(!BackupHistoryRows.exists(sql, "neutral_backup_history") || !sql.rawQuery(
                    "SELECT 1 FROM neutral_backup_history WHERE owner_message_id=? AND kind IN ('generation_output','generation_static_output')",
                    arrayOf(m.id.value)).use { it.moveToFirst() }) { "静态生成原件复制能力未接入" }
            }
            Snapshot(original, prefix, mapped, sourceValues(sql), attachments(sql), archives(sql),
                original.map { SQLiteMessageVideoStore.readVerified(sql, it) }, capture)
        }
        val locator = BranchLocator(copy, snapshot.generation?.outputs?.map { it.identity }.orEmpty())
        var settlement = StaticGenerationSettlement.UNKNOWN
        var attemptedT2 = false
        var rollbackConfirmed = false
        var committed = false
        try {
            // Shared 64 MiB original budget, before any asset read. No per-kind extra allowance.
            val sizes = mutableMapOf<String, Long>()
            fun reserve(hash: String, bytes: Long) {
                require(bytes in 0..NeutralBackupService.MAX_BYTES)
                val old = sizes.put(hash, bytes); require(old == null || old == bytes)
                require(sizes.values.sum() <= NeutralBackupService.MAX_BYTES)
            }
            snapshot.prefix.flatMap { it.blocks + it.historySegments.flatMap { s -> s.blocks } }
                .filterIsInstance<MessageBlock.Image>().forEach { reserve(it.reference.sha256, it.reference.bytes.toLong()) }
            snapshot.inputs.take(snapshot.prefix.size).flatten().flatMap { it.references }.forEach { reserve(it.sha256, it.bytes) }
            snapshot.generation?.outputs?.forEach { reserve(it.source.sha256, it.source.bytes) }
            snapshot.attachments.filter { row -> snapshot.prefix.any { it.id.value == row.getString("message_id") } }
                .forEach { reserve(it.getString("blob_hash"), it.getLong("bytes")) }
            // Archives are not copied, but validating their immutable source preserves the old gate.
            snapshot.archives.forEach { row ->
                checkCancelled()
                val archived = ArchiveCodec.decode(blobs.read(row.getString("blob_hash")))
                MessageHistory.expand(archived)
                require(archived.all { it.conversationId == id } && ContextRevision.digest(archived) == row.getString("revision"))
            }
            snapshot.attachments.filter { row -> snapshot.prefix.any { it.id.value == row.getString("message_id") } }.forEach { row ->
                checkCancelled(); val bytes = blobs.read(row.getString("blob_hash"))
                require(bytes.size.toLong() == row.getLong("bytes"))
            }
            checkCancelled()
            val copied = copyImages(snapshot.prefix, snapshot.mapped)
            require(copied.size == snapshot.mapped.size && copied.zip(snapshot.mapped).all { (a, b) ->
                a.id == b.id && a.conversationId == b.conversationId && a.runId == b.runId && a.role == b.role &&
                    a.historySegments.map { it.messageId to it.role } == b.historySegments.map { it.messageId to it.role }
            })
            MessageHistory.expand(copied)
            val ownerMap = mutableMapOf<String, String>()
            snapshot.prefix.zip(copied).forEach { (old, new) ->
                ownerMap[old.id.value] = new.id.value
                old.historySegments.zip(new.historySegments).forEach { (a, b) -> ownerMap[a.messageId.value] = b.messageId.value }
            }
            val copiedVideos = snapshot.inputs.take(snapshot.prefix.size).mapIndexed { position, inputs ->
                inputs.map { input ->
                    checkCancelled()
                    val owner = app.daiyu.android.media.video.MessageVideoOwner(copy.value, ownerMap.getValue(input.owner.messageId))
                    val references = input.references.map { it.copy(owner = owner) }
                    val access = requireNotNull(videos) { "视频原件复制能力未接入" }
                    val contents = input.references.map { access.read(input.owner, it) }
                    access.restore(owner, references, contents)
                    MessageVideoInput(owner, references)
                }.also { MessageVideoInputCodec.encode(copied[position], it) }
            }
            snapshot.generation?.let { generation!!.prepareAndPublish(it) }
            check(!db.inTransaction()); attemptedT2 = true
            db.beginTransaction()
            var successful = false
            try {
                fun verifySource() {
                    authorize(db)
                    check(ContextService.read(db, id) == snapshot.history) { "来源历史已变化" }
                    check(sourceValues(db).map { it.toString() } == snapshot.sourceValues.map { it.toString() })
                    check(attachments(db).map { it.toString() } == snapshot.attachments.map { it.toString() })
                    check(archives(db).map { it.toString() } == snapshot.archives.map { it.toString() })
                    snapshot.history.forEachIndexed { index, message ->
                        check(SQLiteMessageVideoStore.readVerified(db, message) == snapshot.inputs[index]) { "来源视频或witness已变化" }
                    }
                    snapshot.generation?.let { generation!!.verifySource(db, it) }
                }
                verifySource(); checkCancelled()
                val source = snapshot.sourceValues.single()
                val values = ContentValues().apply {
                    put("id", copy.value); put("title", source.getString("title") + " · 分支")
                    if (source.isNull("folder")) putNull("folder") else put("folder", source.getString("folder"))
                    if (source.isNull("assistant_id")) putNull("assistant_id") else put("assistant_id", source.getString("assistant_id"))
                    put("pinned", 0); put("draft", ""); put("updated", System.currentTimeMillis())
                }
                check(db.insertOrThrow("conversations", null, values) != -1L)
                copied.forEachIndexed { position, mapped ->
                    check(db.insertOrThrow("messages", null, ContextService.messageValues(mapped)) != -1L)
                    SQLiteMessageVideoStore.insert(db, mapped, copiedVideos[position])
                    snapshot.attachments.filter { it.getString("message_id") == snapshot.prefix[position].id.value }.forEach { row ->
                        check(db.insertOrThrow("neutral_assets", null, ContentValues().apply {
                            put("id", UUID.randomUUID().toString()); put("message_id", mapped.id.value); put("kind", row.getString("kind"))
                            put("mime", row.getString("mime")); put("bytes", row.getLong("bytes")); put("blob_hash", row.getString("blob_hash"))
                        }) != -1L)
                    }
                }
                check(db.insertOrThrow("context_branches", null, ContentValues().apply {
                    put("conversation_id", copy.value); put("parent_id", id.value); put("through_message", through.value)
                    put("parent_revision", ContextRevision.digest(snapshot.prefix))
                }) != -1L)
                snapshot.generation?.let { generation!!.save(db, it, ContextService.read(db, copy)) }
                // AFTER the last write: real physical messages, video witness, full source set,
                // exact destination static collection and absence of fabricated live receipts.
                val actual = ContextService.read(db, copy)
                check(actual == copied)
                actual.forEachIndexed { index, message -> check(SQLiteMessageVideoStore.readVerified(db, message) == copiedVideos[index]) }
                values.keySet().forEach { key ->
                    check(db.rawQuery("SELECT 1 FROM conversations WHERE id=? AND $key IS ?",
                        arrayOf(copy.value, values.getAsString(key))).use { it.moveToFirst() })
                }
                check(db.rawQuery("SELECT 1 FROM context_branches WHERE conversation_id=? AND parent_id=? AND through_message=? AND parent_revision=?",
                    arrayOf(copy.value, id.value, through.value, ContextRevision.digest(snapshot.prefix))).use { it.moveToFirst() })
                val expectedAssets = snapshot.attachments.filter { row -> snapshot.prefix.any { it.id.value == row.getString("message_id") } }
                    .map { row -> listOf(ownerMap.getValue(row.getString("message_id")), row.getString("kind"), row.getString("mime"), row.getLong("bytes").toString(), row.getString("blob_hash")) }
                val actualAssets = BackupHistoryRows.read(db, "neutral_assets", listOf("message_id", "kind", "mime", "bytes", "blob_hash"),
                    " WHERE message_id IN (SELECT id FROM messages WHERE conversation_id=?)", arrayOf(copy.value))
                    .map { row -> listOf(row.getString("message_id"), row.getString("kind"), row.getString("mime"), row.getLong("bytes").toString(), row.getString("blob_hash")) }
                check(actualAssets.groupingBy { it }.eachCount() == expectedAssets.groupingBy { it }.eachCount())
                snapshot.generation?.let { generation!!.verifyDestination(db, it, actual) }
                verifySource(); checkCancelled()
                db.setTransactionSuccessful(); successful = true
            } finally {
                // A thrown endTransaction is UNKNOWN, even if a prior body error looked benign.
                db.endTransaction()
                if (successful) committed = true else rollbackConfirmed = true
            }
            settlement = StaticGenerationSettlement.COMMITTED
            return locator
        } catch (failure: Throwable) {
            val publicationUnknown = failure is StaticGenerationPublicationUnknown
            val absent = !committed && (!attemptedT2 || rollbackConfirmed) && !publicationUnknown && runCatching {
                check(!db.inTransaction())
                transactions.run { sql ->
                    !sql.rawQuery("SELECT 1 FROM conversations WHERE id=?", arrayOf(copy.value)).use { it.moveToFirst() } &&
                        locator.staticIdentities.all { StaticGenerationCodec.find(sql, it) == null }
                }
            }.getOrDefault(false)
            if (absent) {
                settlement = StaticGenerationSettlement.ROLLED_BACK_UNREFERENCED
                if (failure is IllegalArgumentException) throw failure
                throw BranchRolledBack(failure)
            }
            throw BranchOutcomeUnknown(locator, failure)
        } finally {
            // No SQL/reader/file lock is retained here. A confirmed commit ignores late cancel.
            snapshot.generation?.let { generation!!.settle(it, settlement) }
        }
    }
}
