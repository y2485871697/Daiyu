package app.daiyu.android.storage

import android.content.Context
import app.daiyu.android.domain.*
import app.daiyu.android.testContext
import java.util.UUID

/** Recovery owns its own database: no live controller can hold this fixture's runtime lease. */
@Suppress("DEPRECATION")
class ImmediateConversationDatabaseTest : android.test.AndroidTestCase() {
    private lateinit var scoped: Context
    private lateinit var database: ConversationDatabase

    override fun setUp() {
        super.setUp()
        scoped = testContext(context, "immediate-conversation-${UUID.randomUUID()}-")
        database = ConversationDatabase(scoped)
    }

    override fun tearDown() {
        try {
            database.close()
            scoped.deleteDatabase("conversations.db")
        } finally { super.tearDown() }
    }

    private fun reopenAndRecover() {
        database.close()
        database = ConversationDatabase(scoped)
        database.recover()
    }

    private fun pair(id: ConversationId): Pair<Message, Message> {
        val run = RunId(UUID.randomUUID().toString())
        val user = Message(MessageId(UUID.randomUUID().toString()), id, run, "user",
            listOf(MessageBlock.Text("u", "首问")), RunStatus.COMPLETED)
        return user to Message(MessageId(UUID.randomUUID().toString()), id, run, "assistant",
            emptyList(), RunStatus.RUNNING)
    }

    fun testEmptyOwnersSurviveRecoveryAndFirstSendReusesTheirId() {
        database.draft("独立草稿保留")
        val first = database.create(database.selectedAssistantId())
        val second = database.create(database.selectedAssistantId())
        assertTrue(first.id != second.id)
        reopenAndRecover()
        assertEquals(setOf(first.id, second.id), database.conversations().map { it.id }.toSet())
        for (id in listOf(first.id, second.id)) assertTrue(database.messages(id).isEmpty())
        val (user, assistant) = pair(first.id)
        database.draft(first.id, "首问")
        val accepted = database.send(user, assistant, newConversation = false)
        assertEquals(first.id, accepted.id)
        assertEquals(2, database.conversations().size)
        assertEquals(listOf(user, assistant), database.messages(first.id).map { it.second })
        assertTrue(database.messages(second.id).isEmpty())
        assertEquals("独立草稿保留", database.draft())
    }

    fun testFailedFirstSendKeepsPreviouslyCreatedEmptyOwnerAcrossRecovery() {
        val created = database.create(database.selectedAssistantId())
        database.draft(created.id, "首问")
        val (user, assistant) = pair(created.id)
        database.writableDatabase.execSQL(
            "CREATE TEMP TRIGGER ignore_first_send BEFORE INSERT ON messages BEGIN SELECT RAISE(IGNORE); END")
        try {
            try {
                database.send(user, assistant, newConversation = false)
                fail("ignored message insert must reject the send")
            } catch (_: IllegalStateException) { }
        } finally { database.writableDatabase.execSQL("DROP TRIGGER ignore_first_send") }
        reopenAndRecover()
        assertEquals(created.id, database.conversations().single().id)
        assertEquals("首问", database.conversations().single().draft)
        assertTrue(database.messages(created.id).isEmpty())
        assertEquals(created.id, database.send(user, assistant, newConversation = false).id)
        assertEquals(1, database.conversations().size)
    }
}
