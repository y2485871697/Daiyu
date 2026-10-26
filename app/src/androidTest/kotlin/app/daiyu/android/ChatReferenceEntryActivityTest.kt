package app.daiyu.android

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.*
import app.daiyu.android.platform.PlatformSafGrants
import app.daiyu.android.storage.ConversationDatabase
import app.daiyu.android.ui.screens.chat.conversation.ChatMenuAction
import java.util.concurrent.atomic.AtomicInteger

class ChatReferenceEntryActivityTest : WiredActivityTestCase() {
    private val original = "reference stays unsent"
    private val readFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
    private fun prepare() {
        val conversation = fixture.database.create("default")
        fixture.database.draft(conversation.id, original)
        mainThread { activity.select(conversation) }
        awaitControl("reference editor ready") { !fixture.controller.dataBusy }
        assertDraft(original)
    }
    private fun attach(tree: Boolean) {
        ui.onNodeWithContentDescription("附件").performClick()
        node("chat-attachment:${if (tree) "ATTACH_FOLDER" else "ATTACH_FILE"}").performClick()
    }
    private fun monitor(result: () -> Instrumentation.ActivityResult, launches: AtomicInteger) =
        object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action !in setOf(Intent.ACTION_OPEN_DOCUMENT, Intent.ACTION_OPEN_DOCUMENT_TREE)) return null
                assertEquals(readFlags, intent.flags and readFlags)
                assertEquals(0, intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                launches.incrementAndGet()
                return result()
            }
        }
    private fun valid(tree: Boolean) = Intent().setData(Uri.parse(
        if (tree) "content://reference-never-read.invalid/tree/selected" else "content://reference-never-read.invalid/document/selected"
    )).addFlags(readFlags)
    private fun assertReferenceDraft(raw: String, body: String) {
        assertDraft(body)
        node("chat-reference:0").assertIsDisplayed()
        ui.onNodeWithText("content://", substring = true, useUnmergedTree = true).assertDoesNotExist()
        assertEquals(raw, fixture.database.conversations().single().draft)
    }

    fun testReferenceBodyEditingAndRemovalPersistWithoutResurrectingOldReferences() {
        prepare()
        val first = "文件引用（未读取）：content://reference-never-read.invalid/document/one"
        val second = "目录引用（未读取）：content://reference-never-read.invalid/tree/two"
        val raw = "$original\n$first\n$second"
        mainThread { retainedControls(activity).onTextChange(raw) }
        assertReferenceDraft(raw, original)
        val oldEdit = checkNotNull(retainedControls(activity).onProjectedDraftEdit)
        node("chat-reference-remove:0").performClick()
        assertReferenceDraft("$original\n$second", original)
        mainThread { assertFalse(oldEdit(raw, "$original\n$first", 0, 0)) }
        assertReferenceDraft("$original\n$second", original)
        node("chat-draft").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetText) {
            assertTrue(it(androidx.compose.ui.text.AnnotatedString("修改后的正文 😀")))
        }
        assertReferenceDraft("修改后的正文 😀\n$second", "修改后的正文 😀")
        activity = recreateActivity(activity)
        assertReferenceDraft("修改后的正文 😀\n$second", "修改后的正文 😀")
        node("chat-reference-remove:0").performClick()
        assertDraft("修改后的正文 😀")
        node("chat-reference-chips").assertDoesNotExist()
        assertEquals("修改后的正文 😀", fixture.database.conversations().single().draft)
        assertEquals(0, fixture.opens.get())
    }

    private fun picked(tree: Boolean) {
        prepare()
        val conversation = fixture.database.conversations().single()
        val messages = fixture.database.messages(conversation.id)
        val settings = fixture.settings.load()
        val grants = PlatformSafGrants(instrumentation.targetContext).grants()
        val persisted = instrumentation.targetContext.contentResolver.persistedUriPermissions.map { it.uri }
        val data = valid(tree)
        val launches = AtomicInteger()
        val picker = monitor({ Instrumentation.ActivityResult(Activity.RESULT_OK, data) }, launches)
        instrumentation.addMonitor(picker)
        try {
            attach(tree)
            // This deliberately nonexistent provider cannot persist READ; do not create a dead attachment.
            val expected = original
            awaitControl("reference grant rejection settled") {
                MainActivity::class.java.getDeclaredField("chatReferencePick").apply { isAccessible = true }.get(activity) == null
            }
            assertDraft(expected)
            assertSelection(original.length, original.length)
            assertEquals(1, launches.get())
            val reopened = ConversationDatabase(fixture.application)
            try {
                assertEquals(expected, reopened.conversations().single { it.id == conversation.id }.draft)
                assertEquals(messages, reopened.messages(conversation.id))
            } finally {
                reopened.close()
            }
            activity = recreateActivity(activity)
            awaitUniqueTag("chat-draft")
            assertDraft(expected)
            assertEquals(settings, fixture.settings.load())
            assertEquals(messages, fixture.database.messages(conversation.id))
            assertEquals(grants, PlatformSafGrants(instrumentation.targetContext).grants())
            assertEquals(persisted, instrumentation.targetContext.contentResolver.persistedUriPermissions.map { it.uri })
            assertEquals(0, fixture.opens.get()); assertNull(fixture.controller.active)
        } finally { instrumentation.removeMonitor(picker) }
    }
    fun testFileMenuRejectsUriWithoutPersistableReadGrant() = picked(false)
    fun testDirectoryMenuRejectsUriWithoutPersistableReadGrant() = picked(true)

    fun testCancelledMalformedAndWrongKindResultsPreserveDraftAndGrants() {
        prepare()
        val before = fixture.database.exportBackup()
        val grants = PlatformSafGrants(instrumentation.targetContext).grants()
        val launches = AtomicInteger()
        val cases = listOf(
            false to Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null),
            false to Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(Uri.parse("file:///never-read")).addFlags(readFlags)),
            false to Instrumentation.ActivityResult(Activity.RESULT_OK, valid(true)),
            true to Instrumentation.ActivityResult(Activity.RESULT_OK, valid(false)),
            true to Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(Uri.parse("content://reference-never-read.invalid/tree/root/document/child")).addFlags(readFlags)),
            false to Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(valid(false).data)),
        )
        for ((tree, result) in cases) {
            val picker = monitor({ result }, launches)
            instrumentation.addMonitor(picker)
            try {
                val count = launches.get()
                attach(tree)
                ui.waitUntil(5_000) { launches.get() == count + 1 }
                ui.waitForIdle()
                assertDraft(original)
                assertEquals(before, fixture.database.exportBackup())
            } finally { instrumentation.removeMonitor(picker) }
        }
        assertEquals(grants, PlatformSafGrants(instrumentation.targetContext).grants())
        assertEquals(0, fixture.opens.get()); assertNull(fixture.controller.active)
    }

    fun testOldMenuCannotLaunchAfterPauseDraftChangeOrSameConversationReentry() {
        prepare()
        val launches = AtomicInteger()
        val picker = monitor({ Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null) }, launches)
        instrumentation.addMonitor(picker)
        try {
            val paused = retainedControls(activity).onMenuAction
            realPauseResume(activity)
            mainThread { paused(ChatMenuAction.ATTACH_FILE) }
            val edited = retainedControls(activity).onMenuAction
            mainThread {
                retainedControls(activity).onTextChange("changed")
                retainedControls(activity).onTextChange(original)
                edited(ChatMenuAction.ATTACH_FOLDER)
            }
            val reentered = retainedControls(activity).onMenuAction
            val conversation = fixture.database.conversations().single()
            mainThread { activity.select(conversation); reentered(ChatMenuAction.ATTACH_FILE) }
            assertEquals(0, launches.get()); assertDraft(original)
        } finally { instrumentation.removeMonitor(picker) }
    }

    // Seed only the host-owned pending slot to exercise lifecycle/result ordering without opening DocumentsUI.
    private fun pending(tree: Boolean): Any {
        val owner = ChatController.TextSendOwner(fixture.database.conversations().single().id,
            MainActivity::class.java.getDeclaredField("editorGeneration").apply { isAccessible = true }.getLong(activity),
            MainActivity::class.java.getDeclaredField("draftRevision").apply { isAccessible = true }.getLong(activity))
        val type = MainActivity::class.java.declaredClasses.single { it.simpleName == "ChatReferencePick" }
        val request = type.declaredConstructors.single().apply { isAccessible = true }.newInstance(tree, owner, original)
        MainActivity::class.java.getDeclaredField("chatReferencePick").apply { isAccessible = true }.set(activity, request)
        return request
    }
    private fun returned(request: Any, tree: Boolean) {
        request.javaClass.getDeclaredField("returned").apply { isAccessible = true }.setBoolean(request, true)
        request.javaClass.getDeclaredField("uri").apply { isAccessible = true }.set(request, valid(tree).data)
        MainActivity::class.java.getDeclaredMethod("settleChatReference").apply { isAccessible = true }.invoke(activity)
    }
    fun testResultBeforeResumedWaitsAndRejectsMissingGrantReceipt() {
        prepare()
        lateinit var request: Any
        mainThread { request = pending(false) }
        realPauseResume(activity, atPause = { returned(request, false) })
        awaitControl("missing grant receipt settled") {
            MainActivity::class.java.getDeclaredField("chatReferencePick").apply { isAccessible = true }.get(activity) == null
        }
        assertDraft(original) // Seeded result has no original read-grant receipt, so it cannot attach.
        assertEquals(0, fixture.opens.get()); assertNull(fixture.controller.active)
    }
    fun testLaterPauseRevokesPendingResultAndUnreturnedSlotBlocksNewPicker() {
        prepare()
        lateinit var request: Any
        mainThread { request = pending(true) }
        realPauseResume(activity)
        realPauseResume(activity)
        val launches = AtomicInteger()
        val picker = monitor({ Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null) }, launches)
        instrumentation.addMonitor(picker)
        try {
            mainThread { retainedControls(activity).onMenuAction(ChatMenuAction.ATTACH_FILE) }
            assertEquals(0, launches.get())
            mainThread { returned(request, true) }
            assertDraft(original)
            mainThread { retainedControls(activity).onMenuAction(ChatMenuAction.ATTACH_FILE) }
            ui.waitUntil(5_000) { launches.get() == 1 }
            assertDraft(original)
        } finally { instrumentation.removeMonitor(picker) }
    }
    fun testDraftRevisionAndNavigationPermanentlyRevokePendingResults() {
        prepare()
        lateinit var request: Any
        mainThread {
            request = pending(false)
            retainedControls(activity).onTextChange("changed")
            retainedControls(activity).onTextChange(original)
            returned(request, false)
        }
        assertDraft(original)
        mainThread {
            request = pending(true)
            retainedControls(activity).onMenuAction(ChatMenuAction.SETTINGS)
            returned(request, true)
        }
        awaitUniqueTag("page:设置"); backToChat(activity)
        assertDraft(original)
        assertEquals(0, fixture.opens.get()); assertNull(fixture.controller.active)
    }
}
