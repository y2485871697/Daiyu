package app.daiyu.android.backup

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
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
    val childCommands: Int = 0, val childRequests: Int = 0,
    val runtimeUsageCheckpoints: Int = 0, val runtimeUsageRequests: Int = 0,
    val staticGenerationOutputs: Int = 0)
data class ImportResult(val packageId: String, val inventory: BackupInventory, val alreadyImported: Boolean,
    val cleanupProblem: String? = null)

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

    private var staticAssets: app.daiyu.android.media.StaticGenerationAssets? = null
    internal fun withStaticAssets(assets: app.daiyu.android.media.StaticGenerationAssets) = apply { staticAssets = assets }
    internal fun staticGenerationOutputs(conversationId: ConversationId? = null) = transactions.run { db ->
        StaticGenerationCodec.read(db, conversationId?.value)
    }
    internal fun findStaticGeneration(identity: String) = transactions.run { db -> StaticGenerationCodec.find(db, identity) }
    internal fun staticGenerationOriginal(output: app.daiyu.android.storage.StaticGenerationOutput): ByteArray {
        check(!transactions.database().inTransaction()) { "静态原件不能在SQL事务内读取" }
        return requireNotNull(staticAssets) { "静态生成原件存储未接入；不能静默遗漏" }
            .openVerified(output, ::findStaticGeneration).use { it.readBytes() }
    }

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
    private data class ExportCapture(val text: String, val tables: JSONObject, val config: JSONObject,
        val generation: JSONObject, val children: JSONObject, val runtime: JSONObject,
        val static: List<app.daiyu.android.storage.StaticGenerationOutput>, val historical: Set<String>, val video: JSONObject?) {
        fun signature() = listOf(text, tables.toString(), config.toString(), generation.toString(), children.toString(),
            runtime.toString(), StaticGenerationCodec.encodeSection(static).toString(), historical.sorted().toString(), video?.toString())
    }
    private fun captureExport(configuration: Map<String, Any?>?, conversationId: ConversationId?) = transactions.run { db ->
        val text = videoIntegration?.exportText?.invoke(db, conversationId)
            ?: if (conversationId == null) store.exportBackup() else store.exportConversationBackup(conversationId)
        store.preflightBackup(text)
        val tables = JSONObject()
        columnsFor(6).forEach { (table, columns) -> tables.put(table,
            if (table == "context_usage") BackupUsageCodec.export(db, conversationId?.value) else readTable(db, table, columns, conversationId)) }
        val config = if (configuration == null) readConfig(db) else projectConfiguration(configuration).first
        validateConfig(config)
        val messages = objectOf(objectOf(text).getString("payload")).getJSONArray("messages")
        ExportCapture(text, tables, config, BackupGenerationCodec.export(db, conversationId?.value),
            BackupChildTaskCodec.export(db, conversationId?.value), BackupRuntimeUsageCodec.export(db, conversationId?.value),
            StaticGenerationCodec.read(db, conversationId?.value),
            BackupHistoryRows.history(db, "generation_output", conversationId?.value).keys.toSet(),
            if (videoIntegration == null) null else BackupVideoManifest.read(db, messages))
    }
    private fun exportPackage(configuration: Map<String, Any?>?, conversationId: ConversationId?): String =
        prepareExport(configuration, conversationId).use { it.file.readText(Charsets.UTF_8) }

    internal class PreparedExport internal constructor(private val spool: BackupPackageSpool, internal val file: java.io.File) : java.io.Closeable {
        fun writeTo(target: java.io.OutputStream) = file.inputStream().use { it.copyTo(target) }
        override fun close() = spool.close()
    }
    internal fun prepareExport(configuration: Map<String, Any?>? = null, conversationId: ConversationId? = null): PreparedExport {
        val spool = BackupPackageSpool(blobs.backupStagingParent)
        try { return prepareExport(if (conversationId == null) configuration else emptyMap(), conversationId, spool) }
        catch (failure: Throwable) {
            try { spool.close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }
    private fun prepareExport(configuration: Map<String, Any?>?, conversationId: ConversationId?, spool: BackupPackageSpool): PreparedExport {
        check(!transactions.database().inTransaction()) { "完整备份原件读取不能在外层SQL事务内" }
        val capture = captureExport(configuration, conversationId)
        // T1 is fully released before any original reader/fence is acquired.
        val text = capture.text; val tables = capture.tables; val config = capture.config
        if (capture.static.isNotEmpty()) requireNotNull(staticAssets) { "静态原件存储未接入；不能声明完整导出" }
        val hashes = mutableSetOf<String>()
        val archives = tables.getJSONArray("context_archives"); val assets = tables.getJSONArray("neutral_assets")
        repeat(archives.length()) { hashes.add(archives.getJSONObject(it).getString("blob_hash")) }
        repeat(assets.length()) { hashes.add(assets.getJSONObject(it).getString("blob_hash")) }
        val originals = spool.bytes
        fun include(hash: String, bytes: ByteArray) = spool.include(hash, bytes)
        hashes.sorted().forEach { hash -> include(hash, blobs.read(hash)) }
        val references = BackupImageManifest.References()
        val textData = objectOf(objectOf(text).getString("payload"))
        references.addRows(textData.getJSONArray("messages"))
        val generationHistory = capture.generation
        val generation = BackupGenerationCodec.validate(generationHistory, textData.getJSONArray("messages"))
        val childHistory = capture.children
        BackupChildTaskCodec.validate(childHistory, textData.getJSONArray("messages"))
        capture.static.forEach { StaticGenerationCodec.validateDestination(it, textData.getJSONArray("messages")) }
        val historicalGeneration = capture.historical
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
        val videoManifest = capture.video
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
        capture.static.forEach { reserve(it.source.sha256, it.source.bytes) }
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
            val bytes = if (BackupGenerationCodec.key(row) in historicalGeneration) historicalGenerationOriginal(HistoricalGenerationOutput(reference))
                else requireNotNull(generationAssets) { "生成原资产存储未接入；不能静默遗漏" }.read(reference)
            BackupGenerationCodec.verify(reference, bytes)
            include(reference.sha256, bytes)
        }
        capture.static.forEach { include(it.source.sha256, staticGenerationOriginal(it)) }
        // Full scalar collection CAS, including insert/delete and inert runtime v4 observations.
        check(captureExport(configuration, conversationId).signature() == capture.signature()) { "备份来源已变化；未自动重试" }
        val contents = JSONArray()
        originals.keys.sorted().forEach { hash -> contents.put(JSONObject().put("sha256", hash)
            .put("bytes", spool.original(hash).length())) }
        val payload = JSONObject().put("text_backup", text).put("tables", tables).put("config", config)
            .put("blobs", contents).put("excluded", JSONArray(listOf("凭据、授权令牌和密钥材料不导出", "生成授权结果及供应商地址不导出；恢复只读历史，不恢复执行许可")))
            .put("generation_history", generationHistory).put("child_task_history", childHistory)
            .put("runtime_usage_history", capture.runtime)
            .put("static_generation_history", StaticGenerationCodec.encodeSection(capture.static))
        if (images.isNotEmpty()) payload.put("message_images", manifest)
        if (videos.sidecars.isNotEmpty()) payload.put("message_videos", videoManifest)
        val inventory = inventory(payload)
        payload.put("inventory", inventoryJson(inventory))
        validatePayload(6, "export-validation", payload, originals)
        return PreparedExport(spool, spool.write(6, payload))
    }
    fun preflight(text: String): BackupInventory {
        Utf8Payload.byteCount(text, MAX_PACKAGE)
        return BackupPackageSpool(blobs.backupStagingParent).use { spool ->
            val envelope = spool.read(java.io.StringReader(text))
            validatePayload(envelope.version, envelope.id, envelope.payload, spool.bytes).inventory
        }
    }
    fun preflight(source: InputStream): BackupInventory = BackupPackageSpool(blobs.backupStagingParent).use { spool ->
        val envelope = spool.read(source)
        validatePayload(envelope.version, envelope.id, envelope.payload, spool.bytes).inventory
    }
    fun importPackage(source: InputStream): ImportResult = withImportSpool { spool ->
        val envelope = spool.read(source)
        importParsed(validatePayload(envelope.version, envelope.id, envelope.payload, spool.bytes))
    }
    internal fun importPackageFrom(open: () -> InputStream): ImportResult =
        importPackageFrom(open, onStage = {})

    internal fun importPackageFrom(open: () -> InputStream, onStage: (String) -> Unit): ImportResult = withImportSpool { spool ->
        val parsed = open().use { source ->
            onStage("正在解析备份包")
            val envelope = spool.read(source)
            onStage("正在校验备份内容")
            validatePayload(envelope.version, envelope.id, envelope.payload, spool.bytes)
        }
        importParsed(parsed, onStage)
    }
    fun importPackage(text: String): ImportResult {
        Utf8Payload.byteCount(text, MAX_PACKAGE)
        return withImportSpool { spool ->
            val envelope = spool.read(java.io.StringReader(text))
            importParsed(validatePayload(envelope.version, envelope.id, envelope.payload, spool.bytes))
        }
    }
    private fun withImportSpool(work: (BackupPackageSpool) -> ImportResult): ImportResult {
        val spool = BackupPackageSpool(blobs.backupStagingParent)
        val result = try { work(spool) } catch (failure: Throwable) {
            try { spool.close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        }
        return try { spool.close(); result } catch (_: Exception) {
            // The import already committed. Preserve its result so the host refreshes committed data.
            result.copy(cleanupProblem = "导入已确认，但私有暂存文件清理未完成")
        }
    }
    private fun importParsed(parsed: Parsed, onStage: (String) -> Unit = {}): ImportResult {
        onStage("正在检查目标状态")
        val access = if (parsed.images.isEmpty()) null else requireNotNull(imageAssets) { "消息原图存储未接入；不能恢复图片引用" }
        val video = if (parsed.videos.sidecars.isEmpty()) null else requireNotNull(videoIntegration) { "原视频存储未接入；不能恢复视频引用" }
        val db = transactions.database()
        check(!db.inTransaction()) { "完整备份原件阶段不能在外层SQL事务内" }
        val duplicateSnapshot = transactions.run { sql ->
            val duplicate = sql.rawQuery("SELECT id FROM neutral_imports WHERE id=?", arrayOf(parsed.id)).use { it.moveToFirst() }
            if (!duplicate) {
                val config = parsed.payload.getJSONObject("config")
                config.keys().forEach { key ->
                    val current = sql.rawQuery("SELECT value FROM neutral_config WHERE name=?", arrayOf(key))
                        .use { if (it.moveToFirst()) it.getString(0) else null }
                    require(current == null || current == config.get(key).toString()) { "非秘密配置冲突，未覆盖当前账户" }
                }
            }
            duplicate
        }
        val static = if (parsed.static.isEmpty()) null else requireNotNull(staticAssets) { "静态原件存储未接入；不能恢复仅含引用的备份" }
        val leases = mutableListOf<app.daiyu.android.media.StaticGenerationAssets.Lease>()
        var outcome = app.daiyu.android.media.StaticGenerationSettlement.UNKNOWN
        var rollbackConfirmed = false
        var attempted = false
        var publicationUnknown = false
        try {
            // Original phase: no cursor, SQL transaction or controller monitor is retained.
            onStage(if (duplicateSnapshot) "正在核验已有原件" else "正在恢复并核验原件")
            if (duplicateSnapshot) {
                verifyPrivateContents(parsed)
                parsed.static.forEach { output ->
                    require(staticGenerationOriginal(output).contentEquals(parsed.bytes.getValue(output.source.sha256)))
                }
            } else {
                parsed.bytes.forEach { (hash, bytes) -> require(blobs.put(bytes) == hash) }
                parsed.static.forEach { output ->
                    leases += static!!.prepare(output, app.daiyu.android.media.StaticGenerationOriginalReader { source ->
                        check(source == output.source); java.io.ByteArrayInputStream(parsed.bytes.getValue(source.sha256))
                    })
                }
                leases.forEach { static!!.publish(it) }
            }
            parsed.images.groupBy { it.owner }.forEach { (_, references) ->
                val assets = references.map(MessageImageReferenceCodec::toAsset)
                val destination = requireNotNull(access)
                if (!duplicateSnapshot) destination.restore(assets.first().owner, assets, references.map { parsed.bytes.getValue(it.sha256) })
                references.zip(assets).forEach { (reference, asset) -> BackupImageManifest.verify(reference, destination.read(asset.owner, asset)) }
            }
            parsed.videos.references.groupBy { it.owner }.forEach { (owner, references) ->
                val destination = requireNotNull(video).assets
                if (!duplicateSnapshot) destination.restore(owner, references, references.map { parsed.bytes.getValue(it.sha256) })
                references.forEach { reference -> BackupVideoManifest.verify(reference, destination.read(owner, reference)) }
            }
            onStage(if (duplicateSnapshot) "正在复核已导入数据" else "正在写入导入数据")
            attempted = true; db.beginTransaction()
            var successful = false
            try {
            val result = run {
            val duplicate = db.rawQuery("SELECT id FROM neutral_imports WHERE id=?", arrayOf(parsed.id)).use { it.moveToFirst() }
            check(duplicate == duplicateSnapshot) { "导入身份在原件阶段已变化；未自动重试" }
            if (duplicate) {
                store.verifyRestoredBackup(db, parsed.payload.getString("text_backup"))
                verifyImportedTables(db, parsed.payload.getJSONObject("tables"), parsed.version, parsed.columns)
                verifyStaticRestored(db, parsed)
                BackupGenerationCodec.verifyRestored(db, parsed.generation)
                BackupChildTaskCodec.verifyRestored(db, parsed.children)
                BackupRuntimeUsageCodec.verify(db, parsed.runtimeUsage)
                verifyImportedConfiguration(db, parsed.payload.getJSONObject("config"))
                store.verifyImportedVideoWitnesses(db, parsed.payload.getString("text_backup"))
                // Original bytes were independently reverified outside SQL, not inferred from this marker.
                BackupVideoManifest.verifySidecars(db, parsed.videos.sidecars)
                return@run ImportResult(parsed.id, parsed.inventory, true)
            }
            // Existing parser validates text protocol, IDs, drafts and collisions; nested transaction shares helper.
            if (video == null) store.importBackup(parsed.payload.getString("text_backup"))
            else store.importBackupForVideoAssets(db, parsed.payload.getString("text_backup"))
            parsed.columns.forEach { (table, columns) -> val rows = parsed.payload.getJSONObject("tables").getJSONArray(table)
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
            BackupRuntimeUsageCodec.restore(db, parsed.runtimeUsage)
            val importedConversations = uniqueIds(objectOf(objectOf(parsed.payload.getString("text_backup"))
                .getString("payload")).getJSONArray("conversations"), "id")
            app.daiyu.android.storage.ConversationUsageStore.backfill(db, importedConversations)
            if (parsed.static.isNotEmpty()) BackupHistoryRows.install(db)
            val importedMessages = objectOf(objectOf(parsed.payload.getString("text_backup")).getString("payload")).getJSONArray("messages")
            parsed.static.forEach { StaticGenerationCodec.saveInTransaction(db, it, importedMessages) }
            if (video != null) {
                // Parent persists against the actual imported physical rows, in THIS transaction.
                video.restoreSidecars(db, parsed.videos.sidecars)
                store.completeVideoBackupWitnesses(db, parsed.payload.getString("text_backup"), parsed.videos.sidecars)
                BackupVideoManifest.verifySidecars(db, parsed.videos.sidecars)
            }
            val importedAt = System.currentTimeMillis()
            check(db.insertOrThrow("neutral_imports", null, ContentValues().apply {
                put("id", parsed.id); put("created", importedAt)
            }) != -1L) { "备份导入标记未写入" }
            onStage("正在复核导入回执")
            // The last SQL write can run triggers too. In particular, reject typed receipt
            // corruption before publishing owner asset batches; recheck all receipts below.
            check(db.rawQuery("SELECT 1 FROM neutral_imports WHERE id=? AND created=?",
                arrayOf(parsed.id, importedAt.toString())).use { it.moveToFirst() }) { "备份导入标记回执不匹配" }
            verifyImportedTables(db, parsed.payload.getJSONObject("tables"), parsed.version, parsed.columns)
            BackupChildTaskCodec.verifyRestored(db, parsed.children)
            BackupRuntimeUsageCodec.verify(db, parsed.runtimeUsage)
            // Immutable originals were published before SQL. SQL failure may leave private
            // orphans, but never authorizes deletion of shared/source image or video originals.
            store.verifyRestoredBackup(db, parsed.payload.getString("text_backup"))
            verifyImportedTables(db, parsed.payload.getJSONObject("tables"), parsed.version, parsed.columns)
            verifyStaticRestored(db, parsed)
            BackupGenerationCodec.verifyRestored(db, parsed.generation)
            BackupChildTaskCodec.verifyRestored(db, parsed.children)
            BackupRuntimeUsageCodec.verify(db, parsed.runtimeUsage)
            verifyImportedConfiguration(db, config)
            BackupVideoManifest.verifySidecars(db, parsed.videos.sidecars)
            ImportResult(parsed.id, parsed.inventory, false)
            }
            onStage("正在提交导入事务")
            db.setTransactionSuccessful(); successful = true
            // Return is evaluated now, but finally confirms COMMIT before delivery.
            return result
            } finally {
                db.endTransaction()
                if (successful) outcome = app.daiyu.android.media.StaticGenerationSettlement.COMMITTED
                else rollbackConfirmed = true
            }
        } catch (failure: Throwable) {
            publicationUnknown = failure is app.daiyu.android.media.StaticGenerationPublicationUnknown
            throw failure
        } finally {
            if (outcome != app.daiyu.android.media.StaticGenerationSettlement.COMMITTED && !publicationUnknown &&
                (!attempted || rollbackConfirmed)) {
                val absent = runCatching {
                    check(!db.inTransaction())
                    transactions.run { sql -> parsed.static.all { StaticGenerationCodec.find(sql, it.identity) == null } }
                }.getOrDefault(false)
                if (absent) outcome = app.daiyu.android.media.StaticGenerationSettlement.ROLLED_BACK_UNREFERENCED
            }
            leases.forEach { lease -> runCatching { static!!.settle(lease, outcome) } }
        }
    }
    private fun verifyStaticRestored(db: SQLiteDatabase, parsed: Parsed) {
        val messages = objectOf(objectOf(parsed.payload.getString("text_backup")).getString("payload")).getJSONArray("messages")
        parsed.static.forEach { StaticGenerationCodec.verifyInTransaction(db, it, messages) }
        // Exact scoped collection: an injected late row must not hide behind per-row receipts.
        val owners = (0 until messages.length()).map { messages.getJSONObject(it).getString("id") }.toSet()
        check(StaticGenerationCodec.readForOwners(db, owners).toSet() == parsed.static.toSet())
    }
    /** Observational history only. These receipts cannot be passed off as registered live outputs. */
    fun historicalGenerationOutputs(conversationId: ConversationId? = null): List<HistoricalGenerationOutput> = transactions.run { db ->
        BackupHistoryRows.history(db, "generation_output", conversationId?.value).values.map {
            HistoricalGenerationOutput(BackupGenerationCodec.reference(it))
        }
    }
    fun historicalGenerationOriginal(reference: HistoricalGenerationOutput): ByteArray {
        check(!transactions.database().inTransaction()) { "历史原件读取不能在外层SQL事务内" }
        transactions.run { db -> require(BackupHistoryRows.history(db, "generation_output", reference.conversationId).values
            .map(BackupGenerationCodec::reference).contains(reference.source)) }
        return blobs.read(reference.sha256).also { BackupGenerationCodec.verify(reference.source, it) }
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
    private fun verifyImportedTables(db: SQLiteDatabase, tables: JSONObject, version: Long, schema: Map<String, List<String>>) {
        BackupUsageCodec.verify(db, tables.getJSONArray("context_usage"), version)
        schema.filterKeys { it != "context_usage" }.forEach { (table, columns) ->
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
        val children: BackupChildTaskCodec.Validated, val runtimeUsage: BackupRuntimeUsageCodec.Validated,
        val version: Long, val columns: Map<String, List<String>>,
        val static: List<app.daiyu.android.storage.StaticGenerationOutput>)
    private fun validatePayload(version: Long, id: String, payload: JSONObject, bytes: Map<String, ByteArray>): Parsed {
        val schema = columnsFor(version)
        val payloadFields = setOf("text_backup", "tables", "config", "blobs", "excluded", "inventory")
        fields(payload, payloadFields + listOf("message_images", "message_videos").filter { payload.has(it) } +
            (if (version >= 2L) setOf("generation_history", "child_task_history") else emptySet()) +
            (if (version >= 4L) setOf("runtime_usage_history") else emptySet()) +
            (if (version >= 5L) setOf("static_generation_history") else emptySet()))
        store.preflightBackup(string(payload, "text_backup"))
        val legacy = objectOf(string(payload, "text_backup"))
        require(legacy.getString("format") == "daiyu-neutral-text" && legacy.getInt("version") in 1..2)
        val oldRaw = string(legacy, "payload"); require(Utf8Payload.sha256(oldRaw) == legacy.getString("sha256"))
        val old = objectOf(oldRaw); val conversations = old.getJSONArray("conversations"); val messages = old.getJSONArray("messages")
        require(conversations.length() <= 1000 && messages.length() <= 50000)
        require(legacy.getInt("conversation_count") == conversations.length() && legacy.getInt("message_count") == messages.length())
        val conversationIds = uniqueIds(conversations, "id"); val messageIds = uniqueIds(messages, "id")
        repeat(messages.length()) { require(messages.getJSONObject(it).getString("conversation_id") in conversationIds) }
        val tables = payload.getJSONObject("tables"); fields(tables, schema.keys)
        schema.forEach { (table, columns) -> val rows = tables.getJSONArray(table); require(rows.length() <= 50000)
            val primary = columns.first(); uniqueIds(rows, primary)
            repeat(rows.length()) { fields(rows.getJSONObject(it), columns.toSet()) }
        }
        val hashes = hashSetOf<String>(); var total = 0L
        val encoded = payload.getJSONArray("blobs"); require(encoded.length() <= 50000)
        repeat(encoded.length()) { val blob = encoded.getJSONObject(it)
            fields(blob, setOf("sha256", "bytes"))
            val hash = string(blob, "sha256"); require(hash.matches(Regex("[0-9a-f]{64}")) && hashes.add(hash))
            val size = integer(blob, "bytes"); require(size in 0..PrivateBlobs.MAX_BLOB)
            total += size; require(total <= MAX_BYTES)
            val decoded = bytes.getValue(hash)
            require(decoded.size.toLong() == size && PrivateBlobs.digest(decoded) == hash)
        }
        require(hashes == bytes.keys)
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
        BackupUsageCodec.validate(tables.getJSONArray("context_usage"), version, conversationIds, messages)
        val images = BackupImageManifest.validate(payload, imageReferences, bytes)
        images.forEach { used.add(it.sha256) }
        val videos = BackupVideoManifest.validate(
            if (payload.has("message_videos")) payload.getJSONObject("message_videos") else null, messages, bytes)
        videos.references.forEach { used.add(it.sha256) }
        val generation = BackupGenerationCodec.validate(if (version >= 2L) payload.getJSONObject("generation_history") else null, messages, bytes)
        val children = BackupChildTaskCodec.validate(if (version >= 2L) payload.getJSONObject("child_task_history") else null, messages)
        val runtimeUsage = BackupRuntimeUsageCodec.validate(if (version >= 4L) payload.getJSONObject("runtime_usage_history") else null,
            conversationIds, messages)
        generation.references.forEach { used.add(it.sha256) }
        val static = if (version >= 5L) StaticGenerationCodec.decodeSection(payload.getJSONObject("static_generation_history"), messages) else emptyList()
        static.forEach { output ->
            val source = output.source; val original = bytes[source.sha256] ?: error("缺静态生成原件")
            require(original.size.toLong() == source.bytes && PrivateBlobs.digest(original) == source.sha256)
            used.add(source.sha256)
        }
        require(used == bytes.keys) { "附件/归档/图片/视频/生成资产清单不一致" }
        validateConfig(payload.getJSONObject("config"))
        val excluded = payload.getJSONArray("excluded"); require(excluded.length() <= 20)
        repeat(excluded.length()) { require(excluded.get(it) is String && excluded.getString(it).length <= 200) }
        val inventory = inventory(payload)
        val declared = payload.getJSONObject("inventory"); val expected = inventoryJson(inventory, version >= 2L, version >= 4L, version >= 5L)
        fields(declared, expected.keys().asSequence().toSet())
        expected.keys().forEach { key -> require(declared.get(key) == expected.get(key) ||
            declared.get(key) is Number && expected.get(key) is Number &&
                integer(declared, key) == integer(expected, key)) { "数量或字节清单不一致" } }
        return Parsed(id, payload, bytes, inventory, images, videos, generation, children, runtimeUsage, version, schema, static)
    }
    private fun inventory(payload: JSONObject): BackupInventory {
        val legacy = objectOf(payload.getString("text_backup")); val tables = payload.getJSONObject("tables")
        val blobs = payload.getJSONArray("blobs"); var size = 0L
        repeat(blobs.length()) { size = Math.addExact(size, integer(blobs.getJSONObject(it), "bytes")) }
        val excluded = payload.getJSONArray("excluded")
        val generation = payload.optJSONObject("generation_history")
        val children = payload.optJSONObject("child_task_history")
        val generationCount = generation?.getJSONArray("outputs")?.length() ?: 0
        val staticCount = payload.optJSONObject("static_generation_history")?.getJSONArray("outputs")?.length() ?: 0
        return BackupInventory(legacy.getInt("conversation_count"), legacy.getInt("message_count"),
            tables.getJSONArray("context_branches").length(), tables.getJSONArray("context_archives").length(),
            tables.getJSONArray("neutral_assets").length() + (payload.optJSONArray("message_images")?.length() ?: 0) +
                BackupVideoManifest.decode(if (payload.has("message_videos")) payload.getJSONObject("message_videos") else null,
                    objectOf(legacy.getString("payload")).getJSONArray("messages")).references.size + generationCount + staticCount,
            size, payload.getJSONObject("config").length(),
            (0 until excluded.length()).map { excluded.getString(it) }, generationOutputs = generationCount,
            generationAttempts = generation?.getJSONArray("attempts")?.length() ?: 0,
            childTasks = children?.getJSONArray("tasks")?.length() ?: 0,
            childCommands = children?.getJSONArray("commands")?.length() ?: 0,
            childRequests = children?.getJSONArray("usage")?.length() ?: 0,
            runtimeUsageCheckpoints = payload.optJSONObject("runtime_usage_history")?.getJSONArray("checkpoints")?.length() ?: 0,
            runtimeUsageRequests = payload.optJSONObject("runtime_usage_history")?.getJSONArray("requests")?.length() ?: 0,
            staticGenerationOutputs = staticCount)
    }
    private fun inventoryJson(i: BackupInventory, extended: Boolean = true, runtime: Boolean = true, static: Boolean = true) = JSONObject().put("conversations", i.conversations).put("messages", i.messages)
        .put("branches", i.branches).put("archives", i.archives).put("attachments", i.attachments)
        .put("blob_bytes", i.blobBytes).put("config_entries", i.configEntries).put("requires_authorization", true).apply {
            if (extended) {
                put("generation_outputs", i.generationOutputs); put("generation_attempts", i.generationAttempts)
                put("child_tasks", i.childTasks); put("child_commands", i.childCommands); put("child_requests", i.childRequests)
            }
            if (static) put("static_generation_outputs", i.staticGenerationOutputs)
            if (runtime) {
                put("runtime_usage_checkpoints", i.runtimeUsageCheckpoints); put("runtime_usage_requests", i.runtimeUsageRequests)
            }
        }
    private fun readTable(db: SQLiteDatabase, table: String, columns: List<String>, conversationId: ConversationId?): JSONArray {
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
                if(key == "protocol") require(value in setOf("responses", "messages", "chat_completions", "gemini")) { "模型协议无效" }
                if (key == "endpoint" && value.isNotEmpty()) {
                    val uri = URI(value); require(uri.scheme == "https" && uri.host != null && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null)
                }
                if (key == "responses-path" && value.isNotEmpty()) require(value.startsWith("/") && !value.contains('?') && !value.contains('#') && !value.contains(".."))
                require(!value.contains('\n') && !value.contains('\r'))
            }
        }
        if(config.optString("protocol") in setOf("chat_completions", "gemini")) {
            require(!config.has("responses-path") || config.get("responses-path") == "")
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
        private fun columnsFor(version: Long): Map<String, List<String>> = LinkedHashMap(COLUMNS).apply {
            put("context_usage", if (version >= 6) BackupUsageCodec.columns else if (version >= 3) BackupUsageCodec.typedColumns else BackupUsageCodec.legacyColumns)
        }
        private val COLUMNS = linkedMapOf(
            "context_branches" to listOf("conversation_id", "parent_id", "through_message", "parent_revision"),
            "context_archives" to listOf("id", "conversation_id", "prefix_count", "revision", "summary", "blob_hash", "created", "active"),
            "context_usage" to BackupUsageCodec.legacyColumns,
            "neutral_assets" to listOf("id", "message_id", "kind", "mime", "bytes", "blob_hash")
        )
    }
}
