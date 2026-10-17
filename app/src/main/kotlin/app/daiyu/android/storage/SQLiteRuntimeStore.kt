package app.daiyu.android.storage

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
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
    private val file: RandomAccessFile, private val lock: FileLock
) : AtomicSupplementRuntimeStore, PreparationRuntimeStore, AutoCloseable {
    private val processOwner = UUID.randomUUID().toString()
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
    @Synchronized override fun preparationUsage(initial: Message): List<RequestUsageCheckpoint> {
        available(); check(recovered)
        return transactions.run { db -> preparations.seed(db, initial) }
    }
    private var recovered = false
    private var closed = false
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
            database.recoverUncheckpointed(db)
            SQLiteChildTaskStore.recoverPending(db)
        }
        recovered = true
    }
    @Synchronized override fun commitCheckpoint(checkpoint: RunCheckpoint) = commit(checkpoint, null)
    @Synchronized override fun commitSupplement(checkpoint: RunCheckpoint, supplement: Message) {
        require(checkpoint.phase == ExecutionPhase.PAUSED)
        require(checkpoint.supplements.count { it.id == supplement.id } == 1 &&
            checkpoint.supplements.single { it.id == supplement.id } == supplement)
        commit(checkpoint, supplement)
    }
    private fun commit(checkpoint: RunCheckpoint, supplement: Message?) {
        available(); check(recovered)
        val encoded = RunCheckpointCodec.encode(checkpoint)
        transactions.run { db ->
            val m = checkpoint.message
            val prior = db.rawQuery("SELECT owner,revision,phase,process_owner,conversation_id,run_id FROM runtime_checkpoints WHERE message_id=?",
                arrayOf(m.id.value)).use { c -> if (!c.moveToFirst()) null else {
                check(c.getString(0) == checkpoint.owner && c.getLong(1) == checkpoint.revision - 1 &&
                    c.getString(2) != ExecutionPhase.TERMINAL.name && c.getString(3) == processOwner &&
                    c.getString(4) == m.conversationId.value && c.getString(5) == m.runId.value) { "拒绝过期或异主检查点" }
                c.getLong(1)
            } }
            if (prior == null) {
                check(checkpoint.revision == 1L) { "首个检查点版本必须为1" }
                preparations.transfer(db, checkpoint)
            } else preparations.preserve(requireNotNull(read(db, m.id.value)).value, checkpoint)
            val persisted = database.runtimeMessage(db, m)
            check(persisted.status == RunStatus.RUNNING) { "消息已终结，不允许接管运行" }
            if (supplement != null) database.insertRuntimeSupplement(db, m, supplement)
            val values = values(checkpoint, encoded)
            if (prior == null) db.insertOrThrow("runtime_checkpoints", null, values)
            else check(db.update("runtime_checkpoints", values,
                "message_id=? AND owner=? AND revision=? AND phase!='TERMINAL' AND process_owner=?",
                arrayOf(m.id.value, checkpoint.owner, prior.toString(), processOwner)) == 1)
            database.writeRuntimeMessage(db, m)
        }
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
    private data class Stored(val value: RunCheckpoint, val processOwner: String)
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
        val encoded = RunCheckpointCodec.encode(result)
        if (actual.status == RunStatus.RUNNING) database.writeRuntimeMessage(db, result.message)
        check(db.update("runtime_checkpoints", values(result, encoded), "message_id=? AND owner=? AND revision=? AND process_owner=?",
            arrayOf(saved.message.id.value, saved.owner, saved.revision.toString(), prior.processOwner)) == 1)
        return result
    }
    private fun sameIdentity(a: Message, b: Message) {
        require(a.id == b.id && a.conversationId == b.conversationId && a.runId == b.runId)
    }
    private fun values(checkpoint: RunCheckpoint, encoded: String) = ContentValues().apply {
        put("message_id", checkpoint.message.id.value); put("conversation_id", checkpoint.message.conversationId.value)
        put("run_id", checkpoint.message.runId.value); put("owner", checkpoint.owner); put("revision", checkpoint.revision)
        put("phase", checkpoint.phase.name); put("process_owner", processOwner); put("payload", encoded)
    }
    @Synchronized override fun close() {
        if (!closed) {
            closed = true
            try { lock.release() } finally { try { file.close() } finally { synchronized(leases) { leases.remove(path) } } }
        }
    }
    companion object {
        /** Read-only access to the same validated snapshot; never acquires a lease or recovers. */
        internal fun readCheckpointSnapshot(db: SQLiteDatabase, messageId: String): RunCheckpoint? =
            read(db, messageId)?.value

        private fun read(db: SQLiteDatabase, messageId: String): Stored? {
            val metadata = db.rawQuery("SELECT conversation_id,run_id,owner,revision,phase,process_owner,length(payload) FROM runtime_checkpoints WHERE message_id=?",
                arrayOf(messageId)).use { c -> if (!c.moveToFirst()) null else {
                val size = c.getLong(6); require(size in 1..(64L * 1024 * 1024))
                listOf(c.getString(0), c.getString(1), c.getString(2), c.getLong(3).toString(), c.getString(4), c.getString(5), size.toString())
            } } ?: return null
            val value = RunCheckpointCodec.decode(requireNotNull(SQLiteText.read(db, "runtime_checkpoints", "payload", "message_id", messageId)))
            check(value.message.id.value == messageId && value.message.conversationId.value == metadata[0] &&
                value.message.runId.value == metadata[1] && value.owner == metadata[2] &&
                value.revision.toString() == metadata[3] && value.phase.name == metadata[4]) { "检查点元数据不匹配" }
            return Stored(value, metadata[5])
        }
        private val leases = mutableSetOf<String>()
        fun acquire(database: ConversationDatabase): SQLiteRuntimeStore {
            val sidecar = database.runtimeLockFile
            val path = sidecar.path
            synchronized(leases) { check(leases.add(path)) { "另一个会话控制器仍拥有运行存储" } }
            var file: RandomAccessFile? = null
            try {
                check(sidecar.parentFile!!.let { it.isDirectory || it.mkdirs() })
                file = RandomAccessFile(sidecar, "rw")
                val lock = file.channel.tryLock() ?: error("另一个进程仍拥有运行存储")
                return SQLiteRuntimeStore(database, path, file, lock)
            } catch (failure: Exception) {
                try { file?.close() } finally { synchronized(leases) { leases.remove(path) } }
                throw failure
            }
        }
        fun installSchema(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS runtime_checkpoints(message_id TEXT PRIMARY KEY REFERENCES messages(id) ON DELETE CASCADE,conversation_id TEXT NOT NULL,run_id TEXT NOT NULL,process_owner TEXT NOT NULL,owner TEXT NOT NULL,revision INTEGER NOT NULL CHECK(revision>0),phase TEXT NOT NULL,payload TEXT NOT NULL)")
        }
    }
}
