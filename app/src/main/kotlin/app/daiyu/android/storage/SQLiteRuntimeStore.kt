package app.daiyu.android.storage

import app.daiyu.android.diagnostics.RuntimeDiagnostics
import android.database.sqlite.SQLiteCursor
import android.database.sqlite.SQLiteDatabase
import app.daiyu.android.context.CheckpointPreservation
import app.daiyu.android.context.DatabaseTransactions
import app.daiyu.android.domain.*
import app.daiyu.android.domain.delegation.*
import app.daiyu.android.domain.runtime.*
import app.daiyu.android.domain.usage.ChildUsageRecorder
import app.daiyu.android.model.usage.SQLiteChildUsageStore
import app.daiyu.android.domain.question.ASK_USER_TOOL_NAME
import app.daiyu.android.domain.question.QuestionIdentity
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.util.UUID

/** The OS lease is held for the controller worker's entire lifetime, not just for a DB transaction. */
internal class SQLiteRuntimeStore private constructor(
    private val database: ConversationDatabase, private val path: String,
    private val file: RandomAccessFile, private val lock: FileLock,
    private val contextService: app.daiyu.android.context.ContextService?
) : AtomicSupplementRuntimeStore, PreparationRuntimeStore, AutoCloseable {
    private val processOwner = UUID.randomUUID().toString()
    private val checkpointReuse = RuntimeCheckpointReuse()
    private val checkpointJsonWriter = CheckpointJsonWriter()
    private val messageDecodeReuse = CommitMessageDecodeReuse()
    @Volatile private var contextUsageBinding: Pair<Message, app.daiyu.android.ui.model.ContextUsageModel>? = null
    @Volatile internal var contextUsageRevision = 0L; private set
    @Synchronized internal fun bindContextUsage(initial: Message, model: app.daiyu.android.ui.model.ContextUsageModel) {
        available(); check(recovered)
        contextUsageBinding = initial to model
    }
    internal fun contextUsageModel(conversation: ConversationId): app.daiyu.android.ui.model.ContextUsageModel? {
        available()
        val binding = contextUsageBinding
        return binding?.takeIf { it.first.conversationId == conversation }?.second
    }
    private val transactions = DatabaseTransactions { database.writableDatabase }
    private val preparations = app.daiyu.android.context.SQLitePreparationRuntime(transactions, processOwner)

    /** No DB write until the bound summary port claims its one actual request. */
    @Synchronized internal fun summaryPreparation(conversation: ConversationId, run: RunId, message: MessageId,
        cancellation: app.daiyu.android.context.ContextPreparationCancellation): app.daiyu.android.context.RuntimeSummaryPreparation {
        available(); check(recovered)
        return preparations.create(conversation, run, message, cancellation) { work ->
            synchronized(this) { available(); check(recovered); work() }
        }
    }
    /** Admission happens on each real auxiliary call, not cache hits or model construction. */
    @Synchronized internal fun auxiliaryUsage(initial: Message): app.daiyu.android.model.usage.AuxiliaryUsageAttempt {
        available(); check(recovered)
        val request = RequestCheckpoint(UUID.randomUUID().toString(), UUID.randomUUID().toString(), processOwner, RequestOperation.NEXT)
        return additionalUsage(initial, request, UsageRequestSource.AUXILIARY_VISION)
    }
    private fun additionalUsage(initial: Message, request: RequestCheckpoint, source: UsageRequestSource):
        app.daiyu.android.model.usage.AuxiliaryUsageAttempt {
        transactions.run { db -> app.daiyu.android.model.usage.SQLiteAdditionalUsageStore.admit(db,
            initial.conversationId.value, initial.runId.value, initial.id.value, request, source, processOwner) }
        return object : app.daiyu.android.model.usage.AuxiliaryUsageAttempt {
            private var ended = false
            private var latest: ModelEvent.Usage? = null
            override fun observe(usage: ModelEvent.Usage) = synchronized(this@SQLiteRuntimeStore) {
                available(); check(recovered && !ended)
                transactions.run { db -> app.daiyu.android.model.usage.SQLiteAdditionalUsageStore.observe(db,
                    initial.conversationId.value, initial.runId.value, initial.id.value, request,
                    source, processOwner, usage, false) }
                latest = usage
            }
            override fun close() = synchronized(this@SQLiteRuntimeStore) {
                if (!ended) {
                    available(); check(recovered)
                    transactions.run { db -> app.daiyu.android.model.usage.SQLiteAdditionalUsageStore.observe(db,
                        initial.conversationId.value, initial.runId.value, initial.id.value, request,
                        source, processOwner, latest, true) }
                    ended = true
                }
            }
        }
    }
    @Synchronized override fun preparationUsage(initial: Message): List<RequestUsageCheckpoint> {
        available(); check(recovered)
        return transactions.run { db -> preparations.seed(db, initial) }
    }
    private var compressionModel: Pair<Message, app.daiyu.android.timeline.SummaryModelIdentity?>? = null
    @Synchronized internal fun bindCompressionModel(initial: Message, model: app.daiyu.android.timeline.SummaryModelIdentity?) {
        available(); check(recovered); compressionModel = initial to model
    }
    private var recovered = false
    @Volatile private var closed = false
    private fun available() { check(!closed && lock.isValid) { "运行存储所有权已释放" } }

    private val childUsageStore = SQLiteChildUsageStore(database)
    internal val childUsage = ChildUsageRecorder { task, checkpoint ->
        childAccess { childUsageStore.record(task, checkpoint) }
    }
    private val childStore = SQLiteChildTaskStore(database)
    internal val childTasks: ChildTaskStore = object : ChildTaskStore {
        override fun enqueue(parent: RunExecutionOwner, tasks: List<ChildTaskRecord>) = childAccess { childStore.enqueue(parent, tasks) }
        override fun get(owner: ChildRunOwner) = childAccess { childStore.get(owner) }
        override fun commandReceipt(command: ChildTaskCommand) = childAccess { childStore.commandReceipt(command) }
        override fun enqueueCommand(parent: RunExecutionOwner, command: ChildTaskCommand, successor: ChildTaskRecord) =
            childAccess { childStore.enqueueCommand(parent, command, successor) }
        override fun started(task: ChildTaskRecord) = childAccess { childStore.started(task) }
        override fun selected(task: ChildTaskRecord, display: ChildTaskDisplay?) = childAccess { childStore.selected(task, display) }
        override fun finished(task: ChildTaskRecord, status: ChildTaskStatus, text: String, detail: String?) =
            childAccess { childStore.finished(task, status, text, detail) }
        override fun list(conversationId: String, limit: Int) = childAccess { childStore.list(conversationId, limit) }
    }
    @Synchronized private fun <T> childAccess(work: () -> T): T {
        available(); check(recovered); return work()
    }

    @Synchronized fun recoverPending(beforeQuestionRecovery: ((QuestionIdentity) -> Unit)? = null) {
        available(); check(!recovered) { "运行恢复只能在取得所有权后执行一次" }
        val priors = recoverable(database.readableDatabase)
        priors.forEach { prepareQuestionRecovery(it, beforeQuestionRecovery) }
        transactions.run { db ->
            check(recoverable(db) == priors) { "恢复准备后检查点已变化" }
            priors.forEach { reconcile(db, it) }
            preparations.recover(db)
            app.daiyu.android.model.usage.SQLiteAdditionalUsageStore.recover(db, processOwner)
            database.recoverUncheckpointed(db)
            SQLiteChildTaskStore.recoverPending(db)
        }
        recovered = true
    }
    @Synchronized override fun commitCheckpoint(checkpoint: RunCheckpoint) = commit(checkpoint, null)
    @Synchronized override fun commitSupplement(checkpoint: RunCheckpoint, supplement: Message) {
        require(checkpoint.phase == ExecutionPhase.PAUSED || checkpoint.phase == ExecutionPhase.BOUNDARY)
        require(checkpoint.supplements.count { it.id == supplement.id } == 1 &&
            checkpoint.supplements.single { it.id == supplement.id } == supplement)
        commit(checkpoint, supplement)
    }
    private fun commit(input: RunCheckpoint, supplement: Message?) {
        available(); check(recovered)
        val checkpoint = RuntimeDiagnostics.measure(RuntimeDiagnostics.Stage.CHECKPOINT_FREEZE, input.revision) { CheckpointPreservation.freeze(input) }
        require(checkpoint.usage.all { it.source == UsageRequestSource.MAIN || it.source == UsageRequestSource.SUMMARY }) {
            "additional_usage_must_not_modify_parent_checkpoint"
        }
        var contextChanged = false
        try {
            val encoded = RuntimeDiagnostics.measure(RuntimeDiagnostics.Stage.CHECKPOINT_ENCODE, checkpoint.revision) {
                RunCheckpointCodec.encodeUtf8(checkpoint, checkpointJsonWriter,
                    historyReceipts = messageDecodeReuse.historyReceipts)
            }
            RuntimeDiagnostics.measure(RuntimeDiagnostics.Stage.CHECKPOINT_TRANSACTION, checkpoint.revision, encoded.size.toLong()) {
            transactions.run { db ->
                val decodeReuse = messageDecodeReuse
                val m = checkpoint.message
                val prior = db.rawQueryWithFactory({ _, driver, table, query ->
                    query.bindBlob(1, checkpointReuse.expectedPayload(m.id.value))
                    query.bindString(2, m.id.value)
                    SQLiteCursor(driver, table, query)
                }, "SELECT owner,revision,phase,process_owner,conversation_id,run_id,CAST(payload AS BLOB)=CAST(? AS BLOB) FROM runtime_checkpoints WHERE message_id=?",
                    null, "runtime_checkpoints").use { c -> if (!c.moveToFirst()) null else {
                    require(c.getType(1) == android.database.Cursor.FIELD_TYPE_INTEGER)
                    check(c.getString(0) == checkpoint.owner && c.getLong(1) == checkpoint.revision - 1 &&
                        c.getString(2) != ExecutionPhase.TERMINAL.name && c.getString(3) == processOwner &&
                        c.getString(4) == m.conversationId.value && c.getString(5) == m.runId.value) { "拒绝过期或异主检查点" }
                    val stamp = RuntimeCheckpointStamp(m.id.value, c.getString(4), c.getString(5), c.getString(0),
                        c.getString(3), c.getLong(1), c.getString(2))
                    stamp to checkpointReuse.preservation(stamp, c.getInt(6) == 1)
                } }
                if (prior == null) {
                    check(checkpoint.revision == 1L) { "首个检查点版本必须为1" }
                    preparations.transfer(db, checkpoint)
                } else {
                    val preserved = prior.second
                    if (preserved != null) preparations.preserve(preserved, checkpoint)
                    else preparations.preserve(requireNotNull(read(db, m.id.value)).value, checkpoint)
                }
                val persisted = database.runtimeMessage(db, m, decodeReuse)
                check(persisted.status == RunStatus.RUNNING) { "消息已终结，不允许接管运行" }
                if (supplement != null) database.insertRuntimeSupplement(db, m, supplement)
                writeCheckpoint(db, checkpoint, encoded, prior?.first)
                database.writeRuntimeMessage(db, m, decodeReuse, checkpointJsonWriter)
                app.daiyu.android.timeline.ChatTimelineStore.contextLimit(checkpoint)?.let {
                    app.daiyu.android.timeline.ChatTimelineStore.write(db, it)
                }
                val boundContext = contextUsageBinding?.takeIf { it.first.id == m.id && it.first.runId == m.runId &&
                    it.first.conversationId == m.conversationId }
                ConversationUsageStore.record(db, checkpoint, boundContext?.second?.windowTokens)
                boundContext?.let { (_, model) ->
                    contextChanged = CloudContextUsageStore.record(db, checkpoint, model)
                }
                val summaryIdentity = compressionModel?.takeIf { it.first.id == m.id && it.first.runId == m.runId &&
                    it.first.conversationId == m.conversationId }?.second
                contextService?.publishRunCompression(db, checkpoint, summaryIdentity)
                if (checkpoint.phase == ExecutionPhase.TERMINAL) contextService?.publishRunArchive(db, checkpoint)
            }
            }
            checkpointReuse.committed(checkpoint, processOwner, encoded)
            if (checkpoint.phase == ExecutionPhase.TERMINAL) {
                checkpointJsonWriter.clear()
                messageDecodeReuse.clear()
            }
        } catch (failure: Throwable) {
            checkpointReuse.clear()
            checkpointJsonWriter.clear()
            messageDecodeReuse.clear()
            throw failure
        }
        if (contextChanged) contextUsageRevision++
    }
    @Synchronized override fun readCommittedCheckpoint(checkpoint: RunCheckpoint): RunCheckpoint? {
        available(); check(recovered)
        return transactions.run { db ->
            val prior = read(db, checkpoint.message.id.value) ?: return@run null
            sameIdentity(prior.value.message, checkpoint.message)
            check(prior.processOwner == processOwner && prior.value.owner == checkpoint.owner &&
                prior.value.revision <= checkpoint.revision) { "拒绝异主回执读取" }
            check(database.runtimeMessage(db, prior.value.message) == prior.value.message) { "消息与检查点不一致" }
            prior.value
        }
    }
    /** Only a new OS lease may recover a previous lease's rows. Caller state is never trusted as a write. */
    @Synchronized override fun reconcileCheckpoint(checkpoint: RunCheckpoint): RunCheckpoint =
        reconcileCheckpoint(checkpoint, null)

    @Synchronized fun reconcileCheckpoint(checkpoint: RunCheckpoint,
        beforeQuestionRecovery: ((QuestionIdentity) -> Unit)?): RunCheckpoint {
        available()
        val prior = requireNotNull(read(database.readableDatabase, checkpoint.message.id.value)) { "检查点不存在" }
        sameIdentity(prior.value.message, checkpoint.message)
        prepareQuestionRecovery(prior, beforeQuestionRecovery)
        return transactions.run { db ->
            check(read(db, checkpoint.message.id.value) == prior) { "恢复准备后检查点已变化" }
            reconcile(db, prior)
        }
    }
    private data class Stored(val value: RunCheckpoint, val processOwner: String, val recoverySourceOwner: String? = null)
    private fun recoverable(db: SQLiteDatabase): List<Stored> {
        val ids = db.rawQuery(
            "SELECT message_id FROM runtime_checkpoints WHERE phase!='TERMINAL' ORDER BY message_id", null
        ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
        return ids.map { requireNotNull(read(db, it)) }
    }
    private fun prepareQuestionRecovery(prior: Stored, beforeQuestionRecovery: ((QuestionIdentity) -> Unit)?) {
        val saved = prior.value
        if (saved.phase == ExecutionPhase.TERMINAL) return
        check(prior.processOwner != processOwner) { "活跃所有者不能执行死亡恢复" }
        val question = saved.tools.firstOrNull {
            it.invocation.name == ASK_USER_TOOL_NAME && it.disposition == ToolDisposition.IN_FLIGHT
        } ?: return
        val identity = QuestionIdentity(saved.message.conversationId.value, saved.message.runId.value,
            saved.owner, question.invocation.id.value)
        checkNotNull(beforeQuestionRecovery) { "恢复提问前必须确认旧作用域已持久中断" }.invoke(identity)
    }
    private fun reconcile(db: SQLiteDatabase, prior: Stored): RunCheckpoint {
        val saved = prior.value
        val actual = database.runtimeMessage(db, saved.message)
        if (saved.phase == ExecutionPhase.TERMINAL) {
            check(actual == saved.message) { "终态消息与检查点不一致" }; return saved
        }
        check(prior.processOwner != processOwner) { "活跃所有者不能执行死亡恢复" }
        val interrupted = ProcessRecovery.interrupt(saved)
        val result = interrupted.copy(message = if (actual.status == RunStatus.RUNNING) interrupted.message else actual,
            phase = ExecutionPhase.TERMINAL, messageReconciled = true, owner = "recovery-${UUID.randomUUID()}",
            revision = saved.revision + 1, pending = null,
            tools = saved.tools.map {
                if (it.disposition == ToolDisposition.IN_FLIGHT) it.copy(disposition = ToolDisposition.UNKNOWN) else it
            })
        val encoded = RunCheckpointCodec.encodeUtf8(result, checkpointJsonWriter, saved.owner)
        if (actual.status == RunStatus.RUNNING) database.writeRuntimeMessage(db, result.message)
        contextService?.publishRunArchive(db, result)
        writeCheckpoint(db, result, encoded, RuntimeCheckpointStamp.of(saved, prior.processOwner))
        return result
    }
    private fun sameIdentity(a: Message, b: Message) {
        require(a.id == b.id && a.conversationId == b.conversationId && a.runId == b.runId)
    }
    private fun writeCheckpoint(db: SQLiteDatabase, checkpoint: RunCheckpoint, encoded: ByteArray,
        prior: RuntimeCheckpointStamp?) {
        val columns = "message_id,conversation_id,run_id,owner,revision,phase,process_owner,payload"
        val sql = if (prior == null) "INSERT INTO runtime_checkpoints($columns) VALUES(?,?,?,?,?,?,?,CAST(? AS TEXT))"
            else "UPDATE runtime_checkpoints SET message_id=?,conversation_id=?,run_id=?,owner=?,revision=?,phase=?,process_owner=?,payload=CAST(? AS TEXT) " +
                "WHERE message_id=? AND owner=? AND revision=? AND phase!='TERMINAL' AND process_owner=?"
        db.compileStatement(sql).use { statement ->
            statement.bindString(1, checkpoint.message.id.value)
            statement.bindString(2, checkpoint.message.conversationId.value)
            statement.bindString(3, checkpoint.message.runId.value)
            statement.bindString(4, checkpoint.owner)
            statement.bindLong(5, checkpoint.revision)
            statement.bindString(6, checkpoint.phase.name)
            statement.bindString(7, processOwner)
            statement.bindBlob(8, encoded)
            if (prior == null) check(statement.executeInsert() != -1L)
            else {
                statement.bindString(9, prior.message)
                statement.bindString(10, prior.owner)
                statement.bindLong(11, prior.revision)
                statement.bindString(12, prior.process)
                check(statement.executeUpdateDelete() == 1)
            }
        }
    }
    @Synchronized override fun close() {
        if (!closed) {
            closed = true
            checkpointReuse.clear()
            checkpointJsonWriter.clear()
            messageDecodeReuse.close()
            try { lock.release() } finally { try { file.close() } finally { synchronized(leases) { leases.remove(path) } } }
        }
    }
    companion object {
        /** Read-only access to the same validated snapshot; never acquires a lease or recovers. */
        internal fun readCheckpointSnapshot(db: SQLiteDatabase, messageId: String): RunCheckpoint? =
            read(db, messageId)?.value

        internal fun readQuestionCheckpoint(db: SQLiteDatabase, messageId: String): Pair<RunCheckpoint, String?>? =
            read(db, messageId)?.let { it.value to it.recoverySourceOwner }

        private fun read(db: SQLiteDatabase, messageId: String): Stored? {
            val ownTransaction = !db.inTransaction()
            if (ownTransaction) {
                // Keep every payload chunk in one snapshot without taking a writer lock on API35+.
                if (android.os.Build.VERSION.SDK_INT >= 35) db.beginTransactionReadOnly()
                else db.beginTransactionNonExclusive()
            }
            val raw = try {
                val metadata = db.rawQuery("SELECT conversation_id,run_id,owner,revision,phase,process_owner,length(payload) FROM runtime_checkpoints WHERE message_id=?",
                    arrayOf(messageId)).use { c -> if (!c.moveToFirst()) null else {
                    require(c.getType(3) == android.database.Cursor.FIELD_TYPE_INTEGER)
                    val size = c.getLong(6); require(size in 1..(64L * 1024 * 1024))
                    listOf(c.getString(0), c.getString(1), c.getString(2), c.getLong(3).toString(), c.getString(4), c.getString(5), size.toString())
                } } ?: return null
                val payload = requireNotNull(SQLiteText.read(db, "runtime_checkpoints", "payload", "message_id", messageId))
                metadata to payload
            } finally {
                if (ownTransaction) db.endTransaction()
            }
            val (metadata, payload) = raw
            val decoded = RunCheckpointCodec.decodeWithRecovery(payload)
            val value = decoded.checkpoint
            val recoveryOwner = decoded.recoverySourceOwner
            check(value.message.id.value == messageId && value.message.conversationId.value == metadata[0] &&
                value.message.runId.value == metadata[1] && value.owner == metadata[2] &&
                value.revision.toString() == metadata[3] && value.phase.name == metadata[4]) { "检查点元数据不匹配" }
            return Stored(value, metadata[5], recoveryOwner)
        }
        private val leases = mutableSetOf<String>()
        fun acquire(database: ConversationDatabase, contextService: app.daiyu.android.context.ContextService? = null): SQLiteRuntimeStore {
            val sidecar = database.runtimeLockFile
            val path = sidecar.path
            synchronized(leases) { check(leases.add(path)) { "另一个会话控制器仍拥有运行存储" } }
            var file: RandomAccessFile? = null
            try {
                check(sidecar.parentFile!!.let { it.isDirectory || it.mkdirs() })
                file = RandomAccessFile(sidecar, "rw")
                val lock = file.channel.tryLock() ?: error("另一个进程仍拥有运行存储")
                return SQLiteRuntimeStore(database, path, file, lock, contextService)
            } catch (failure: Exception) {
                try { file?.close() } finally { synchronized(leases) { leases.remove(path) } }
                throw failure
            }
        }
        fun installSchema(db: SQLiteDatabase) {
            CloudContextUsageStore.installSchema(db)
            ConversationUsageStore.installSchema(db)
            db.execSQL("CREATE TABLE IF NOT EXISTS runtime_checkpoints(message_id TEXT PRIMARY KEY REFERENCES messages(id) ON DELETE CASCADE,conversation_id TEXT NOT NULL,run_id TEXT NOT NULL,process_owner TEXT NOT NULL,owner TEXT NOT NULL,revision INTEGER NOT NULL CHECK(revision>0),phase TEXT NOT NULL,payload TEXT NOT NULL)")
        }
    }
}
