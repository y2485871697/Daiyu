package app.daiyu.android.storage

import android.database.sqlite.SQLiteDatabase
import app.daiyu.android.context.ContextService
import app.daiyu.android.context.DatabaseTransactions
import app.daiyu.android.domain.*
import app.daiyu.android.domain.context.ContextRevision
import app.daiyu.android.domain.context.isHistoryClosedPrefix
import app.daiyu.android.media.MessageImageAssetStore
import app.daiyu.android.media.MessageImageOwner as MediaOwner
import app.daiyu.android.media.MessageImageReference as MediaReference

/** Physical owner and displayed identity are BOTH required (03 supplements may be virtual).
 * The revision covers all authoritative rows, never only the visible page. */
internal data class MessageActionTarget(val conversationId: ConversationId, val messageId: MessageId,
    val ownerId: MessageId, val runId: RunId, val role: String, val revision: String,
    val assistantId: String?, val toolCallId: ToolCallId? = null)
internal data class MessageActionSnapshot(val conversationId: ConversationId, val revision: String,
    val targets: Map<String, MessageActionTarget>, val steps: Map<String, MessageActionTarget> = emptyMap(),
    val rowRevisions: Map<String, String> = emptyMap()) {
    fun matchesRendered(rows: List<Message>): Boolean = rows.all {
        it.conversationId == conversationId && rowRevisions[it.id.value] == ContextRevision.digest(listOf(it))
    }
}

/** Local persistence only. No provider, runner recovery, tool invocation or asset deletion.
 * Caller serializes this with controller admission. SQLite owns the final history/binding CAS.
 * Asset copies may leave private unreferenced orphans after a rollback; never delete originals
 * on an unknown commit, and never share a message-owned image directory with a branch. */
internal class MessageActionStore(private val database: ConversationDatabase,
    private val context: ContextService, private val images: MessageImageAssetStore,
    private val videos: app.daiyu.android.backup.BackupVideoAssets? = null,
    private val staticGeneration: app.daiyu.android.backup.StaticGenerationBranch? = null,
    private val cancelled: () -> Boolean = { false }) {
    private val transactions = DatabaseTransactions { database.writableDatabase }

    fun snapshot(id: ConversationId): MessageActionSnapshot = transactions.run { db ->
        val binding = binding(db, id)
        val history = ContextService.read(db, id)
        val revision = ContextRevision.digest(history)
        val targets = linkedMapOf<String, MessageActionTarget>()
        history.forEach { owner ->
            fun add(id: MessageId, role: String) {
                val target = MessageActionTarget(owner.conversationId, id, owner.id, owner.runId, role, revision, binding)
                val previous = targets[id.value]
                require(previous == null || previous.runId == target.runId && previous.role == target.role) {
                    "消息身份不唯一；未开放操作"
                }
                // A standalone supplement is resolved to its authoritative segment owner.
                if (previous == null || owner.historySegments.isNotEmpty()) targets[id.value] = target
            }
            add(owner.id, owner.role)
            owner.historySegments.filter { it.role == Message.USER_ROLE }.forEach { add(it.messageId, it.role) }
        }
        val steps = linkedMapOf<String, MessageActionTarget>()
        val ambiguousSteps = mutableSetOf<String>()
        history.filter { it.role == Message.ASSISTANT_ROLE }.forEach { owner ->
            val blocks = if (owner.historySegments.isEmpty()) owner.blocks else
                owner.historySegments.filter { it.role == Message.ASSISTANT_ROLE }.flatMap { it.blocks }
            blocks.filterIsInstance<MessageBlock.Tool>().forEach { tool ->
                val key = tool.invocation.id.value
                if (key in steps) { steps.remove(key); ambiguousSteps.add(key) }
                else if (key !in ambiguousSteps) steps[key] = targets.getValue(owner.id.value).copy(toolCallId = tool.invocation.id)
            }
        }
        MessageActionSnapshot(id, revision, targets.toMap(), steps.toMap(),
            history.associate { it.id.value to ContextRevision.digest(listOf(it)) })
    }

    fun validate(target: MessageActionTarget) { transactions.run { authorize(it, target) } }

    fun originalUserText(target: MessageActionTarget): String = transactions.run { db ->
        val history = authorize(db, target)
        val selected = displayed(history, target)
        require(selected.role == Message.USER_ROLE) { "只能回填原用户文本" }
        selected.blocks.filterIsInstance<MessageBlock.Text>().joinToString("\n") { it.text }
            .also { require(it.isNotBlank()) { "该用户消息没有可编辑文本；附件保持原样" } }
    }

    fun branch(target: MessageActionTarget): Conversation {
        // Do not wrap the two-phase ContextService entry in transactions.run: safe live readers
        // reject an outer transaction, and acquiring their fence there reverses the lock order.
        val through = transactions.run { db ->
            val history = authorize(db, target)
            val end = history.indexOfLast { it.runId == target.runId } + 1
            require(end > 0 && isHistoryClosedPrefix(history, end)) { "不能分离工具批次或补充消息" }
            require(history.take(end).all { it.status == RunStatus.COMPLETED }) { "只能复制已完成的历史基线" }
            history[end - 1].id
        }
        val locator = context.branchWithStatic(target.conversationId, through, videos, staticGeneration,
            authorize = { authorize(it, target) }, cancelled = cancelled, copyAssets = ::copyImages)
        // Post-COMMIT presentation must not lose the already allocated UNKNOWN locator.
        return try { transactions.run { conversation(it, locator.conversationId) } }
        catch (failure: Exception) { throw app.daiyu.android.context.BranchOutcomeUnknown(locator, failure) }
    }

    /** Remove one COMPLETE physical run, not a UI fragment or an individual tool result.
     * Runtime/generation tombstones stay intact. Physical files are retained, not garbage-collected.
     * Deleting metadata and its independent video witness is one FK-protected transaction. */
    fun deleteTurn(target: MessageActionTarget) = transactions.run { db ->
        val history = authorize(db, target)
        val removed = history.filter { it.runId == target.runId }
        val retained = history.filterNot { it.runId == target.runId }
        require(removed.isNotEmpty())
        MessageHistory.expand(retained)
        require(retained.none { owner -> owner.historySegments.any { segment ->
            removed.any { it.id == segment.messageId }
        } }) { "不能删除另一所有者引用的消息" }
        history.forEach { SQLiteMessageVideoStore.readVerified(db, it) }
        UsageStatisticsStore.retireMessages(db, removed)
        removed.forEach { row ->
            db.delete("neutral_assets", "message_id=?", arrayOf(row.id.value))
            db.delete(SQLiteGenerationOutputStore.TABLE, "conversation_id=? AND assistant_id=? AND run_id=?",
                arrayOf(row.conversationId.value, row.id.value, row.runId.value))
            check(db.delete("messages", "id=? AND conversation_id=? AND run_id=?",
                arrayOf(row.id.value, row.conversationId.value, row.runId.value)) == 1) { "删除回执未确认" }
        }
        db.execSQL("UPDATE context_archives SET active=0 WHERE conversation_id=?", arrayOf(target.conversationId.value))
        // RAISE(IGNORE) can suppress UPDATE without throwing or proving deactivation.
        check(!db.rawQuery("SELECT 1 FROM context_archives WHERE conversation_id=? AND active<>0 LIMIT 1",
            arrayOf(target.conversationId.value)).use { it.moveToFirst() }) { "历史摘要失效回执未确认" }
        check(ContextService.read(db, target.conversationId) == retained) { "删除后历史回执不一致" }
        removed.forEach { row ->
            for (table in listOf("message_video_inputs", "message_video_witnesses")) {
                check(!db.rawQuery("SELECT 1 FROM $table WHERE message_id=?", arrayOf(row.id.value)).use { it.moveToFirst() })
            }
            check(!db.rawQuery("SELECT 1 FROM generation_outputs WHERE conversation_id=? AND assistant_id=?",
                arrayOf(target.conversationId.value, row.id.value)).use { it.moveToFirst() })
        }
        retained.forEach { SQLiteMessageVideoStore.readVerified(db, it) }
        SQLiteMessageVideoStore.assertNoOrphans(db)
        CloudContextUsageStore.invalidate(db, target.conversationId)
    }

    data class Regeneration(val conversation: Conversation, val text: String)

    /** Explicit fork-and-new-request, never resume an old provider attempt. Conservative contract:
     * only known completed TEXT history and a simple user/assistant target run. No tool in even
     * the old target may be rerun. More capable replay needs an audited tool-ledger contract. */
    fun prepareRegeneration(target: MessageActionTarget): Regeneration = transactions.run { db ->
        val history = authorize(db, target)
        require(target.role == Message.ASSISTANT_ROLE) { "请从助手答复重新生成" }
        val start = history.indexOfFirst { it.runId == target.runId }
        val run = history.filter { it.runId == target.runId }
        require(start >= 0 && run.size == 2 && run[0].role == Message.USER_ROLE &&
            history.getOrNull(start + 1) == run[1] &&
            run[1].id == target.ownerId && run[1].role == Message.ASSISTANT_ROLE &&
            run.all { it.historySegments.isEmpty() }) { "复杂运行没有安全重生成契约；未重放工具" }
        val baseline = history.take(start)
        require(isHistoryClosedPrefix(history, start))
        require((baseline + run).all { it.status == RunStatus.COMPLETED && it.error == null &&
            it.historySegments.isEmpty() && it.blocks.all { block -> block is MessageBlock.Text } }) {
            "历史包含附件、工具、失败或 UNKNOWN；未发起请求，请新建会话核实后发送"
        }
        (baseline + run).forEach { row ->
            require(SQLiteMessageVideoStore.readVerified(db, row).isEmpty()) { "原视频不能按纯文本重新发送" }
            require(!db.rawQuery("SELECT 1 FROM generation_outputs WHERE conversation_id=? AND assistant_id=?",
                arrayOf(target.conversationId.value, row.id.value)).use { it.moveToFirst() }) { "不能重新提交生成资产操作" }
        }
        val text = run[0].blocks.filterIsInstance<MessageBlock.Text>().joinToString("\n") { it.text }
        require(text.isNotBlank())
        val copy = if (baseline.isEmpty()) {
            // create() retains the conversation binding; it does not send anything.
            database.create(target.assistantId)
        } else conversation(db, context.branch(target.conversationId, baseline.last().id) { _, mapped ->
            // A new request uses canonical text facts, never old opaque provider continuation
            // items (which may carry old tool/request IDs even beside a text projection).
            mapped.map { it.copy(protocolItems = emptyList()) }
        })
        database.draft(copy.id, text)
        Regeneration(copy.copy(draft = text), text)
    }

    fun rejectRetry(target: MessageActionTarget): Nothing = transactions.run { db ->
        val history = authorize(db, target)
        val call = requireNotNull(target.toolCallId) { "工具步骤身份缺失" }
        val owner = history.single { it.id == target.ownerId && it.runId == target.runId }
        val blocks = if (owner.historySegments.isEmpty()) owner.blocks else
            owner.historySegments.filter { it.role == Message.ASSISTANT_ROLE }.flatMap { it.blocks }
        require(blocks.filterIsInstance<MessageBlock.Tool>().count { it.invocation.id == call } == 1) { "工具步骤所有者不匹配" }
        throw IllegalArgumentException("此步骤没有安全重试契约；UNKNOWN 或外部副作用不会自动重放，请核实结果后新建请求")
    }

    private fun authorize(db: SQLiteDatabase, target: MessageActionTarget): List<Message> {
        require(binding(db, target.conversationId) == target.assistantId) { "助手所有者已变化；请刷新" }
        val history = ContextService.read(db, target.conversationId)
        require(ContextRevision.digest(history) == target.revision) { "消息或历史版本已变化；请刷新后重试" }
        require(history.none { it.status == RunStatus.RUNNING }) { "运行中不能操作历史" }
        val owner = history.singleOrNull { it.id == target.ownerId && it.runId == target.runId && it.conversationId == target.conversationId }
        require(owner != null) { "消息所有者不匹配" }
        require(displayed(history, target).role == target.role) { "消息角色已变化" }
        return history
    }

    private fun displayed(history: List<Message>, target: MessageActionTarget): Message {
        val owner = history.single { it.id == target.ownerId && it.runId == target.runId }
        if (target.messageId == owner.id) return owner
        val segment = owner.historySegments.single { it.messageId == target.messageId && it.role == target.role }
        return owner.copy(id = segment.messageId, role = segment.role, blocks = segment.blocks,
            protocolItems = segment.protocolItems, historySegments = emptyList())
    }

    private fun copyImages(original: List<Message>, mapped: List<Message>): List<Message> {
        val ids = mutableMapOf<String, String>()
        original.zip(mapped).forEach { (old, new) ->
            ids[old.id.value] = new.id.value
            old.historySegments.zip(new.historySegments).forEach { (a, b) -> ids[a.messageId.value] = b.messageId.value }
        }
        val destination = mapped.first().conversationId.value
        val refs = original.flatMap { it.blocks + it.historySegments.flatMap { s -> s.blocks } }
            .filterIsInstance<MessageBlock.Image>().map { it.reference }.distinct()
        val replacements = refs.associateWith { ref ->
            ref.copy(owner = MessageImageOwner(destination, checkNotNull(ids[ref.owner.messageId])))
        }
        refs.groupBy { it.owner }.forEach { (owner, group) ->
            fun media(ref: MessageImageReference) = MediaReference(MediaOwner(ref.owner.conversationId, ref.owner.messageId),
                ref.id, ref.mime, ref.bytes, ref.sha256, ref.width, ref.height)
            val newRefs = group.map { media(replacements.getValue(it)) }
            val bytes = group.map { ref -> images.resolver.read(MediaOwner(owner.conversationId, owner.messageId), media(ref)) }
            images.restore(newRefs.first().owner, newRefs, bytes)
        }
        fun blocks(values: List<MessageBlock>) = values.map { if (it is MessageBlock.Image)
            MessageBlock.Image(replacements.getValue(it.reference)) else it }
        return mapped.map { it.copy(blocks = blocks(it.blocks), historySegments = it.historySegments.map { s -> s.copy(blocks = blocks(s.blocks)) }) }
    }

    private fun binding(db: SQLiteDatabase, id: ConversationId): String? = db.rawQuery(
        "SELECT assistant_id FROM conversations WHERE id=?", arrayOf(id.value)).use {
        require(it.moveToFirst()) { "会话已不存在" }; if (it.isNull(0)) null else it.getString(0)
    }
    private fun conversation(db: SQLiteDatabase, id: ConversationId): Conversation = db.rawQuery(
        "SELECT title,draft,updated,folder,pinned,assistant_id FROM conversations WHERE id=?", arrayOf(id.value)).use {
        check(it.moveToFirst()); Conversation(id, it.getString(0), it.getString(1), it.getLong(2), it.getString(3), it.getInt(4) != 0,
            if (it.isNull(5)) null else it.getString(5))
    }
}
