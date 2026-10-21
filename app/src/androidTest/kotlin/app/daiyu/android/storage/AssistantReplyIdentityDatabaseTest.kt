package app.daiyu.android.storage

import android.test.InstrumentationTestCase
import app.daiyu.android.assistants.AssistantReplyIdentity
import app.daiyu.android.assistants.AssistantProfile
import app.daiyu.android.assistants.SqliteAssistantStore
import app.daiyu.android.domain.*
import app.daiyu.android.testContext
import java.util.UUID

@Suppress("DEPRECATION")
class AssistantReplyIdentityDatabaseTest : InstrumentationTestCase() {
    fun testV23UpgradeDoesNotInventReplyAuthors() {
        val context = testContext(instrumentation.targetContext, "reply-migration-${UUID.randomUUID()}-")
        var database = ConversationDatabase(context)
        try {
            database.writableDatabase.execSQL("DROP TABLE assistant_reply_identities")
            database.writableDatabase.version = 23
            database.close()
            database = ConversationDatabase(context)
            assertEquals(24, database.readableDatabase.version)
            database.readableDatabase.rawQuery("SELECT count(*) FROM assistant_reply_identities", null).use {
                assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
            }
        } finally { database.close(); context.deleteDatabase("conversations.db") }
    }

    fun testBranchRetainsFrozenIdentityAndLeavesLegacyRepliesUnknown() {
        val base = testContext(instrumentation.targetContext, "reply-branch-${UUID.randomUUID()}-")
        val files = java.io.File(base.cacheDir, "reply-branch-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val context = object : android.content.ContextWrapper(base) { override fun getFilesDir() = files }
        val database = ConversationDatabase(context)
        try {
            SqliteAssistantStore(database).requireReady()
            val conversation = database.create("default")
            val identity = AssistantReplyIdentity("default", "发送时的助手")
            val replies = (0..1).map { index ->
                val user = Message(MessageId("user-$index"), conversation.id, RunId("run-$index"), Message.USER_ROLE,
                    listOf(MessageBlock.Text("u", "原始问题$index")), RunStatus.COMPLETED)
                val reply = user.copy(id = MessageId("reply-$index"), role = Message.ASSISTANT_ROLE, status = RunStatus.RUNNING)
                database.send(user, reply, replyIdentity = if (index == 0) identity else null)
                reply.copy(status = RunStatus.COMPLETED).also(database::save)
            }
            val original = database.messages(conversation.id)
            val service = app.daiyu.android.context.ContextService(context, database)
            for (production in listOf(false, true)) {
                fun branch() = if (production) service.branchWithStatic(conversation.id, replies.last().id,
                    videos = null, generation = null, authorize = {}, cancelled = { false },
                    copyAssets = { _, mapped -> mapped }).conversationId
                else service.branch(conversation.id, replies.last().id)
                val copy = branch()
                val copied = database.messages(copy).map { it.second }.filter { it.role == Message.ASSISTANT_ROLE }
                assertEquals(2, copied.size)
                assertEquals(identity, database.replyIdentity(copied.first()))
                assertNull(database.replyIdentity(copied.last()))
                assertEquals(original, database.messages(conversation.id))
                assertTrue(copied.none { candidate -> original.any { it.second.id == candidate.id } })
                database.writableDatabase.execSQL("""CREATE TRIGGER reject_branch_reply BEFORE INSERT ON assistant_reply_identities
                    WHEN NEW.conversation_id != '${conversation.id.value}' BEGIN SELECT RAISE(ABORT,'identity'); END""")
                val before = database.conversations().map { it.id }.toSet()
                try { branch(); fail("must roll back branch") }
                catch (failure: Exception) {
                    if (production) assertTrue(failure is app.daiyu.android.context.BranchRolledBack)
                    else assertTrue(failure is android.database.sqlite.SQLiteException)
                }
                assertEquals(before, database.conversations().map { it.id }.toSet())
                assertEquals(original, database.messages(conversation.id))
                database.writableDatabase.execSQL("DROP TRIGGER reject_branch_reply")
            }
        } finally { database.close(); context.deleteDatabase("conversations.db"); files.deleteRecursively() }
    }

    fun testIdentityIsAtomicImmutableAndNotInferredForLegacyMessages() {
        val context = testContext(instrumentation.targetContext, "reply-identity-${UUID.randomUUID()}-")
        val database = ConversationDatabase(context)
        try {
            SqliteAssistantStore(database).apply { requireReady(); save(AssistantProfile("a", "原名", "")) }
            val conversation = database.create("a")
            fun pair(): Pair<Message, Message> {
                val run = RunId(UUID.randomUUID().toString())
                val user = Message(MessageId(UUID.randomUUID().toString()), conversation.id, run, Message.USER_ROLE,
                    listOf(MessageBlock.Text("u", "text")), RunStatus.COMPLETED)
                return user to user.copy(id = MessageId(UUID.randomUUID().toString()), role = Message.ASSISTANT_ROLE,
                    blocks = emptyList(), status = RunStatus.RUNNING)
            }
            val (user, assistant) = pair()
            database.draft(conversation.id, "keep")
            val identity = AssistantReplyIdentity("a", "原名")
            database.writableDatabase.execSQL("""CREATE TRIGGER reject_reply BEFORE INSERT ON assistant_reply_identities
                BEGIN SELECT RAISE(ABORT,'identity'); END""")
            try { database.send(user, assistant, replyIdentity = identity); fail("must roll back") }
            catch (_: android.database.sqlite.SQLiteException) { }
            assertTrue(database.messages(conversation.id).isEmpty())
            assertEquals("keep", database.conversations().single().draft)
            database.writableDatabase.execSQL("DROP TRIGGER reject_reply")
            database.send(user, assistant, replyIdentity = identity)
            database.save(assistant.copy(status = RunStatus.COMPLETED))
            SqliteAssistantStore(database).save(AssistantProfile("a", "新名", ""))
            assertEquals(identity, database.replyIdentity(assistant))
            val backup = database.exportConversationBackup(conversation.id)
            val importedContext = testContext(instrumentation.targetContext, "reply-import-${UUID.randomUUID()}-")
            val imported = ConversationDatabase(importedContext)
            try {
                imported.importBackup(backup)
                assertEquals(identity, imported.replyIdentity(assistant))
                assertEquals(backup, imported.exportConversationBackup(conversation.id))
            } finally { imported.close(); importedContext.deleteDatabase("conversations.db") }
            val (oldUser, oldAssistant) = pair()
            database.send(oldUser, oldAssistant)
            assertNull(database.replyIdentity(oldAssistant))
            database.delete(conversation.id)
            database.readableDatabase.rawQuery("SELECT count(*) FROM assistant_reply_identities", null).use {
                assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
            }
        } finally { database.close(); context.deleteDatabase("conversations.db") }
    }
}
