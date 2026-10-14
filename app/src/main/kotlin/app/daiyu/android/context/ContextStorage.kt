package app.daiyu.android.context

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import app.daiyu.android.domain.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** All services use the SAME helper connection; the owner may inject its transaction callback. */
class DatabaseTransactions(val database: () -> SQLiteDatabase) {
    fun <T> run(block: (SQLiteDatabase) -> T): T {
        val db = database(); db.beginTransaction()
        try { val result = block(db); db.setTransactionSuccessful(); return result }
        finally { db.endTransaction() }
    }
}

object ContextStorage {
    /** Call from ConversationDatabase onCreate and v3->v4 onUpgrade, inside helper migration. */
    fun installSchema(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS context_archives(id TEXT PRIMARY KEY,conversation_id TEXT NOT NULL,prefix_count INTEGER NOT NULL,revision TEXT NOT NULL,summary TEXT NOT NULL,blob_hash TEXT NOT NULL,created INTEGER NOT NULL,active INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS context_archive_owner ON context_archives(conversation_id,active)")
        db.execSQL("CREATE TABLE IF NOT EXISTS context_branches(conversation_id TEXT PRIMARY KEY,parent_id TEXT NOT NULL,through_message TEXT NOT NULL,parent_revision TEXT NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS context_usage(request_id TEXT PRIMARY KEY,run_id TEXT NOT NULL,branch_id TEXT NOT NULL,input_tokens INTEGER,output_tokens INTEGER,cached_tokens INTEGER,reasoning_tokens INTEGER)")
        db.execSQL("CREATE TABLE IF NOT EXISTS neutral_assets(id TEXT PRIMARY KEY,message_id TEXT,kind TEXT NOT NULL,mime TEXT NOT NULL,bytes INTEGER NOT NULL,blob_hash TEXT NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS neutral_config(name TEXT PRIMARY KEY,value TEXT NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS neutral_imports(id TEXT PRIMARY KEY,created INTEGER NOT NULL)")
    }
}

/** Content addressed immutable private files. Files precede DB references: crash leaves only harmless orphans. */
class PrivateBlobs(context: Context) {
    private val root = File(context.filesDir, "neutral-context-blobs").apply { check(mkdirs() || isDirectory) }
    fun put(bytes: ByteArray): String {
        require(bytes.size.toLong() <= MAX_BLOB)
        val hash = digest(bytes); val target = file(hash)
        if (target.exists()) { check(read(hash).contentEquals(bytes)); return hash }
        val temporary = File.createTempFile("stage-", ".tmp", root)
        try {
            java.io.FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }
            check(temporary.renameTo(target)) { "归档写入失败" }
            check(read(hash).contentEquals(bytes)); return hash
        } finally { temporary.delete() }
    }
    fun read(hash: String): ByteArray {
        val source = file(hash); require(source.isFile && source.length() <= MAX_BLOB) { "原文或附件缺档" }
        val bytes = source.readBytes(); require(digest(bytes) == hash) { "原文或附件损坏" }; return bytes
    }
    private fun file(hash: String): File {
        require(hash.matches(Regex("[0-9a-f]{64}"))); val f = File(root, hash)
        require(f.canonicalFile.parentFile == root.canonicalFile); return f
    }
    companion object {
        const val MAX_BLOB = 64L * 1024 * 1024
        fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}

/** Archive codec preserves every block, tool state, error and protocol item. No assistant-host history. */
object ArchiveCodec {
    fun encode(messages: List<Message>): ByteArray = JSONArray().apply {
        messages.forEach { m -> put(JSONObject().put("id", m.id.value).put("conversation", m.conversationId.value)
            .put("run", m.runId.value).put("role", m.role).put("status", m.status.name)
            .put("error", m.error ?: JSONObject.NULL).put("protocol", JSONArray(m.protocolItems))
            .put("blocks", blocks(m.blocks))) }
    }.toString().toByteArray(Charsets.UTF_8)
    fun blocks(values: List<MessageBlock>): JSONArray = JSONArray().apply { values.forEach { b -> put(when (b) {
        is MessageBlock.Text -> JSONObject().put("type", "text").put("item", b.itemId).put("text", b.text)
        is MessageBlock.Tool -> JSONObject().put("type", "tool").put("id", b.invocation.id.value)
            .put("name", b.invocation.name).put("arguments", b.invocation.arguments).put("result", b.result ?: JSONObject.NULL)
    }) } }
    fun decode(bytes: ByteArray): List<Message> {
        val array = JSONArray(String(bytes, Charsets.UTF_8))
        return (0 until array.length()).map { i -> val m = array.getJSONObject(i)
            val blocks = m.getJSONArray("blocks"); val protocol = m.getJSONArray("protocol")
            Message(MessageId(m.getString("id")), ConversationId(m.getString("conversation")), RunId(m.getString("run")),
                m.getString("role"), decodeBlocks(blocks), RunStatus.valueOf(m.getString("status")),
                if (m.isNull("error")) null else m.getString("error"), (0 until protocol.length()).map { protocol.getString(it) })
        }
    }
    fun decodeBlocks(blocks: JSONArray): List<MessageBlock> = (0 until blocks.length()).map { j -> val b = blocks.getJSONObject(j)
        when (b.getString("type")) {
            "text" -> MessageBlock.Text(b.getString("item"), b.getString("text"))
            "tool" -> MessageBlock.Tool(ToolInvocation(ToolCallId(b.getString("id")), b.getString("name"), b.getString("arguments")),
                if (b.isNull("result")) null else b.getString("result"))
            else -> error("未知原文块")
        }
    }
}
