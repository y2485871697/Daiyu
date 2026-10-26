package app.daiyu.android

import app.daiyu.android.domain.*
import java.util.UUID

/** Real Activity callbacks, Controller worker and SQLite; no provider or external tool requests. */
class MessageActionsActivityTest : WiredActivityTestCase() {
    private fun field(name: String): Any? = MainActivity::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.get(activity)
    private fun call(name: String) = MainActivity::class.java.declaredMethods
        .single { it.name == name && it.parameterCount == 0 }.apply { isAccessible = true }.invoke(activity)
    private fun seed(): Pair<Conversation, Message> {
        val conversation = fixture.database.create("default")
        val suffix = UUID.randomUUID().toString()
        val user = Message(MessageId("user-$suffix"), conversation.id, RunId("run-$suffix"),
            Message.USER_ROLE, listOf(MessageBlock.Text("body", "original user")), RunStatus.COMPLETED)
        val assistant = user.copy(id = MessageId("assistant-$suffix"), role = Message.ASSISTANT_ROLE,
            blocks = listOf(MessageBlock.Text("answer", "original answer")), status = RunStatus.RUNNING)
        fixture.database.send(user, assistant)
        fixture.database.save(assistant.copy(status = RunStatus.COMPLETED))
        return conversation to user
    }
    private fun readySnapshot() {
        awaitControl("message snapshot complete") {
            !fixture.controller.dataBusy && field("messageActionSnapshot") != null
        }
    }

    fun testActualEditCallbackOpensInlineEditorAndCancelKeepsDraftAndHistory() {
        val (conversation, user) = seed()
        mainThread { activity.select(conversation) }; readySnapshot()
        val before = fixture.database.messages(conversation.id)
        mainThread { retainedControls(activity).onEditUser(user.id.value) }
        awaitControl("inline editor") { !fixture.controller.dataBusy && call("getInlineMessageEditor") != null }
        assertDraft("")
        mainThread { retainedControls(activity).onMessageEditText("cancelled edit"); retainedControls(activity).onCancelMessageEdit() }
        assertNull(call("getInlineMessageEditor")); assertDraft("")
        assertEquals(before, fixture.database.messages(conversation.id))
        assertEquals(0, fixture.opens.get())
    }

    fun testOldInlineEditorCallbacksCannotOverwriteOrCancelANewVisit() {
        val (conversation, user) = seed()
        mainThread { activity.select(conversation) }; readySnapshot()
        mainThread { retainedControls(activity).onEditUser(user.id.value) }
        awaitControl("first inline edit") { !fixture.controller.dataBusy && call("getInlineMessageEditor") != null }
        lateinit var old: app.daiyu.android.ui.screens.chat.conversation.ChatScreenActions
        mainThread { old = retainedControls(activity); old.onCancelMessageEdit() }
        mainThread { retainedControls(activity).onEditUser(user.id.value) }
        awaitControl("second inline edit") { !fixture.controller.dataBusy && call("getInlineMessageEditor") != null }
        val current = call("getInlineMessageEditor")
        mainThread { old.onMessageEditText("stale"); old.onCancelMessageEdit(); old.onSaveMessageEdit() }
        assertSame(current, call("getInlineMessageEditor"))
        assertDraft(""); assertEquals(0, fixture.opens.get())
    }

    fun testOldCallbackAfterNewEditorCannotBranchOrMutateDraft() {
        val (conversation, user) = seed()
        mainThread { activity.select(conversation) }; readySnapshot()
        lateinit var old: app.daiyu.android.ui.screens.chat.conversation.ChatScreenActions
        mainThread { old = retainedControls(activity); activity.select(null) }
        mainThread { old.onBranch(user.id.value); old.onEditUser(user.id.value) }
        assertEquals(1, fixture.database.conversations().size)
        assertDraft("")
        assertEquals(0, fixture.opens.get())
    }

    fun testFailedSnapshotRefreshConsumesKeyWithoutAutomaticRetry() {
        val (conversation, _) = seed()
        mainThread { activity.select(conversation) }; readySnapshot()
        val raw = fixture.database.writableDatabase
        val encoded = raw.rawQuery("SELECT blocks FROM messages WHERE conversation_id=? AND role='user'", arrayOf(conversation.id.value))
            .use { assertTrue(it.moveToFirst()); it.getString(0) }
        raw.execSQL("UPDATE messages SET blocks='invalid-json' WHERE conversation_id=? AND role='user'", arrayOf(conversation.id.value))
        mainThread { call("invalidateMessageActionEditor"); call("prepareMessageActionSnapshot") }
        awaitControl("failed snapshot drained") { !fixture.controller.dataBusy && field("messageActionLoading") == null }
        raw.execSQL("UPDATE messages SET blocks=? WHERE conversation_id=? AND role='user'", arrayOf(encoded, conversation.id.value))
        lateinit var attempted: Any
        mainThread {
            assertNull(field("messageActionSnapshot"))
            attempted = checkNotNull(field("messageActionAttempted"))
            repeat(4) { call("refresh") }
            assertSame(attempted, field("messageActionAttempted"))
            assertFalse(fixture.controller.dataBusy)
        }
        assertEquals(0, fixture.opens.get())
    }
}
