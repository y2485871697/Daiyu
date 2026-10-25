package app.daiyu.android.storage

import android.database.sqlite.SQLiteDatabase
import android.test.InstrumentationTestCase
import app.daiyu.android.backup.BackupStatisticsCodec

@Suppress("DEPRECATION")
class UsageStatisticsDatabaseTest : InstrumentationTestCase() {
    private fun database() = SQLiteDatabase.create(null).apply {
        execSQL("CREATE TABLE conversations(id TEXT PRIMARY KEY,updated INTEGER NOT NULL)")
        execSQL("CREATE TABLE messages(id TEXT PRIMARY KEY,conversation_id TEXT NOT NULL,role TEXT NOT NULL,blocks TEXT NOT NULL)")
        ConversationUsageStore.installSchema(this)
        tx(this) { UsageStatisticsStore.install(this) }
    }
    private fun <T> tx(db: SQLiteDatabase, action: () -> T): T {
        db.beginTransaction(); return try { action().also { db.setTransactionSuccessful() } } finally { db.endTransaction() }
    }
    private fun create(db: SQLiteDatabase, id: String, at: Long) = tx(db) {
        db.execSQL("INSERT INTO conversations VALUES(?,?)", arrayOf(id, at))
        UsageStatisticsStore.created(db, id, at)
    }
    private fun record(db: SQLiteDatabase, id: String, input: Long, output: Long) = tx(db) {
        UsageStatisticsStore.record(db, id, "one", UsageStatisticsStore.ModelIdentity("provider", "Provider", "model", "Model"),
            100, UsageStatisticsStore.Fields(input, output, 0, 0), null)
    }
    fun testRequestReplacementSurvivesMessagePruningAndConversationDeletionWithoutCountingEmptyAssistant() = database().use { db ->
        create(db, "one", 0)
        db.execSQL("INSERT INTO messages VALUES('user','one','user','[]')")
        db.execSQL("INSERT INTO messages VALUES('empty','one','assistant','[]')")
        record(db, "request", 100, 20); record(db, "request", 90, 0)
        val before = tx(db) { UsageStatisticsStore.snapshot(db, 7) }
        assertEquals(1, before.currentMessages); assertEquals(1, before.lifetimeMessages)
        assertEquals(90L, before.lifetimeInputTokens); assertEquals(0L, before.lifetimeOutputTokens)
        tx(db) { UsageStatisticsStore.retire(db, "one"); db.delete("messages", null, null); db.delete("conversations", null, null) }
        val after = tx(db) { UsageStatisticsStore.snapshot(db, 7) }
        assertEquals(0, after.currentMessages); assertEquals(1, after.lifetimeMessages)
        assertEquals(0L, after.currentInputTokens); assertEquals(90L, after.lifetimeInputTokens)
        assertEquals(1, after.lifetimeConversations); assertEquals(7, after.launchCount)
    }
    fun testLegacyReceiptMigrationIsScopedAndLeavesModelAndDateUnknown() = database().use { db ->
        create(db, "one", 0)
        db.execSQL("INSERT INTO conversation_usage_receipts VALUES('one','owner','request',10000,7,NULL,NULL,1000)")
        tx(db) { UsageStatisticsStore.importLegacyReceipts(db, setOf("one")) }
        tx(db) { UsageStatisticsStore.importLegacyReceipts(db, setOf("one")) }
        val snapshot = tx(db) { UsageStatisticsStore.snapshot(db, 0) }
        assertEquals(0L, snapshot.lifetimeInputTokens); assertEquals(7L, snapshot.lifetimeOutputTokens)
        assertEquals(1, snapshot.unattributedUsageRequests); assertTrue(snapshot.modelUsage.providers.isEmpty())
    }
    fun testRetirementRollbackAndOverlappingBackupFactsRemainAtomicAndDeduplicated() = database().use { source ->
        create(source, "one", 0); record(source, "request", 100, 20)
        source.beginTransaction()
        try { UsageStatisticsStore.retire(source, "one"); source.delete("conversations", null, null) }
        finally { source.endTransaction() }
        assertEquals(1, tx(source) { UsageStatisticsStore.snapshot(source, 0) }.currentConversations)
        val section = tx(source) { BackupStatisticsCodec.export(source, null) }
        val data = BackupStatisticsCodec.validate(section, setOf("one"))!!
        database().use { target ->
            tx(target) {
                val expected = BackupStatisticsCodec.prepare(target, data)
                BackupStatisticsCodec.restore(target, data); BackupStatisticsCodec.verify(target, data, expected)
            }
            tx(target) {
                val expected = BackupStatisticsCodec.prepare(target, data)
                BackupStatisticsCodec.restore(target, data); BackupStatisticsCodec.verify(target, data, expected)
            }
            assertEquals(120L, tx(target) { UsageStatisticsStore.snapshot(target, 0) }.overviewTokens.total)
            val modified = org.json.JSONObject(section.toString())
            modified.getJSONArray("requests").getJSONObject(0).put("input_tokens", 101)
            val conflicting = BackupStatisticsCodec.validate(modified, setOf("one"))!!
            try { tx(target) { BackupStatisticsCodec.prepare(target, conflicting) }; fail("conflict accepted") }
            catch (_: IllegalArgumentException) { }
        }
    }
    fun testEmptySectionFreezesWholeSetIncludingDuplicateImportAndNullableCache() = database().use { db ->
        val empty = BackupStatisticsCodec.validate(tx(db) { BackupStatisticsCodec.export(db, null) }, emptySet())!!
        tx(db) {
            val expected = BackupStatisticsCodec.prepare(db, empty)
            BackupStatisticsCodec.restore(db, empty); BackupStatisticsCodec.verify(db, empty, expected)
        }
        db.beginTransaction()
        try {
            val expected = BackupStatisticsCodec.prepare(db, empty)
            db.execSQL("INSERT INTO usage_statistics_requests(id) VALUES('late')")
            try { BackupStatisticsCodec.verify(db, empty, expected); fail("late row accepted") }
            catch (_: IllegalStateException) { }
        } finally { db.endTransaction() }
        assertEquals(0L, tx(db) { UsageStatisticsStore.snapshot(db, 0) }.overviewTokens.total)
    }
}
