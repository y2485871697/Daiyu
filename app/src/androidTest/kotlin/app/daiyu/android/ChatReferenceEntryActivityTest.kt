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
    private class MetadataProvider : android.content.ContentProvider() {
        var columns = arrayOf(android.provider.OpenableColumns.DISPLAY_NAME)
        var values = arrayOf<Any?>("工作目录")
        var unavailable = false
        val queries = mutableListOf<Pair<Uri, List<String>>>()
        var returned: android.database.MatrixCursor? = null
        override fun onCreate() = true
        override fun getType(uri: Uri) = android.provider.DocumentsContract.Document.MIME_TYPE_DIR
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
            selectionArgs: Array<out String>?, sortOrder: String?): android.database.Cursor {
            queries.add(uri to projection.orEmpty().toList())
            check(uri.pathSegments.contains("document")) { "tree URI is not a document" }
            if (unavailable) throw IllegalArgumentException("unavailable metadata")
            return android.database.MatrixCursor(columns).apply { addRow(values); returned = this }
        }
        override fun insert(uri: Uri, values: android.content.ContentValues?): Uri? = throw AssertionError("no writes")
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = throw AssertionError("no writes")
        override fun update(uri: Uri, values: android.content.ContentValues?, selection: String?, selectionArgs: Array<out String>?) = throw AssertionError("no writes")
    }

    private fun metadataProvider(): Pair<MetadataProvider, android.test.mock.MockContentResolver> {
        val provider = MetadataProvider()
        provider.attachInfo(instrumentation.targetContext, android.content.pm.ProviderInfo().apply {
            authority = "chat-reference.fixture"; exported = false
        })
        return provider to android.test.mock.MockContentResolver().apply { addProvider("chat-reference.fixture", provider) }
    }

    fun testDirectoryMetadataQueriesRootDocumentNameOnlyWithoutSizeOrChildren() {
        val (provider, resolver) = metadataProvider()
        val tree = Uri.parse("content://chat-reference.fixture/tree/primary%3A%E5%B7%A5%E4%BD%9C")
        val description = app.daiyu.android.references.ChatReferenceMetadata.describe(resolver, tree, true)
        assertEquals("工作目录", description)
        assertEquals(1, provider.queries.size)
        assertEquals(android.provider.DocumentsContract.buildDocumentUriUsingTree(tree,
            android.provider.DocumentsContract.getTreeDocumentId(tree)), provider.queries.single().first)
        assertEquals(listOf(android.provider.OpenableColumns.DISPLAY_NAME), provider.queries.single().second)
        assertTrue(provider.returned!!.isClosed)
        assertFalse(description.contains("大小"))
        assertEquals("content://chat-reference.fixture/tree/primary%3A%E5%B7%A5%E4%BD%9C", tree.toString())
    }

    fun testDirectoryMissingOrUnavailableMetadataKeepsNameWithoutReportingSize() {
        val (provider, resolver) = metadataProvider()
        val tree = Uri.parse("content://chat-reference.fixture/tree/primary%3A%E4%B8%AD%E6%96%87%2B%E7%9B%AE%E5%BD%95")
        provider.columns = arrayOf("unrelated"); provider.values = arrayOf<Any?>(null)
        assertEquals("中文+目录", app.daiyu.android.references.ChatReferenceMetadata.describe(resolver, tree, true))
        provider.unavailable = true
        assertEquals("中文+目录", app.daiyu.android.references.ChatReferenceMetadata.describe(resolver, tree, true))
        assertEquals(2, provider.queries.size)
        assertTrue(provider.queries.all { it.second == listOf(android.provider.OpenableColumns.DISPLAY_NAME) })
    }

    fun testFileMetadataRetainsNameAndActualOrUnknownSizeWithReorderedColumns() {
        val (provider, resolver) = metadataProvider()
        val file = Uri.parse("content://chat-reference.fixture/document/log")
        provider.columns = arrayOf(android.provider.OpenableColumns.SIZE, android.provider.OpenableColumns.DISPLAY_NAME)
        provider.values = arrayOf<Any?>(42L, "日志.txt")
        assertEquals("日志.txt（42 字节）", app.daiyu.android.references.ChatReferenceMetadata.describe(resolver, file, false))
        provider.columns = arrayOf(android.provider.OpenableColumns.DISPLAY_NAME); provider.values = arrayOf<Any?>("日志.txt")
        assertEquals("日志.txt（大小未知）", app.daiyu.android.references.ChatReferenceMetadata.describe(resolver, file, false))
        assertEquals(file, provider.queries.first().first)
    }

    fun testOldDirectoryPlaceholderDisplaysDecodedNameWithoutChangingPersistedReference() {
        prepare()
        val raw = "$original\n目录引用（未读取）：content://reference-never-read.invalid/tree/primary%3A%E5%B7%A5%E4%BD%9C%E7%9B%AE%E5%BD%95\n" +
            "附件信息（按需读取）：名称及大小未知；用 list_files 按需列举，勿自动读取全部文件。"
        mainThread { retainedControls(activity).onTextChange(raw) }
        assertReferenceDraft(raw, original)
        ui.onNodeWithText("目录 · 工作目录").assertIsDisplayed()
        ui.onNodeWithText("名称及大小未知", substring = true).assertDoesNotExist()
        activity = recreateActivity(activity)
        assertReferenceDraft(raw, original)
        ui.onNodeWithText("目录 · 工作目录").assertIsDisplayed()
        assertEquals(0, fixture.opens.get())
    }

    fun testRealVideoAttachmentMenuLaunchesOnceAndCancellationKeepsDraft() {
        prepare()
        val launches = AtomicInteger()
        val picker = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action != Intent.ACTION_GET_CONTENT || intent.type != "video/*") return null
                assertTrue(intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false))
                launches.incrementAndGet()
                return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
            }
        }
        instrumentation.addMonitor(picker)
        try {
            repeat(2) { index ->
                ui.onNodeWithContentDescription("附件").performClick()
                node("chat-attachment:ATTACH_VIDEO").performClick()
                ui.waitUntil(5_000) { launches.get() == index + 1 }
                ui.waitForIdle()
                assertDraft(original)
                assertNull(invokeHost(activity, "getVideoPickEntry"))
            }
            assertEquals(2, launches.get())
            assertEquals(0, fixture.opens.get())
        } finally { instrumentation.removeMonitor(picker) }
    }

    fun testRealVideoMenuKeepsRequestUntilSameOwnerRecoveryFinishes() {
        prepare()
        val host = fixture.controller.videoHost
        @Suppress("UNCHECKED_CAST")
        val recovering = VideoChatHost::class.java.getDeclaredField("recoveringOwners").apply { isAccessible = true }
            .get(host) as MutableSet<app.daiyu.android.media.video.VideoAttachmentOwner>
        val publish = VideoChatHost::class.java.getDeclaredMethod("publish").apply { isAccessible = true }
        val launches = AtomicInteger()
        val picker = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action != Intent.ACTION_GET_CONTENT || intent.type != "video/*") return null
                launches.incrementAndGet()
                return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
            }
        }
        instrumentation.addMonitor(picker)
        try {
            mainThread { recovering.add(host.state.owner); publish.invoke(host) }
            ui.onNodeWithContentDescription("附件").performClick()
            node("chat-attachment:ATTACH_VIDEO").performClick()
            ui.waitForIdle()
            assertEquals(0, launches.get())
            assertNotNull(invokeHost(activity, "getVideoPickEntry"))
            mainThread { recovering.remove(host.state.owner); publish.invoke(host) }
            ui.waitUntil(5_000) { launches.get() == 1 }
            ui.waitForIdle()
            assertDraft(original)
            assertEquals(0, fixture.opens.get())
        } finally {
            instrumentation.removeMonitor(picker)
            mainThread { recovering.clear(); publish.invoke(host) }
        }
    }

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

    fun testFileReferenceSingleClickSendsTheExactUnexpandedReference() {
        prepare()
        val reference = "文件引用（未读取）：content://reference-never-read.invalid/document/send-once"
        val raw = "$original\n$reference"
        mainThread { retainedControls(activity).onTextChange(raw) }
        assertReferenceDraft(raw, original)
        assertEquals(0, fixture.opens.get())
        node("chat-send").assertIsEnabled().performClick()
        node("image-host-cancel").assertDoesNotExist()
        node("image_attachments_submitting").assertDoesNotExist()
        awaitControl("file reference accepted once") { fixture.opens.get() == 1 && fixture.controller.active == null }
        val conversation = fixture.database.conversations().single()
        val users = fixture.database.messages(conversation.id).map { it.second }
            .filter { it.role == app.daiyu.android.domain.Message.USER_ROLE }
        assertEquals(1, users.size)
        assertEquals(listOf(raw), users.single().blocks.filterIsInstance<app.daiyu.android.domain.MessageBlock.Text>().map { it.text })
        assertEquals(1, fixture.opens.get())
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
        val slot = MainActivity::class.java.getDeclaredField("chatReferencePick").apply { isAccessible = true }
        val request = slot.type.getDeclaredConstructor(java.lang.Boolean.TYPE,
            ChatController.TextSendOwner::class.java, String::class.java, Any::class.java)
            .apply { isAccessible = true }.newInstance(tree, owner, original, null)
        slot.set(activity, request)
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
