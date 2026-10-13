package app.daiyu.android.storage

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import app.daiyu.android.domain.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class ConversationDatabase(context: Context) : SQLiteOpenHelper(context, "conversations.db", null, 1), ConversationStore {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE conversations(id TEXT PRIMARY KEY,title TEXT NOT NULL,draft TEXT NOT NULL,updated INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE messages(seq INTEGER PRIMARY KEY AUTOINCREMENT,id TEXT UNIQUE NOT NULL,conversation_id TEXT NOT NULL,run_id TEXT NOT NULL,role TEXT NOT NULL,blocks TEXT NOT NULL,status TEXT NOT NULL,error TEXT,protocol TEXT NOT NULL)")
        db.execSQL("CREATE INDEX messages_tail ON messages(conversation_id,seq)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) { error("不支持此数据库版本，保留原数据") }
    fun recover() { writableDatabase.execSQL("UPDATE messages SET status='INTERRUPTED',error='上次运行中断；未自动重发模型请求或工具' WHERE status='RUNNING'") }
    fun conversations(): List<Conversation> = readableDatabase.rawQuery("SELECT id,title,draft,updated FROM conversations ORDER BY updated DESC", null).use { c ->
        buildList { while (c.moveToNext()) add(Conversation(ConversationId(c.getString(0)),c.getString(1),c.getString(2),c.getLong(3))) }
    }
    fun create(): Conversation {
        val value=Conversation(ConversationId(UUID.randomUUID().toString()),"新会话","",System.currentTimeMillis())
        writableDatabase.insertOrThrow("conversations",null,ContentValues().apply {put("id",value.id.value);put("title",value.title);put("draft","");put("updated",value.updatedAt)})
        return value
    }
    fun draft(id: ConversationId, text: String) { writableDatabase.update("conversations",ContentValues().apply {put("draft",text)},"id=?",arrayOf(id.value)) }
    fun rename(id: ConversationId, title: String) { require(title.isNotBlank());writableDatabase.update("conversations",ContentValues().apply {put("title",title)},"id=?",arrayOf(id.value)) }
    fun delete(id: ConversationId) { transaction { db -> db.delete("messages","conversation_id=?",arrayOf(id.value));db.delete("conversations","id=?",arrayOf(id.value)) } }
    fun send(user: Message, assistant: Message) { transaction { db ->
        write(db,user);write(db,assistant)
        db.update("conversations",ContentValues().apply {put("draft","");put("updated",System.currentTimeMillis())},"id=?",arrayOf(user.conversationId.value))
    } }
    override fun save(message: Message) { try { transaction { write(it,message) } } catch (e: Exception) {throw PersistenceException(e)} }
    private fun write(db: SQLiteDatabase, message: Message) {
        val values=ContentValues().apply {
            put("id",message.id.value);put("conversation_id",message.conversationId.value);put("run_id",message.runId.value)
            put("role",message.role);put("blocks",encode(message.blocks).toString());put("status",message.status.name);put("error",message.error);put("protocol",JSONArray(message.protocolItems).toString())
        }
        if(db.update("messages",values,"id=?",arrayOf(message.id.value))==0) db.insertOrThrow("messages",null,values)
    }
    private fun transaction(block:(SQLiteDatabase)->Unit) { val db=writableDatabase;db.beginTransaction();try {block(db);db.setTransactionSuccessful()} finally {db.endTransaction()} }
    fun messages(id: ConversationId, before: Long = Long.MAX_VALUE, limit: Int = 50): List<Pair<Long,Message>> =
        readableDatabase.rawQuery("SELECT seq,id,run_id,role,blocks,status,error,protocol FROM messages WHERE conversation_id=? AND seq<? ORDER BY seq DESC LIMIT ?",arrayOf(id.value,before.toString(),limit.toString())).use { c ->
            buildList { while(c.moveToNext()) add(c.getLong(0) to Message(MessageId(c.getString(1)),id,RunId(c.getString(2)),c.getString(3),decode(JSONArray(c.getString(4))),RunStatus.valueOf(c.getString(5)),c.getString(6),jsonStrings(JSONArray(c.getString(7))))) }.reversed()
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
