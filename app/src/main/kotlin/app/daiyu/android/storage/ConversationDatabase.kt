package app.daiyu.android.storage

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import app.daiyu.android.domain.*
import app.daiyu.android.context.ContextStorage
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class ConversationDatabase(context: Context) : SQLiteOpenHelper(context, "conversations.db", null, 7), ConversationStore {
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
        db.execSQL("CREATE TABLE conversations(id TEXT PRIMARY KEY,title TEXT NOT NULL,draft TEXT NOT NULL,updated INTEGER NOT NULL,folder TEXT NOT NULL DEFAULT '',pinned INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE messages(seq INTEGER PRIMARY KEY AUTOINCREMENT,id TEXT UNIQUE NOT NULL,conversation_id TEXT NOT NULL,run_id TEXT NOT NULL,role TEXT NOT NULL,blocks TEXT NOT NULL,status TEXT NOT NULL,error TEXT,protocol TEXT NOT NULL,history_segments TEXT NOT NULL DEFAULT '[]')")
        db.execSQL("CREATE INDEX messages_tail ON messages(conversation_id,seq)")
        createUiFolders(db)
        createComposer(db)
        ContextStorage.installSchema(db)
        SQLiteRuntimeStore.installSchema(db)
    }
    private fun createComposer(db:SQLiteDatabase) {
        db.execSQL("CREATE TABLE composer(id INTEGER PRIMARY KEY CHECK(id=1),draft TEXT NOT NULL)")
        db.execSQL("INSERT INTO composer(id,draft) VALUES(1,'')")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        check(oldVersion in 1..6 && newVersion==7) {"不支持此数据库版本，保留原数据"}
        if(oldVersion==1) createComposer(db)
        if(oldVersion<=2) {
            db.execSQL("ALTER TABLE conversations ADD COLUMN folder TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE conversations ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0")
        }
        if(oldVersion<=4) db.execSQL("ALTER TABLE messages ADD COLUMN history_segments TEXT NOT NULL DEFAULT '[]'")
        ContextStorage.installSchema(db)
        SQLiteRuntimeStore.installSchema(db)
        createUiFolders(db)
    }
    private fun createUiFolders(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS conversation_folders(name TEXT PRIMARY KEY NOT NULL CHECK(length(name)>0 AND length(name)<=200))")
        db.execSQL("INSERT OR IGNORE INTO conversation_folders(name) SELECT DISTINCT folder FROM conversations WHERE folder<>''")
    }
    fun draft():String=readableDatabase.rawQuery("SELECT draft FROM composer WHERE id=1",null).use {it.moveToFirst();it.getString(0)}
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
        val list=readableDatabase.rawQuery("SELECT id,title,draft,updated,folder,pinned FROM conversations$where ORDER BY pinned DESC,updated DESC,id ASC",args.toTypedArray()).use {c->
            buildList {while(c.moveToNext()) add(Conversation(ConversationId(c.getString(0)),c.getString(1),c.getString(2),c.getLong(3),c.getString(4),c.getInt(5)!=0))}
        }
        if(query.isEmpty()) return list
        val matches=mutableSetOf<String>()
        val candidates=list.map {it.id.value}.toSet()
        readableDatabase.rawQuery("SELECT conversation_id,blocks FROM messages ORDER BY seq",null).use {c->while(c.moveToNext()) {
            val id=c.getString(0)
            if(id in candidates && id !in matches && decode(JSONArray(c.getString(1))).any {block->when(block) {
                is MessageBlock.Text->query in block.text
                is MessageBlock.Tool->query in block.invocation.arguments || block.result?.contains(query)==true
            }}) matches.add(id)
        }}
        return list.filter {query in it.title || query in it.draft || it.id.value in matches}
    }
    fun organize(id:ConversationId,folder:String,pinned:Boolean) {
        require(folder.length<=200)
        check(writableDatabase.update("conversations",ContentValues().apply {put("folder",folder);put("pinned",if(pinned) 1 else 0)},"id=?",arrayOf(id.value))==1) {"会话不存在"}
    }
    fun create():Conversation {
        val db=writableDatabase
        val value=Conversation(ConversationId(UUID.randomUUID().toString()),"新会话","",System.currentTimeMillis())
        db.insertOrThrow("conversations",null,ContentValues().apply {put("id",value.id.value);put("title",value.title);put("draft","");put("updated",value.updatedAt)})
        return value
    }
    fun draft(id: ConversationId, text: String) {check(writableDatabase.update("conversations",ContentValues().apply {put("draft",text)},"id=?",arrayOf(id.value))==1) {"会话不存在"}}
    fun rename(id: ConversationId, title: String) { require(title.isNotBlank());check(writableDatabase.update("conversations",ContentValues().apply {put("title",title)},"id=?",arrayOf(id.value))==1) {"会话不存在"}}
    fun delete(id: ConversationId) { transaction { db ->
        db.execSQL("DELETE FROM neutral_assets WHERE message_id IN (SELECT id FROM messages WHERE conversation_id=?)", arrayOf(id.value))
        db.delete("context_archives","conversation_id=?",arrayOf(id.value))
        db.delete("context_branches","conversation_id=?",arrayOf(id.value))
        db.delete("context_usage","branch_id=?",arrayOf(id.value))
        db.delete("messages","conversation_id=?",arrayOf(id.value))
        db.delete("conversations","id=?",arrayOf(id.value))
    } }
    internal fun storedMessage(db: SQLiteDatabase, conversation: ConversationId, messageId: String): Message {
        val metadata = db.rawQuery("SELECT run_id,role,status FROM messages WHERE id=? AND conversation_id=?",
            arrayOf(messageId, conversation.value)).use { c ->
            check(c.moveToFirst()) { "消息不存在" }; Triple(c.getString(0), c.getString(1), c.getString(2))
        }
        fun text(column: String) = SQLiteText.read(db, "messages", column, "id", messageId)
        return Message(MessageId(messageId), conversation, RunId(metadata.first), metadata.second,
            decode(JSONArray(requireNotNull(text("blocks")))), RunStatus.valueOf(metadata.third), text("error"),
            jsonStrings(JSONArray(requireNotNull(text("protocol")))),
            MessageHistoryCodec.decode(JSONArray(requireNotNull(text(historyColumn))))).also { MessageHistoryCodec.validate(it) }
    }
    private fun validateRunHistory(db: SQLiteDatabase, id: ConversationId, run: RunId) {
        val ids = db.rawQuery("SELECT id FROM messages WHERE conversation_id=? AND run_id=? ORDER BY seq",
            arrayOf(id.value,run.value)).use { c -> buildList { while(c.moveToNext()) add(c.getString(0)) } }
        MessageHistory.validate(ids.map { storedMessage(db,id,it) })
    }
    fun send(user:Message,assistant:Message,newConversation:Boolean=false):Conversation {
        require(user.conversationId==assistant.conversationId && user.runId==assistant.runId)
        require(user.role=="user" && user.status==RunStatus.COMPLETED && assistant.role=="assistant" && assistant.status==RunStatus.RUNNING)
        var conversation:Conversation?=null
        transaction { db ->
            if(newConversation) {
                val value=Conversation(user.conversationId,"新会话","",System.currentTimeMillis())
                db.insertOrThrow("conversations",null,ContentValues().apply {put("id",value.id.value);put("title",value.title);put("draft","");put("updated",value.updatedAt)})
                check(db.update("composer",ContentValues().apply {put("draft","")},"id=1",null)==1)
            }
            check(db.update("conversations",ContentValues().apply {put("draft","");put("updated",System.currentTimeMillis())},"id=?",arrayOf(user.conversationId.value))==1) {"会话不存在"}
            db.insertOrThrow("messages",null,values(user));db.insertOrThrow("messages",null,values(assistant))
            validateRunHistory(db,user.conversationId,user.runId)
            conversation=db.rawQuery("SELECT title,updated,folder,pinned FROM conversations WHERE id=?",arrayOf(user.conversationId.value)).use {it.moveToFirst();Conversation(user.conversationId,it.getString(0),"",it.getLong(1),it.getString(2),it.getInt(3)!=0)}
        }
        return checkNotNull(conversation)
    }
    override fun save(message:Message) {
        try { transaction { db ->
            require(message.role == Message.ASSISTANT_ROLE) { "仅允许保存 assistant 运行回执" }
            check(!db.rawQuery("SELECT 1 FROM runtime_checkpoints WHERE message_id=?", arrayOf(message.id.value)).use { it.moveToFirst() }) {
                "运行已由原子检查点所有者管理，拒绝旧消息写入路径"
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
    internal fun writeRuntimeMessage(db: SQLiteDatabase, message: Message) {
        check(db.update("messages", values(message), "id=? AND conversation_id=? AND run_id=? AND role='assistant' AND status='RUNNING'",
            arrayOf(message.id.value, message.conversationId.value, message.runId.value)) == 1) { "运行消息已结束，拒绝迟到检查点" }
        validateRunHistory(db, message.conversationId, message.runId)
    }
    fun exportBackup():String {
        var result=""
        transaction {db->
            val conversationCount=db.rawQuery("SELECT count(*) FROM conversations",null).use {it.moveToFirst();it.getLong(0)}
            val messageCount=db.rawQuery("SELECT count(*) FROM messages",null).use {it.moveToFirst();it.getLong(0)}
            require(conversationCount<=1000 && messageCount<=50000) {"文本备份最多1000个会话、50000条消息"}
            val rawBytes=db.rawQuery("SELECT (SELECT coalesce(sum(length(CAST(id AS BLOB))+length(CAST(title AS BLOB))+length(CAST(draft AS BLOB))+length(CAST(folder AS BLOB))),0) FROM conversations)+(SELECT coalesce(sum(length(CAST(id AS BLOB))+length(CAST(conversation_id AS BLOB))+length(CAST(run_id AS BLOB))+length(CAST(blocks AS BLOB))+length(CAST(protocol AS BLOB))+coalesce(length(CAST(error AS BLOB)),0)+length(CAST(history_segments AS BLOB))),0) FROM messages)+(SELECT length(CAST(draft AS BLOB)) FROM composer WHERE id=1)",null).use {it.moveToFirst();it.getLong(0)}
            require(rawBytes<=16*1024*1024) {"备份超过16MiB上限"}
            var encodedBytes=0L
            fun reserve(value:JSONObject) {
                encodedBytes+=value.toString().toByteArray(Charsets.UTF_8).size+1
                require(encodedBytes<=16*1024*1024) {"备份超过16MiB上限"}
            }
            val conversations=JSONArray()
            db.rawQuery("SELECT id,title,draft,updated,folder,pinned FROM conversations ORDER BY id",null).use {c->while(c.moveToNext()) {
                val value=JSONObject().put("id",c.getString(0)).put("title",c.getString(1)).put("draft",c.getString(2)).put("updated",c.getLong(3)).put("folder",c.getString(4)).put("pinned",c.getInt(5)!=0)
                reserve(value);conversations.put(value)
            }}
            val messages=JSONArray()
            db.rawQuery("SELECT id,conversation_id,run_id,role,blocks,status,error,protocol,history_segments FROM messages ORDER BY seq",null).use {c->while(c.moveToNext()) {
                val blocks=JSONArray(c.getString(4));validateBlocks(blocks)
                val protocol=JSONArray(c.getString(7));validateProtocol(protocol)
                val value=JSONObject().put("id",c.getString(0)).put("conversation_id",c.getString(1)).put("run_id",c.getString(2)).put("role",c.getString(3)).put("blocks",blocks).put("status",c.getString(5)).put("error",c.getString(6) ?: JSONObject.NULL).put("protocol",protocol)
                val rawSegments=c.getString(8)
                if (JSONArray(rawSegments).length()!=0) {
                    val segments=MessageHistoryCodec.decode(JSONArray(rawSegments))
                    val projection=Message(MessageId(c.getString(0)),ConversationId(c.getString(1)),RunId(c.getString(2)),c.getString(3),decode(blocks),RunStatus.valueOf(c.getString(5)),c.getString(6),jsonStrings(protocol),historySegments=segments)
                    MessageHistoryCodec.validate(projection)
                    value.put("history_segments", JSONArray(rawSegments))
                }
                reserve(value);messages.put(value)
            }}
            val draft=db.rawQuery("SELECT draft FROM composer WHERE id=1",null).use {it.moveToFirst();it.getString(0)}
            reserve(JSONObject().put("draft",draft))
            val payload=JSONObject().put("conversations",conversations).put("messages",messages).put("draft",draft).toString()
            result=JSONObject().put("format","daiyu-neutral-text").put("version",1).put("conversation_count",conversations.length()).put("message_count",messages.length()).put("sha256",backupDigest(payload)).put("payload",payload).toString()
        }
        require(result.toByteArray(Charsets.UTF_8).size<=16*1024*1024) {"备份超过16MiB上限"}
        parseBackup(result)
        return result
    }
    private fun parseBackup(text:String):Triple<List<Conversation>,List<Message>,String> {
        require(text.toByteArray(Charsets.UTF_8).size<=16*1024*1024)
        val envelope=backupObject(text)
        require(envelope.keys().asSequence().toSet()==setOf("format","version","conversation_count","message_count","sha256","payload"))
        require(string(envelope,"format")=="daiyu-neutral-text" && integer(envelope,"version")==1L)
        val payload=string(envelope,"payload");require(backupDigest(payload)==string(envelope,"sha256"))
        val data=backupObject(payload);require(data.keys().asSequence().toSet()==setOf("conversations","messages","draft"))
        val conversations=data.getJSONArray("conversations");val messages=data.getJSONArray("messages")
        require(conversations.length()<=1000 && messages.length()<=50000)
        require(conversations.length().toLong()==integer(envelope,"conversation_count") && messages.length().toLong()==integer(envelope,"message_count"))
        val ids=mutableSetOf<String>();val messageIds=mutableSetOf<String>()
        fun identity(value:String):String {require(value.isNotBlank() && value.length<=200);return value}
        val imported=(0 until conversations.length()).map {i->val c=conversations.getJSONObject(i)
            require(c.keys().asSequence().toSet()==setOf("id","title","draft","updated","folder","pinned"))
            val id=identity(string(c,"id"));require(ids.add(id));val folder=string(c,"folder");require(folder.length<=200)
            Conversation(ConversationId(id),string(c,"title"),string(c,"draft"),integer(c,"updated"),folder,boolean(c,"pinned"))
        }
        val restored=(0 until messages.length()).map {i->val m=messages.getJSONObject(i)
            require(m.keys().asSequence().toSet().let {it==setOf("id","conversation_id","run_id","role","blocks","status","error","protocol") || it==setOf("id","conversation_id","run_id","role","blocks","status","error","protocol","history_segments")})
            val id=identity(string(m,"id"));require(messageIds.add(id));val conversation=identity(string(m,"conversation_id"));require(conversation in ids)
            val role=string(m,"role");require(role in setOf("user","assistant"))
            val status=RunStatus.valueOf(string(m,"status"));val encoded=m.getJSONArray("blocks");validateBlocks(encoded);val blocks=decode(encoded)
            val protocol=m.getJSONArray("protocol");validateProtocol(protocol)
            blocks.forEach {b->when(b) {
                is MessageBlock.Text->identity(b.itemId)
                is MessageBlock.Tool->{require(role=="assistant");identity(b.invocation.id.value);identity(b.invocation.name)}
            }}
            require(blocks.filterIsInstance<MessageBlock.Tool>().map {it.invocation.id}.distinct().size==blocks.filterIsInstance<MessageBlock.Tool>().size)
            val originalError=if(m.isNull("error")) null else string(m,"error")
            val segments = if (m.has("history_segments")) {
                val encodedSegments = m.getJSONArray("history_segments")
                require(encodedSegments.length() > 0)
                MessageHistoryCodec.decode(encodedSegments).also { rows -> rows.forEach { validateProtocol(JSONArray(it.protocolItems)) } }
            } else emptyList()
            val restored = Message(MessageId(id),ConversationId(conversation),RunId(identity(string(m,"run_id"))),role,blocks,
                if(status==RunStatus.RUNNING) RunStatus.INTERRUPTED else status,
                if(status==RunStatus.RUNNING) "导入时原运行未结束；未自动重发请求或工具" else originalError,jsonStrings(protocol),
                historySegments = segments)
            MessageHistoryCodec.validate(restored)
            restored
        }
        MessageHistory.validate(restored)
        return Triple(imported,restored,string(data,"draft"))
    }
    internal fun preflightBackup(text:String) { parseBackup(text) }
    fun importBackup(text:String):Pair<Int,Int> {
        val (imported,restored,draft)=parseBackup(text)
        transaction {db->
            // Append only. Any identity collision aborts the entire transaction.
            imported.forEach {c->db.insertOrThrow("conversations",null,ContentValues().apply {
                put("id",c.id.value);put("title",c.title);put("draft",c.draft);put("updated",c.updatedAt);put("folder",c.folder);put("pinned",if(c.pinned) 1 else 0)
            })}
            restored.forEach {db.insertOrThrow("messages",null,values(it))}
            val existing=db.rawQuery("SELECT draft FROM composer WHERE id=1",null).use {it.moveToFirst();it.getString(0)}
            require(draft.isEmpty() || existing.isEmpty() || draft==existing) {"未创建会话草稿冲突；未覆盖"}
            if(existing.isEmpty()) check(db.update("composer",ContentValues().apply {put("draft",draft)},"id=1",null)==1)
        }
        return imported.size to restored.size
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
                "text"->{require(block.keys().asSequence().toSet()==setOf("type","item","text"));string(block,"item");string(block,"text")}
                "tool"->{require(block.keys().asSequence().toSet()==setOf("type","id","name","arguments","result"));string(block,"id");string(block,"name");string(block,"arguments");if(!block.isNull("result")) string(block,"result")}
                else->error("文本备份不支持此消息块")
            }
        }
    }
    private fun validateProtocol(array:JSONArray) {
        repeat(array.length()) {i->
            val raw=array.get(i);require(raw is String)
            val item=backupObject(raw)
            fun fields(required:Set<String>,optional:Set<String> = emptySet()) {
                require(item.keys().asSequence().toSet().containsAll(required) && item.keys().asSequence().toSet().all {it in required || it in optional}) {"文本备份不支持此协议字段"}
            }
            if(item.has("id")) string(item,"id")
            if(item.has("status")) require(string(item,"status") in setOf("completed","in_progress","incomplete"))
            when(string(item,"type")) {
                "message"->{
                    fields(setOf("type","role","content"),setOf("id","status"))
                    require(string(item,"role")=="assistant")
                    val content=item.getJSONArray("content")
                    repeat(content.length()) {j->val part=content.getJSONObject(j)
                        when(string(part,"type")) {
                            "output_text"->{require(part.keys().asSequence().toSet().containsAll(setOf("type","text")) && part.keys().asSequence().toSet().all {it in setOf("type","text","annotations","logprobs")});string(part,"text")
                                // Nonempty metadata is preserved by the protocol layer, but not supported by this text format.
                                for(key in listOf("annotations","logprobs")) if(part.has(key)) require(part.getJSONArray(key).length()==0)
                            }
                            "refusal"->{require(part.keys().asSequence().toSet()==setOf("type","refusal"));string(part,"refusal")}
                            else->error("文本备份不支持附件或此内容类型")
                        }
                    }
                }
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
    private fun backupDigest(value:String)=java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") {"%02x".format(it.toInt() and 255)}
    private fun values(message:Message)=ContentValues().apply {
        MessageHistoryCodec.validate(message)
        put("id",message.id.value);put("conversation_id",message.conversationId.value);put("run_id",message.runId.value)
        put("role",message.role);put("blocks",encode(message.blocks).toString());put("status",message.status.name);put("error",message.error);put("protocol",JSONArray(message.protocolItems).toString())
        put(historyColumn, if (message.historySegments.isEmpty()) "[]" else MessageHistoryCodec.encode(message.historySegments).toString())
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
            val result=ids.map { (seq,key) -> seq to storedMessage(db,id,key) }
            db.setTransactionSuccessful();return result
        } finally { db.endTransaction() }
    }
    private fun jsonStrings(array:JSONArray) = (0 until array.length()).map {array.getString(it)}
    private fun encode(blocks:List<MessageBlock>)=JSONArray().apply { blocks.forEach { b -> put(when(b) {
        is MessageBlock.Text -> JSONObject().put("type","text").put("item",b.itemId).put("text",b.text)
        is MessageBlock.Tool -> JSONObject().put("type","tool").put("id",b.invocation.id.value).put("name",b.invocation.name).put("arguments",b.invocation.arguments).put("result",b.result ?: JSONObject.NULL)
    }) } }
    private fun decode(array:JSONArray):List<MessageBlock> = (0 until array.length()).map { i -> val b=array.getJSONObject(i)
        when(b.getString("type")) {
            "text" -> MessageBlock.Text(b.getString("item"),b.getString("text"))
            "tool" -> MessageBlock.Tool(ToolInvocation(ToolCallId(b.getString("id")),b.getString("name"),b.getString("arguments")),if(b.isNull("result")) null else b.getString("result"))
            else -> error("无法识别的消息块，保留数据")
        }
    }
}
