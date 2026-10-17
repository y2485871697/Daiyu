package app.daiyu.android.backup

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Base64
import app.daiyu.android.context.*
import app.daiyu.android.domain.context.ContextRevision
import app.daiyu.android.storage.ConversationDatabase
import app.daiyu.android.domain.ConversationId
import app.daiyu.android.domain.messagesEndpoint
import app.daiyu.android.domain.MessageImageReference
import app.daiyu.android.storage.MessageImageReferenceCodec
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.net.URI

data class BackupInventory(val conversations: Int, val messages: Int, val branches: Int,
    val archives: Int, val attachments: Int, val blobBytes: Long, val configEntries: Int,
    val excluded: List<String>, val requiresAuthorization: Boolean = true,
    val generationOutputs: Int = 0, val generationAttempts: Int = 0, val childTasks: Int = 0,
    val childCommands: Int = 0, val childRequests: Int = 0)
data class ImportResult(val packageId: String, val inventory: BackupInventory, val alreadyImported: Boolean)

/** Extends, never reimplements, schema3 text backup. Blobs are immutable and all visible references/config
 * commit in the same SQLite transaction as the existing text importer. Source is always read-only.
 * A crash before commit leaves old state plus unreferenced immutable files, never half-visible new state.
 */
class NeutralBackupService(private val store: ConversationDatabase, private val blobs: PrivateBlobs,
    private val transactions: DatabaseTransactions = DatabaseTransactions { store.writableDatabase },
    private val imageAssets: BackupImageAssets? = null,
    private val videoIntegration: BackupVideoIntegration? = null,
    private val generationAssets: BackupGenerationAssets? = null) {
    constructor(context: Context, store: ConversationDatabase, imageAssets: BackupImageAssets?,
        videoIntegration: BackupVideoIntegration?, generationAssets: BackupGenerationAssets?) :
        this(store, PrivateBlobs(context), imageAssets = imageAssets, videoIntegration = videoIntegration,
            generationAssets = generationAssets)
    constructor(context: Context, store: ConversationDatabase, imageAssets: BackupImageAssets) :
        this(store, PrivateBlobs(context), imageAssets = imageAssets)
    constructor(context: Context, store: ConversationDatabase, imageAssets: BackupImageAssets?,
        videoIntegration: BackupVideoIntegration) :
        this(store, PrivateBlobs(context), imageAssets = imageAssets, videoIntegration = videoIntegration)
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
        val (safe, excluded) = projectConfiguration(settings)
        transactions.run { db ->
            db.delete("neutral_config", null, null)
            safe.keys().forEach { key ->
                db.insertOrThrow("neutral_config", null, ContentValues().apply { put("name", key); put("value", safe.get(key).toString()) })
            }
        }
        return excluded.distinct()
    }
    private fun projectConfiguration(settings: Map<String, Any?>): Pair<JSONObject, List<String>> {
        val safe = JSONObject(); val excluded = mutableListOf<String>()
        settings.forEach { (key, value) ->
            if (key in CONFIG_KEYS) { require(value is String || value is Boolean); safe.put(key, value) }
            else excluded.add("非白名单配置已剔除（可能含凭据）")
        }
        validateConfig(safe)
        return safe to excluded.distinct()
    }
    /** Reads model preferences without decrypting keys or touching keystore. */
    fun captureNonSecretConfiguration(context: Context): List<String> =
        setNonSecretConfiguration(context.getSharedPreferences("model-settings", Context.MODE_PRIVATE).all)
    fun importedConfiguration(): JSONObject = transactions.run { db -> readConfig(db) }

    fun exportPackage(configuration: Map<String, Any?>? = null): String = exportPackage(configuration, null)
    /** A conversation package excludes global drafts/configuration and never follows parent provenance. */
    fun exportConversation(conversationId: ConversationId): String = exportPackage(emptyMap(), conversationId)
    private fun exportPackage(configuration: Map<String, Any?>?, conversationId: ConversationId?): String {
        // Generation fences use gate -> SQL. Never acquire them from the package SQL transaction.
        // The host holds its common acceptance/deletion lock over BOTH phases. Compare again in
        // the final snapshot so even an incorrectly wired host cannot silently export mixed rows.
        val generationSnapshot = transactions.run { db ->
            BackupGenerationCodec.export(db, conversationId?.value)
        }
        val sourceRows = BackupHistoryRows.rows(generationSnapshot.getJSONArray("outputs"))
        val historical = transactions.run { db -> BackupHistoryRows.history(db, "generation_output", conversationId?.value).keys }
        val sourceReferences = sourceRows.filter { BackupGenerationCodec.key(it) !in historical }.map(BackupGenerationCodec::reference)
        sourceReferences.forEach { require(it.bytes in 1..MAX_BYTES) }
        require(sourceReferences.map { it.sha256 to it.bytes }.distinct().sumOf { it.second } <= MAX_BYTES)
        val deduplicated = mutableMapOf<String, ByteArray>()
        val generationOriginals = sourceReferences.associateWith { reference ->
            val bytes = requireNotNull(generationAssets) { "生成原资产存储未接入；不能无提示丢失生成资产" }.read(reference)
            BackupGenerationCodec.verify(reference, bytes)
            val prior = deduplicated[reference.sha256]
            if (prior == null) bytes.also { deduplicated[reference.sha256] = it }
            else { require(prior.contentEquals(bytes)); prior }
        }
        return exportSnapshot(configuration, conversationId, generationSnapshot, generationOriginals)
    }
    private fun exportSnapshot(configuration: Map<String, Any?>?, conversationId: ConversationId?,
        generationSnapshot: JSONObject,
        generationOriginals: Map<app.daiyu.android.storage.GenerationOutputReference, ByteArray>): String = transactions.run { db ->
        val text = videoIntegration?.exportText?.invoke(db, conversationId)
            ?: if (conversationId == null) store.exportBackup() else store.exportConversationBackup(conversationId)
        store.preflightBackup(text)
        val tables = JSONObject()
        COLUMNS.forEach { (table, _) -> tables.put(table, readTable(db, table, conversationId)) }
        val config = if (configuration == null) readConfig(db) else projectConfiguration(configuration).first
        validateConfig(config)
        val hashes = mutableSetOf<String>()
        val archives = tables.getJSONArray("context_archives"); val assets = tables.getJSONArray("neutral_assets")
        repeat(archives.length()) { hashes.add(archives.getJSONObject(it).getString("blob_hash")) }
        repeat(assets.length()) { hashes.add(assets.getJSONObject(it).getString("blob_hash")) }
        val originals = linkedMapOf<String, ByteArray>(); var total = 0L
        fun include(hash: String, bytes: ByteArray) {
            require(PrivateBlobs.digest(bytes) == hash)
            val previous = originals[hash]
            if (previous != null) require(previous.contentEquals(bytes))
            else { total += bytes.size; require(total <= MAX_BYTES); originals[hash] = bytes }
        }
        hashes.sorted().forEach { hash -> include(hash, blobs.read(hash)) }
        val references = BackupImageManifest.References()
        val textData = objectOf(objectOf(text).getString("payload"))
        references.addRows(textData.getJSONArray("messages"))
        val generationHistory = BackupGenerationCodec.export(db, conversationId?.value)
        require(generationHistory.toString() == generationSnapshot.toString()) { "生成历史在备份期间已变更，请重试" }
        val generation = BackupGenerationCodec.validate(generationHistory, textData.getJSONArray("messages"))
        val childHistory = BackupChildTaskCodec.export(db, conversationId?.value)
        BackupChildTaskCodec.validate(childHistory, textData.getJSONArray("messages"))
        val historicalGeneration = BackupHistoryRows.history(db, "generation_output", conversationId?.value)
        val conversations = textData.getJSONArray("conversations")
        val conversationOwners = (0 until conversations.length()).map { conversations.getJSONObject(it).getString("id") }.toSet()
        repeat(archives.length()) {
            val row = archives.getJSONObject(it)
            require(string(row, "conversation_id") in conversationOwners)
            val archived = ArchiveCodec.decode(originals.getValue(row.getString("blob_hash")))
            // The archive table's persisted owner is authority, not the blob's own claims.
            require(archived.all { it.conversationId.value == string(row, "conversation_id") })
            require(archived.size.toLong() == integer(row, "prefix_count") && ContextRevision.digest(archived) == string(row, "revision"))
            references.addArchive(archived)
        }
        val images = references.all()
        // Check the metadata bound before opening any original image.
        val manifest = BackupImageManifest.encode(images)
        val videoManifest = if (videoIntegration == null) null else BackupVideoManifest.read(db, textData.getJSONArray("messages"))
        val videos = BackupVideoManifest.decode(videoManifest, textData.getJSONArray("messages"))
        // Reserve the deduplicated package byte budget BEFORE opening video originals.
        val sizes = originals.mapValues { it.value.size.toLong() }.toMutableMap()
        fun reserve(hash: String, bytes: Long) {
            val previous = sizes.put(hash, bytes)
            require(previous == null || previous == bytes)
        }
        images.forEach { reserve(it.sha256, it.bytes.toLong()) }
        videos.references.forEach { reserve(it.sha256, it.bytes) }
        generation.references.forEach { reserve(it.sha256, it.bytes) }
        require(sizes.values.sum() <= MAX_BYTES)
        if (images.isNotEmpty()) {
            val access = requireNotNull(imageAssets) { "消息原图存储未接入；不能导出仅含引用的备份" }
            images.forEach { reference ->
                val asset = MessageImageReferenceCodec.toAsset(reference)
                val bytes = access.read(asset.owner, asset)
                BackupImageManifest.verify(reference, bytes)
                include(reference.sha256, bytes)
            }
        }
        videos.references.forEach { reference ->
            val bytes = requireNotNull(videoIntegration).assets.read(reference.owner, reference)
            BackupVideoManifest.verify(reference, bytes)
            include(reference.sha256, bytes)
        }
        generation.outputs.forEach { row ->
            val reference = BackupGenerationCodec.reference(row)
            val bytes = if (historicalGeneration.containsKey(BackupGenerationCodec.key(row))) blobs.read(reference.sha256)
                else generationOriginals.getValue(reference)
            BackupGenerationCodec.verify(reference, bytes)
            include(reference.sha256, bytes)
        }
        val contents = JSONArray()
        originals.toSortedMap().forEach { (hash, bytes) -> contents.put(JSONObject().put("sha256", hash).put("bytes", bytes.size)
            .put("data", Base64.encodeToString(bytes, Base64.NO_WRAP))) }
        val payload = JSONObject().put("text_backup", text).put("tables", tables).put("config", config)
            .put("blobs", contents).put("excluded", JSONArray(listOf("凭据、授权令牌和密钥材料不导出", "生成授权结果及供应商地址不导出；恢复只读历史，不恢复执行许可")))
            .put("generation_history", generationHistory).put("child_task_history", childHistory)
        if (images.isNotEmpty()) payload.put("message_images", manifest)
        if (videos.sidecars.isNotEmpty()) payload.put("message_videos", videoManifest)
        val inventory = inventory(payload)
        payload.put("inventory", inventoryJson(inventory))
        val raw = payload.toString()
        val result = JSONObject().put("format", "daiyu-neutral-complete").put("version", 2)
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
        val access = if (parsed.images.isEmpty()) null else requireNotNull(imageAssets) { "消息原图存储未接入；不能恢复图片引用" }
        val video = if (parsed.videos.sidecars.isEmpty()) null else requireNotNull(videoIntegration) { "原视频存储未接入；不能恢复视频引用" }
        return transactions.run { db ->
            val duplicate = db.rawQuery("SELECT id FROM neutral_imports WHERE id=?", arrayOf(parsed.id)).use { it.moveToFirst() }
            if (duplicate) {
                store.verifyRestoredBackup(db, parsed.payload.getString("text_backup"))
                verifyImportedTables(db, parsed.payload.getJSONObject("tables"))
                verifyPrivateContents(parsed)
                BackupGenerationCodec.verifyRestored(db, parsed.generation)
                BackupChildTaskCodec.verifyRestored(db, parsed.children)
                verifyImportedConfiguration(db, parsed.payload.getJSONObject("config"))
                store.verifyImportedVideoWitnesses(db, parsed.payload.getString("text_backup"))
                // A marker alone is not evidence that the original assets still exist.
                parsed.images.forEach { reference ->
                    val asset = MessageImageReferenceCodec.toAsset(reference)
                    BackupImageManifest.verify(reference, requireNotNull(access).read(asset.owner, asset))
                }
                BackupVideoManifest.verifySidecars(db, parsed.videos.sidecars)
                parsed.videos.references.forEach { reference ->
                    BackupVideoManifest.verify(reference, requireNotNull(video).assets.read(reference.owner, reference))
                }
                return@run ImportResult(parsed.id, parsed.inventory, true)
            }
            // Existing parser validates text protocol, IDs, drafts and collisions; nested transaction shares helper.
            if (video == null) store.importBackup(parsed.payload.getString("text_backup"))
            else store.importBackupForVideoAssets(db, parsed.payload.getString("text_backup"))
            parsed.bytes.forEach { (hash, bytes) -> require(blobs.put(bytes) == hash) }
            COLUMNS.forEach { (table, columns) -> val rows = parsed.payload.getJSONObject("tables").getJSONArray(table)
                repeat(rows.length()) { val row = rows.getJSONObject(it); val values = ContentValues()
                    columns.forEach { key -> when (val value = row.get(key)) {
                        JSONObject.NULL -> values.putNull(key)
                        is String -> values.put(key, value)
                        is Number -> values.put(key, value.toLong())
                        else -> error("非法字段")
                    } }
                    check(db.insertOrThrow(table, null, values) != -1L) { "备份附表未写入" }
                }
            }
            val config = parsed.payload.getJSONObject("config")
            config.keys().forEach { key ->
                val current = db.rawQuery("SELECT value FROM neutral_config WHERE name=?", arrayOf(key)).use { if (it.moveToFirst()) it.getString(0) else null }
                require(current == null || current == config.get(key).toString()) { "非秘密配置冲突，未覆盖当前账户" }
                if (current == null) check(db.insertOrThrow("neutral_config", null, ContentValues().apply { put("name", key); put("value", config.get(key).toString()) }) != -1L) { "备份配置未写入" }
            }
            BackupGenerationCodec.restore(db, parsed.generation)
            BackupChildTaskCodec.restore(db, parsed.children)
            if (video != null) {
                // Parent persists against the actual imported physical rows, in THIS transaction.
                video.restoreSidecars(db, parsed.videos.sidecars)
                store.completeVideoBackupWitnesses(db, parsed.payload.getString("text_backup"), parsed.videos.sidecars)
                BackupVideoManifest.verifySidecars(db, parsed.videos.sidecars)
            }
            // All package bytes/references were checked before the transaction; publish immutable
            // owner batches only after SQL collisions/config conflicts have passed. A later SQL
            // failure can leave harmless orphans, never delete/overwrite pre-existing assets.
            parsed.images.groupBy { it.owner }.forEach { (_, references) ->
                val assets = references.map(MessageImageReferenceCodec::toAsset)
                val destination = requireNotNull(access)
                destination.restore(assets.first().owner, assets, references.map { parsed.bytes.getValue(it.sha256) })
                references.zip(assets).forEach { (reference, asset) ->
                    BackupImageManifest.verify(reference, destination.read(asset.owner, asset))
                }
            }
            parsed.videos.references.groupBy { it.owner }.forEach { (owner, references) ->
                val destination = requireNotNull(video).assets
                destination.restore(owner, references, references.map { parsed.bytes.getValue(it.sha256) })
                references.forEach { reference ->
                    BackupVideoManifest.verify(reference, destination.read(owner, reference))
                }
            }
            val importedAt = System.currentTimeMillis()
            check(db.insertOrThrow("neutral_imports", null, ContentValues().apply {
                put("id", parsed.id); put("created", importedAt)
            }) != -1L) { "备份导入标记未写入" }
            // The last SQL write can run triggers too; validate receipts only after it finishes.
            check(db.rawQuery("SELECT 1 FROM neutral_imports WHERE id=? AND created=?",
                arrayOf(parsed.id, importedAt.toString())).use { it.moveToFirst() }) { "备份导入标记回执不匹配" }
            store.verifyRestoredBackup(db, parsed.payload.getString("text_backup"))
            verifyImportedTables(db, parsed.payload.getJSONObject("tables"))
            verifyPrivateContents(parsed)
            BackupGenerationCodec.verifyRestored(db, parsed.generation)
            BackupChildTaskCodec.verifyRestored(db, parsed.children)
            verifyImportedConfiguration(db, config)
            BackupVideoManifest.verifySidecars(db, parsed.videos.sidecars)
            ImportResult(parsed.id, parsed.inventory, false)
        }
    }
    /** Observational history only. These receipts cannot be passed off as registered live outputs. */
    fun historicalGenerationOutputs(conversationId: ConversationId? = null): List<HistoricalGenerationOutput> = transactions.run { db ->
        BackupHistoryRows.history(db, "generation_output", conversationId?.value).values.map {
            HistoricalGenerationOutput(BackupGenerationCodec.reference(it))
        }
    }
    fun historicalGenerationOriginal(reference: HistoricalGenerationOutput): ByteArray = transactions.run { db ->
        require(BackupHistoryRows.history(db, "generation_output", reference.conversationId).values
            .map(BackupGenerationCodec::reference).contains(reference.source))
        blobs.read(reference.sha256).also { BackupGenerationCodec.verify(reference.source, it) }
    }
    private fun verifyPrivateContents(parsed: Parsed) {
        // Package metadata and decoded bytes are already capped at MAX_BYTES. No untrusted path
        // is accepted. The first wrong target digest/size fails, without repairing or overwriting it.
        parsed.bytes.forEach { (hash, expected) ->
            val actual = blobs.read(hash)
            check(actual.size == expected.size && actual.contentEquals(expected)) { "目标备份原文或附件损坏" }
        }
    }
    private fun verifyImportedConfiguration(db: SQLiteDatabase, config: JSONObject) {
        config.keys().forEach { key ->
            check(db.rawQuery("SELECT 1 FROM neutral_config WHERE name=? AND value=?",
                arrayOf(key, config.get(key).toString())).use { it.moveToFirst() }) { "备份配置回执不匹配" }
        }
    }
    private fun verifyImportedTables(db: SQLiteDatabase, tables: JSONObject) {
        COLUMNS.forEach { (table, columns) ->
            val rows = tables.getJSONArray(table)
            repeat(rows.length()) { index ->
                val row = rows.getJSONObject(index)
                val args = columns.map { key -> if (row.isNull(key)) null else row.get(key).toString() }.toTypedArray()
                check(db.rawQuery("SELECT 1 FROM $table WHERE " + columns.joinToString(" AND ") { "$it IS ?" } + " LIMIT 1",
                    args).use { it.moveToFirst() }) { "备份附表回执不匹配" }
            }
        }
    }
    private data class Parsed(val id: String, val payload: JSONObject, val bytes: Map<String, ByteArray>,
        val inventory: BackupInventory, val images: List<MessageImageReference>,
        val videos: BackupVideoManifest.Validated, val generation: BackupGenerationCodec.Validated,
        val children: BackupChildTaskCodec.Validated)
    private fun validate(text: String): Parsed {
        require(text.toByteArray(Charsets.UTF_8).size.toLong() <= MAX_PACKAGE)
        val envelope = objectOf(text); fields(envelope, setOf("format", "version", "sha256", "payload"))
        val version = integer(envelope, "version")
        require(envelope.get("format") == "daiyu-neutral-complete" && version in 1L..2L)
        val raw = string(envelope, "payload"); val id = PrivateBlobs.digest(raw.toByteArray(Charsets.UTF_8))
        require(id == string(envelope, "sha256")) { "备份摘要不匹配" }
        val payload = objectOf(raw)
        val payloadFields = setOf("text_backup", "tables", "config", "blobs", "excluded", "inventory")
        fields(payload, payloadFields + listOf("message_images", "message_videos").filter { payload.has(it) } +
            if (version == 2L) setOf("generation_history", "child_task_history") else emptySet())
        store.preflightBackup(string(payload, "text_backup"))
        val legacy = objectOf(string(payload, "text_backup"))
        require(legacy.getString("format") == "daiyu-neutral-text" && legacy.getInt("version") in 1..2)
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
            total += size; require(total <= MAX_BYTES) // Reserve before allocating decoded originals.
            val data = string(blob, "data"); require(data.length.toLong() <= (size + 2) / 3 * 4)
            val decoded = Base64.decode(data, Base64.NO_WRAP); require(decoded.size.toLong() == size && PrivateBlobs.digest(decoded) == hash)
            bytes[hash] = decoded
        }
        val used = mutableSetOf<String>()
        val imageReferences = BackupImageManifest.References().apply { addRows(messages) }
        val archives = tables.getJSONArray("context_archives")
        repeat(archives.length()) { val row = archives.getJSONObject(it)
            val owner = string(row, "conversation_id"); require(owner in conversationIds)
            val hash = string(row, "blob_hash"); used.add(hash); val archived = ArchiveCodec.decode(bytes[hash] ?: error("缺原文档案"))
            require(archived.all { it.conversationId.value == owner } && archived.size.toLong() == integer(row, "prefix_count"))
            require(ContextRevision.digest(archived) == string(row, "revision")); require(string(row, "summary").isNotBlank())
            imageReferences.addArchive(archived)
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
        val images = BackupImageManifest.validate(payload, imageReferences, bytes)
        images.forEach { used.add(it.sha256) }
        val videos = BackupVideoManifest.validate(
            if (payload.has("message_videos")) payload.getJSONObject("message_videos") else null, messages, bytes)
        videos.references.forEach { used.add(it.sha256) }
        val generation = BackupGenerationCodec.validate(if (version == 2L) payload.getJSONObject("generation_history") else null, messages, bytes)
        val children = BackupChildTaskCodec.validate(if (version == 2L) payload.getJSONObject("child_task_history") else null, messages)
        generation.references.forEach { used.add(it.sha256) }
        require(used == bytes.keys) { "附件/归档/图片/视频/生成资产清单不一致" }
        validateConfig(payload.getJSONObject("config"))
        val excluded = payload.getJSONArray("excluded"); require(excluded.length() <= 20)
        repeat(excluded.length()) { require(excluded.get(it) is String && excluded.getString(it).length <= 200) }
        val inventory = inventory(payload)
        val declared = payload.getJSONObject("inventory"); val expected = inventoryJson(inventory, version == 2L)
        fields(declared, expected.keys().asSequence().toSet())
        expected.keys().forEach { key -> require(declared.get(key) == expected.get(key) ||
            declared.get(key) is Number && expected.get(key) is Number &&
                integer(declared, key) == integer(expected, key)) { "数量或字节清单不一致" } }
        return Parsed(id, payload, bytes, inventory, images, videos, generation, children)
    }
    private fun inventory(payload: JSONObject): BackupInventory {
        val legacy = objectOf(payload.getString("text_backup")); val tables = payload.getJSONObject("tables")
        val blobs = payload.getJSONArray("blobs"); var size = 0L
        repeat(blobs.length()) { size = Math.addExact(size, integer(blobs.getJSONObject(it), "bytes")) }
        val excluded = payload.getJSONArray("excluded")
        val generation = payload.optJSONObject("generation_history")
        val children = payload.optJSONObject("child_task_history")
        val generationCount = generation?.getJSONArray("outputs")?.length() ?: 0
        return BackupInventory(legacy.getInt("conversation_count"), legacy.getInt("message_count"),
            tables.getJSONArray("context_branches").length(), tables.getJSONArray("context_archives").length(),
            tables.getJSONArray("neutral_assets").length() + (payload.optJSONArray("message_images")?.length() ?: 0) +
                BackupVideoManifest.decode(if (payload.has("message_videos")) payload.getJSONObject("message_videos") else null,
                    objectOf(legacy.getString("payload")).getJSONArray("messages")).references.size + generationCount,
            size, payload.getJSONObject("config").length(),
            (0 until excluded.length()).map { excluded.getString(it) }, generationOutputs = generationCount,
            generationAttempts = generation?.getJSONArray("attempts")?.length() ?: 0,
            childTasks = children?.getJSONArray("tasks")?.length() ?: 0,
            childCommands = children?.getJSONArray("commands")?.length() ?: 0,
            childRequests = children?.getJSONArray("usage")?.length() ?: 0)
    }
    private fun inventoryJson(i: BackupInventory, extended: Boolean = true) = JSONObject().put("conversations", i.conversations).put("messages", i.messages)
        .put("branches", i.branches).put("archives", i.archives).put("attachments", i.attachments)
        .put("blob_bytes", i.blobBytes).put("config_entries", i.configEntries).put("requires_authorization", true).apply {
            if (extended) {
                put("generation_outputs", i.generationOutputs); put("generation_attempts", i.generationAttempts)
                put("child_tasks", i.childTasks); put("child_commands", i.childCommands); put("child_requests", i.childRequests)
            }
        }
    private fun readTable(db: SQLiteDatabase, table: String, conversationId: ConversationId?): JSONArray {
        val columns = COLUMNS.getValue(table)
        val where = if (conversationId == null) "" else when (table) {
            "context_branches", "context_archives" -> " WHERE conversation_id=?"
            "context_usage" -> " WHERE branch_id=?"
            "neutral_assets" -> " WHERE message_id IN (SELECT id FROM messages WHERE conversation_id=?)"
            else -> error("未知备份表")
        }
        return db.rawQuery("SELECT ${columns.joinToString(",")} FROM $table$where ORDER BY ${columns.first()}",
            conversationId?.let { arrayOf(it.value) }).use { c ->
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
                if(key == "protocol") require(value == "responses" || value == "messages") { "模型协议无效" }
                if (key == "endpoint" && value.isNotEmpty()) {
                    val uri = URI(value); require(uri.scheme == "https" && uri.host != null && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null)
                }
                if (key == "responses-path" && value.isNotEmpty()) require(value.startsWith("/") && !value.contains('?') && !value.contains('#') && !value.contains(".."))
                require(!value.contains('\n') && !value.contains('\r'))
            }
        }
        if(config.optString("protocol") == "messages") {
            require(!config.has("responses-path") || config.get("responses-path") == "")
            if(config.has("endpoint") && config.getString("endpoint").isNotEmpty()) messagesEndpoint(config.getString("endpoint"))
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
        private val CONFIG_KEYS = setOf("endpoint", "model", "responses-path", "protocol") + BOOLEAN_KEYS
        private val COLUMNS = linkedMapOf(
            "context_branches" to listOf("conversation_id", "parent_id", "through_message", "parent_revision"),
            "context_archives" to listOf("id", "conversation_id", "prefix_count", "revision", "summary", "blob_hash", "created", "active"),
            "context_usage" to listOf("request_id", "run_id", "branch_id", "input_tokens", "output_tokens", "cached_tokens", "reasoning_tokens"),
            "neutral_assets" to listOf("id", "message_id", "kind", "mime", "bytes", "blob_hash")
        )
    }
}
