package app.daiyu.android

import app.daiyu.android.context.ContextService
import app.daiyu.android.domain.*
import app.daiyu.android.storage.ConversationDatabase
import app.daiyu.android.ui.RetainedConversationData

@Suppress("DEPRECATION")
class RetainedConversationDataTest : android.test.AndroidTestCase() {
    private lateinit var scoped: android.content.Context
    private lateinit var database: ConversationDatabase
    private lateinit var data: RetainedConversationData
    override fun setUp() {
        super.setUp(); scoped = testContext(context, "retained-ui-${java.util.UUID.randomUUID()}-")
        database = ConversationDatabase(scoped)
        data = RetainedConversationData(database, ContextService(scoped, database))
    }
    override fun tearDown() {
        try { database.close(); scoped.deleteDatabase("conversations.db") } finally { super.tearDown() }
    }
    fun testFolderCatalogPersistsEmptyFoldersAndKeepsPinnedMembershipOnRenameAndDelete() {
        val conversation = database.create()
        data.createFolder("独立空目录")
        assertEquals(listOf("独立空目录"), data.folders().map { it.name })
        database.close(); database = ConversationDatabase(scoped)
        data = RetainedConversationData(database, ContextService(scoped, database))
        assertEquals(listOf("独立空目录"), data.folders().map { it.name })
        database.organize(conversation.id, "独立空目录", true)
        data.changeFolder("独立空目录", "更名")
        assertEquals("更名", database.conversations().single().folder)
        assertTrue(database.conversations().single().pinned)
        database.close(); database = ConversationDatabase(scoped)
        data = RetainedConversationData(database, ContextService(scoped, database))
        assertEquals(listOf("更名"), data.folders().map { it.name })
        data.changeFolder("更名", null)
        assertTrue(data.folders().isEmpty())
        assertEquals("", database.conversations().single().folder)
        assertTrue(database.conversations().single().pinned)
    }
    fun testFolderRenameConflictPreservesBothCatalogAndConversation() {
        val conversation = database.create()
        data.createFolder("原目录"); data.createFolder("目标")
        database.organize(conversation.id, "原目录", true)
        try { data.changeFolder("原目录", "目标"); fail("conflict accepted") } catch (_: IllegalStateException) { }
        assertEquals(setOf("原目录", "目标"), data.folders().map { it.name }.toSet())
        assertEquals("原目录", database.conversations().single().folder)
    }
    fun testFullHistorySearchReturnsExactOlderMessageIdentityAndStatsDoNotInventUsage() {
        val conversation = database.create("default")
        val run = RunId("search-run")
        val user = Message(MessageId("older-user"), conversation.id, run, "user", listOf(MessageBlock.Text("needle-text", "before Needle after")), RunStatus.COMPLETED)
        val assistant = Message(MessageId("older-answer"), conversation.id, run, "assistant", emptyList(), RunStatus.RUNNING)
        database.send(user, assistant)
        database.save(assistant.copy(status = RunStatus.COMPLETED))
        repeat(60) { n ->
            val r = RunId("later-$n")
            val u = Message(MessageId("u-$n"), conversation.id, r, "user", listOf(MessageBlock.Text("text-$n", "later $n")), RunStatus.COMPLETED)
            val a = Message(MessageId("a-$n"), conversation.id, r, "assistant", emptyList(), RunStatus.RUNNING)
            database.send(u, a); database.save(a.copy(status = RunStatus.COMPLETED))
        }
        val hit = data.search("needle").single()
        assertEquals(conversation.id.value, hit.conversationId); assertEquals(user.id.value, hit.messageId)
        val snapshot = data.stats()
        assertEquals(1, snapshot.currentConversations); assertEquals(122, snapshot.currentMessages)
        assertFalse(snapshot.heatmapAvailable); assertFalse(snapshot.tokenAccountingAvailable); assertFalse(snapshot.lifetimeAccountingAvailable)
        assertTrue(snapshot.modelUsage.providers.isEmpty())
        val recorded = snapshot.recordedUsage!!
        assertTrue(recorded.scanComplete); assertEquals(61, recorded.messagesWithoutCheckpoint)
        assertEquals(0L, recorded.summary.requestSnapshotCount); assertNull(recorded.summary.input.reportedTokens)
        val summary = data.summaries(null).single()
        assertFalse(summary.isActiveRun); assertTrue(summary.hasCompletionMarker) // Real completed events remain unread.
    }
    fun testMixedImageHistorySearchKeepsSegmentAndNavigationOwnerWithoutMetadataLeaks() {
        val conversation = database.create("default"); val run = RunId("image-history-run")
        val initial = Message(MessageId("initial-user"), conversation.id, run, Message.USER_ROLE,
            listOf(MessageBlock.Text("initial", "start")), RunStatus.COMPLETED)
        val assistant = initial.copy(id = MessageId("history-owner"), role = Message.ASSISTANT_ROLE,
            blocks = emptyList(), status = RunStatus.RUNNING)
        database.send(initial, assistant)
        val imageOwner = MessageImageOwner(conversation.id.value, "historical-supplement")
        val reference = MessageImageReference(imageOwner, "00000000-0000-0000-0000-000000000001",
            "image/png", 12, "b".repeat(64), 2, 2)
        database.save(assistant.copy(status = RunStatus.COMPLETED, historySegments = listOf(
            MessageHistorySegment(assistant.id, Message.ASSISTANT_ROLE, listOf(MessageBlock.Text("before", "before"))),
            MessageHistorySegment(MessageId(imageOwner.messageId), Message.USER_ROLE, listOf(
                MessageBlock.Text("needle", "historical needle"), MessageBlock.Image(reference),
                MessageBlock.Text("after", "after image"))),
            MessageHistorySegment(assistant.id, Message.ASSISTANT_ROLE, listOf(MessageBlock.Text("final", "final"))),
        )))
        database.close(); database = ConversationDatabase(scoped)
        data = RetainedConversationData(database, ContextService(scoped, database))
        val hit = data.search("needle").single()
        assertEquals(conversation.id.value, hit.conversationId)
        assertEquals(imageOwner.messageId, hit.messageId)
        assertEquals(assistant.id.value, hit.ownerMessageId)
        assertTrue(hit.snippet.contains("after image"))
        assertFalse(hit.snippet.contains(reference.id))
        assertTrue(data.search(reference.id).isEmpty())
        assertTrue(data.search(reference.sha256).isEmpty())
        assertTrue(data.search(reference.mime).isEmpty())
    }

    fun testStatsConsumesCommittedRunnerUsageReplacementReopenAndDeletion() {
        val conversation = database.create("default"); val run = RunId("usage-run")
        val user = Message(MessageId("usage-user"), conversation.id, run, "user", emptyList(), RunStatus.COMPLETED)
        val assistant = user.copy(id = MessageId("usage-answer"), role = "assistant", status = RunStatus.RUNNING)
        app.daiyu.android.storage.SQLiteRuntimeStore.acquire(database).use { runtime ->
            runtime.recoverPending()
            database.send(user, assistant)
            var rounds = 0
            val runner = ChatRunner(ModelPort { object : ModelSession {
                override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                    if (++rounds == 1) {
                        sink(ModelEvent.Usage(10, 20, 5)); sink(ModelEvent.Usage(null, 0, 0))
                        sink(ModelEvent.ToolReady(ToolInvocation(ToolCallId("usage-tool"), "get_battery_status", "{}")))
                    } else sink(ModelEvent.Usage(7, 9, 2))
                }
                override fun cancel() {}
            } }, database, BatteryTool { "ok" }, app.daiyu.android.domain.runtime.RunnerOptions(runtimeStore = runtime)) {}
            assertEquals(RunStatus.COMPLETED, runner.run(assistant, listOf(user)).status)
        }
        val snapshot = data.stats(); val summary = snapshot.recordedUsage!!.summary
        assertEquals(2L, summary.requestSnapshotCount)
        assertEquals(7L, summary.input.reportedTokens); assertEquals(1L, summary.input.unknownRequestCount)
        assertEquals(9L, summary.output.reportedTokens); assertEquals(2L, summary.cached.reportedTokens)
        assertFalse(snapshot.tokenAccountingAvailable); assertFalse(snapshot.lifetimeAccountingAvailable)
        assertTrue(snapshot.modelUsage.providers.isEmpty())
        assertEquals(7L, snapshot.overviewTokens.input); assertEquals(9L, snapshot.overviewTokens.output)
        assertEquals(2L, snapshot.overviewTokens.cached); assertNull(snapshot.overviewTokens.total)
        database.close(); database = ConversationDatabase(scoped)
        data = RetainedConversationData(database, ContextService(scoped, database))
        assertEquals(snapshot.recordedUsage, data.stats().recordedUsage)
        assertEquals(snapshot.overviewTokens, data.stats().overviewTokens)
        data.deleteAll(); val empty = data.stats().recordedUsage!!
        assertEquals(0L, empty.summary.requestSnapshotCount); assertNull(empty.summary.output.reportedTokens)
        assertTrue(empty.scanComplete)
        assertNull(data.stats().overviewTokens.input)
    }
    fun testDeleteAllRollsBackEarlierDeletesWhenLaterDeleteFails() {
        val first = database.create(); val second = database.create()
        val last = database.conversations().last().id.value
        database.writableDatabase.execSQL("CREATE TRIGGER reject_one BEFORE DELETE ON conversations WHEN old.id='$last' BEGIN SELECT RAISE(ABORT,'test'); END")
        try { data.deleteAll(); fail("failure missing") } catch (_: android.database.sqlite.SQLiteException) { }
        assertEquals(setOf(first.id, second.id), database.conversations().map { it.id }.toSet())
        database.writableDatabase.execSQL("DROP TRIGGER reject_one")
        data.deleteAll(); assertTrue(database.conversations().isEmpty())
    }
    fun testVersionSixUpgradePreservesHistoryDraftPinAndExistingFolder() {
        val conversation = database.create()
        database.draft(conversation.id, "迁移草稿")
        database.organize(conversation.id, "已有目录", true)
        database.writableDatabase.execSQL("DROP TABLE conversation_folders")
        removeAssistantSchemaForLegacyFixture(database.writableDatabase)
        database.writableDatabase.version = 6
        database.close(); database = ConversationDatabase(scoped)
        data = RetainedConversationData(database, ContextService(scoped, database))
        assertEquals(23, database.readableDatabase.version)
        val restored = database.conversations().single()
        assertEquals(conversation.id, restored.id); assertEquals("迁移草稿", restored.draft)
        assertEquals("已有目录", restored.folder); assertTrue(restored.pinned)
        assertEquals(listOf("已有目录"), data.folders().map { it.name })
    }
}
