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
        val owner = db.create().id
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
    fun testLateConfigurationConflictRollsBackTextTablesAndImportMarker() {
        val (_, source, exporter) = fresh(); seed(source, 1)
        exporter.setNonSecretConfiguration(mapOf("model" to "incoming")); val text = exporter.exportPackage()
        val (_, target, importer) = fresh(); seed(target, 1); importer.setNonSecretConfiguration(mapOf("model" to "existing"))
        val before = target.exportBackup(); reject { importer.importPackage(text) }
        assertEquals(before, target.exportBackup()); assertEquals("existing", importer.importedConfiguration().getString("model"))
        val count = target.readableDatabase.rawQuery("SELECT count(*) FROM neutral_imports", null).use { it.moveToFirst(); it.getInt(0) }
        assertEquals(0, count)
    }
    fun testConfigurationCaptureReplacesSnapshotAndClearsRemovedPreferences() {
        val (context, db, backup) = fresh()
        assertEquals(6, db.writableDatabase.version)
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
        assertEquals(6, db.writableDatabase.version)
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
            assertEquals(6, reopened.readableDatabase.version)
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

}
