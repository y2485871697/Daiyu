package app.daiyu.android.context

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import app.daiyu.android.domain.*
import app.daiyu.android.storage.MessageHistoryCodec
import app.daiyu.android.storage.MessageImageReferenceCodec
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

/**
 * The archive is deliberately still a JSON array.  This is the format used by the
 * first archive implementation, and changing it when there are no history segments
 * would change ContextRevision (and invalidate every existing blob).
 *
 * A message which has history uses the optional `history_segments` member.  The
 * worker-owned MessageHistoryCodec is the only codec for that member: keeping the
 * validation there means that replay, import and archive paging all enforce the
 * same segment identity rules.
 */
object ArchiveCodec {
    private data class ArchiveIdentity(val conversation: String, val run: String, val message: String)

    /** Maximum input accepted before handing anything to the JSON parser. */
    // Archives are private blobs, not text backups: retain the established 64 MiB
    // blob ceiling so reopening an existing archive cannot become impossible.
    const val MAX_ARCHIVE_BYTES = PrivateBlobs.MAX_BLOB
    const val MAX_MESSAGES = 50_000
    const val MAX_BLOCKS = 10_000
    const val MAX_PROTOCOL_ITEMS = 10_000
    const val MAX_ID_LENGTH = 200
    const val MAX_FIELD_BYTES = 4L * 1024 * 1024

    /**
     * Keep this method byte-for-byte compatible with the legacy encoder when all
     * historySegments are empty.  In particular, do not emit an empty member.
     */
    fun encode(messages: List<Message>): ByteArray {
        require(messages.size <= MAX_MESSAGES) { "归档消息数量超限" }
        MessageHistory.validate(messages)
        val value = JSONArray()
        val identities = HashSet<ArchiveIdentity>()
        messages.forEach { message ->
            validateMessage(message, identities)
            val item = JSONObject().put("id", message.id.value).put("conversation", message.conversationId.value)
                .put("run", message.runId.value).put("role", message.role).put("status", message.status.name)
                .put("error", message.error ?: JSONObject.NULL).put("protocol", JSONArray(message.protocolItems))
                .put("blocks", blocks(message.blocks))
            if (message.historySegments.isNotEmpty()) {
                // Validate before writing so a bad segment can never become a blob
                // which only fails much later during a branch or a replay.
                MessageHistoryCodec.validate(message)
                item.put("history_segments", MessageHistoryCodec.encode(message.historySegments))
            }
            value.put(item)
        }
        return value.toString().toByteArray(Charsets.UTF_8).also {
            require(it.size.toLong() <= MAX_ARCHIVE_BYTES) { "归档超过大小上限" }
        }
    }

    fun blocks(values: List<MessageBlock>): JSONArray {
        require(values.size <= MAX_BLOCKS) { "消息块数量超限" }
        return JSONArray().apply { values.forEach { b -> put(when (b) {
            is MessageBlock.Text -> JSONObject().put("type", "text").put("item", b.itemId).put("text", b.text)
            is MessageBlock.Image -> MessageImageReferenceCodec.encodeBlock(b)
            is MessageBlock.Tool -> JSONObject().put("type", "tool").put("id", b.invocation.id.value)
                .put("name", b.invocation.name).put("arguments", b.invocation.arguments).put("result", b.result ?: JSONObject.NULL)
        }) } }
    }

    /** Decode both legacy arrays and arrays whose message entries contain history. */
    fun decode(bytes: ByteArray): List<Message> {
        require(bytes.size.toLong() <= MAX_ARCHIVE_BYTES) { "归档超过大小上限" }
        val text = strictUtf8(bytes)
        val parser = org.json.JSONTokener(text)
        val parsed = parser.nextValue()
        require(parsed is JSONArray && parser.nextClean() == '\u0000') { "归档须为完整JSON数组" }
        val array = parsed
        require(array.length() <= MAX_MESSAGES) { "归档消息数量超限" }
        val identities = HashSet<ArchiveIdentity>()
        return (0 until array.length()).map { i ->
            val m = array.getJSONObject(i)
            val keys = m.keys().asSequence().toSet()
            require(keys == LEGACY_KEYS || keys == HISTORY_KEYS) { "归档字段不完整或含未知字段" }
            val id = identity(m, "id"); val conversation = identity(m, "conversation")
            val run = identity(m, "run"); val role = stringField(m, "role")
            require(role == "user" || role == "assistant") { "消息归属角色非法" }
            require(identities.add(ArchiveIdentity(conversation, run, id))) { "归档包含重复消息身份" }
            val encodedBlocks = m.getJSONArray("blocks")
            val decodedBlocks = decodeBlocks(encodedBlocks, role)
            val protocol = decodeProtocol(m.getJSONArray("protocol"))
            val status = try { RunStatus.valueOf(stringField(m, "status")) }
                catch (_: Exception) { error("归档状态非法") }
            val errorText = if (m.isNull("error")) null else stringField(m, "error")
            checkSize(errorText, "error")
            val segments = if (m.has("history_segments")) {
                val encoded = m.getJSONArray("history_segments")
                require(encoded.length() > 0) { "空历史应使用旧字段形状" }
                val decoded = MessageHistoryCodec.decode(encoded)
                // The segment codec validates segment shape and identities.  Validate
                // the complete owning message as well, rather than trusting JSON IDs.
                val message = Message(MessageId(id), ConversationId(conversation), RunId(run), role,
                    decodedBlocks, status, errorText, protocol, decoded)
                if (decoded.isNotEmpty()) MessageHistoryCodec.validate(message)
                decoded
            } else emptyList()
            Message(MessageId(id), ConversationId(conversation), RunId(run), role, decodedBlocks,
                status, errorText, protocol, segments).also {
                // A reference is not its own authority: check the enclosing row and
                // each segment's real message identity, including legacy-shaped rows.
                MessageImages.validate(it)
            }
        }.also { MessageHistory.validate(it) }
    }

    fun decodeBlocks(blocks: JSONArray): List<MessageBlock> = decodeBlocks(blocks, null)

    private fun decodeBlocks(blocks: JSONArray, role: String?): List<MessageBlock> {
        require(blocks.length() <= MAX_BLOCKS) { "消息块数量超限" }
        val toolIds = HashSet<String>()
        return (0 until blocks.length()).map { j ->
            val b = blocks.getJSONObject(j)
            val type = stringField(b, "type")
            when (type) {
                "image" -> MessageImageReferenceCodec.decodeBlock(b)
                "text" -> {
                    require(b.keys().asSequence().toSet() == setOf("type", "item", "text")) { "文本块字段非法" }
                    val item = stringField(b, "item"); val text = stringField(b, "text")
                    checkIdentity(item, "item"); checkSize(text, "text")
                    MessageBlock.Text(item, text)
                }
                "tool" -> {
                    require(role == null || role == "assistant") { "用户消息不能包含工具调用" }
                    require(b.keys().asSequence().toSet() == setOf("type", "id", "name", "arguments", "result")) { "工具块字段非法" }
                    val id = stringField(b, "id"); val name = stringField(b, "name"); val arguments = stringField(b, "arguments")
                    checkIdentity(id, "tool id"); checkIdentity(name, "tool name"); checkSize(arguments, "arguments")
                    require(toolIds.add(id)) { "归档包含重复工具身份" }
                    val result = if (b.isNull("result")) null else stringField(b, "result").also { checkSize(it, "result") }
                    MessageBlock.Tool(ToolInvocation(ToolCallId(id), name, arguments), result)
                }
                else -> error("未知原文块")
            }
        }
    }

    private fun validateMessage(message: Message, identities: MutableSet<ArchiveIdentity>) {
        checkIdentity(message.id.value, "message id")
        require(identities.add(ArchiveIdentity(message.conversationId.value, message.runId.value, message.id.value))) { "归档包含重复消息身份" }
        checkIdentity(message.conversationId.value, "conversation")
        checkIdentity(message.runId.value, "run")
        require(message.role == "user" || message.role == "assistant") { "消息归属角色非法" }
        require(message.blocks.size <= MAX_BLOCKS) { "消息块数量超限" }
        // Shared policy checks enclosing owners, duplicate assets and aggregate limits.
        MessageImages.validate(message)
        val toolIds = HashSet<String>()
        message.blocks.forEach { block ->
            when (block) {
                is MessageBlock.Text -> { checkIdentity(block.itemId, "item"); checkSize(block.text, "text") }
                is MessageBlock.Image -> Unit // Strict reference metadata is validated by core-domain.
                is MessageBlock.Tool -> {
                    require(message.role == "assistant") { "用户消息不能包含工具调用" }
                    checkIdentity(block.invocation.id.value, "tool id"); checkIdentity(block.invocation.name, "tool name")
                    require(toolIds.add(block.invocation.id.value)) { "归档包含重复工具身份" }
                    checkSize(block.invocation.arguments, "arguments"); block.result?.let { checkSize(it, "result") }
                }
            }
        }
        require(message.protocolItems.size <= MAX_PROTOCOL_ITEMS) { "协议项数量超限" }
        message.protocolItems.forEach { checkSize(it, "protocol") }
        message.error?.let { checkSize(it, "error") }
    }

    private fun decodeProtocol(protocol: JSONArray): List<String> {
        require(protocol.length() <= MAX_PROTOCOL_ITEMS) { "协议项数量超限" }
        return (0 until protocol.length()).map { i ->
            val item = protocol.get(i)
            require(item is String) { "协议项必须为文本" }
            checkSize(item, "protocol")
            item
        }
    }

    private fun identity(value: JSONObject, key: String): String = stringField(value, key).also { checkIdentity(it, key) }
    private fun stringField(value: JSONObject, key: String): String {
        val raw = value.get(key)
        return raw as? String ?: error("字段 $key 须为文本")
    }
    private fun checkIdentity(value: String, field: String) {
        require(value.isNotBlank() && value.length <= MAX_ID_LENGTH) { "$field 身份非法" }
    }
    private fun checkSize(value: String?, field: String) {
        if (value != null) require(value.toByteArray(Charsets.UTF_8).size.toLong() <= MAX_FIELD_BYTES) { "$field 超过大小上限" }
    }
    private fun strictUtf8(bytes: ByteArray): String = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    } catch (_: java.nio.charset.CharacterCodingException) {
        error("归档不是合法 UTF-8")
    }

    /** Digest helper for callers which have a separately persisted blob hash. */
    fun digest(bytes: ByteArray): String = PrivateBlobs.digest(bytes)
    fun decode(bytes: ByteArray, expectedDigest: String): List<Message> {
        require(expectedDigest.matches(Regex("[0-9a-f]{64}"))) { "档案哈希非法" }
        require(digest(bytes) == expectedDigest) { "档案哈希不一致" }
        return decode(bytes)
    }

    private val LEGACY_KEYS = setOf("id", "conversation", "run", "role", "status", "error", "protocol", "blocks")
    private val HISTORY_KEYS = LEGACY_KEYS + "history_segments"
}
