package app.daiyu.android.backup

import android.content.Context
import android.content.ContextWrapper
import android.test.InstrumentationTestCase
import app.daiyu.android.testContext
import app.daiyu.android.context.*
import app.daiyu.android.domain.*
import app.daiyu.android.domain.context.CompressionPolicy
import app.daiyu.android.storage.ConversationDatabase
import org.json.JSONObject
import java.io.File
import java.util.UUID

@Suppress("DEPRECATION")
class NeutralBackupServiceTest : InstrumentationTestCase() {
    private val databases = mutableListOf<Pair<Context, ConversationDatabase>>()
    private fun fresh(): Triple<Context, ConversationDatabase, NeutralBackupService> {
        val base = testContext(instrumentation.targetContext, "backup-${UUID.randomUUID()}-")
        val files = File(base.cacheDir, "backup-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) { override fun getFilesDir(): File = files }
        val db = ConversationDatabase(context); databases.add(context to db)
        return Triple(context, db, NeutralBackupService(context, db))
    }
    override fun tearDown() {
        databases.forEach { (c, db) -> db.close(); c.deleteDatabase("conversations.db"); c.filesDir.deleteRecursively() }; super.tearDown()
    }
    private fun seed(db: ConversationDatabase, turns: Int): ConversationId {
        val owner = db.create("default").id
        repeat(turns) { n -> val r = RunId("r-$n-${owner.value}")
            val u = Message(MessageId("u-$n-${owner.value}"), owner, r, "user", listOf(MessageBlock.Text("t", "question $n ".repeat(200))), RunStatus.COMPLETED)
            val a = Message(MessageId("a-$n-${owner.value}"), owner, r, "assistant", listOf(MessageBlock.Text("t", "answer $n ".repeat(200))), RunStatus.RUNNING)
            db.send(u, a); db.save(a.copy(status = RunStatus.COMPLETED)) }
        return owner
    }
    private fun mutate(text: String, block: (JSONObject) -> Unit): String {
        val envelope = JSONObject(text); val payload = JSONObject(envelope.getString("payload")); block(payload)
        val raw = payload.toString(); return envelope.put("payload", raw)
            .put("sha256", PrivateBlobs.digest(raw.toByteArray(Charsets.UTF_8))).toString()
    }
    private fun reject(block: () -> Unit) { try { block(); fail("must reject") } catch (_: Exception) {} }
    fun testHundredMiBCompleteFileRoundTripReopenDuplicateAndCleanup() {
        if (androidx.test.platform.app.InstrumentationRegistry.getArguments()
                .getString("daiyuLargeBackupOptIn") != "true") return
        check(instrumentation.targetContext.packageName == "app.daiyu.android.synthetic")
        val (sourceContext, source, exporter) = fresh()
        val (targetContext, target, importer) = fresh()
        fun stages(c: Context) = c.cacheDir.listFiles().orEmpty()
            .filter { it.name.startsWith("complete-backup-") }.map { it.name }.toSet()
        fun fileHash(file: File): String {
            val sha = java.security.MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                while (true) { val n = input.read(buffer); if (n < 0) break; sha.update(buffer, 0, n) }
            }
            return sha.digest().joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
        }
        val sourceStages = stages(sourceContext); val targetStages = stages(targetContext)
        val owner = source.create("default").id
        val body = "x".repeat(512 * 1024)
        var attachmentOwner = ""
        repeat(15) { n ->
            val run = RunId("large-r-$n")
            val user = Message(MessageId("large-u-$n"), owner, run, "user",
                listOf(MessageBlock.Text("u", body)), RunStatus.COMPLETED)
            val answer = Message(MessageId("large-a-$n"), owner, run, "assistant",
                listOf(MessageBlock.Text("a", body)), RunStatus.RUNNING)
            source.send(user, answer); source.save(answer.copy(status = RunStatus.COMPLETED))
            attachmentOwner = answer.id.value
        }
        val expectedHashes = (0 until 16).map { index ->
            val bytes = ByteArray(4 * 1024 * 1024) { (it * 31 + index).toByte() }
            exporter.attach("large-asset-$index", attachmentOwner, "application/octet-stream", bytes)
            PrivateBlobs.digest(bytes)
        }
        assertEquals(16, expectedHashes.toSet().size)
        val expectedTextHash = Utf8Payload.sha256(source.exportBackup())
        val output = File(sourceContext.filesDir, "hundred-mib.json")
        lateinit var preparedFile: File
        exporter.prepareExport().use { prepared ->
            preparedFile = prepared.file
            output.outputStream().use { prepared.writeTo(it) }
        }
        assertFalse(preparedFile.exists())
        assertTrue(output.length() >= 100L * 1024 * 1024)
        assertTrue(output.length() <= NeutralBackupService.MAX_PACKAGE)
        assertEquals(sourceStages, stages(sourceContext))
        val packageHash = fileHash(output)
        val inventory = output.inputStream().use { importer.preflight(it) }
        assertEquals(1, inventory.conversations); assertEquals(30, inventory.messages)
        assertEquals(16, inventory.attachments); assertEquals(64L * 1024 * 1024, inventory.blobBytes)
        assertEquals(targetStages, stages(targetContext)); assertTrue(target.conversations().isEmpty())
        val imported = importer.importPackageFrom { output.inputStream() }
        assertFalse(imported.alreadyImported); assertNull(imported.cleanupProblem)
        assertEquals(inventory, imported.inventory); assertEquals(targetStages, stages(targetContext))
        target.close()
        val reopened = ConversationDatabase(targetContext)
        try {
            val restored = NeutralBackupService(targetContext, reopened)
            assertEquals(expectedTextHash, Utf8Payload.sha256(reopened.exportBackup()))
            expectedHashes.forEachIndexed { index, expected ->
                assertEquals(expected, PrivateBlobs.digest(restored.attachment("large-asset-$index")))
            }
            val duplicate = restored.importPackageFrom { output.inputStream() }
            assertTrue(duplicate.alreadyImported); assertNull(duplicate.cleanupProblem)
            assertEquals(imported.packageId, duplicate.packageId)
            assertEquals(1, reopened.readableDatabase.rawQuery("SELECT count(*) FROM neutral_imports", null)
                .use { it.moveToFirst(); it.getInt(0) })
        } finally { reopened.close() }
        assertEquals(packageHash, fileHash(output))
        assertEquals(expectedTextHash, Utf8Payload.sha256(source.exportBackup()))
        expectedHashes.forEachIndexed { index, expected ->
            assertEquals(expected, PrivateBlobs.digest(exporter.attachment("large-asset-$index")))
        }
        assertEquals(sourceStages, stages(sourceContext)); assertEquals(targetStages, stages(targetContext))
        val packageBytes = output.length()
        assertTrue(output.delete())
        val report = JSONObject().put("package_bytes", packageBytes).put("package_sha256", packageHash)
            .put("text_utf8_bytes", 15L * 1024 * 1024).put("original_bytes", 64L * 1024 * 1024)
            .put("messages", 30).put("attachments", 16).put("heap_max", Runtime.getRuntime().maxMemory())
            .put("restored_and_reopened", true).put("duplicate_idempotent", true)
            .put("owned_staging_removed", true).put("output_removed", true)
            .put("source_originals_unchanged", true).put("saf_measured", false).put("production_delivery", false)
        val evidence = File(checkNotNull(instrumentation.targetContext.getExternalFilesDir("large-backup")),
            "roundtrip-${UUID.randomUUID()}.json")
        java.io.FileOutputStream(evidence).use { stream ->
            stream.write(report.toString().toByteArray(Charsets.UTF_8)); stream.fd.sync()
        }
    }

    fun testPreparedConversationFileUsesV5AndExcludesGlobalConfiguration() {
        val (_, source, backup) = fresh(); val owner = seed(source, 1)
        backup.setNonSecretConfiguration(mapOf("model" to "global-only"))
        val binary = ByteArray(60_003) { it.toByte() }
        backup.attach("stream-asset", source.messages(owner).first().second.id.value, "application/octet-stream", binary)
        val (_, target, importer) = fresh()
        backup.prepareExport(conversationId = owner).use { prepared ->
            val envelope = JSONObject(prepared.file.readText())
            assertEquals(5, envelope.getInt("version"))
            assertEquals(0, JSONObject(envelope.getString("payload")).getJSONObject("config").length())
            assertEquals(1, prepared.file.inputStream().use(backup::preflight).attachments)
            assertFalse(importer.importPackageFrom { prepared.file.inputStream() }.alreadyImported)
            assertTrue(importer.importPackageFrom { prepared.file.inputStream() }.alreadyImported)
        }
        assertEquals(source.messages(owner), target.messages(owner))
        assertTrue(binary.contentEquals(importer.attachment("stream-asset")))
        assertEquals(0, importer.importedConfiguration().length())
    }
    fun testActualCompleteSourceCloseFailurePrecedesOriginalPublicationAndSql() {
        val (_, source, backup) = fresh(); val owner = seed(source, 1)
        backup.attach("close-asset", source.messages(owner).first().second.id.value, "application/octet-stream", byteArrayOf(1, 2, 3))
        val text = backup.exportPackage()
        val (context, target, importer) = fresh(); val before = target.exportBackup()
        val temporary = context.cacheDir.listFiles().orEmpty().filter { it.name.startsWith("complete-backup-") }.map { it.name }.toSet()
        var closes = 0; val expected = java.io.IOException("test_source_close_failed")
        try {
            importer.importPackageFrom {
                object : java.io.ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)) {
                    override fun close() { closes++; throw expected }
                }
            }
            fail("source close failure must precede publication")
        } catch (actual: java.io.IOException) { assertSame(expected, actual) }
        assertEquals(1, closes); assertEquals(before, target.exportBackup())
        assertEquals(0, target.readableDatabase.rawQuery("SELECT count(*) FROM neutral_imports", null)
            .use { it.moveToFirst(); it.getInt(0) })
        assertTrue(File(context.filesDir, "neutral-context-blobs").listFiles().orEmpty().isEmpty())
        assertEquals(temporary, context.cacheDir.listFiles().orEmpty().filter { it.name.startsWith("complete-backup-") }.map { it.name }.toSet())
    }
    fun testStringAndStreamRejectDuplicateEnvelopeFieldsWithoutChangingTarget() {
        val (_, source, backup) = fresh(); seed(source, 1)
        val text = backup.exportPackage().replaceFirst("{", "{\"version\":99,")
        val (_, target, importer) = fresh(); val before = target.exportBackup()
        reject { importer.importPackage(text) }
        reject { text.byteInputStream().use(importer::importPackage) }
        assertEquals(before, target.exportBackup())
    }
    fun testCompleteRoundTripCountsHashesArchivesDraftsAndDuplicate() {
        val (sourceContext, source, backup) = fresh(); val owner = seed(source, 6)
        source.draft(owner, "会话草稿"); source.draft("未发送草稿")
        val contexts = ContextService(sourceContext, source)
        val archive = contexts.compress(owner, CompressionPolicy(keepTurns = 2), ContextSummarizer { "事实已确认" })!!
        val copy = contexts.branch(owner, contexts.displayHistory(owner)[3].id)
        val message = source.messages(owner).first().second.id.value
        val binary = byteArrayOf(0, 1, 2, -1, 42)
        backup.attach("asset", message, "application/octet-stream", binary)
        assertTrue(backup.setNonSecretConfiguration(mapOf("endpoint" to "https://example.org", "model" to "model", "battery" to true, "api-key" to "SYNTHETIC_SECRET")).isNotEmpty())
        val text = backup.exportPackage(); assertFalse(text.contains("SYNTHETIC_SECRET"))
        val inv = backup.preflight(text); assertEquals(2, inv.conversations); assertEquals(16, inv.messages)
        assertEquals(1, inv.branches); assertEquals(1, inv.archives); assertEquals(1, inv.attachments); assertEquals(5L, inv.blobBytes - ArchiveCodec.encode(contexts.archivePage(archive).messages).size)
        val (targetContext, target, importer) = fresh(); val imported = importer.importPackage(text.byteInputStream())
        assertFalse(imported.alreadyImported); assertTrue(importer.importPackage(text).alreadyImported)
        assertEquals(source.exportBackup(), target.exportBackup())
        assertTrue(binary.contentEquals(importer.attachment("asset")))
        assertEquals(contexts.archivePage(archive), ContextService(targetContext, target).archivePage(archive))
        assertEquals(contexts.prepareSend(copy).messages, ContextService(targetContext, target).prepareSend(copy).messages)
        assertEquals("https://example.org", importer.importedConfiguration().getString("endpoint"))
        assertEquals(0, targetContext.getSharedPreferences("model-settings", Context.MODE_PRIVATE).all.size)
    }
    fun testBadHashesMissingAssetsUnknownSecretsAndTraversalLeaveTargetUnchanged() {
        val (_, source, exporter) = fresh(); val owner = seed(source, 1)
        exporter.attach("asset", source.messages(owner).first().second.id.value, "image/png", byteArrayOf(1, 2, 3))
        val text = exporter.exportPackage(); val (_, target, importer) = fresh(); seed(target, 1)
        val before = target.exportBackup()
        val bad = listOf(
            mutate(text) { it.getJSONArray("blobs").getJSONObject(0).put("sha256", "0".repeat(64)) },
            mutate(text) { it.getJSONArray("blobs").remove(0) },
            mutate(text) { it.getJSONObject("tables").getJSONArray("neutral_assets").getJSONObject(0).put("id", "../../escape") },
            mutate(text) { it.getJSONObject("config").put("api-key", "SYNTHETIC_SECRET") },
            mutate(text) { it.getJSONObject("config").put("endpoint", "https://user:secret@example.org") },
            mutate(text) { it.getJSONObject("inventory").put("attachments", 99) })
        bad.forEach { rejected -> reject { importer.importPackage(rejected) }; assertEquals(before, target.exportBackup()) }
        assertFalse(File(databases.last().first.filesDir, "neutral-context-blobs").listFiles().orEmpty().any { it.isFile })
    }
    fun testExistingConfigurationConflictRejectsBeforeImport() {
        val (_, source, exporter) = fresh(); seed(source, 1)
        exporter.setNonSecretConfiguration(mapOf("model" to "incoming")); val text = exporter.exportPackage()
        val (_, target, importer) = fresh(); seed(target, 1); importer.setNonSecretConfiguration(mapOf("model" to "existing"))
        val before = target.exportBackup(); reject { importer.importPackage(text) }
        assertEquals(before, target.exportBackup()); assertEquals("existing", importer.importedConfiguration().getString("model"))
        val count = target.readableDatabase.rawQuery("SELECT count(*) FROM neutral_imports", null).use { it.moveToFirst(); it.getInt(0) }
        assertEquals(0, count)
    }
    fun testConfigurationConflictAfterTextInsertRollsBackAndSurvivesReopen() {
        val (_, source, exporter) = fresh(); seed(source, 1)
        exporter.setNonSecretConfiguration(mapOf("model" to "incoming"))
        val text = exporter.exportPackage()
        val (context, target, importer) = fresh(); seed(target, 1)
        importer.setNonSecretConfiguration(mapOf("model" to "incoming"))
        val before = target.exportBackup()
        target.writableDatabase.execSQL("""
            CREATE TRIGGER conflict_after_text_insert AFTER INSERT ON messages
            BEGIN
                UPDATE neutral_config SET value='raced' WHERE name='model';
            END
        """.trimIndent())
        try { importer.importPackage(text); fail("late configuration conflict must reject") }
        catch (failure: IllegalArgumentException) {
            assertEquals("非秘密配置冲突，未覆盖当前账户", failure.message)
        }
        fun unchanged(db: ConversationDatabase, backup: NeutralBackupService) {
            assertEquals(before, db.exportBackup())
            assertEquals("incoming", backup.importedConfiguration().getString("model"))
            assertEquals(0, db.readableDatabase.rawQuery("SELECT count(*) FROM neutral_imports", null)
                .use { it.moveToFirst(); it.getInt(0) })
        }
        unchanged(target, importer)
        target.close()
        val reopened = ConversationDatabase(context)
        try {
            val restored = NeutralBackupService(context, reopened)
            unchanged(reopened, restored)
            reopened.writableDatabase.execSQL("DROP TRIGGER conflict_after_text_insert")
            assertFalse(restored.importPackage(text).alreadyImported)
            assertTrue(restored.importPackage(text).alreadyImported)
        } finally { reopened.close() }
    }
    fun testConfigurationCaptureReplacesSnapshotAndClearsRemovedPreferences() {
        val (context, db, backup) = fresh()
        assertEquals(20, db.writableDatabase.version)
        val preferences = context.getSharedPreferences("model-settings", Context.MODE_PRIVATE)
        val full = linkedMapOf<String, Any>("endpoint" to "https://example.org", "model" to "model",
            "responses-path" to "/v1/responses", "battery" to true, "device-status" to false)
        fun checkSnapshot(expected: Map<String, Any>) {
            val captured = backup.importedConfiguration()
            val text = backup.exportPackage()
            val exported = JSONObject(JSONObject(text).getString("payload")).getJSONObject("config")
            assertEquals(expected.size, captured.length()); assertEquals(expected.size, exported.length())
            expected.forEach { (key, value) -> assertEquals(value, captured.get(key)); assertEquals(value, exported.get(key)) }
            assertEquals(expected.size, backup.preflight(text).configEntries)
            assertEquals(expected.size, db.readableDatabase.rawQuery("SELECT count(*) FROM neutral_config", null).use { it.moveToFirst(); it.getInt(0) })
        }
        assertTrue(preferences.edit().clear().putString("endpoint", "https://example.org").putString("model", "model")
            .putString("responses-path", "/v1/responses").putBoolean("battery", true).putBoolean("device-status", false).commit())
        assertEquals(5, preferences.all.size)
        assertTrue(backup.captureNonSecretConfiguration(context).isEmpty()); checkSnapshot(full)
        assertTrue(preferences.edit().clear().putString("model", "remaining").commit())
        assertEquals(1, preferences.all.size)
        assertTrue(backup.captureNonSecretConfiguration(context).isEmpty()); checkSnapshot(mapOf("model" to "remaining"))
        assertTrue(preferences.edit().clear().commit()); assertEquals(0, preferences.all.size)
        assertTrue(backup.captureNonSecretConfiguration(context).isEmpty()); checkSnapshot(emptyMap())
    }
    fun testInvalidConfigurationLeavesPreviousSnapshotUnchanged() {
        val (_, db, backup) = fresh()
        backup.setNonSecretConfiguration(mapOf("model" to "existing", "battery" to false))
        val old = backup.importedConfiguration().toString(); val before = backup.exportPackage()
        val invalid = listOf(
            mapOf("endpoint" to "https://user:secret@example.org"),
            mapOf("battery" to "true"),
            mapOf("model" to "invalid\nmodel"))
        invalid.forEach { settings ->
            reject { backup.setNonSecretConfiguration(settings) }
            assertEquals(old, backup.importedConfiguration().toString()); assertEquals(before, backup.exportPackage())
            assertEquals(2, db.readableDatabase.rawQuery("SELECT count(*) FROM neutral_config", null).use { it.moveToFirst(); it.getInt(0) })
        }
    }
    fun testSecondConfigurationInsertFailureRollsBackSnapshotAfterReopen() {
        val (context, db, backup) = fresh(); seed(db, 1)
        assertEquals(20, db.writableDatabase.version)
        backup.setNonSecretConfiguration(mapOf("model" to "existing", "battery" to false))
        val old = backup.importedConfiguration().toString(); val before = backup.exportPackage()
        db.writableDatabase.execSQL("""CREATE TRIGGER fail_second_configuration_insert BEFORE INSERT ON neutral_config
            WHEN (SELECT count(*) FROM neutral_config)=1
            BEGIN SELECT RAISE(ABORT,'injected second configuration insert'); END""")
        try {
            backup.setNonSecretConfiguration(mapOf("model" to "replacement", "battery" to true))
            fail("must reject second configuration insert")
        } catch (failure: android.database.sqlite.SQLiteConstraintException) {
            assertTrue(failure.message.orEmpty().contains("injected second configuration insert"))
        }
        assertEquals(old, backup.importedConfiguration().toString()); assertEquals(before, backup.exportPackage())
        assertEquals(2, db.readableDatabase.rawQuery("SELECT count(*) FROM neutral_config", null).use { it.moveToFirst(); it.getInt(0) })
        db.close()
        val reopened = ConversationDatabase(context)
        try {
            assertEquals(20, reopened.readableDatabase.version)
            val restored = NeutralBackupService(context, reopened)
            assertEquals(old, restored.importedConfiguration().toString()); assertEquals(before, restored.exportPackage())
            assertEquals(2, reopened.readableDatabase.rawQuery("SELECT count(*) FROM neutral_config", null).use { it.moveToFirst(); it.getInt(0) })
        } finally { reopened.close() }
    }
    fun testExplicitExportConfigurationDoesNotMutateImportedMetadata() {
        val (_, _, backup) = fresh()
        backup.setNonSecretConfiguration(mapOf("model" to "imported", "battery" to true))
        val before = backup.importedConfiguration().toString(); val previous = backup.exportPackage()
        val explicit = backup.exportPackage(mapOf("model" to "current", "key" to "synthetic-secret"))
        val config = JSONObject(JSONObject(explicit).getString("payload")).getJSONObject("config")
        assertEquals(1, config.length()); assertEquals("current", config.getString("model"))
        assertFalse(explicit.contains("synthetic-secret")); assertEquals(1, backup.preflight(explicit).configEntries)
        assertEquals(0, backup.preflight(backup.exportPackage(emptyMap())).configEntries)
        reject { backup.exportPackage(mapOf("battery" to "invalid")) }
        assertEquals(before, backup.importedConfiguration().toString()); assertEquals(previous, backup.exportPackage())
    }
    fun testTransactionFailureHasNoHalfReferencesAfterReopen() {
        val (context, db, _) = fresh(); val old = db.exportBackup()
        try { DatabaseTransactions { db.writableDatabase }.run { sql ->
            sql.execSQL("INSERT INTO neutral_config(name,value) VALUES('model','uncommitted')")
            seed(db, 1); throw IllegalStateException("injected before commit")
        }; fail() } catch (_: IllegalStateException) {}
        assertEquals(old, db.exportBackup()); db.close()
        val reopened = ConversationDatabase(context)
        try { assertEquals(old, reopened.exportBackup()); assertEquals(0, reopened.readableDatabase.rawQuery("SELECT count(*) FROM neutral_config", null).use { it.moveToFirst(); it.getInt(0) }) }
        finally { reopened.close() }
    }
    fun testMessagesProtocolRoundTripIsMetadataUntilExplicitRestore() {
        val (context,source,exporter)=fresh();seed(source,1)
        val prefs=context.getSharedPreferences("model-settings",Context.MODE_PRIVATE)
        assertTrue(prefs.edit().putString("protocol","messages").putString("endpoint","https://example.invalid/v1/messages")
            .putString("responses-path","").putString("model","fixture").putBoolean("battery",true)
            .putString("key","SYNTHETIC_SECRET").commit())
        assertTrue(exporter.captureNonSecretConfiguration(context).isNotEmpty())
        val text=exporter.exportPackage();assertFalse(text.contains("SYNTHETIC_SECRET"))
        val (targetContext,target,importer)=fresh()
        importer.importPackage(text)
        assertEquals("messages",importer.importedConfiguration().getString("protocol"))
        val targetPrefs=targetContext.getSharedPreferences("model-settings",Context.MODE_PRIVATE)
        assertTrue(targetPrefs.all.isEmpty())
        val settings=app.daiyu.android.storage.ModelSettingsStore(targetContext)
        val plan=settings.prepareNonSecretRestore(importer.importedConfiguration())
        assertTrue(plan.credentialRebindRequired)
        settings.applyNonSecretRestore(plan,true)
        assertEquals("messages",targetPrefs.getString("protocol",null))
        assertFalse(targetPrefs.getBoolean("battery",true))
        assertFalse(targetPrefs.getBoolean("device-status",true))
        reject {settings.load()}
        assertEquals(source.exportBackup(),target.exportBackup())
    }

    fun testInvalidMessagesBackupConfigurationRejectsWithoutMutatingMetadata() {
        val (_,_,backup)=fresh()
        backup.setNonSecretConfiguration(mapOf("model" to "existing"))
        val before=backup.exportPackage()
        for(config in listOf(
            mapOf("protocol" to "unknown"),mapOf("protocol" to false),
            mapOf("protocol" to "messages","responses-path" to "/responses"),
            mapOf("protocol" to "messages","endpoint" to "https://example.invalid/v1/responses"))) {
            reject {backup.setNonSecretConfiguration(config)}
            assertEquals(before,backup.exportPackage())
        }
    }

    private fun payloadOf(text: String) = JSONObject(JSONObject(text).getString("payload"))
    fun testSingleConversationPackageScopesArchivesAssetsUsageAndDraftsWithoutMutation() {
        val (context, source, backup) = fresh(); val selected = seed(source, 6); val other = seed(source, 6)
        source.rename(selected, "selected title"); source.rename(other, "OTHER_TITLE")
        source.draft(selected, "selected draft"); source.draft(other, "OTHER_DRAFT"); source.draft("GLOBAL_DRAFT")
        val contexts = ContextService(context, source)
        val archive = contexts.compress(selected, CompressionPolicy(keepTurns = 2), ContextSummarizer { "selected summary" })!!
        contexts.compress(other, CompressionPolicy(keepTurns = 2), ContextSummarizer { "OTHER_SUMMARY" })!!
        contexts.recordUsage(app.daiyu.android.domain.context.UsageRecord("selected-request", "selected-run", selected.value, 10, 4, 2, null))
        contexts.recordUsage(app.daiyu.android.domain.context.UsageRecord("other-request", "other-run", other.value, 20, 8, null, null))
        val selectedBytes = byteArrayOf(0, 1, -1, 42)
        backup.attach("selected-asset", source.messages(selected).first().second.id.value, "application/octet-stream", selectedBytes)
        backup.attach("other-asset", source.messages(other).first().second.id.value, "application/octet-stream", byteArrayOf(7, 8))
        backup.setNonSecretConfiguration(mapOf("model" to "GLOBAL_MODEL"))
        val before = backup.exportPackage(); val metadata = backup.importedConfiguration().toString()
        val text = backup.exportConversation(selected); val payload = payloadOf(text); val inventory = backup.preflight(text)
        assertEquals(1, inventory.conversations); assertEquals(12, inventory.messages); assertEquals(source.messages(selected).size, inventory.messages)
        assertEquals(1, inventory.archives); assertEquals(1, inventory.attachments); assertEquals(0, inventory.configEntries)
        assertEquals(0, inventory.branches); assertEquals(0, payload.getJSONObject("config").length())
        for(marker in listOf("OTHER_TITLE", "OTHER_DRAFT", "GLOBAL_DRAFT", "OTHER_SUMMARY", "GLOBAL_MODEL", "other-request", "other-asset")) assertFalse(marker, text.contains(marker))
        val inner = payloadOf(payload.getString("text_backup")); assertEquals("", inner.getString("draft"))
        assertEquals("selected draft", inner.getJSONArray("conversations").getJSONObject(0).getString("draft"))
        val usage = payload.getJSONObject("tables").getJSONArray("context_usage")
        assertEquals(1, usage.length()); assertEquals("selected-request", usage.getJSONObject(0).getString("request_id"))
        val (targetContext, target, importer) = fresh(); target.draft("target global draft")
        assertFalse(importer.importPackage(text).alreadyImported); assertTrue(importer.importPackage(text).alreadyImported)
        assertEquals(listOf(selected), target.conversations().map {it.id})
        assertEquals(source.messages(selected).map {it.second}, target.messages(selected).map {it.second})
        assertEquals("selected draft", target.conversations().single().draft); assertEquals("target global draft", target.draft())
        assertTrue(selectedBytes.contentEquals(importer.attachment("selected-asset")))
        assertEquals(contexts.archivePage(archive), ContextService(targetContext, target).archivePage(archive))
        assertEquals(contexts.usage(selected.value), ContextService(targetContext, target).usage(selected.value))
        assertEquals(0, importer.importedConfiguration().length())
        assertEquals(before, backup.exportPackage()); assertEquals(metadata, backup.importedConfiguration().toString())
        target.close(); val reopened = ConversationDatabase(targetContext)
        try {
            assertEquals(source.messages(selected).map {it.second}, reopened.messages(selected).map {it.second})
            assertTrue(NeutralBackupService(targetContext, reopened).importPackage(text).alreadyImported)
            assertEquals("target global draft", reopened.draft())
        } finally { reopened.close() }
    }
    fun testSingleBranchPackageKeepsProvenanceWithoutIncludingParentOrSibling() {
        val (context, source, backup) = fresh(); val parent = seed(source, 2)
        val contexts = ContextService(context, source); val through = source.messages(parent)[1].second.id
        val child = contexts.branch(parent, through); contexts.branch(parent, through)
        source.rename(parent, "PARENT_ONLY_TITLE"); source.draft(parent, "PARENT_ONLY_DRAFT")
        backup.attach("parent-only-asset", source.messages(parent).first().second.id.value, "application/octet-stream", byteArrayOf(8))
        val text = backup.exportConversation(child); val payload = payloadOf(text)
        val branch = payload.getJSONObject("tables").getJSONArray("context_branches")
        assertEquals(1, branch.length()); assertEquals(child.value, branch.getJSONObject(0).getString("conversation_id"))
        assertEquals(parent.value, branch.getJSONObject(0).getString("parent_id")); assertEquals(through.value, branch.getJSONObject(0).getString("through_message"))
        assertEquals(0, backup.preflight(text).attachments)
        for(marker in listOf("PARENT_ONLY_TITLE", "PARENT_ONLY_DRAFT", "parent-only-asset")) assertFalse(text.contains(marker))
        assertEquals(0, backup.preflight(backup.exportConversation(parent)).branches)
        val (_, target, importer) = fresh(); importer.importPackage(text)
        assertEquals(listOf(child), target.conversations().map {it.id}); assertEquals(source.messages(child).map {it.second}, target.messages(child).map {it.second})
        source.delete(parent); assertEquals(text, backup.exportConversation(child))
    }
    fun testSingleConversationIgnoresUnrelatedCorruptionAndWholeLibraryLimits() {
        val (_, source, backup) = fresh(); val selected = seed(source, 1); val other = seed(source, 1)
        source.draft("GLOBAL_DRAFT"); val text = backup.exportConversation(selected)
        source.writableDatabase.beginTransaction()
        try {
            repeat(1000) { source.writableDatabase.execSQL("INSERT INTO conversations(id,title,draft,updated,folder,pinned) VALUES(?,?,?,0,'',0)", arrayOf("unrelated-$it", "other", "")) }
            source.writableDatabase.setTransactionSuccessful()
        } finally { source.writableDatabase.endTransaction() }
        assertEquals(text, backup.exportConversation(selected))
        try { backup.exportPackage(); fail("whole-library conversation limit must reject") }
        catch (failure: IllegalArgumentException) { assertEquals("文本备份最多1000个会话、50000条消息", failure.message) }
        source.writableDatabase.delete("conversations", "id LIKE ?", arrayOf("unrelated-%"))
        backup.attach("unrelated-missing", source.messages(other).first().second.id.value, "application/octet-stream", byteArrayOf(9))
        source.writableDatabase.execSQL("UPDATE neutral_assets SET blob_hash=? WHERE id=?", arrayOf("0".repeat(64), "unrelated-missing"))
        source.writableDatabase.execSQL("UPDATE messages SET blocks='not-json',protocol='not-json',history_segments='not-json' WHERE conversation_id=?", arrayOf(other.value))
        source.writableDatabase.execSQL("INSERT INTO neutral_config(name,value) VALUES('api-key','UNRELATED_SECRET')")
        assertEquals(text, backup.exportConversation(selected)); assertEquals(1, backup.preflight(text).conversations)
        assertFalse(text.contains("UNRELATED_SECRET"))
        source.writableDatabase.execSQL("UPDATE messages SET blocks='not-json' WHERE conversation_id=?", arrayOf(selected.value))
        reject { backup.exportConversation(selected) }
    }
    fun testSingleConversationMissingDeletedAndEmptyAreDistinct() {
        val (_, source, backup) = fresh(); val empty = source.create().id; val deleted = source.create().id
        source.delete(deleted); val before = backup.exportPackage()
        for(id in listOf(ConversationId("missing"), deleted, ConversationId("' OR 1=1 --"))) {
            reject { source.exportConversationBackup(id) }; reject { backup.exportConversation(id) }
        }
        val text = backup.exportConversation(empty); val inventory = backup.preflight(text)
        assertEquals(1, inventory.conversations); assertEquals(0, inventory.messages); assertEquals(0L, inventory.blobBytes)
        val (_, target, importer) = fresh(); importer.importPackage(text)
        assertEquals(listOf(empty), target.conversations().map {it.id}); assertEquals(before, backup.exportPackage())
    }
    fun testSingleConversationReadsOnlyReferencedUniqueBlobsAndRejectsMissingSelectedBlob() {
        val (context, source, backup) = fresh(); val selected = seed(source, 1); val other = seed(source, 1)
        val bytes = byteArrayOf(11, 22, 33); val owner = source.messages(selected).first().second.id.value
        backup.attach("first", owner, "application/octet-stream", bytes); backup.attach("second", owner, "application/octet-stream", bytes)
        backup.attach("unrelated", source.messages(other).first().second.id.value, "application/octet-stream", bytes)
        val text = backup.exportConversation(selected); val payload = payloadOf(text)
        assertEquals(2, backup.preflight(text).attachments); assertEquals(bytes.size.toLong(), backup.preflight(text).blobBytes)
        assertEquals(1, payload.getJSONArray("blobs").length()); assertFalse(text.contains("unrelated"))
        val (_, _, importer) = fresh(); importer.importPackage(text)
        assertTrue(bytes.contentEquals(importer.attachment("first"))); assertTrue(bytes.contentEquals(importer.attachment("second")))
        val hash = payload.getJSONArray("blobs").getJSONObject(0).getString("sha256")
        assertTrue(File(context.filesDir, "neutral-context-blobs/$hash").delete())
        reject { backup.exportConversation(selected) }
    }
    fun testSingleConversationOwnByteBudgetStillRejectsBeforeReadingOversizedRow() {
        val (_, source, backup) = fresh(); val selected = seed(source, 1); val other = source.create().id
        val before = backup.exportConversation(selected)
        source.writableDatabase.execSQL("UPDATE conversations SET title=CAST(zeroblob(16777217) AS TEXT) WHERE id=?", arrayOf(other.value))
        source.writableDatabase.execSQL("UPDATE composer SET draft=CAST(zeroblob(16777217) AS TEXT) WHERE id=1")
        fun budgetReject(work: () -> Unit) {
            try { work(); fail("byte budget must reject before row read") }
            catch (failure: IllegalArgumentException) { assertEquals("备份超过16MiB上限", failure.message) }
        }
        assertEquals(before, backup.exportConversation(selected)); budgetReject { source.exportBackup() }
        source.writableDatabase.execSQL("UPDATE conversations SET title=CAST(zeroblob(16777217) AS TEXT) WHERE id=?", arrayOf(selected.value))
        budgetReject { source.exportConversationBackup(selected) }; budgetReject { backup.exportConversation(selected) }
    }

}
