package app.daiyu.android.storage

import android.content.ContentValues
import android.content.Context
import app.daiyu.android.ControllerVideoManifestWitness
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import app.daiyu.android.domain.*
import app.daiyu.android.context.Utf8Payload
import app.daiyu.android.context.ContextStorage
import app.daiyu.android.model.MessagesRequest
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class ConversationDatabase internal constructor(context: Context, private val afterSendRowsWritten: () -> Unit) : SQLiteOpenHelper(context, "conversations.db", null, 26), ConversationStore {
    constructor(context: Context) : this(context, {})
    private val assistantPreferences = context.getSharedPreferences("daiyu-assistants", Context.MODE_PRIVATE)
    private fun installAssistants(db: SQLiteDatabase) = app.daiyu.android.assistants.SqliteAssistantStore.install(
        db, assistantPreferences.getString(app.daiyu.android.assistants.LegacyAssistantCodec.KEY, null))
    internal val runtimeLockFile = java.io.File(context.getDatabasePath("conversations.db").canonicalPath + ".runtime-owner")
    /** A message row keeps the v4 columns as the legacy projection.  This column is an
     * append-only, lossless history of the provider attempts.  Empty means an old row and
     * is deliberately handled by the v4 codec (important for old backup digests). */
    private val historyColumn = "history_segments"
    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
        db.execSQL("PRAGMA synchronous=FULL")
    }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE conversations(id TEXT PRIMARY KEY,title TEXT NOT NULL,draft TEXT NOT NULL,updated INTEGER NOT NULL,folder TEXT NOT NULL DEFAULT '',pinned INTEGER NOT NULL DEFAULT 0,assistant_id TEXT)")
        db.execSQL("CREATE TABLE messages(seq INTEGER PRIMARY KEY AUTOINCREMENT,id TEXT UNIQUE NOT NULL,conversation_id TEXT NOT NULL,run_id TEXT NOT NULL,role TEXT NOT NULL,blocks TEXT NOT NULL,status TEXT NOT NULL,error TEXT,protocol TEXT NOT NULL,history_segments TEXT NOT NULL DEFAULT '[]',created_at INTEGER CHECK(created_at IS NULL OR (typeof(created_at)='integer' AND created_at>=0)))")
        db.execSQL("CREATE INDEX messages_tail ON messages(conversation_id,seq)")
        createUiFolders(db)
        createComposer(db)
        installAssistants(db)
        ContextStorage.installSchema(db)
        SQLiteRuntimeStore.installSchema(db)
        SQLiteChildTaskStore.installSchema(db)
        app.daiyu.android.model.usage.SQLiteChildUsageStore.installSchema(db)
        app.daiyu.android.context.RuntimePreparationSchema.install(db)
        app.daiyu.android.model.usage.SQLiteAdditionalUsageStore.install(db)
        installGenerationSchema(db)
        SQLiteMessageVideoStore.installSchema(db)
        ControllerVideoManifestWitness.installSchema(db)
        app.daiyu.android.title.SqliteTitleCommitAdapter.installSchema(db)
        app.daiyu.android.platform.NotificationHistoryStore.installSchema(db)
        ConversationCompletionMarkers.installSchema(db)
        RecordedUsageHistory.install(db)
        AssistantReplyIdentities.install(db)
        app.daiyu.android.timeline.ChatTimelineStore.install(db)
    }
    private fun installGenerationSchema(db: SQLiteDatabase) {
        SQLiteGenerationAttemptStore.installSchema(db)
        SQLiteGenerationOutputStore.installSchema(db)
    }
    private fun createComposer(db:SQLiteDatabase) {
        db.execSQL("CREATE TABLE composer(id INTEGER PRIMARY KEY CHECK(id=1),draft TEXT NOT NULL)")
        db.execSQL("INSERT INTO composer(id,draft) VALUES(1,'')")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        check(oldVersion in 1..25 && newVersion == 26) { "不支持此数据库版本，保留原数据" }
        app.daiyu.android.timeline.ChatTimelineStore.install(db)
        // Install before the >=13 early return; do not rewrite timestamps or video witnesses.
        app.daiyu.android.title.SqliteTitleCommitAdapter.installSchema(db)
        app.daiyu.android.platform.NotificationHistoryStore.installSchema(db)
        ConversationCompletionMarkers.installSchema(db)
        RecordedUsageHistory.install(db)
        AssistantReplyIdentities.install(db)
        // No backfill: conversation.updated is not evidence of any message's creation time.
        // Existing-column check also supports old-schema test fixtures retaining newer columns.
        val hasMessageTime = db.rawQuery("PRAGMA table_info(messages)", null).use { c ->
            var found = false
            while (c.moveToNext()) if (c.getString(1) == "created_at") found = true
            found
        }
        if (!hasMessageTime) db.execSQL("ALTER TABLE messages ADD COLUMN created_at INTEGER CHECK(created_at IS NULL OR (typeof(created_at)='integer' AND created_at>=0))")
        if (oldVersion < 10) {
            db.execSQL("ALTER TABLE conversations ADD COLUMN assistant_id TEXT")
            installAssistants(db)
            if(oldVersion==1) createComposer(db)
            if(oldVersion<=2) {
                db.execSQL("ALTER TABLE conversations ADD COLUMN folder TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE conversations ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0")
            }
            if(oldVersion<=4) db.execSQL("ALTER TABLE messages ADD COLUMN history_segments TEXT NOT NULL DEFAULT '[]'")
            ContextStorage.installSchema(db)
            SQLiteRuntimeStore.installSchema(db)
            SQLiteChildTaskStore.installSchema(db)
            app.daiyu.android.model.usage.SQLiteChildUsageStore.installSchema(db)
            createUiFolders(db)
        }
        if (oldVersion >= 10) {
            val hasDescription = db.rawQuery("PRAGMA table_info(assistants)", null).use { c ->
                var found = false
                while (c.moveToNext()) if (c.getString(1) == "description") found = true
                found
            }
            if (!hasDescription) db.execSQL("ALTER TABLE assistants ADD COLUMN description TEXT NOT NULL DEFAULT ''")
        }
        // Install AFTER legacy child tables exist and BEFORE the >=13 early return.
        app.daiyu.android.context.RuntimePreparationSchema.install(db)
        app.daiyu.android.model.usage.SQLiteAdditionalUsageStore.install(db)
        if (oldVersion < 11) upgradeGenerationSchema(db)
        if (oldVersion < 14) SQLiteChildTaskStore.installCommandSchema(db)
        SQLiteChildTaskStore.installDisplaySchema(db)
        installUsageCreationColumns(db)
        CloudContextUsageStore.installSchema(db)
        ConversationUsageStore.installSchema(db)
        ConversationUsageStore.backfill(db)
        if (oldVersion >= 13) return // Existing command/video rows and witnesses remain unchanged.
        SQLiteMessageVideoStore.installSchema(db)
        ControllerVideoManifestWitness.installSchema(db)
        check(!db.rawQuery("SELECT 1 FROM message_video_inputs v LEFT JOIN messages m ON m.id=v.message_id WHERE m.id IS NULL LIMIT 1", null).use { it.moveToFirst() })
        val keys = db.rawQuery("SELECT conversation_id,id FROM messages ORDER BY seq", null).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0) to c.getString(1)) }
        }
        keys.forEach { (conversation, id) ->
            val row = storedMessage(db, ConversationId(conversation), id, verifyVideoWitness = false)
            ControllerVideoManifestWitness.migrateLegacy(db, listOf(row), emptyIsProven = oldVersion < 12)
        }
    }
    private fun installUsageCreationColumns(db: SQLiteDatabase) {
        listOf("child_request_usage", "runtime_preparations", "context_usage", RecordedUsageHistory.REQUESTS).forEach { table ->
            val columns = db.rawQuery("PRAGMA table_info($table)", null).use { c ->
                buildSet { while (c.moveToNext()) add(c.getString(1)) }
            }
            if ("cache_creation_tokens" !in columns) db.execSQL("ALTER TABLE $table ADD COLUMN cache_creation_tokens INTEGER")
        }
    }
    private fun upgradeGenerationSchema(db: SQLiteDatabase) {
        val hasOutputs = db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?",
            arrayOf(SQLiteGenerationOutputStore.TABLE)).use { it.moveToFirst() }
        if (!hasOutputs) {
            installGenerationSchema(db)
            return
        }
        // Check the OLD storage classes before INTEGER/TEXT affinity can normalize bad data.
        val integerRanges = mapOf("output_index" to "0 AND 31", "byte_count" to "1 AND 9223372036854775807",
            "width" to "1 AND 2147483647", "height" to "1 AND 2147483647",
            "duration_ms" to "0 AND 9223372036854775807")
        val textColumns = listOf("operation_id", "item_id", "fingerprint", "protocol", "account_scope",
            "conversation_id", "assistant_id", "run_id", "tool_call_id", "tool_name", "receipt", "kind", "mime_type", "sha256")
        val invalid = (integerRanges.map { (column, range) ->
            "typeof($column)!='integer' OR $column NOT BETWEEN $range"
        } + textColumns.map { "typeof($it)!='text'" }).joinToString(" OR ")
        db.rawQuery("SELECT 1 FROM generation_outputs WHERE $invalid LIMIT 1", null).use {
            check(!it.moveToFirst()) { "生成数据类型无效，保留原数据库" }
        }
        // SQLiteOpenHelper owns this transaction. Reject invalid legacy rows without deleting them.
        db.execSQL("ALTER TABLE generation_outputs RENAME TO generation_outputs_v10")
        db.execSQL("DROP INDEX IF EXISTS generation_outputs_owner")
        installGenerationSchema(db)
        val columns = "operation_id,item_id,output_index,fingerprint,protocol,account_scope," +
            "conversation_id,assistant_id,run_id,tool_call_id,tool_name,receipt,kind,mime_type," +
            "byte_count,sha256,width,height,duration_ms"
        db.execSQL("INSERT INTO generation_outputs($columns) SELECT $columns FROM generation_outputs_v10")
        db.execSQL("DROP TABLE generation_outputs_v10")
    }
    private fun createUiFolders(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS conversation_folders(name TEXT PRIMARY KEY NOT NULL CHECK(length(name)>0 AND length(name)<=200))")
        db.execSQL("INSERT OR IGNORE INTO conversation_folders(name) SELECT DISTINCT folder FROM conversations WHERE folder<>''")
    }
    fun draft():String {
        val db=readableDatabase
        db.beginTransaction()
        try {
            val result=composerDraft(db)
            db.setTransactionSuccessful()
            return result
        } finally { db.endTransaction() }
    }
    private fun composerDraft(db: SQLiteDatabase): String {
        // Same byte-based 64 KiB reads as SQLiteText; composer has no message-size ceiling.
        // Reading a whole draft into a CursorWindow breaks unrelated sends in old chats.
        val length=db.rawQuery("SELECT length(CAST(draft AS BLOB)) FROM composer WHERE id=1",null).use {
            check(it.moveToFirst()) {"发送草稿不存在"}; it.getLong(0)
        }
        val bytes=java.io.ByteArrayOutputStream(); var start=1L
        while(start<=length) {
            val part=db.rawQuery("SELECT substr(CAST(draft AS BLOB),?,65536) FROM composer WHERE id=1",arrayOf(start.toString())).use {
                check(it.moveToFirst()) {"发送草稿不存在"}; it.getBlob(0)
            }
            check(part.isNotEmpty() && bytes.size().toLong()+part.size<=length)
            bytes.write(part); start+=part.size
        }
        check(bytes.size().toLong()==length)
        return Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes.toByteArray())).toString()
    }
    fun draft(text:String) {check(writableDatabase.update("composer",ContentValues().apply {put("draft",text)},"id=1",null)==1)}
    fun recover() { SQLiteRuntimeStore.acquire(this).use { it.recoverPending() } }
    internal fun recoverUncheckpointed(db: SQLiteDatabase) {
        db.execSQL("UPDATE messages SET status='INTERRUPTED',error='上次运行中断；未自动重发模型请求或工具' WHERE status='RUNNING' AND NOT EXISTS (SELECT 1 FROM runtime_checkpoints WHERE message_id=messages.id)")
    }
    fun conversations(query:String="",folder:String?=null): List<Conversation> {
        require(query.length<=1000 && (folder==null || folder.length<=200))
        val conditions=mutableListOf<String>();val args=mutableListOf<String>()
        if(folder!=null) {conditions.add("folder=?");args.add(folder)}
        val where=if(conditions.isEmpty()) "" else " WHERE "+conditions.joinToString(" AND ")
        val list=readableDatabase.rawQuery("SELECT id,title,draft,updated,folder,pinned,assistant_id FROM conversations$where ORDER BY pinned DESC,updated DESC,id ASC",args.toTypedArray()).use {c->
            buildList {while(c.moveToNext()) add(Conversation(ConversationId(c.getString(0)),c.getString(1),c.getString(2),c.getLong(3),c.getString(4),c.getInt(5)!=0,c.getString(6)))}
        }
        if(query.isEmpty()) return list
        val matches=mutableSetOf<String>()
        val candidates=list.map {it.id.value}.toSet()
        readableDatabase.rawQuery("SELECT conversation_id,id FROM messages ORDER BY seq",null).use {c->while(c.moveToNext()) {
            val id=c.getString(0)
            if(id in candidates && id !in matches && storedMessage(readableDatabase,ConversationId(id),c.getString(1), verifyVideoWitness = false).blocks.any {block->when(block) {
                is MessageBlock.Text->query in block.text
                is MessageBlock.Image->false // References are not searchable text or local paths.
                is MessageBlock.Tool->query in block.invocation.arguments || block.result?.contains(query)==true
            }}) matches.add(id)
        }}
        return list.filter {query in it.title || query in it.draft || it.id.value in matches}
    }
    fun organize(id:ConversationId,folder:String,pinned:Boolean) {
        require(folder.length<=200)
        check(writableDatabase.update("conversations",ContentValues().apply {put("folder",folder);put("pinned",if(pinned) 1 else 0)},"id=?",arrayOf(id.value))==1) {"会话不存在"}
    }
    fun selectedAssistantId(): String = readableDatabase.rawQuery("SELECT selected_id FROM assistant_selection WHERE id=1", null).use {
        check(it.moveToFirst()) { "助手选择不存在" }; it.getString(0)
    }
    fun assistantId(id: ConversationId): String? = readableDatabase.rawQuery("SELECT assistant_id FROM conversations WHERE id=?", arrayOf(id.value)).use {
        check(it.moveToFirst()) { "会话不存在" }; it.getString(0)
    }
    private fun requireAssistant(db: SQLiteDatabase, id: String?) {
        if (id == null) throw app.daiyu.android.assistants.MissingAssistantBindingException()
        if (!db.rawQuery("SELECT 1 FROM assistants WHERE id=?", arrayOf(id)).use { it.moveToFirst() })
            throw app.daiyu.android.assistants.AssistantNotFoundException(id)
    }
    /** Explicit repair only. NULL and dangling IDs are distinct expected states. */
    fun bindAssistant(id: ConversationId, expected: String?, replacement: String): Boolean {
        var changed = false
        transaction { db ->
            requireAssistant(db, replacement)
            check(!db.rawQuery("SELECT 1 FROM messages WHERE conversation_id=? AND status='RUNNING'", arrayOf(id.value)).use { it.moveToFirst() }) { "会话正在运行" }
            changed = db.update("conversations", ContentValues().apply { put("assistant_id", replacement) },
                if(expected==null) "id=? AND assistant_id IS NULL" else "id=? AND assistant_id=?",
                if(expected==null) arrayOf(id.value) else arrayOf(id.value, expected)) == 1
            if (changed) check(db.rawQuery("SELECT 1 FROM conversations WHERE id=? AND assistant_id=?", arrayOf(id.value, replacement)).use { it.moveToFirst() }) { "助手绑定回执不匹配" }
        }
        return changed
    }
    fun create(assistantId: String? = null):Conversation {
        val value=Conversation(ConversationId(UUID.randomUUID().toString()),"新会话","",System.currentTimeMillis(),assistantId=assistantId)
        transaction { db ->
            // NULL is reserved for explicit legacy/import fixtures, never a global fallback.
            if (assistantId != null) requireAssistant(db, assistantId)
            check(db.insertOrThrow("conversations",null,ContentValues().apply {put("id",value.id.value);put("title",value.title);put("draft","");put("updated",value.updatedAt);put("assistant_id",assistantId)}) != -1L)
        }
        return value
    }
    fun draft(id: ConversationId, text: String) {check(writableDatabase.update("conversations",ContentValues().apply {put("draft",text)},"id=?",arrayOf(id.value))==1) {"会话不存在"}}
    fun rename(id: ConversationId, title: String) { require(title.isNotBlank());check(writableDatabase.update("conversations",ContentValues().apply {put("title",title)},"id=?",arrayOf(id.value))==1) {"会话不存在"}}
    fun delete(id: ConversationId) { transaction { db ->
        SQLiteMessageVideoStore.deleteConversation(db, id.value)
        db.delete(SQLiteGenerationOutputStore.TABLE, "conversation_id=?", arrayOf(id.value))
        db.rawQuery("SELECT 1 FROM generation_outputs WHERE conversation_id=? LIMIT 1", arrayOf(id.value)).use {
            check(!it.moveToFirst()) { "生成引用未删除，保留会话" }
        }
        // Paid-attempt tombstones outlive deleted messages; deletion never permits a new POST.
        db.execSQL("DELETE FROM neutral_assets WHERE message_id IN (SELECT id FROM messages WHERE conversation_id=?)", arrayOf(id.value))
        db.delete("context_archives","conversation_id=?",arrayOf(id.value))
        db.delete("context_branches","conversation_id=?",arrayOf(id.value))
        db.delete("context_usage","branch_id=?",arrayOf(id.value))
        db.delete("messages","conversation_id=?",arrayOf(id.value))
        db.delete("conversations","id=?",arrayOf(id.value))
    } }
    internal fun storedMessage(db: SQLiteDatabase, conversation: ConversationId, messageId: String,
        verifyVideoWitness: Boolean = true): Message {
        val metadata = db.rawQuery("SELECT run_id,role,status FROM messages WHERE id=? AND conversation_id=?",
            arrayOf(messageId, conversation.value)).use { c ->
            check(c.moveToFirst()) { "消息不存在" }; Triple(c.getString(0), c.getString(1), c.getString(2))
        }
        val createdAt = db.rawQuery("SELECT created_at FROM messages WHERE id=?", arrayOf(messageId)).use {
            check(it.moveToFirst()); if (it.isNull(0)) null else it.getLong(0)
        }
        fun text(column: String) = SQLiteText.read(db, "messages", column, "id", messageId)
        return Message(MessageId(messageId), conversation, RunId(metadata.first), metadata.second,
            decode(JSONArray(requireNotNull(text("blocks")))), RunStatus.valueOf(metadata.third), text("error"),
            jsonStrings(JSONArray(requireNotNull(text("protocol")))),
            MessageHistoryCodec.decode(JSONArray(requireNotNull(text(historyColumn)))), createdAt = createdAt).also {
                MessageHistoryCodec.validate(it)
                MessageImages.validate(it)
                if (verifyVideoWitness) SQLiteMessageVideoStore.readVerified(db, it)
                else SQLiteMessageVideoStore.read(db, it)
            }
    }
    internal fun videoInputs(messages: List<Message>): List<app.daiyu.android.model.videos.MessageVideoInput> {
        val db = readableDatabase
        db.beginTransaction()
        try {
            val result = messages.flatMap { expected ->
                val stored = storedMessage(db, expected.conversationId, expected.id.value)
                check(stored == expected) { "video_history_changed" }
                SQLiteMessageVideoStore.readVerified(db, stored)
            }
            db.setTransactionSuccessful()
            return result
        } finally { db.endTransaction() }
    }
    private fun validateRunHistory(db: SQLiteDatabase, id: ConversationId, run: RunId) {
        val ids = db.rawQuery("SELECT id FROM messages WHERE conversation_id=? AND run_id=? ORDER BY seq",
            arrayOf(id.value,run.value)).use { c -> buildList { while(c.moveToNext()) add(c.getString(0)) } }
        MessageHistory.validate(ids.map { storedMessage(db,id,it) })
    }
    /** preserveDraft is for explicit local child-command receipt runs, never composer sends. */
    fun send(user:Message,assistant:Message,newConversation:Boolean=false,
        expectedAssistantId:String? = if(newConversation) selectedAssistantId() else assistantId(user.conversationId),
        videoInputs: List<app.daiyu.android.model.videos.MessageVideoInput> = emptyList(),
        preserveDraft: Boolean = false,
        replyIdentity: app.daiyu.android.assistants.AssistantReplyIdentity? = null):Conversation {
        require(user.conversationId==assistant.conversationId && user.runId==assistant.runId)
        require(!preserveDraft || !newConversation)
        require(replyIdentity == null || replyIdentity.assistantId == expectedAssistantId)
        require(user.role=="user" && user.status==RunStatus.COMPLETED && assistant.role=="assistant" && assistant.status==RunStatus.RUNNING)
        var conversation:Conversation?=null
        transaction { db ->
            val videoHistory = app.daiyu.android.ControllerVideoHistory(this).capture(db, user.conversationId)
            requireAssistant(db, expectedAssistantId)
            if (!newConversation) check(db.rawQuery("SELECT 1 FROM conversations WHERE id=? AND assistant_id=?",
                arrayOf(user.conversationId.value, expectedAssistantId)).use { it.moveToFirst() }) { "会话助手绑定已变化；草稿保留" }
            val expectedComposer=if(newConversation) "" else composerDraft(db)
            val retainedDraft = if (preserveDraft) db.rawQuery("SELECT draft FROM conversations WHERE id=?", arrayOf(user.conversationId.value)).use {
                check(it.moveToFirst()); it.getString(0)
            } else ""
            var newConversationRowId:Long?=null
            if(newConversation) {
                val value=Conversation(user.conversationId,"新会话","",System.currentTimeMillis(),assistantId=expectedAssistantId)
                newConversationRowId=db.insertOrThrow("conversations",null,ContentValues().apply {put("id",value.id.value);put("title",value.title);put("draft","");put("updated",value.updatedAt);put("assistant_id",expectedAssistantId)})
                check(newConversationRowId != -1L) {"新会话未持久化"}
                check(db.update("composer",ContentValues().apply {put("draft","")},"id=1",null)==1)
            }
            check(db.update("conversations",ContentValues().apply {if (!preserveDraft) put("draft","");put("updated",System.currentTimeMillis())},"id=?",arrayOf(user.conversationId.value))==1) {"会话不存在"}
            // RAISE(IGNORE) can return -1 without throwing, even from insertOrThrow.
            val userRowId=db.insertOrThrow("messages",null,values(user))
            check(userRowId != -1L) {"用户消息未持久化"}
            val assistantRowId=db.insertOrThrow("messages",null,values(assistant))
            check(assistantRowId != -1L) {"运行消息未持久化"}
            if (replyIdentity != null) AssistantReplyIdentities.insert(db, assistant, replyIdentity)
            afterSendRowsWritten()
            SQLiteMessageVideoStore.insert(db, user, videoInputs)
            SQLiteMessageVideoStore.insert(db, assistant, emptyList())
            // Verify both receipts after all inserts, so trigger side effects cannot leave a half-run.
            for((rowId,message) in listOf(userRowId to user,assistantRowId to assistant)) {
                check(db.rawQuery("SELECT 1 FROM messages WHERE seq=? AND id=? AND conversation_id=? AND run_id=? AND role=? AND status=?",
                    arrayOf(rowId.toString(),message.id.value,message.conversationId.value,message.runId.value,message.role,message.status.name)).use {it.moveToFirst()}) {"发送消息身份不匹配"}
                // Reuse the chunked reader rather than putting large blocks in one CursorWindow.
                check(storedMessage(db,message.conversationId,message.id.value)==message) {"发送消息内容不匹配"}
            }
            check(AssistantReplyIdentities.read(db, assistant) == replyIdentity) { "发送回复身份回执不一致" }
            validateRunHistory(db,user.conversationId,user.runId)
            app.daiyu.android.ControllerVideoHistory(this).assertAppended(db, videoHistory, user, assistant, videoInputs)
            conversation=db.rawQuery("SELECT title,updated,folder,pinned,rowid,length(CAST(draft AS BLOB))=0,assistant_id FROM conversations WHERE id=?",arrayOf(user.conversationId.value)).use {
                check(it.moveToFirst() && (newConversationRowId==null || it.getLong(4)==newConversationRowId)) {"发送会话身份不匹配"}
                val nextDraft = if (preserveDraft) db.rawQuery("SELECT draft FROM conversations WHERE id=?", arrayOf(user.conversationId.value)).use { c ->
                    check(c.moveToFirst()); c.getString(0)
                }.also { draft -> check(draft == retainedDraft) { "子任务命令改变了草稿" } }
                    else { check(it.getInt(5)==1) {"发送会话草稿未清空"}; "" }
                check(it.getString(6)==expectedAssistantId) { "发送助手绑定回执不匹配" }
                requireAssistant(db, expectedAssistantId)
                Conversation(user.conversationId,it.getString(0),nextDraft,it.getLong(1),it.getString(2),it.getInt(3)!=0,it.getString(6))
            }
            check(composerDraft(db)==expectedComposer) {"发送草稿不匹配"}
        }
        return checkNotNull(conversation)
    }
    fun replyIdentity(message: Message): app.daiyu.android.assistants.AssistantReplyIdentity? =
        AssistantReplyIdentities.read(readableDatabase, message)

    override fun save(message:Message) {
        try { transaction { db ->
            require(message.role == Message.ASSISTANT_ROLE) { "仅允许保存 assistant 运行回执" }
            check(!db.rawQuery("SELECT 1 FROM runtime_checkpoints WHERE message_id=?", arrayOf(message.id.value)).use { it.moveToFirst() }) {
                "运行已由原子检查点所有者管理，拒绝旧消息写入路径"
            }
            check(storedMessage(db, message.conversationId, message.id.value).createdAt == message.createdAt) {
                "消息创建时间不可更改"
            }
            val encoded = values(message)
            val count=db.update("messages",encoded,"id=? AND conversation_id=? AND run_id=? AND role=? AND status='RUNNING' AND EXISTS (SELECT 1 FROM conversations WHERE id=?)",
                arrayOf(message.id.value,message.conversationId.value,message.runId.value,message.role,message.conversationId.value))
            if (count==0) {
                val identical=db.rawQuery("SELECT conversation_id,run_id,role,blocks,status,error,protocol,history_segments FROM messages WHERE id=?",arrayOf(message.id.value)).use {c ->
                    c.moveToFirst() && c.getString(0)==message.conversationId.value && c.getString(1)==message.runId.value && c.getString(2)==message.role &&
                        c.getString(3)==encoded.getAsString("blocks") && c.getString(4)==encoded.getAsString("status") &&
                        (c.isNull(5) && encoded.get("error")==null || !c.isNull(5) && c.getString(5)==encoded.getAsString("error")) &&
                        c.getString(6)==encoded.getAsString("protocol") && c.getString(7)==encoded.getAsString(historyColumn)
                }
                check(identical) {"消息已结束或所属会话不存在，拒绝迟到回执"}
            }
            SQLiteMessageVideoStore.readVerified(db, message)
            validateRunHistory(db,message.conversationId,message.runId)
        } } catch(e:Exception) {throw PersistenceException(e)}
    }
    internal fun runtimeMessage(db: SQLiteDatabase, expected: Message): Message {
        check(db.rawQuery("SELECT 1 FROM conversations WHERE id=?", arrayOf(expected.conversationId.value)).use { it.moveToFirst() }) {
            "所属会话已不存在"
        }
        return storedMessage(db, expected.conversationId, expected.id.value).also {
            require(it.runId == expected.runId && it.role == Message.ASSISTANT_ROLE)
        }
    }
    internal fun insertRuntimeSupplement(db: SQLiteDatabase, assistant: Message, supplement: Message) {
        check(db.inTransaction())
        require(supplement.conversationId == assistant.conversationId && supplement.runId == assistant.runId &&
            supplement.id != assistant.id && supplement.role == Message.USER_ROLE && supplement.status == RunStatus.COMPLETED &&
            supplement.error == null && supplement.historySegments.isEmpty() && supplement.protocolItems.isEmpty() &&
            supplement.blocks.isNotEmpty() && supplement.blocks.all { it is MessageBlock.Text })
        check(db.insertOrThrow("messages", null, values(supplement)) != -1L)
        SQLiteMessageVideoStore.insert(db, supplement, emptyList())
    }
    internal fun writeRuntimeMessage(db: SQLiteDatabase, message: Message) {
        check(storedMessage(db, message.conversationId, message.id.value).createdAt == message.createdAt) {
            "消息创建时间不可更改"
        }
        check(db.update("messages", values(message), "id=? AND conversation_id=? AND run_id=? AND role='assistant' AND status='RUNNING'",
            arrayOf(message.id.value, message.conversationId.value, message.runId.value)) == 1) { "运行消息已结束，拒绝迟到检查点" }
        SQLiteMessageVideoStore.readVerified(db, message)
        validateRunHistory(db, message.conversationId, message.runId)
    }
    fun exportBackup():String = exportBackup(null)
    fun exportConversationBackup(conversationId:ConversationId):String = exportBackup(conversationId)
    internal fun exportBackupForVideoAssets(
        db: SQLiteDatabase,
        conversationId: ConversationId?,
        assets: app.daiyu.android.backup.BackupVideoAssets,
    ): String {
        check(db === writableDatabase && db.inTransaction())
        return exportBackup(conversationId, assets)
    }
    internal fun restoreVideoBackupSidecars(
        db: SQLiteDatabase,
        sidecars: List<app.daiyu.android.backup.BackupVideoSidecar>,
    ) {
        check(db === writableDatabase && db.inTransaction())
        sidecars.forEach { sidecar ->
            val conversation = db.rawQuery("SELECT conversation_id FROM messages WHERE id=?",
                arrayOf(sidecar.messageId)).use { cursor ->
                check(cursor.moveToFirst()) { "视频物理消息不存在" }
                ConversationId(cursor.getString(0))
            }
            val physical = storedMessage(db, conversation, sidecar.messageId, verifyVideoWitness = false)
            val inputs = MessageVideoInputCodec.decode(physical, sidecar.payload)
            check(inputs.isNotEmpty())
            SQLiteMessageVideoStore.insert(db, physical, inputs)
        }
    }
    private fun exportBackup(conversationId:ConversationId?,
        videoAssets: app.daiyu.android.backup.BackupVideoAssets? = null):String {
        var result=""
        val conversationWhere=if(conversationId==null) "" else " WHERE id=?"
        val messageWhere=if(conversationId==null) "" else " WHERE conversation_id=?"
        val args=conversationId?.let {arrayOf(it.value)}
        transaction {db->
            SQLiteMessageVideoStore.assertNoOrphans(db)
            check(videoAssets != null || !SQLiteMessageVideoStore.hasVideos(db, conversationId?.value)) {
                "文本备份不能保留原视频，请使用支持视频原件的备份格式"
            }
            val conversationCount=db.rawQuery("SELECT count(*) FROM conversations$conversationWhere",args).use {it.moveToFirst();it.getLong(0)}
            require(conversationId==null || conversationCount==1L) {"会话不存在或已删除"}
            val messageCount=db.rawQuery("SELECT count(*) FROM messages$messageWhere",args).use {it.moveToFirst();it.getLong(0)}
            require(conversationCount<=1000 && messageCount<=50000) {"文本备份最多1000个会话、50000条消息"}
            val composerBytes=if(conversationId==null) "(SELECT length(CAST(draft AS BLOB)) FROM composer WHERE id=1)" else "0"
            val rawBytes=db.rawQuery("SELECT (SELECT coalesce(sum(length(CAST(id AS BLOB))+length(CAST(title AS BLOB))+length(CAST(draft AS BLOB))+length(CAST(folder AS BLOB))),0) FROM conversations$conversationWhere)+(SELECT coalesce(sum(length(CAST(id AS BLOB))+length(CAST(conversation_id AS BLOB))+length(CAST(run_id AS BLOB))+length(CAST(blocks AS BLOB))+length(CAST(protocol AS BLOB))+coalesce(length(CAST(error AS BLOB)),0)+length(CAST(history_segments AS BLOB))),0) FROM messages$messageWhere)+$composerBytes",args?.let {it+it}).use {it.moveToFirst();it.getLong(0)}
            require(rawBytes<=16*1024*1024) {"备份超过16MiB上限"}
            var encodedBytes=0L
            fun reserve(value:JSONObject) {
                encodedBytes+=Utf8Payload.byteCount(value.toString(), 16L*1024*1024)+1
                require(encodedBytes<=16*1024*1024) {"备份超过16MiB上限"}
            }
            val conversations=JSONArray()
            db.rawQuery("SELECT id,title,draft,updated,folder,pinned,assistant_id FROM conversations$conversationWhere ORDER BY id",args).use {c->while(c.moveToNext()) {
                val value=JSONObject().put("id",c.getString(0)).put("title",c.getString(1)).put("draft",c.getString(2)).put("updated",c.getLong(3)).put("folder",c.getString(4)).put("pinned",c.getInt(5)!=0).put("assistant_id",c.getString(6) ?: JSONObject.NULL)
                reserve(value);conversations.put(value)
            }}
            val messages=JSONArray()
            db.rawQuery("SELECT id,conversation_id,run_id,role,blocks,status,error,protocol,history_segments,created_at FROM messages$messageWhere ORDER BY seq",args).use {c->while(c.moveToNext()) {
                val blocks=JSONArray(c.getString(4));validateBlocks(blocks)
                val protocol=JSONArray(c.getString(7))
                val value=JSONObject().put("id",c.getString(0)).put("conversation_id",c.getString(1)).put("run_id",c.getString(2)).put("role",c.getString(3)).put("blocks",blocks).put("status",c.getString(5)).put("error",c.getString(6) ?: JSONObject.NULL).put("protocol",protocol)
                val createdAt = if (c.isNull(9)) null else c.getLong(9)
                MessageTimeCodec.write(value, createdAt)
                val rawSegments=c.getString(8)
                val segments=MessageHistoryCodec.decode(JSONArray(rawSegments))
                val projection=Message(MessageId(c.getString(0)),ConversationId(c.getString(1)),RunId(c.getString(2)),c.getString(3),decode(blocks),RunStatus.valueOf(c.getString(5)),c.getString(6),jsonStrings(protocol),historySegments=segments,createdAt=createdAt)
                AssistantReplyIdentities.encode(db, projection)?.let { value.put("reply_identity", it) }
                MessageHistoryCodec.validate(projection)
                MessageImages.validate(projection)
                SQLiteMessageVideoStore.readVerified(db, projection)
                if (segments.isNotEmpty()) {
                    segments.forEach { validateProtocol(JSONArray(it.protocolItems), it.blocks, it.role) }
                    value.put("history_segments", JSONArray(rawSegments))
                }
                validateProtocol(protocol, if (JSONArray(rawSegments).length()==0) decode(blocks) else emptyList(), c.getString(3))
                reserve(value);messages.put(value)
            }}
            val draft=if(conversationId==null) db.rawQuery("SELECT draft FROM composer WHERE id=1",null).use {it.moveToFirst();it.getString(0)} else ""
            reserve(JSONObject().put("draft",draft))
            val payload=JSONObject().put("conversations",conversations).put("messages",messages).put("draft",draft).toString()
            result=JSONObject().put("format","daiyu-neutral-text").put("version",2).put("conversation_count",conversations.length()).put("message_count",messages.length()).put("sha256",backupDigest(payload)).put("payload",payload).toString()
        }
        Utf8Payload.byteCount(result, 16L*1024*1024)
        parseBackup(result)
        return result
    }
    private fun parseBackup(text:String):Triple<List<Conversation>,List<Message>,String> {
        Utf8Payload.byteCount(text, 16L*1024*1024)
        val envelope=backupObject(text)
        require(envelope.keys().asSequence().toSet()==setOf("format","version","conversation_count","message_count","sha256","payload"))
        val version=integer(envelope,"version")
        require(string(envelope,"format")=="daiyu-neutral-text" && version in 1L..2L)
        val payload=string(envelope,"payload");require(backupDigest(payload)==string(envelope,"sha256"))
        val data=backupObject(payload);require(data.keys().asSequence().toSet()==setOf("conversations","messages","draft"))
        val conversations=data.getJSONArray("conversations");val messages=data.getJSONArray("messages")
        require(conversations.length()<=1000 && messages.length()<=50000)
        require(conversations.length().toLong()==integer(envelope,"conversation_count") && messages.length().toLong()==integer(envelope,"message_count"))
        val ids=mutableSetOf<String>();val messageIds=mutableSetOf<String>()
        fun identity(value:String):String {require(value.isNotBlank() && value.length<=200);return value}
        val imported=(0 until conversations.length()).map {i->val c=conversations.getJSONObject(i)
            val fields=setOf("id","title","draft","updated","folder","pinned")
            require(c.keys().asSequence().toSet()==if(version==1L) fields else fields+"assistant_id")
            val id=identity(string(c,"id"));require(ids.add(id));val folder=string(c,"folder");require(folder.length<=200)
            // v1 has no binding: keep NULL. v2 retains the exact ID, including a deleted ID;
            // importing never selects the global assistant or creates replacement profiles.
            val assistantId=if(version==1L || c.isNull("assistant_id")) null else app.daiyu.android.assistants.AssistantStorage.id(string(c,"assistant_id"))
            Conversation(ConversationId(id),string(c,"title"),string(c,"draft"),integer(c,"updated"),folder,boolean(c,"pinned"),assistantId)
        }
        val restored=(0 until messages.length()).map {i->val m=messages.getJSONObject(i)
            require((m.keys().asSequence().toSet() - MessageTimeCodec.FIELD - "reply_identity").let {it==setOf("id","conversation_id","run_id","role","blocks","status","error","protocol") || it==setOf("id","conversation_id","run_id","role","blocks","status","error","protocol","history_segments")})
            val id=identity(string(m,"id"));require(messageIds.add(id));val conversation=identity(string(m,"conversation_id"));require(conversation in ids)
            val role=string(m,"role");require(role in setOf("user","assistant"))
            if (m.has("reply_identity")) {
                require(role == Message.ASSISTANT_ROLE)
                AssistantReplyIdentities.decode(m.getJSONObject("reply_identity"))
            }
            val status=RunStatus.valueOf(string(m,"status"));val encoded=m.getJSONArray("blocks");validateBlocks(encoded);val blocks=decode(encoded)
            val protocol=m.getJSONArray("protocol")
            blocks.forEach {b->when(b) {
                is MessageBlock.Text->identity(b.itemId)
                is MessageBlock.Image->Unit // Strict wire fields checked by validateBlocks; owner below.
                is MessageBlock.Tool->{require(role=="assistant");identity(b.invocation.id.value);identity(b.invocation.name)}
            }}
            require(blocks.filterIsInstance<MessageBlock.Tool>().map {it.invocation.id}.distinct().size==blocks.filterIsInstance<MessageBlock.Tool>().size)
            val originalError=if(m.isNull("error")) null else string(m,"error")
            val segments = if (m.has("history_segments")) {
                val encodedSegments = m.getJSONArray("history_segments")
                require(encodedSegments.length() > 0)
                MessageHistoryCodec.decode(encodedSegments).also { rows -> rows.forEach { validateProtocol(JSONArray(it.protocolItems), it.blocks, it.role) } }
            } else emptyList()
            validateProtocol(protocol, if (segments.isEmpty()) blocks else emptyList(), role)
            val restored = Message(MessageId(id),ConversationId(conversation),RunId(identity(string(m,"run_id"))),role,blocks,
                if(status==RunStatus.RUNNING) RunStatus.INTERRUPTED else status,
                if(status==RunStatus.RUNNING) "导入时原运行未结束；未自动重发请求或工具" else originalError,jsonStrings(protocol),
                historySegments = segments, createdAt = MessageTimeCodec.read(m))
            MessageHistoryCodec.validate(restored)
            MessageImages.validate(restored)
            restored
        }
        MessageHistory.validate(restored)
        return Triple(imported,restored,string(data,"draft"))
    }
    private fun backupReplyIdentities(text: String, rows: List<Message>): Map<String, app.daiyu.android.assistants.AssistantReplyIdentity> {
        val messages = backupObject(string(backupObject(text), "payload")).getJSONArray("messages")
        return buildMap {
            repeat(messages.length()) { index ->
                val value = messages.getJSONObject(index)
                if (value.has("reply_identity")) {
                    val row = rows[index]
                    require(row.role == Message.ASSISTANT_ROLE)
                    put(row.id.value, AssistantReplyIdentities.decode(value.getJSONObject("reply_identity")))
                }
            }
        }
    }
    internal fun preflightBackup(text:String): List<Message> { val (_, rows, _) = parseBackup(text); backupReplyIdentities(text, rows); return rows }
    fun importBackup(text:String):Pair<Int,Int> = importBackup(text, deferVideoWitness = false)
    internal fun importBackupForVideoAssets(db: SQLiteDatabase, text: String): Pair<Int, Int> {
        check(db === writableDatabase && db.inTransaction())
        return importBackup(text, deferVideoWitness = true)
    }
    private fun importBackup(text: String, deferVideoWitness: Boolean): Pair<Int, Int> {
        val (imported,restored,draft)=parseBackup(text)
        val replyIdentities = backupReplyIdentities(text, restored)
        transaction {db->
            // Append only. Any identity collision aborts the entire transaction.
            imported.forEach {c->check(db.insertOrThrow("conversations",null,ContentValues().apply {
                put("id",c.id.value);put("title",c.title);put("draft",c.draft);put("updated",c.updatedAt);put("folder",c.folder);put("pinned",if(c.pinned) 1 else 0);put("assistant_id",c.assistantId)
            }) != -1L) { "备份会话未写入" }}
            restored.forEach {
                check(db.insertOrThrow("messages",null,values(it)) != -1L) { "备份消息未写入" }
                replyIdentities[it.id.value]?.let { identity -> AssistantReplyIdentities.insert(db, it, identity) }
                if (!deferVideoWitness) SQLiteMessageVideoStore.insert(db, it, emptyList())
            }
            val existing=db.rawQuery("SELECT draft FROM composer WHERE id=1",null).use {it.moveToFirst();it.getString(0)}
            require(draft.isEmpty() || existing.isEmpty() || draft==existing) {"未创建会话草稿冲突；未覆盖"}
            if(existing.isEmpty()) check(db.update("composer",ContentValues().apply {put("draft",draft)},"id=1",null)==1)
            check(db.rawQuery("SELECT 1 FROM composer WHERE id=1 AND draft=?",
                arrayOf(if (existing.isEmpty()) draft else existing)).use { it.moveToFirst() })
            verifyBackupRows(db, imported, restored, verifyVideoWitness = !deferVideoWitness)
            restored.forEach { check(AssistantReplyIdentities.read(db, it) == replyIdentities[it.id.value]) { "回复身份导入回执不一致" } }
        }
        return imported.size to restored.size
    }
    internal fun verifyRestoredBackup(db: SQLiteDatabase, text: String) {
        check(db === writableDatabase && db.inTransaction())
        val (imported, restored, _) = parseBackup(text)
        verifyBackupRows(db, imported, restored)
        val identities = backupReplyIdentities(text, restored)
        restored.forEach { check(AssistantReplyIdentities.read(db, it) == identities[it.id.value]) { "回复身份导入回执不一致" } }
    }
    internal fun verifyImportedVideoWitnesses(db: SQLiteDatabase, text: String) {
        check(db === writableDatabase && db.inTransaction())
        SQLiteMessageVideoStore.assertNoOrphans(db)
        val (_, rows, _) = parseBackup(text)
        rows.forEach { original ->
            val current = storedMessage(db, original.conversationId, original.id.value)
            check(current.runId == original.runId && current.role == original.role) {
                "video_import_owner_changed"
            }
        }
    }
    internal fun completeVideoBackupWitnesses(db: SQLiteDatabase, text: String,
        sidecars: List<app.daiyu.android.backup.BackupVideoSidecar>) {
        check(db === writableDatabase && db.inTransaction())
        val (_, rows, _) = parseBackup(text)
        val manifests = sidecars.associate { it.messageId to it.payload }
        check(manifests.size == sidecars.size && manifests.keys.all { id -> rows.any { it.id.value == id } })
        rows.forEach { row ->
            val expected = manifests[row.id.value]?.let { MessageVideoInputCodec.decode(row, it) } ?: emptyList()
            ControllerVideoManifestWitness.insert(db, row, expected)
        }
    }
    private fun verifyBackupRows(db: SQLiteDatabase, imported: List<Conversation>, restored: List<Message>,
        verifyVideoWitness: Boolean = true) {
        imported.forEach { c ->
            val args = mutableListOf(c.id.value, c.title, c.draft, c.updatedAt.toString(), c.folder,
                if (c.pinned) "1" else "0")
            val assistantId = c.assistantId
            val assistant = if (assistantId == null) "assistant_id IS NULL" else {
                args += assistantId
                "assistant_id IS ?"
            }
            check(db.rawQuery("SELECT 1 FROM conversations WHERE id=? AND title=? AND draft=? AND updated=? AND folder=? AND pinned=? AND $assistant",
                args.toTypedArray()).use { it.moveToFirst() }) { "备份会话回执不匹配" }
        }
        restored.forEach { message ->
            check(storedMessage(db, message.conversationId, message.id.value, verifyVideoWitness) == message) { "备份消息回执不匹配" }
        }
    }
    private fun string(value:JSONObject,key:String):String = (value.get(key) as? String) ?: error("字段须为文本")
    private fun integer(value:JSONObject,key:String):Long {
        val number=value.get(key);require(number is Int || number is Long) {"字段须为整数"}
        return (number as Number).toLong()
    }
    private fun boolean(value:JSONObject,key:String):Boolean = (value.get(key) as? Boolean) ?: error("字段须为布尔值")
    private fun backupObject(text:String):JSONObject {
        var depth=0;var quoted=false;var escaped=false
        text.forEach {ch->
            if(quoted) {if(escaped) escaped=false else if(ch=='\\') escaped=true else if(ch=='"') quoted=false}
            else when(ch) {'"'->quoted=true;'[','{'->{depth++;require(depth<=32) {"备份结构过深"}};']','}'->{depth--;require(depth>=0)}}
        }
        require(!quoted && depth==0)
        val parser=org.json.JSONTokener(text)
        val value=parser.nextValue();require(value is JSONObject && parser.nextClean()=='\u0000')
        return value
    }
    private fun validateBlocks(array:JSONArray) {
        repeat(array.length()) {i->val block=array.getJSONObject(i)
            when(string(block,"type")) {
                "image"->MessageImageReferenceCodec.decodeBlock(block)
                "text"->{require(block.keys().asSequence().toSet()==setOf("type","item","text"));string(block,"item");string(block,"text")}
                "tool"->{require(block.keys().asSequence().toSet()==setOf("type","id","name","arguments","result"));string(block,"id");string(block,"name");string(block,"arguments");if(!block.isNull("result")) string(block,"result")}
                else->error("文本备份不支持此消息块")
            }
        }
    }
    private fun validateProtocol(array:JSONArray, blocks:List<MessageBlock>, role:String) {
        if(array.length()==0) return
        val rawItems=(0 until array.length()).map { array.get(it).also { value -> require(value is String) } as String }
        val items=rawItems.map { raw ->
            val item=try {
                backupObject(raw)
            } catch(_:IllegalArgumentException) {
                MessagesRequest.jsonObject(raw, MessagesRequest.MAX_MESSAGE_CONTAINER_DEPTH)
                    .also { require(!it.has("type")) { "Responses 协议结构过深" } }
            }
            if(item.has("type")) item
            else MessagesRequest.jsonObject(raw, MessagesRequest.MAX_MESSAGE_CONTAINER_DEPTH)
        }
        if(items.any { !it.has("type") }) {
            require(items.all { !it.has("type") }) { "同一协议数组不可混用供应商格式" }
            // Validate wire history without changing the imported status or the saved raw items.
            val owner=Message(MessageId("backup-validation"), ConversationId("backup-validation"),
                RunId("backup-validation"), role, blocks, RunStatus.COMPLETED, protocolItems=rawItems)
            when {
                items.all { it.optJSONArray("content") != null && !it.has("parts") } -> MessagesRequest.replayHistory(owner, validateOnly = true)
                items.all { it.optJSONArray("parts") != null } -> app.daiyu.android.model.NativeCompletionRequest.history(
                    app.daiyu.android.model.ProviderProtocol.GEMINI, listOf(owner), allowPending = true)
                items.all { !it.has("parts") && (it.opt("content") is String || it.isNull("content")) } ->
                    app.daiyu.android.model.NativeCompletionRequest.history(
                        app.daiyu.android.model.ProviderProtocol.CHAT_COMPLETIONS, listOf(owner), allowPending = true)
                else -> throw IllegalArgumentException("文本备份包含不支持或混用的原生协议格式")
            }
            return
        }
        repeat(array.length()) {i->
            val item=items[i]
            fun fields(required:Set<String>,optional:Set<String> = emptySet()) {
                require(item.keys().asSequence().toSet().containsAll(required) && item.keys().asSequence().toSet().all {it in required || it in optional}) {"文本备份不支持此协议字段"}
            }
            if(item.has("id")) string(item,"id")
            if(item.has("status") && item.opt("type") != "web_search_call") require(string(item,"status") in setOf("completed","in_progress","incomplete"))
            when(string(item,"type")) {
                "message"->{
                    fields(setOf("type","role","content"),setOf("id","status"))
                    require(string(item,"role")=="assistant")
                    val content=item.getJSONArray("content")
                    repeat(content.length()) {j->val part=content.getJSONObject(j)
                        when(string(part,"type")) {
                            "output_text"->{require(part.keys().asSequence().toSet().containsAll(setOf("type","text")) && part.keys().asSequence().toSet().all {it in setOf("type","text","annotations","logprobs")});string(part,"text")
                                app.daiyu.android.model.ResponsesSearchProtocol.validateTextPart(part)
                                if(part.has("logprobs")) require(part.getJSONArray("logprobs").length()==0)
                            }
                            "refusal"->{require(part.keys().asSequence().toSet()==setOf("type","refusal"));string(part,"refusal")}
                            else->error("文本备份不支持附件或此内容类型")
                        }
                    }
                }
                "web_search_call"->app.daiyu.android.model.ResponsesSearchProtocol.validateItem(item)
                "function_call"->{fields(setOf("type","call_id","name","arguments"),setOf("id","status"));string(item,"call_id");string(item,"name");string(item,"arguments")}
                "function_call_output"->{fields(setOf("type","call_id","output"),setOf("id","status"));string(item,"call_id");string(item,"output")}
                "reasoning"->{
                    fields(setOf("type"),setOf("id","status","summary","content","encrypted_content"))
                    if(item.has("encrypted_content") && !item.isNull("encrypted_content")) string(item,"encrypted_content")
                    for(key in listOf("summary","content")) if(item.has(key)) {
                        val parts=item.getJSONArray(key)
                        repeat(parts.length()) {j->val part=parts.getJSONObject(j);require(part.keys().asSequence().toSet()==setOf("type","text"));require(string(part,"type")==if(key=="summary") "summary_text" else "reasoning_text");string(part,"text")}
                    }
                }
                else->error("文本备份不支持此协议类型")
            }
        }
    }
    private fun backupDigest(value:String)=Utf8Payload.sha256(value)
    private fun values(message:Message)=ContentValues().apply {
        MessageHistoryCodec.validate(message)
        MessageImages.validate(message)
        put("id",message.id.value);put("conversation_id",message.conversationId.value);put("run_id",message.runId.value)
        put("role",message.role);put("blocks",encode(message.blocks).toString());put("status",message.status.name);put("error",message.error);put("protocol",JSONArray(message.protocolItems).toString())
        put(historyColumn, if (message.historySegments.isEmpty()) "[]" else MessageHistoryCodec.encode(message.historySegments).toString())
        put("created_at", message.createdAt)
    }
    private fun transaction(block:(SQLiteDatabase)->Unit) { val db=writableDatabase;db.beginTransaction();try {block(db);db.setTransactionSuccessful()} finally {db.endTransaction()} }
    fun messages(id: ConversationId, before: Long = Long.MAX_VALUE, limit: Int = 50): List<Pair<Long,Message>> {
        require(limit in 1..200) {"分页数量须为1至200"}
        val db=readableDatabase
        db.beginTransaction()
        try {
            val ids=db.rawQuery("SELECT seq,id FROM messages WHERE conversation_id=? AND seq<? ORDER BY seq DESC LIMIT ?",
                arrayOf(id.value,before.toString(),limit.toString())).use { c ->
                buildList { while(c.moveToNext()) add(c.getLong(0) to c.getString(1)) }.reversed()
            }
            val result=ids.map { (seq,key) -> seq to storedMessage(db,id,key, verifyVideoWitness = false) }
            db.setTransactionSuccessful();return result
        } finally { db.endTransaction() }
    }
    private fun jsonStrings(array:JSONArray) = (0 until array.length()).map {array.getString(it)}
    private fun encode(blocks:List<MessageBlock>)=JSONArray().apply { blocks.forEach { b -> put(when(b) {
        is MessageBlock.Image -> MessageImageReferenceCodec.encodeBlock(b)
        is MessageBlock.Text -> JSONObject().put("type","text").put("item",b.itemId).put("text",b.text)
        is MessageBlock.Tool -> JSONObject().put("type","tool").put("id",b.invocation.id.value).put("name",b.invocation.name).put("arguments",b.invocation.arguments).put("result",b.result ?: JSONObject.NULL)
    }) } }
    private fun decode(array:JSONArray):List<MessageBlock> = (0 until array.length()).map { i -> val b=array.getJSONObject(i)
        when(b.getString("type")) {
            "image" -> MessageImageReferenceCodec.decodeBlock(b)
            "text" -> MessageBlock.Text(b.getString("item"),b.getString("text"))
            "tool" -> MessageBlock.Tool(ToolInvocation(ToolCallId(b.getString("id")),b.getString("name"),b.getString("arguments")),if(b.isNull("result")) null else b.getString("result"))
            else -> error("无法识别的消息块，保留数据")
        }
    }
}
