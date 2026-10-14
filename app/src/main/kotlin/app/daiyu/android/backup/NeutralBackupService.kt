package app.daiyu.android.backup

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Base64
import app.daiyu.android.context.*
import app.daiyu.android.domain.context.ContextRevision
import app.daiyu.android.storage.ConversationDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.net.URI

data class BackupInventory(val conversations: Int, val messages: Int, val branches: Int,
    val archives: Int, val attachments: Int, val blobBytes: Long, val configEntries: Int,
    val excluded: List<String>, val requiresAuthorization: Boolean = true)
data class ImportResult(val packageId: String, val inventory: BackupInventory, val alreadyImported: Boolean)

/** Extends, never reimplements, schema3 text backup. Blobs are immutable and all visible references/config
 * commit in the same SQLite transaction as the existing text importer. Source is always read-only.
 * A crash before commit leaves old state plus unreferenced immutable files, never half-visible new state.
 */
class NeutralBackupService(private val store: ConversationDatabase, private val blobs: PrivateBlobs,
    private val transactions: DatabaseTransactions = DatabaseTransactions { store.writableDatabase }) {
    constructor(context: Context) : this(ConversationDatabase(context), PrivateBlobs(context))
    constructor(context: Context, store: ConversationDatabase) : this(store, PrivateBlobs(context))

    /** Explicit asset registration: no path supplied by caller ever becomes a filesystem path. */
    fun attach(id: String, messageId: String, mime: String, bytes: ByteArray) {
        identity(id); identity(messageId); require(mime.matches(Regex("[a-zA-Z0-9.+-]+/[a-zA-Z0-9.+-]+")))
        require(bytes.size.toLong() <= PrivateBlobs.MAX_BLOB)
        transactions.run { db ->
            db.rawQuery("SELECT id FROM messages WHERE id=?", arrayOf(messageId)).use { require(it.moveToFirst()) }
            val hash = blobs.put(bytes)
            db.insertOrThrow("neutral_assets", null, ContentValues().apply {
                put("id", id); put("message_id", messageId); put("kind", "attachment"); put("mime", mime)
                put("bytes", bytes.size); put("blob_hash", hash)
            })
        }
    }
    fun attachment(id: String): ByteArray = transactions.run { db ->
        db.rawQuery("SELECT blob_hash,bytes FROM neutral_assets WHERE id=?", arrayOf(id)).use { c ->
            require(c.moveToFirst()); blobs.read(c.getString(0)).also { require(it.size.toLong() == c.getLong(1)) }
        }
    }
    /** Allowlist projection. Unknown/secret keys are not serialized; only key names have fixed reasons. */
    fun setNonSecretConfiguration(settings: Map<String, Any?>): List<String> {
        val safe = JSONObject(); val excluded = mutableListOf<String>()
        settings.forEach { (key, value) ->
            if (key in CONFIG_KEYS) { require(value is String || value is Boolean); safe.put(key, value) }
            else excluded.add("非白名单配置已剔除（可能含凭据）")
        }
        validateConfig(safe)
        transactions.run { db -> safe.keys().forEach { key ->
            db.insertWithOnConflict("neutral_config", null, ContentValues().apply { put("name", key); put("value", safe.get(key).toString()) }, SQLiteDatabase.CONFLICT_REPLACE)
        } }
        return excluded.distinct()
    }
    /** Reads model preferences without decrypting keys or touching keystore. */
    fun captureNonSecretConfiguration(context: Context): List<String> =
        setNonSecretConfiguration(context.getSharedPreferences("model-settings", Context.MODE_PRIVATE).all)
    fun importedConfiguration(): JSONObject = transactions.run { db -> readConfig(db) }

    fun exportPackage(): String = transactions.run { db ->
        val text = store.exportBackup()
        val tables = JSONObject()
        COLUMNS.forEach { (table, _) -> tables.put(table, readTable(db, table)) }
        val config = readConfig(db); validateConfig(config)
        val hashes = mutableSetOf<String>()
        val archives = tables.getJSONArray("context_archives"); val assets = tables.getJSONArray("neutral_assets")
        repeat(archives.length()) { hashes.add(archives.getJSONObject(it).getString("blob_hash")) }
        repeat(assets.length()) { hashes.add(assets.getJSONObject(it).getString("blob_hash")) }
        val contents = JSONArray(); var total = 0L
        hashes.sorted().forEach { hash -> val bytes = blobs.read(hash); total += bytes.size
            require(total <= MAX_BYTES); contents.put(JSONObject().put("sha256", hash).put("bytes", bytes.size)
                .put("data", Base64.encodeToString(bytes, Base64.NO_WRAP))) }
        val payload = JSONObject().put("text_backup", text).put("tables", tables).put("config", config)
            .put("blobs", contents).put("excluded", JSONArray(listOf("凭据、授权令牌和密钥材料不导出")))
        val inventory = inventory(payload)
        payload.put("inventory", inventoryJson(inventory))
        val raw = payload.toString()
        val result = JSONObject().put("format", "daiyu-neutral-complete").put("version", 1)
            .put("sha256", PrivateBlobs.digest(raw.toByteArray(Charsets.UTF_8))).put("payload", raw).toString()
        require(result.toByteArray(Charsets.UTF_8).size.toLong() <= MAX_PACKAGE)
        validate(result); result
    }
    fun preflight(text: String): BackupInventory = validate(text).inventory
    fun importPackage(source: InputStream): ImportResult {
        // No ownership/close/mutation of the old source, and bounded reading before JSON allocation.
        val bytes = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
        while (true) { val n = source.read(buffer); if (n < 0) break
            require(bytes.size().toLong() + n <= MAX_PACKAGE); bytes.write(buffer, 0, n) }
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
        return importPackage(decoder.decode(java.nio.ByteBuffer.wrap(bytes.toByteArray())).toString())
    }
    fun importPackage(text: String): ImportResult {
        val parsed = validate(text)
        return transactions.run { db ->
            val duplicate = db.rawQuery("SELECT id FROM neutral_imports WHERE id=?", arrayOf(parsed.id)).use { it.moveToFirst() }
            if (duplicate) return@run ImportResult(parsed.id, parsed.inventory, true)
            // Existing parser validates text protocol, IDs, drafts and collisions; nested transaction shares helper.
            store.importBackup(parsed.payload.getString("text_backup"))
            parsed.bytes.forEach { (hash, bytes) -> require(blobs.put(bytes) == hash) }
            COLUMNS.forEach { (table, columns) -> val rows = parsed.payload.getJSONObject("tables").getJSONArray(table)
                repeat(rows.length()) { val row = rows.getJSONObject(it); val values = ContentValues()
                    columns.forEach { key -> when (val value = row.get(key)) {
                        JSONObject.NULL -> values.putNull(key)
                        is String -> values.put(key, value)
                        is Number -> values.put(key, value.toLong())
                        else -> error("非法字段")
                    } }
                    db.insertOrThrow(table, null, values)
                }
            }
            val config = parsed.payload.getJSONObject("config")
            config.keys().forEach { key ->
                val current = db.rawQuery("SELECT value FROM neutral_config WHERE name=?", arrayOf(key)).use { if (it.moveToFirst()) it.getString(0) else null }
                require(current == null || current == config.get(key).toString()) { "非秘密配置冲突，未覆盖当前账户" }
                if (current == null) db.insertOrThrow("neutral_config", null, ContentValues().apply { put("name", key); put("value", config.get(key).toString()) })
            }
            db.insertOrThrow("neutral_imports", null, ContentValues().apply { put("id", parsed.id); put("created", System.currentTimeMillis()) })
            ImportResult(parsed.id, parsed.inventory, false)
        }
    }
    private data class Parsed(val id: String, val payload: JSONObject, val bytes: Map<String, ByteArray>, val inventory: BackupInventory)
    private fun validate(text: String): Parsed {
        require(text.toByteArray(Charsets.UTF_8).size.toLong() <= MAX_PACKAGE)
        val envelope = objectOf(text); fields(envelope, setOf("format", "version", "sha256", "payload"))
        require(envelope.get("format") == "daiyu-neutral-complete" && integer(envelope, "version") == 1L)
        val raw = string(envelope, "payload"); val id = PrivateBlobs.digest(raw.toByteArray(Charsets.UTF_8))
        require(id == string(envelope, "sha256")) { "备份摘要不匹配" }
        val payload = objectOf(raw); fields(payload, setOf("text_backup", "tables", "config", "blobs", "excluded", "inventory"))
        val legacy = objectOf(string(payload, "text_backup"))
        require(legacy.getString("format") == "daiyu-neutral-text" && legacy.getInt("version") == 1)
        val oldRaw = string(legacy, "payload"); require(PrivateBlobs.digest(oldRaw.toByteArray(Charsets.UTF_8)) == legacy.getString("sha256"))
        val old = objectOf(oldRaw); val conversations = old.getJSONArray("conversations"); val messages = old.getJSONArray("messages")
        require(conversations.length() <= 1000 && messages.length() <= 50000)
        require(legacy.getInt("conversation_count") == conversations.length() && legacy.getInt("message_count") == messages.length())
        val conversationIds = uniqueIds(conversations, "id"); val messageIds = uniqueIds(messages, "id")
        repeat(messages.length()) { require(messages.getJSONObject(it).getString("conversation_id") in conversationIds) }
        val tables = payload.getJSONObject("tables"); fields(tables, COLUMNS.keys)
        COLUMNS.forEach { (table, columns) -> val rows = tables.getJSONArray(table); require(rows.length() <= 50000)
            val primary = columns.first(); uniqueIds(rows, primary)
            repeat(rows.length()) { fields(rows.getJSONObject(it), columns.toSet()) }
        }
        val bytes = mutableMapOf<String, ByteArray>(); var total = 0L
        val encoded = payload.getJSONArray("blobs"); require(encoded.length() <= 50000)
        repeat(encoded.length()) { val blob = encoded.getJSONObject(it); fields(blob, setOf("sha256", "bytes", "data"))
            val hash = string(blob, "sha256"); require(hash.matches(Regex("[0-9a-f]{64}")) && hash !in bytes)
            val size = integer(blob, "bytes"); require(size in 0..PrivateBlobs.MAX_BLOB)
            val data = string(blob, "data"); require(data.length.toLong() <= (size + 2) / 3 * 4)
            val decoded = Base64.decode(data, Base64.NO_WRAP); require(decoded.size.toLong() == size && PrivateBlobs.digest(decoded) == hash)
            total += size; require(total <= MAX_BYTES); bytes[hash] = decoded
        }
        val used = mutableSetOf<String>()
        val archives = tables.getJSONArray("context_archives")
        repeat(archives.length()) { val row = archives.getJSONObject(it)
            val owner = string(row, "conversation_id"); require(owner in conversationIds)
            val hash = string(row, "blob_hash"); used.add(hash); val archived = ArchiveCodec.decode(bytes[hash] ?: error("缺原文档案"))
            require(archived.all { it.conversationId.value == owner } && archived.size.toLong() == integer(row, "prefix_count"))
            require(ContextRevision.digest(archived) == string(row, "revision")); require(string(row, "summary").isNotBlank())
            require(integer(row, "active") in 0..1); integer(row, "created")
        }
        val assets = tables.getJSONArray("neutral_assets")
        repeat(assets.length()) { val row = assets.getJSONObject(it); require(string(row, "message_id") in messageIds)
            require(string(row, "kind") == "attachment" && string(row, "mime").matches(Regex("[a-zA-Z0-9.+-]+/[a-zA-Z0-9.+-]+")))
            val hash = string(row, "blob_hash"); used.add(hash)
            require((bytes[hash] ?: error("缺附件")).size.toLong() == integer(row, "bytes"))
        }
        val branches = tables.getJSONArray("context_branches")
        repeat(branches.length()) { val row = branches.getJSONObject(it)
            require(string(row, "conversation_id") in conversationIds)
            // Parent may have been deleted after the copy was materialized: provenance only, no live foreign prefix.
            identity(string(row, "parent_id")); identity(string(row, "through_message"))
            require(string(row, "parent_revision").matches(Regex("[0-9a-f]{64}")))
        }
        val usage = tables.getJSONArray("context_usage")
        repeat(usage.length()) { val r = usage.getJSONObject(it)
            fun n(key: String): Long? = if (r.isNull(key)) null else integer(r, key)
            require(string(r, "branch_id") in conversationIds)
            app.daiyu.android.domain.context.UsageRecord(string(r, "request_id"), string(r, "run_id"), string(r, "branch_id"), n("input_tokens"), n("output_tokens"), n("cached_tokens"), n("reasoning_tokens"))
        }
        require(used == bytes.keys) { "附件/归档清单不一致" }
        validateConfig(payload.getJSONObject("config"))
        val excluded = payload.getJSONArray("excluded"); require(excluded.length() <= 20)
        repeat(excluded.length()) { require(excluded.get(it) is String && excluded.getString(it).length <= 200) }
        val inventory = inventory(payload)
        val declared = payload.getJSONObject("inventory"); val expected = inventoryJson(inventory)
        fields(declared, expected.keys().asSequence().toSet())
        expected.keys().forEach { key -> require(declared.get(key) == expected.get(key) ||
            declared.get(key) is Number && expected.get(key) is Number &&
                integer(declared, key) == integer(expected, key)) { "数量或字节清单不一致" } }
        return Parsed(id, payload, bytes, inventory)
    }
    private fun inventory(payload: JSONObject): BackupInventory {
        val legacy = objectOf(payload.getString("text_backup")); val tables = payload.getJSONObject("tables")
        val blobs = payload.getJSONArray("blobs"); var size = 0L
        repeat(blobs.length()) { size = Math.addExact(size, integer(blobs.getJSONObject(it), "bytes")) }
        val excluded = payload.getJSONArray("excluded")
        return BackupInventory(legacy.getInt("conversation_count"), legacy.getInt("message_count"),
            tables.getJSONArray("context_branches").length(), tables.getJSONArray("context_archives").length(),
            tables.getJSONArray("neutral_assets").length(), size, payload.getJSONObject("config").length(),
            (0 until excluded.length()).map { excluded.getString(it) })
    }
    private fun inventoryJson(i: BackupInventory) = JSONObject().put("conversations", i.conversations).put("messages", i.messages)
        .put("branches", i.branches).put("archives", i.archives).put("attachments", i.attachments)
        .put("blob_bytes", i.blobBytes).put("config_entries", i.configEntries).put("requires_authorization", true)
    private fun readTable(db: SQLiteDatabase, table: String): JSONArray {
        val columns = COLUMNS.getValue(table)
        return db.rawQuery("SELECT ${columns.joinToString(",")} FROM $table ORDER BY ${columns.first()}", null).use { c ->
            JSONArray().apply { while (c.moveToNext()) { val row = JSONObject(); columns.forEachIndexed { i, key ->
                row.put(key, when { c.isNull(i) -> JSONObject.NULL; c.getType(i) == android.database.Cursor.FIELD_TYPE_INTEGER -> c.getLong(i); else -> c.getString(i) })
            }; put(row) } }
        }
    }
    private fun readConfig(db: SQLiteDatabase): JSONObject = db.rawQuery("SELECT name,value FROM neutral_config ORDER BY name", null).use { c ->
        JSONObject().apply { while (c.moveToNext()) {
            val key = c.getString(0); val value = c.getString(1)
            put(key, if (key in BOOLEAN_KEYS) { require(value in setOf("true", "false")); value == "true" } else value)
        } }
    }
    private fun validateConfig(config: JSONObject) {
        require(config.keys().asSequence().all { it in CONFIG_KEYS }) { "拒绝凭据或未知配置字段" }
        config.keys().forEach { key ->
            if (key in BOOLEAN_KEYS) require(config.get(key) is Boolean)
            else { val value = string(config, key); require(value.length <= 2000)
                if (key == "endpoint" && value.isNotEmpty()) {
                    val uri = URI(value); require(uri.scheme == "https" && uri.host != null && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null)
                }
                if (key == "responses-path" && value.isNotEmpty()) require(value.startsWith("/") && !value.contains('?') && !value.contains('#') && !value.contains(".."))
                require(!value.contains('\n') && !value.contains('\r'))
            }
        }
    }
    private fun uniqueIds(rows: JSONArray, key: String): Set<String> = mutableSetOf<String>().apply {
        repeat(rows.length()) { require(add(identity(string(rows.getJSONObject(it), key)))) { "重复身份" } }
    }
    private fun identity(value: String): String { require(value.isNotBlank() && value.length <= 200 && !value.contains('/') && !value.contains('\\') && value != "." && value != ".."); return value }
    private fun fields(value: JSONObject, expected: Set<String>) { require(value.keys().asSequence().toSet() == expected) { "未知字段（可能含凭据）" } }
    private fun string(value: JSONObject, key: String): String = value.get(key) as? String ?: error("字段须为文本")
    private fun integer(value: JSONObject, key: String): Long { val n = value.get(key); require(n is Int || n is Long); return (n as Number).toLong() }
    private fun objectOf(text: String): JSONObject {
        var depth = 0; var quoted = false; var escaped = false
        text.forEach { ch -> if (quoted) { if (escaped) escaped = false else if (ch == '\\') escaped = true else if (ch == '"') quoted = false }
            else when (ch) { '"' -> quoted = true; '{', '[' -> { depth++; require(depth <= 32) }; '}', ']' -> { depth--; require(depth >= 0) } } }
        require(!quoted && depth == 0); val parser = org.json.JSONTokener(text); val obj = parser.nextValue()
        require(obj is JSONObject && parser.nextClean() == '\u0000'); return obj
    }
    companion object {
        const val MAX_BYTES = 64L * 1024 * 1024
        const val MAX_PACKAGE = 112L * 1024 * 1024
        private val BOOLEAN_KEYS = setOf("battery", "device-status")
        private val CONFIG_KEYS = setOf("endpoint", "model", "responses-path") + BOOLEAN_KEYS
        private val COLUMNS = linkedMapOf(
            "context_branches" to listOf("conversation_id", "parent_id", "through_message", "parent_revision"),
            "context_archives" to listOf("id", "conversation_id", "prefix_count", "revision", "summary", "blob_hash", "created", "active"),
            "context_usage" to listOf("request_id", "run_id", "branch_id", "input_tokens", "output_tokens", "cached_tokens", "reasoning_tokens"),
            "neutral_assets" to listOf("id", "message_id", "kind", "mime", "bytes", "blob_hash")
        )
    }
}
