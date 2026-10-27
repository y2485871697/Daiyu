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

    fun testActualEditCallbackFillsComposerAndCancelRestoresDraftAndHistory() {
        val (conversation, user) = seed()
        mainThread { activity.select(conversation) }; readySnapshot()
        mainThread {
            retainedControls(activity).onTextChange("saved composer draft")
            retainedControls(activity).onSelectionChange(2, 5)
        }
        val before = fixture.database.messages(conversation.id)
        mainThread { retainedControls(activity).onEditUser(user.id.value) }
        awaitControl("inline editor") { !fixture.controller.dataBusy && call("getInlineMessageEditor") != null }
        assertDraft("original user")
        assertEquals("saved composer draft", field("draft"))
        assertEquals("saved composer draft", fixture.database.conversations().single { it.id == conversation.id }.draft)
        mainThread {
            val state = field("chatState") as app.daiyu.android.ui.screens.chat.conversation.ChatScreenState
            assertEquals("original user", state.composer.text)
            assertTrue(state.composer.enabled)
            retainedControls(activity).onTextChange("cancelled edit")
            retainedControls(activity).onSelectionChange(1, 3)
            retainedControls(activity).onCancelMessageEdit()
            val restored = field("chatState") as app.daiyu.android.ui.screens.chat.conversation.ChatScreenState
            assertEquals("saved composer draft", restored.composer.text)
            assertEquals(2, restored.composer.selectionStart)
            assertEquals(5, restored.composer.selectionEnd)
        }
        assertNull(call("getInlineMessageEditor")); assertDraft("saved composer draft")
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
        mainThread { old.onTextChange("stale"); old.onCancelMessageEdit(); old.onSend("original user") }
        assertSame(current, call("getInlineMessageEditor"))
        assertDraft("original user")
        assertEquals("", field("draft"))
        assertEquals("original user", (field("chatState") as app.daiyu.android.ui.screens.chat.conversation.ChatScreenState).composer.text)
        assertEquals(0, fixture.opens.get())
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

    fun testComposerReferenceEditsStayTemporaryAndSaveUsesMessageRevision() {
        val (conversation, user) = seed()
        mainThread { activity.select(conversation) }; readySnapshot()
        mainThread { retainedControls(activity).onTextChange("unrelated draft") }
        mainThread { retainedControls(activity).onEditUser(user.id.value) }
        awaitControl("composer editing") { !fixture.controller.dataBusy && call("getInlineMessageEditor") != null }
        mainThread {
            assertTrue(retainedControls(activity).onProjectedDraftEdit!!.invoke("original user", "edited @file.txt", 6, 6))
        }
        assertDraft("edited @file.txt")
        assertEquals("unrelated draft", field("draft"))
        assertEquals("unrelated draft", fixture.database.conversations().single { it.id == conversation.id }.draft)
        assertEquals("edited @file.txt", (field("chatState") as app.daiyu.android.ui.screens.chat.conversation.ChatScreenState).composer.text)
        fixture.session("edited answer", held = false)
        mainThread { retainedControls(activity).onSend("edited @file.txt") }
        awaitControl("edit save accepted") { !fixture.controller.dataBusy && call("getInlineMessageEditor") == null && fixture.opens.get() == 1 }
        val rewritten = fixture.database.messages(conversation.id).first().second
        assertEquals(user.id, rewritten.id)
        assertEquals("edited @file.txt", rewritten.blocks.filterIsInstance<MessageBlock.Text>().single().text)
        assertEquals("unrelated draft", fixture.database.conversations().single { it.id == conversation.id }.draft)
        assertEquals(1, fixture.opens.get())
    }

    fun testOriginalImagesReturnToComposerWithoutChangingHistoryOrDraft() {
        val (conversation, user) = seed()
        val images = (0..1).map { index -> MessageBlock.Image(MessageImageReference(
            MessageImageOwner(conversation.id.value, user.id.value), UUID.randomUUID().toString(),
            "image/png", 64, (if (index == 0) "a" else "b").repeat(64), 2, 2)) }
        fixture.database.save(user.copy(blocks = user.blocks + images))
        mainThread { activity.select(conversation) }; readySnapshot()
        val before = fixture.database.messages(conversation.id)
        mainThread { retainedControls(activity).onTextChange("kept draft"); retainedControls(activity).onEditUser(user.id.value) }
        awaitControl("image edit") { !fixture.controller.dataBusy && call("getInlineMessageEditor") != null }
        val editor = checkNotNull(call("getInlineMessageEditor"))
        val restored = editor.javaClass.getDeclaredMethod("getImages").apply { isAccessible = true }.invoke(editor) as List<*>
        assertEquals(images.map { it.reference }, restored.map {
            (it as app.daiyu.android.ui.screens.chat.conversation.MessageContentUi.Image).image.reference
        })
        mainThread { retainedControls(activity).onCancelMessageEdit() }
        assertDraft("kept draft")
        assertEquals(before, fixture.database.messages(conversation.id))
        assertEquals(0, fixture.opens.get())
    }

    fun testEditingMatchingShortcutTextCannotWriteTheOrdinaryDraft() {
        val (conversation, user) = seed()
        mainThread { activity.select(conversation) }; readySnapshot()
        mainThread { retainedControls(activity).onTextChange("@"); retainedControls(activity).onEditUser(user.id.value) }
        awaitControl("shortcut edit") { !fixture.controller.dataBusy && call("getInlineMessageEditor") != null }
        mainThread {
            retainedControls(activity).onTextChange("@")
            val trigger = checkNotNull(app.daiyu.android.ui.screens.chat.conversation.ComposerShortcut.at("@", 1))
            retainedControls(activity).onReferenceShortcut(trigger, "@")
            assertEquals(false, call("composerShortcutAvailable"))
            assertNull(field("composerShortcutPick"))
            retainedControls(activity).onCancelMessageEdit()
        }
        assertDraft("@")
        assertEquals("@", fixture.database.conversations().single { it.id == conversation.id }.draft)
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
