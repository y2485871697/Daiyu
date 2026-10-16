package app.daiyu.android.storage

import android.content.Context
import android.database.sqlite.SQLiteException
import app.daiyu.android.domain.Conversation
import app.daiyu.android.domain.ConversationId
import app.daiyu.android.domain.Message
import app.daiyu.android.domain.MessageBlock
import app.daiyu.android.domain.MessageId
import app.daiyu.android.domain.RunId
import app.daiyu.android.domain.RunStatus
import app.daiyu.android.testContext
import java.util.UUID

/** Real SQLite triggers, not mocked insert return values. */
@Suppress("DEPRECATION")
class ConversationSendReceiptTest : android.test.AndroidTestCase() {
    private lateinit var scoped: Context
    private lateinit var database: ConversationDatabase

    override fun setUp() {
        super.setUp()
        scoped = testContext(context, "send-receipt-${UUID.randomUUID()}-")
        database = ConversationDatabase(scoped)
    }

    override fun tearDown() {
        try {
            database.close()
            scoped.deleteDatabase("conversations.db")
        } finally {
            super.tearDown()
        }
    }

    private fun reopen() {
        database.close()
        database = ConversationDatabase(scoped)
    }

    private fun messages(id: ConversationId): Pair<Message, Message> {
        val key = UUID.randomUUID().toString()
        val user = Message(MessageId("user-$key"), id, RunId("run-$key"), "user",
            listOf(MessageBlock.Text("text-$key", "send text")), RunStatus.COMPLETED)
        return user to user.copy(id = MessageId("assistant-$key"), role = "assistant",
            blocks = emptyList(), status = RunStatus.RUNNING)
    }

    private fun retainedConversation(): ConversationId {
        val id = database.create().id
        val (user, assistant) = messages(id)
        database.send(user, assistant)
        database.save(assistant.copy(status = RunStatus.COMPLETED))
        database.rename(id, "retained title")
        database.organize(id, "retained folder", true)
        database.draft(id, "retained conversation draft")
        return id
    }

    private data class State(
        val composer: String,
        val conversations: List<Conversation>,
        val messages: List<List<String?>>
    )

    // Include raw rows (even orphaned ones) and seq, not just the visible conversation tail.
    private fun state(): State = State(database.draft(), database.conversations(),
        database.readableDatabase.rawQuery(
            "SELECT seq,id,conversation_id,run_id,role,blocks,status,error,protocol,history_segments FROM messages ORDER BY seq", null
        ).use { c -> buildList {
            while (c.moveToNext()) add(List(c.columnCount) { i -> if (c.isNull(i)) null else c.getString(i) })
        } })

    private fun rejected(block: () -> Unit): Exception {
        try { block() } catch (e: Exception) { return e }
        fail("send must not acknowledge an uncommitted pair")
        throw AssertionError("unreachable")
    }

    private fun assertRolledBack(before: State) {
        assertEquals(before, state())
        reopen()
        assertEquals(before, state())
    }

    private fun assertCommitted(user: Message, assistant: Message, newConversation: Boolean) {
        val receipt = database.send(user, assistant, newConversation)
        reopen()
        assertEquals(receipt, database.conversations().single { it.id == user.conversationId })
        assertEquals("", receipt.draft)
        assertEquals(listOf(user, assistant), database.messages(receipt.id).takeLast(2).map { it.second })
        assertEquals(if (newConversation) "" else "composer draft", database.draft())
    }

    private fun failureAndRetry(newConversation: Boolean, trigger: String, sqliteException: Boolean = false) {
        val retained = retainedConversation()
        val id = if (newConversation) ConversationId(UUID.randomUUID().toString()) else retained
        val (user, assistant) = messages(id)
        database.draft("composer draft")
        database.writableDatabase.execSQL("CREATE TRIGGER reject_send $trigger")
        val before = state()
        val failure = rejected { database.send(user, assistant, newConversation) }
        if (sqliteException) assertTrue(failure is SQLiteException)
        else assertTrue(failure is IllegalStateException)
        assertRolledBack(before)
        database.writableDatabase.execSQL("DROP TRIGGER reject_send")
        assertCommitted(user, assistant, newConversation)
    }

    fun testNormalNewConversationCommitsBothRowsAndClearsOnlyComposerDraft() {
        val retained = retainedConversation()
        val before = database.conversations().single { it.id == retained }
        val oldMessages = database.messages(retained)
        database.draft("composer draft")
        val (user, assistant) = messages(ConversationId(UUID.randomUUID().toString()))
        assertCommitted(user, assistant, true)
        assertEquals(before, database.conversations().single { it.id == retained })
        assertEquals(oldMessages, database.messages(retained))
    }

    fun testNormalExistingConversationPreservesMetadataHistoryAndComposer() {
        val id = retainedConversation()
        val oldMessages = database.messages(id)
        database.draft("composer draft")
        val (user, assistant) = messages(id)
        assertCommitted(user, assistant, false)
        val stored = database.conversations().single()
        assertEquals("retained title", stored.title)
        assertEquals("retained folder", stored.folder)
        assertTrue(stored.pinned)
        assertEquals(oldMessages, database.messages(id).dropLast(2))
    }

    fun testIgnoredUserInsertRollsBackNewAndExistingConversationAndCanRetry() {
        for (newConversation in listOf(true, false)) failureAndRetry(newConversation,
            "BEFORE INSERT ON messages WHEN NEW.role='user' BEGIN SELECT RAISE(IGNORE); END")
    }

    fun testIgnoredAssistantInsertRollsBackUserAndDraftsAndCanRetry() {
        for (newConversation in listOf(true, false)) failureAndRetry(newConversation,
            "BEFORE INSERT ON messages WHEN NEW.role='assistant' BEGIN SELECT RAISE(IGNORE); END")
    }

    fun testIgnoredNewConversationInsertPreservesComposerAndRetainedRows() {
        failureAndRetry(true, "BEFORE INSERT ON conversations BEGIN SELECT RAISE(IGNORE); END")
    }

    fun testIgnoredNewConversationInsertCannotAcknowledgeAnExistingConversation() {
        val id = retainedConversation()
        val (user, assistant) = messages(id)
        database.draft("composer draft")
        database.writableDatabase.execSQL(
            "CREATE TRIGGER reject_send BEFORE INSERT ON conversations BEGIN SELECT RAISE(IGNORE); END")
        val before = state()
        assertTrue(rejected { database.send(user, assistant, true) } is IllegalStateException)
        assertRolledBack(before)
        database.writableDatabase.execSQL("DROP TRIGGER reject_send")
        assertCommitted(user, assistant, false)
    }

    fun testSqliteExceptionStillRollsBackWholeSendAndCanRetry() {
        for (newConversation in listOf(true, false)) failureAndRetry(newConversation,
            "BEFORE INSERT ON messages WHEN NEW.role='assistant' BEGIN SELECT RAISE(ABORT, 'injected failure'); END",
            sqliteException = true)
    }

    fun testMissingExistingConversationDoesNotClearComposerOrInsertMessages() {
        retainedConversation()
        database.draft("composer draft")
        val (user, assistant) = messages(ConversationId(UUID.randomUUID().toString()))
        val before = state()
        assertTrue(rejected { database.send(user, assistant) } is IllegalStateException)
        assertRolledBack(before)
    }

    fun testDuplicateMessageIdentityStillRollsBackDraftAndEarlierInsert() {
        val id = retainedConversation()
        database.draft("composer draft")
        val (user, assistant) = messages(id)
        val duplicate = assistant.copy(id = database.messages(id).last().second.id)
        val before = state()
        assertTrue(rejected { database.send(user, duplicate) } is SQLiteException)
        assertRolledBack(before)
        assertCommitted(user, assistant, false)
    }

    fun testPositiveInsertReceiptsCannotHideDeletedUserRow() {
        failureAndRetry(false,
            "AFTER INSERT ON messages WHEN NEW.role='assistant' BEGIN " +
                "DELETE FROM messages WHERE conversation_id=NEW.conversation_id AND run_id=NEW.run_id AND role='user'; END")
    }

    fun testPositiveInsertReceiptMustStillBelongToRequestedRun() {
        failureAndRetry(false,
            "AFTER INSERT ON messages WHEN NEW.role='assistant' BEGIN " +
                "UPDATE messages SET run_id='redirected-run' WHERE id=NEW.id; END")
    }

    fun testNewConversationReceiptMustStillIdentifyTheInsertedRow() {
        failureAndRetry(true,
            "AFTER INSERT ON messages WHEN NEW.role='assistant' BEGIN " +
                "UPDATE conversations SET rowid=rowid+1000000 WHERE id=NEW.conversation_id; END")
    }

    fun testRewrittenUserBlocksRollBackNewAndExistingConversationAndCanRetry() {
        for (newConversation in listOf(true, false)) failureAndRetry(newConversation,
            "AFTER INSERT ON messages WHEN NEW.role='assistant' BEGIN " +
                "UPDATE messages SET blocks='[{\"type\":\"text\",\"item\":\"rewritten\",\"text\":\"changed user text\"}]' " +
                "WHERE conversation_id=NEW.conversation_id AND run_id=NEW.run_id AND role='user'; END")
    }

    fun testRewrittenAssistantBlocksRollBackNewAndExistingConversationAndCanRetry() {
        for (newConversation in listOf(true, false)) failureAndRetry(newConversation,
            "AFTER INSERT ON messages WHEN NEW.role='assistant' BEGIN " +
                "UPDATE messages SET blocks='[{\"type\":\"text\",\"item\":\"rewritten\",\"text\":\"changed assistant text\"}]' " +
                "WHERE id=NEW.id; END")
    }

    fun testRewrittenConversationDraftRollsBackNewAndExistingConversationAndCanRetry() {
        for (newConversation in listOf(true, false)) failureAndRetry(newConversation,
            "AFTER INSERT ON messages WHEN NEW.role='assistant' BEGIN " +
                "UPDATE conversations SET draft='rewritten conversation draft' WHERE id=NEW.conversation_id; END")
    }

    fun testRewrittenComposerRollsBackNewAndExistingConversationAndCanRetry() {
        for (newConversation in listOf(true, false)) failureAndRetry(newConversation,
            "AFTER INSERT ON messages WHEN NEW.role='assistant' BEGIN " +
                "UPDATE composer SET draft='rewritten composer draft' WHERE id=1; END")
    }

    fun testLargeComposerDoesNotBreakSmallSendInExistingConversation() {
        val id = retainedConversation()
        val oldMessages = database.messages(id)
        // More than a CursorWindow, with multibyte code points crossing 64 KiB byte chunks.
        val composer = "草稿🙂\u0000".repeat(400_000) + "unchanged tail"
        database.draft(composer)
        val (user, assistant) = messages(id)
        val receipt = database.send(user, assistant)
        assertEquals("", receipt.draft)
        assertEquals(composer, database.draft())
        reopen()
        assertEquals(composer, database.draft())
        assertEquals(receipt, database.conversations().single { it.id == id })
        assertEquals(oldMessages, database.messages(id).dropLast(2))
        assertEquals(listOf(user, assistant), database.messages(id).takeLast(2).map { it.second })
    }

    fun testLargeComposerTamperingStillRollsBack() {
        val id = retainedConversation()
        database.draft("large 草稿🙂".repeat(300_000))
        val before = state()
        val (user, assistant) = messages(id)
        database.writableDatabase.execSQL(
            "CREATE TRIGGER reject_send AFTER INSERT ON messages WHEN NEW.role='assistant' BEGIN " +
                "UPDATE composer SET draft=draft || 'changed' WHERE id=1; END")
        assertTrue(rejected { database.send(user, assistant) } is IllegalStateException)
        assertRolledBack(before)
    }
}
