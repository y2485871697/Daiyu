package app.daiyu.android

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.*
import app.daiyu.android.attachments.OpenedAttachmentInbox
import app.daiyu.android.attachments.OpenedFileStore

/** Host handoff to real draft persistence. External provider/chooser execution is tested separately. */
class OpenedAttachmentEntryActivityTest : WiredActivityTestCase() {
    private fun document(id: String = "00000000-0000-0000-0000-000000000001") = OpenedFileStore.Document(
        Uri.parse("content://${instrumentation.targetContext.packageName}.opened.attachments/$id"), "中文附件.txt", "text/plain", 42)
    private fun handoff(conversation: String?, documents: List<OpenedFileStore.Document> = listOf(document())) {
        mainThread {
            assertTrue(OpenedAttachmentInbox.offer(OpenedAttachmentInbox.Batch(conversation, documents)))
            instrumentation.callActivityOnNewIntent(activity, Intent(activity, MainActivity::class.java).setAction("app.daiyu.android.OPEN_ATTACHMENTS"))
        }
    }
    override fun tearDown() {
        mainThread { OpenedAttachmentInbox.take() }
        super.tearDown()
    }
    fun testCurrentConversationKeepsBodyAndAddsUnsentAttachmentOnlyOnce() {
        val conversation = fixture.database.create("default")
        fixture.database.draft(conversation.id, "原有正文")
        mainThread { activity.select(conversation) }
        handoff(conversation.id.value)
        awaitControl("external attachment persisted") { fixture.database.conversations().single().draft.contains(document().uri.toString()) }
        node("chat-draft").assertEditableText("原有正文")
        node("chat-reference:0").assertIsDisplayed()
        assertEquals("原有正文\n${document().reference()}", fixture.database.conversations().single().draft)
        mainThread { instrumentation.callActivityOnNewIntent(activity, Intent(activity, MainActivity::class.java).setAction("app.daiyu.android.OPEN_ATTACHMENTS")) }
        assertEquals("原有正文\n${document().reference()}", fixture.database.conversations().single().draft)
        assertEquals(0, fixture.opens.get()); assertNull(fixture.controller.active)
        assertTrue(fixture.database.messages(conversation.id).isEmpty())
    }
    fun testStandaloneComposerDoesNotCreateConversationOrAutoSend() {
        mainThread { activity.select(null); retainedControls(activity).onTextChange("独立草稿") }
        handoff(null)
        awaitControl("standalone attachment persisted") { fixture.database.draft().contains(document().uri.toString()) }
        assertEquals("独立草稿\n${document().reference()}", fixture.database.draft())
        assertTrue(fixture.database.conversations().isEmpty()); assertEquals(0, fixture.opens.get())
        activity = recreateActivity(activity)
        node("chat-reference:0").assertIsDisplayed()
        assertEquals("独立草稿\n${document().reference()}", fixture.database.draft())
    }
    fun testStaleConversationHandoffCannotAttachToAnotherEditor() {
        val first = fixture.database.create("default"); val second = fixture.database.create("default")
        fixture.database.draft(second.id, "新会话保留")
        mainThread { activity.select(second) }
        handoff(first.id.value)
        assertDraft("新会话保留")
        assertEquals("新会话保留", fixture.database.conversations().first { it.id == second.id }.draft)
        assertFalse(OpenedAttachmentInbox.hasPending()); assertEquals(0, fixture.opens.get())
    }
    fun testMultipleDocumentsUseOriginalReferenceChipsAndJSONIsNotImportedAsBackup() {
        mainThread { activity.select(null) }
        val json = document("00000000-0000-0000-0000-000000000002").copy(name = "会话.json", mime = "application/json")
        handoff(null, listOf(document(), json))
        awaitControl("two files persisted") { fixture.database.draft().contains(json.uri.toString()) }
        node("chat-reference:0").assertIsDisplayed(); node("chat-reference:1").assertIsDisplayed()
        assertTrue(fixture.database.conversations().isEmpty()); assertEquals(0, fixture.opens.get())
        assertEquals(0L, fixture.controller.dataRevision)
    }
}
