package app.daiyu.android.storage

import app.daiyu.android.context.ContextService
import app.daiyu.android.domain.*
import app.daiyu.android.testContext
import app.daiyu.android.ui.RetainedConversationData

@Suppress("DEPRECATION")
class ConversationCompletionMarkersTest : android.test.AndroidTestCase() {
    private lateinit var scoped: android.content.Context
    private lateinit var database: ConversationDatabase
    override fun setUp() {
        super.setUp()
        scoped = testContext(context, "completion-markers-${java.util.UUID.randomUUID()}-")
        database = ConversationDatabase(scoped)
    }
    override fun tearDown() {
        try { database.close(); scoped.deleteDatabase("conversations.db") } finally { super.tearDown() }
    }
    private fun turn(conversation: Conversation, key: String): Message {
        val user = Message(MessageId("user-$key"), conversation.id, RunId(key), Message.USER_ROLE,
            listOf(MessageBlock.Text("user", key)), RunStatus.COMPLETED)
        val answer = user.copy(id = MessageId("assistant-$key"), role = Message.ASSISTANT_ROLE,
            blocks = emptyList(), status = RunStatus.RUNNING)
        database.send(user, answer)
        return answer
    }
    private fun receipt(conversation: Conversation) = ConversationCompletionMarkers.read(database.readableDatabase, conversation.id)
    private fun marked() = RetainedConversationData(database, ContextService(scoped, database)).summaries(null).single().hasCompletionMarker

    fun testActualTransitionPersistsAndOldAcknowledgementCannotClearNewCompletion() {
        val conversation = database.create("default")
        val first = turn(conversation, "first")
        assertNull(receipt(conversation)); assertFalse(marked())
        database.save(first.copy(status = RunStatus.COMPLETED))
        val old = checkNotNull(receipt(conversation)); assertTrue(marked())
        // Idempotent terminal replay is not a second unread event.
        database.save(first.copy(status = RunStatus.COMPLETED))
        assertEquals(old, receipt(conversation))
        database.close(); database = ConversationDatabase(scoped)
        assertEquals(old, receipt(conversation)); assertTrue(marked())
        val next = turn(conversation, "next")
        database.save(next.copy(status = RunStatus.COMPLETED))
        val fresh = checkNotNull(receipt(conversation))
        assertFalse(old.token == fresh.token)
        assertFalse(ConversationCompletionMarkers.acknowledge(database.writableDatabase, old))
        assertEquals(fresh, receipt(conversation)); assertTrue(marked())
        assertTrue(ConversationCompletionMarkers.acknowledge(database.writableDatabase, fresh))
        assertFalse(marked())
        database.close(); database = ConversationDatabase(scoped)
        assertFalse(marked()) // Historical latest COMPLETED must not relight after reopen.
    }

    fun testFailedStoppedRollbackAndDeletedConversationsDoNotLeaveCompletion() {
        val conversation = database.create("default")
        for (status in listOf(RunStatus.FAILED, RunStatus.STOPPED, RunStatus.INTERRUPTED)) {
            val answer = turn(conversation, status.name)
            database.save(answer.copy(status = status))
            assertFalse(marked())
        }
        val answer = turn(conversation, "rollback")
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("UPDATE messages SET status='COMPLETED' WHERE id=?", arrayOf(answer.id.value))
            assertNotNull(receipt(conversation))
            // No setTransactionSuccessful: the completion event rolls back with the message.
        } finally { db.endTransaction() }
        assertFalse(marked())
        database.save(answer.copy(status = RunStatus.COMPLETED))
        assertTrue(marked())
        database.delete(conversation.id)
        assertNull(receipt(conversation))
    }

    fun testImportedCompletedHistoryDoesNotInventUnreadCompletion() {
        val conversation = database.create("default")
        val answer = turn(conversation, "backup-history")
        database.save(answer.copy(status = RunStatus.COMPLETED))
        assertTrue(marked())
        val backup = database.exportBackup()
        database.delete(conversation.id)
        database.importBackup(backup)
        assertEquals(RunStatus.COMPLETED, database.messages(conversation.id).last().second.status)
        assertFalse(marked()) // Backup INSERT is not an observed live completion event.
    }

    fun testVersion17And18UpgradePreservesHistoryAndInstallsEmptyMarkers() {
        for (version in listOf(17, 18)) {
            val conversation = database.create("default")
            val previous = turn(conversation, "migration-$version")
            database.save(previous.copy(status = RunStatus.COMPLETED))
            database.writableDatabase.apply {
                execSQL("DROP TRIGGER conversation_completion_event")
                execSQL("DROP TRIGGER conversation_completion_message_deleted")
                execSQL("DROP TABLE conversation_completion_markers")
                this.version = version
            }
            database.close(); database = ConversationDatabase(scoped)
            assertEquals(24, database.readableDatabase.version)
            assertEquals(RunStatus.COMPLETED, database.messages(conversation.id).last().second.status)
            assertNull(receipt(conversation))
            val next = turn(conversation, "after-migration-$version")
            database.save(next.copy(status = RunStatus.COMPLETED))
            assertNotNull(receipt(conversation))
            database.delete(conversation.id)
        }
    }

    fun testAdditiveInstallDoesNotBackfillOldCompletedHistory() {
        val conversation = database.create("default")
        val db = database.writableDatabase
        db.execSQL("DROP TRIGGER conversation_completion_event")
        val answer = turn(conversation, "pre-migration-history")
        database.save(answer.copy(status = RunStatus.COMPLETED))
        ConversationCompletionMarkers.installSchema(db)
        assertFalse(marked())
        val next = turn(conversation, "post-migration-live")
        database.save(next.copy(status = RunStatus.COMPLETED))
        assertTrue(marked())
    }
}
