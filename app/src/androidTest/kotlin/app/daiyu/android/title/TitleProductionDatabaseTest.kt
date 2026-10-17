package app.daiyu.android.title

import android.content.Context
import android.content.ContextWrapper
import android.test.InstrumentationTestCase
import app.daiyu.android.context.ContextService
import app.daiyu.android.domain.*
import app.daiyu.android.domain.context.UsageRecord
import app.daiyu.android.storage.ConversationDatabase
import app.daiyu.android.testContext
import java.io.File
import java.util.UUID

/** Actual ConversationDatabase + unchanged rename/delete + LEGACY usage port compatibility.
 * Current typed production title authority is covered by P21RequestCoverageDatabaseTest. */
@Suppress("DEPRECATION")
class TitleProductionDatabaseTest : InstrumentationTestCase() {
    private lateinit var context: Context
    private lateinit var database: ConversationDatabase
    private lateinit var service: ContextService
    private val run = RunId("title-parent-run")
    override fun setUp() {
        super.setUp()
        val base = testContext(instrumentation.targetContext, "title-production-${UUID.randomUUID()}-")
        val files = File(base.cacheDir, "title-production-${UUID.randomUUID()}").apply { mkdirs() }
        context = object : ContextWrapper(base) { override fun getFilesDir(): File = files }
        database = ConversationDatabase(context)
        service = ContextService(context, database)
        assertEquals(19, database.writableDatabase.version)
        // Production helper, not a test-only manual schema installation.
        assertTrue(database.writableDatabase.rawQuery("SELECT 1 FROM sqlite_master WHERE name='conversation_title_revisions'", null).use { it.moveToFirst() })
    }
    override fun tearDown() {
        database.close(); context.deleteDatabase("conversations.db"); context.filesDir.deleteRecursively()
        super.tearDown()
    }
    private fun seed(): ConversationId {
        val id = database.create().id
        database.save(Message(MessageId("u-${id.value}"), id, run, "user", listOf(MessageBlock.Text("u", "synthetic question")), RunStatus.COMPLETED))
        database.save(Message(MessageId("a-${id.value}"), id, run, "assistant", listOf(MessageBlock.Text("a", "synthetic answer")), RunStatus.COMPLETED))
        return id
    }
    private fun adapter() = SqliteTitleCommitAdapter({ database.writableDatabase }, ParentTitleUsagePort { sql, r ->
        assertSame(database.writableDatabase, sql)
        service.recordUsage(UsageRecord(r.requestId, r.parentRunId.value, r.conversationId.value,
            r.usage?.inputTokens, r.usage?.outputTokens, r.usage?.cachedTokens))
    })
    private fun receipt(id: ConversationId) = ParentTitleUsage("title-synthetic-receipt", id, run, ModelEvent.Usage(8, null, 2))
    private fun legacyUsageCount(id: ConversationId): Long = database.readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM context_usage WHERE branch_id=? AND usage_source='LEGACY'", arrayOf(id.value)).use {
        it.moveToFirst(); it.getLong(0)
    }

    fun testTitleAndExistingUsageSurviveReopenAndDeleteUsesExistingCleanup() {
        val id = seed(); val adapter = adapter(); val frozen = adapter.capture(id)!!
        assertEquals("synthetic question", adapter.firstQuestion(frozen))
        assertTrue(adapter.commit(frozen, "保全标题", receipt(id)))
        database.close(); database = ConversationDatabase(context); service = ContextService(context, database)
        assertEquals("保全标题", database.conversations().single().title)
        assertEquals(1L, legacyUsageCount(id))
        assertEquals(0L, service.usage(id.value).requests) // No source evidence: legacy is preserved, not promoted.
        assertFalse(adapter().isCurrent(frozen))
        database.delete(id)
        assertTrue(database.conversations().isEmpty())
        assertNull(adapter().capture(id))
        assertEquals(0L, legacyUsageCount(id))
    }
    fun testUnchangedManualRenameSameTitleFencesResultAndEmitsNoUsage() {
        val id = seed(); val adapter = adapter(); val frozen = adapter.capture(id)!!
        database.rename(id, "新会话")
        assertFalse(adapter.commit(frozen, "保全标题", receipt(id)))
        assertEquals(0L, legacyUsageCount(id))
    }
    fun testUpgrade13Through15InstallsBeforeEarlyReturnAndPreservesTimeAndVideoWitness() {
        val id = seed()
        val sql = database.writableDatabase
        sql.execSQL("UPDATE messages SET created_at=123456 WHERE role='user'")
        fun witness() = database.readableDatabase.rawQuery(
            "SELECT message_id,reference_count,manifest_sha256 FROM message_video_witnesses ORDER BY message_id", null).use { c ->
            buildList { while (c.moveToNext()) add(listOf(c.getString(0), c.getString(1), if (c.isNull(2)) null else c.getString(2))) }
        }
        val originalWitness = witness()
        for (old in 13..15) {
            database.writableDatabase.apply {
                execSQL("DROP TRIGGER conversation_title_insert")
                execSQL("DROP TRIGGER conversation_title_update")
                execSQL("DROP TRIGGER conversation_title_delete")
                execSQL("DROP TABLE conversation_title_revisions")
                version = old
            }
            database.close()
            database = ConversationDatabase(context); service = ContextService(context, database)
            assertEquals(19, database.writableDatabase.version)
            val captured = adapter().capture(id)!!
            assertTrue(adapter().isCurrent(captured)); assertFalse(captured.titleTouched)
            assertEquals("新会话", captured.originalTitle)
            assertEquals(originalWitness, witness())
            assertEquals(123456L, database.readableDatabase.rawQuery("SELECT created_at FROM messages WHERE role='user'", null).use {
                assertTrue(it.moveToFirst()); it.getLong(0)
            })
            assertTrue(database.readableDatabase.rawQuery("SELECT created_at FROM messages WHERE role='assistant'", null).use {
                assertTrue(it.moveToFirst()); it.isNull(0)
            })
            assertEquals(0L, legacyUsageCount(id)) // migration never generates titles
        }
    }

    fun testNeutralBackupRoundTripKeepsTitleUsageAndDoesNotReplayTitleJobs() {
        val id = seed(); val frozen = adapter().capture(id)!!
        assertTrue(adapter().commit(frozen, "保全标题", receipt(id)))
        val backup = app.daiyu.android.backup.NeutralBackupService(context, database)
        val exported = backup.exportPackage()
        assertFalse(exported.contains("title-model-api-key"))
        assertFalse(exported.contains("title-model-provider"))
        database.delete(id)
        assertFalse(backup.importPackage(exported).alreadyImported)
        val restored = database.conversations().single()
        assertEquals("保全标题", restored.title)
        assertEquals(1L, legacyUsageCount(restored.id))
        assertFalse(adapter().isCurrent(frozen))
        assertTrue(backup.importPackage(exported).alreadyImported)
        assertEquals(1L, legacyUsageCount(restored.id))
    }

    fun testExistingUsageInsertFailureRollsBackTitleAndWitness() {
        val id = seed(); val adapter = adapter(); val frozen = adapter.capture(id)!!
        database.writableDatabase.execSQL("CREATE TRIGGER fail_title_usage BEFORE INSERT ON context_usage BEGIN SELECT RAISE(ABORT,'synthetic failure'); END")
        try { adapter.commit(frozen, "保全标题", receipt(id)); fail() } catch (_: TitleUsagePersistenceException) { }
        assertEquals(frozen, adapter.capture(id))
        assertEquals("新会话", database.conversations().single().title)
        assertEquals(0L, legacyUsageCount(id))
    }
}
