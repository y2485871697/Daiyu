package app.daiyu.android.storage

import android.database.sqlite.SQLiteDatabase
import app.daiyu.android.context.ContextService
import app.daiyu.android.context.DatabaseTransactions
import app.daiyu.android.domain.*
import app.daiyu.android.domain.context.ContextRevision
import app.daiyu.android.media.MessageImageAssetStore
import app.daiyu.android.media.MessageImageOwner as MediaOwner
import app.daiyu.android.media.MessageImageReference as MediaReference

/** Physical owner and displayed identity are BOTH required (03 supplements may be virtual).
 * The revision covers all authoritative rows, never only the visible page. */
internal data class MessageActionTarget(val conversationId: ConversationId, val messageId: MessageId,
    val ownerId: MessageId, val runId: RunId, val role: String, val revision: String,
    val assistantId: String?, val toolCallId: ToolCallId? = null, val segmentIndex: Int = 0)
internal data class MessageActionSnapshot(val conversationId: ConversationId, val revision: String,
    val targets: Map<String, MessageActionTarget>, val steps: Map<String, MessageActionTarget> = emptyMap(),
    val rowRevisions: Map<String, String> = emptyMap(),
    val renderedTargets: Map<String, MessageActionTarget> = emptyMap()) {
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
    private val cancelled: () -> Boolean = { false },
    private val draftImages: app.daiyu.android.media.ImageAttachmentStore? = null) {
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
        val renderedTargets = linkedMapOf<String, MessageActionTarget>()
        val counts = mutableMapOf<MessageId, Int>()
        MessageHistory.expandWithSources(history).forEach { entry ->
            val row = entry.message
            val segment = counts[row.id] ?: 0
            counts[row.id] = segment + 1
            val owner = history[entry.sourcePhysicalIndex]
            val target = MessageActionTarget(row.conversationId, row.id, owner.id, row.runId, row.role,
                revision, binding, segmentIndex = segment)
            val key = app.daiyu.android.timeline.TimelineAnchor(row.conversationId.value, row.runId.value,
                row.id.value, segment).listKey
            renderedTargets[key] = target
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
            history.associate { it.id.value to ContextRevision.digest(listOf(it)) }, renderedTargets.toMap())
    }

    fun validate(target: MessageActionTarget) { transactions.run { authorize(it, target) } }

    data class UserEdit(val text: String, val videos: List<app.daiyu.android.media.video.MessageVideoReference>)

    fun originalUserText(target: MessageActionTarget): String = originalUserEdit(target).text

    fun originalUserEdit(target: MessageActionTarget): UserEdit = transactions.run { db ->
        val history = authorize(db, target)
        val selected = displayed(history, target)
        require(selected.role == Message.USER_ROLE) { "只能回填原用户文本" }
        val owner = history.single { it.id == target.ownerId && it.runId == target.runId }
        val videos = SQLiteMessageVideoStore.readVerified(db, owner).filter {
            it.owner.conversationId == target.conversationId.value && it.owner.messageId == target.messageId.value
        }.flatMap { it.references }
        UserEdit(selected.blocks.filterIsInstance<MessageBlock.Text>().joinToString("\n") { it.text }, videos)
    }

    fun branch(target: MessageActionTarget): Conversation {
        // Do not wrap the two-phase ContextService entry in transactions.run: safe live readers
        // reject an outer transaction, and acquiring their fence there reverses the lock order.
        val through = transactions.run { db ->
            val history = authorize(db, target)
            val prefix = MessageHistoryCut.prefix(history, MessageHistoryCut.index(history, target) + 1)
            require(prefix.isNotEmpty())
            prefix.last().id
        }
        val locator = context.branchWithStatic(target.conversationId, through, videos, staticGeneration,
            authorize = { authorize(it, target) }, cancelled = cancelled, copyAssets = ::copyImages,
            selectPrefix = { history -> MessageHistoryCut.prefix(history, MessageHistoryCut.index(history, target) + 1) })
        // Post-COMMIT presentation must not lose the already allocated UNKNOWN locator.
        return try { transactions.run { conversation(it, locator.conversationId) } }
        catch (failure: Exception) { throw app.daiyu.android.context.BranchOutcomeUnknown(locator, failure) }
    }

    /** Delete the selected displayed segment and its tail, never the preceding user question. */
    fun deleteTurn(target: MessageActionTarget) = transactions.run { db ->
        val history = authorize(db, target)
        val retained = MessageHistoryCut.prefix(history, MessageHistoryCut.index(history, target))
        context.validateActionArchives(db, target.conversationId)
        commitPrefix(db, target.conversationId, history, retained)
    }

    fun prepareRegeneration(target: MessageActionTarget, replacementText: String? = null,
        addedImages: List<app.daiyu.android.media.ImageAttachment> = emptyList()): MessageRevision = transactions.run { db ->
        require(replacementText != null || addedImages.isEmpty()) { "只能为编辑消息添加附件" }
        val history = authorize(db, target)
        val logical = MessageHistory.expand(history)
        val index = MessageHistoryCut.index(history, target)
        val userIndex = if (replacementText != null) {
            require(target.role == Message.USER_ROLE) { "只能编辑用户消息" }; index
        } else {
            require(target.role == Message.ASSISTANT_ROLE) { "请从助手答复重新生成" }
            (index - 1 downTo 0).firstOrNull { logical[it].role == Message.USER_ROLE }
                ?: error("原用户消息不存在")
        }
        val selected = logical[userIndex]
        val text = replacementText ?: selected.blocks.filterIsInstance<MessageBlock.Text>().joinToString("\n") { it.text }
        require(text.isNotBlank() || selected.blocks.any { it is MessageBlock.Image } || addedImages.isNotEmpty()) { "消息内容不能为空" }
        val blocks = if (replacementText == null) selected.blocks else buildList {
            if (text.isNotBlank()) add(MessageBlock.Text("edited-user", text))
            addAll(selected.blocks.filterNot { it is MessageBlock.Text })
        }
        val prefix = MessageHistoryCut.prefix(history, userIndex)
        // Historical tool results stay data. No prior tool session, approval or attempt is resumed.
        require(MessageHistory.expand(prefix).none { row -> row.blocks.any { it is MessageBlock.Tool && it.result == null } }) {
            "保留的历史有未确认工具结果；请先核实结果"
        }
        require(history.all { SQLiteMessageVideoStore.readVerified(db, it).isEmpty() }) {
            "重新提交原视频需要新的上传批准"
        }
        context.validateActionArchives(db, target.conversationId)
        val edited = selected.copy(blocks = blocks, protocolItems = emptyList(),
            historySegments = emptyList(), status = RunStatus.COMPLETED, error = null)
        MessageRevision(target, history, prefix, if (addedImages.isEmpty()) edited else appendEditImages(edited, addedImages))
    }

    private fun appendEditImages(user: Message, added: List<app.daiyu.android.media.ImageAttachment>): Message {
        val source = requireNotNull(draftImages) { "编辑图片存储不可用" }
        val draftOwner = added.first().owner
        require(draftOwner.conversationId == user.conversationId.value && added.all { it.owner == draftOwner })
        val original = user.blocks.filterIsInstance<MessageBlock.Image>().map { it.reference }
        val limits = app.daiyu.android.media.ImageAttachmentLimits()
        require(original.size + added.size <= limits.maxImages) { "每条消息最多8张图片" }
        require(original.sumOf { it.bytes.toLong() } + added.sumOf { it.bytes.toLong() } <= limits.maxDraftBytes) {
            "图片总大小超过限制"
        }
        val frozen = source.freeze(draftOwner, added, imageInputSupported = true, isCancelled = cancelled)
        // Publish a new immutable batch, never append into or overwrite the historical owner.
        val id = MessageId(java.util.UUID.randomUUID().toString())
        val owner = MediaOwner(user.conversationId.value, id.value)
        val oldMedia = original.map { ref -> MediaReference(MediaOwner(ref.owner.conversationId, ref.owner.messageId),
            ref.id, ref.mime, ref.bytes, ref.sha256, ref.width, ref.height) }
        val refs = oldMedia.map { it.copy(owner = owner) } + added.map { image ->
            MediaReference(owner, image.id, image.mime, image.bytes, image.sha256, image.width, image.height)
        }
        require(refs.map { it.id }.distinct().size == refs.size)
        val contents = oldMedia.map { images.resolver.read(it.owner, it) } + frozen.map { it.bytes() }
        check(!cancelled())
        images.restore(owner, refs, contents)
        val domainOwner = MessageImageOwner(owner.conversationId, owner.messageId)
        val newBlocks = user.blocks.map { block -> if (block is MessageBlock.Image)
            MessageBlock.Image(block.reference.copy(owner = domainOwner)) else block } + added.map { image ->
            MessageBlock.Image(MessageImageReference(domainOwner, image.id, image.mime, image.bytes,
                image.sha256, image.width, image.height))
        }
        return user.copy(id = id, blocks = newBlocks).also(MessageImages::validate)
    }

    /** Called inside the existing send transaction after provider preflight and original-history CAS. */
    fun commitRevision(db: SQLiteDatabase, revision: MessageRevision) {
        check(db.inTransaction())
        val history = authorize(db, revision.target)
        check(history == revision.original)
        commitPrefix(db, revision.target.conversationId, history, revision.prefix, revision.user.id)
    }

    private fun commitPrefix(db: SQLiteDatabase, id: ConversationId, history: List<Message>, retained: List<Message>,
        preservedUser: MessageId? = null) {
        val byId = retained.associateBy { it.id }
        val removed = history.filter { it.id !in byId }
        val inputs = history.associate { it.id to SQLiteMessageVideoStore.readVerified(db, it) }
        app.daiyu.android.backup.MessageGenerationCut.retain(db, id, history, retained)
        UsageStatisticsStore.retireMessages(db, removed.filter { it.id != preservedUser })
        removed.forEach { row ->
            db.delete("neutral_assets", "message_id=?", arrayOf(row.id.value))
            check(db.delete("messages", "id=? AND conversation_id=? AND run_id=?",
                arrayOf(row.id.value, row.conversationId.value, row.runId.value)) == 1) { "删除回执未确认" }
        }
        retained.forEach { row ->
            val before = history.single { it.id == row.id }
            if (row != before) {
                val videos = inputs.getValue(row.id).filter { input ->
                    MessageHistory.expand(listOf(row)).any { it.role == Message.USER_ROLE && it.id.value == input.owner.messageId }
                }
                check(db.update("messages", ContextService.messageValues(row), "id=? AND conversation_id=?",
                    arrayOf(row.id.value, id.value)) == 1)
                SQLiteMessageVideoStore.reviseKnown(db, before, row, inputs.getValue(row.id), videos)
            }
        }
        context.retainActionArchives(db, id, retained)
        check(ContextService.read(db, id) == retained) { "修订后历史回执不一致" }
        retained.forEach { SQLiteMessageVideoStore.readVerified(db, it) }
        SQLiteMessageVideoStore.assertNoOrphans(db)
        app.daiyu.android.backup.MessageGenerationCut.verify(db, id, retained)
        check(db.update("conversations", android.content.ContentValues().apply { put("updated", System.currentTimeMillis()) },
            "id=?", arrayOf(id.value)) == 1)
        CloudContextUsageStore.invalidate(db, id)
    }

    fun rejectRetry(target: MessageActionTarget): Nothing = transactions.run { db ->
        val history = authorize(db, target)
        val call = requireNotNull(target.toolCallId) { "工具步骤身份缺失" }
        val owner = history.single { it.id == target.ownerId && it.runId == target.runId }
        val blocks = if (owner.historySegments.isEmpty()) owner.blocks else
            owner.historySegments.filter { it.role == Message.ASSISTANT_ROLE }.flatMap { it.blocks }
        require(blocks.filterIsInstance<MessageBlock.Tool>().count { it.invocation.id == call } == 1) { "工具步骤所有者不匹配" }
        throw IllegalArgumentException("此步骤不支持重试，请核实结果后新建请求")
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

    private fun displayed(history: List<Message>, target: MessageActionTarget): Message =
        MessageHistory.expand(history)[MessageHistoryCut.index(history, target)]

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
        "SELECT title,draft,updated,folder,pinned,assistant_id,workspace_binding FROM conversations WHERE id=?", arrayOf(id.value)).use {
        check(it.moveToFirst()); Conversation(id, it.getString(0), it.getString(1), it.getLong(2), it.getString(3), it.getInt(4) != 0,
            if (it.isNull(5)) null else it.getString(5), if (it.isNull(6)) null else it.getString(6))
    }
}
