package app.daiyu.android

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.ClipData
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.test.mock.MockContentResolver
import androidx.compose.ui.test.*
import app.daiyu.android.domain.*
import app.daiyu.android.media.ImageAttachmentOwner
import app.daiyu.android.model.images.FrozenMessageImagePlan
import app.daiyu.android.storage.ConversationDatabase
import app.daiyu.android.storage.ModelSettings
import app.daiyu.android.storage.ModelSettingsStore
import app.daiyu.android.ui.screens.chat.conversation.ChatMenuAction
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Real MainActivity -> ActivityResultRegistry -> importer -> controller -> history loader.
 * The system picker alone is substituted by an Instrumentation ActivityResult, not the callback.
 */
@Suppress("DEPRECATION")
class ImageChatHostActivityTest : ComposeActivityTestCase() {
    private class Documents(private val file: File) : ContentProvider() {
        val opens = AtomicInteger()
        var entered: CountDownLatch? = null
        var release: CountDownLatch? = null
        override fun onCreate() = true
        override fun getType(uri: Uri) = "image/png"
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            check(Looper.myLooper() != Looper.getMainLooper()) { "picker IO on main" }
            opens.incrementAndGet(); entered?.countDown()
            check(release?.await(10, TimeUnit.SECONDS) != false)
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = error("unused")
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = error("unused")
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = error("unused")
    }
    private class FixtureApplication(base: Context, private val resolver: MockContentResolver) : Application() {
        init { attachBaseContext(base) }
        override fun getContentResolver() = resolver
    }
    private lateinit var app: FixtureApplication
    private lateinit var controller: ChatController
    private lateinit var original: ChatController
    private lateinit var settings: ModelSettingsStore
    private lateinit var directory: File
    private lateinit var alias: String
    private lateinit var documents: Documents
    private lateinit var activity: MainActivity
    private val calls = AtomicInteger()
    private val histories = java.util.concurrent.CopyOnWriteArrayList<List<Message>>()

    override fun setUp() {
        super.setUp()
        val id = UUID.randomUUID().toString(); alias = "app.daiyu.android.test.imagehost.$id"
        directory = File(instrumentation.targetContext.cacheDir, "image-host-$id").apply { check(mkdir()) }
        val file = File(directory, "source.png")
        val bitmap = Bitmap.createBitmap(3, 2, Bitmap.Config.ARGB_8888)
        try { file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
        documents = Documents(file).apply {
            attachInfo(instrumentation.targetContext, ProviderInfo().apply { authority = "image-host"; exported = false })
        }
        val resolver = MockContentResolver(instrumentation.targetContext).apply { addProvider("image-host", documents) }
        val base = object : ContextWrapper(testContext(instrumentation.targetContext, "image-host-$id-")) {
            override fun getFilesDir() = directory
        }
        app = FixtureApplication(base, resolver)
        settings = ModelSettingsStore(app, alias)
        settings.save(ModelSettings("https://example.invalid", "not-in-a-capability-catalog", "fixture-key", false))
        val model = object : ModelPort {
            override fun open(history: List<Message>): ModelSession = error("lost image plan")
            override fun open(history: List<Message>, definitions: List<ToolDefinition>, images: ModelImagePlan): ModelSession {
                check(images is FrozenMessageImagePlan && images.hasImages)
                histories.add(history); calls.incrementAndGet()
                return object : ModelSession {
                    override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                        sink(ModelEvent.TextFinal("answer", "done"))
                    }
                    override fun cancel() {}
                }
            }
        }
        controller = ChatController(app, ConversationDatabase(app), settings, app.getSharedPreferences("conversation-ui", 0),
            { error("text-only factory must not be used") }, { model })
        awaitControl("image controller ready") { controller.ready }
        mainThread {
            val application = instrumentation.targetContext.applicationContext as ChatApplication
            original = application.controller; application.controller = controller
        }
        activity = startMain()
    }

    override fun tearDown() {
        try {
            documents.release?.countDown()
            mainThread { activity.finish() }
            mainThread { (instrumentation.targetContext.applicationContext as ChatApplication).controller = original }
            controller.close()
            app.deleteDatabase("conversations.db")
            listOf("model-settings", "conversation-ui", "image-send-identities").forEach {
                app.getSharedPreferences(it, 0).edit().clear().commit()
            }
            val keys = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            java.util.Collections.list(keys.aliases()).filter { it.startsWith(alias) }.forEach { keys.deleteEntry(it) }
            directory.deleteRecursively()
        } finally { super.tearDown() }
    }

    private fun host(): ImageChatHost = MainActivity::class.java.getDeclaredMethod("getImageHost")
        .apply { isAccessible = true }.invoke(activity) as ImageChatHost

    private fun pick(count: Int) {
        val clip = ClipData.newRawUri("image", Uri.parse("content://image-host/0"))
        repeat(count - 1) { clip.addItem(ClipData.Item(Uri.parse("content://image-host/${it + 1}"))) }
        val result = Intent().setClipData(clip).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val monitor = instrumentation.addMonitor(IntentFilter(Intent.ACTION_GET_CONTENT).apply { addDataType("image/*"); addCategory(Intent.CATEGORY_OPENABLE) },
            Instrumentation.ActivityResult(Activity.RESULT_OK, result), true)
        try {
            mainThread { retainedControls(activity).onMenuAction(ChatMenuAction.ATTACH_IMAGE) }
            ui.waitForIdle()
        } finally { instrumentation.removeMonitor(monitor) }
    }
    private fun waitImages(count: Int) {
        ui.waitUntil(10_000) { host().session.attachments.state.value.ready.size == count }
    }
    private fun authorize() {
        node("image-host-authorize").performClick()
        ui.waitUntil(10_000) { ui.onAllNodesWithTag("dialog-confirm").fetchSemanticsNodes().isNotEmpty() }
        node("dialog-confirm").performClick()
        ui.waitUntil(10_000) { host().authorizationLabel.startsWith("已声明") }
    }

    fun testRealPickerOrderedImportExplicitCapabilitySendAndHistoryRead() {
        setDraft("正文在图片之前")
        pick(2); waitImages(2)
        val owner = host().session.owner
        val ordered = host().session.attachments.state.value.ready.map { it.attachment.id }
        assertEquals(2, documents.opens.get())
        // Unknown capability never submits, including a plausible model name.
        node("image-host-send").performClick()
        ui.waitUntil(10_000) { !host().sending }
        assertEquals(0, calls.get()); assertEquals(2, host().session.attachments.state.value.ready.size)
        assertDraft("正文在图片之前")
        authorize()
        node("image-host-send").performClick()
        awaitControl("one image request") { calls.get() == 1 && controller.active == null }
        ui.waitUntil(10_000) { !host().sending && host().session.attachments.state.value.items.isEmpty() }
        val conversation = controller.database.conversations().single()
        assertEquals(owner.conversationId, conversation.id.value)
        val user = controller.database.messages(conversation.id).first().second
        assertEquals("正文在图片之前", (user.blocks.first() as MessageBlock.Text).text)
        assertEquals(ordered, user.blocks.filterIsInstance<MessageBlock.Image>().map { it.reference.id })
        assertEquals("", conversation.draft); assertDraft("")
        ui.waitUntil(10_000) { controller.imageAttachments.store.list(owner).isEmpty() }
        // History is decoded from the independent message asset store, not re-opened picker URIs.
        node("chat-messages").performScrollToIndex(0)
        ui.onNodeWithContentDescription("消息图片 1").assertExists()
        assertEquals(2, documents.opens.get())
        assertEquals(listOf("正文在图片之前"), histories.single().first().blocks.filterIsInstance<MessageBlock.Text>().map { it.text })
    }

    fun testSwitchDuringImportCancelsOriginalOwnerAndCannotPopulateNewDraft() {
        documents.entered = CountDownLatch(1); documents.release = CountDownLatch(1)
        setDraft("旧草稿")
        pick(1)
        assertTrue(documents.entered!!.await(10, TimeUnit.SECONDS))
        val oldOwner = host().session.owner
        val conversation = controller.database.create()
        mainThread { activity.select(conversation) }
        setDraft("新草稿")
        documents.release!!.countDown()
        ui.waitUntil(10_000) { controller.imageAttachments.store.list(oldOwner).isEmpty() }
        assertTrue(host().session.attachments.state.value.items.isEmpty())
        assertDraft("新草稿"); assertEquals(0, calls.get())
    }

    fun testUnknownReceiptRetainsExactDraftAndDisablesResend() {
        setDraft("不能重发")
        pick(1); waitImages(1); authorize()
        val owner = host().session.owner
        val before = host().session.attachments.state.value.ready.map { it.attachment }
        controller.database.writableDatabase.execSQL("CREATE TRIGGER fail_image_host BEFORE INSERT ON messages WHEN NEW.role='assistant' BEGIN SELECT RAISE(IGNORE); END")
        node("image-host-send").performClick()
        ui.waitUntil(10_000) { host().session.uncertain }
        assertDraft("不能重发")
        assertEquals(before, host().session.attachments.state.value.ready.map { it.attachment })
        assertEquals(before, controller.imageAttachments.store.list(owner))
        assertEquals(0, calls.get())
        node("image-host-send").assertIsNotEnabled()
        mainThread { retainedControls(activity).onSend("不能重发") }
        assertEquals(0, calls.get())
        val revision = host().session.attachments.state.value.draft.revision
        val other = controller.database.create()
        mainThread { activity.select(other); activity.select(null) }
        assertEquals(owner, host().session.owner)
        assertEquals(revision, host().session.attachments.state.value.draft.revision)
        assertTrue(host().session.uncertain)
        assertEquals(before, host().session.attachments.state.value.ready.map { it.attachment })
        activity = recreateActivity(activity)
        assertEquals(owner, host().session.owner)
        assertTrue(host().session.uncertain)
        node("image-host-send").assertIsNotEnabled()
        mainThread { retainedControls(activity).onSend("不能重发") }
        assertEquals(0, calls.get())
        controller.database.writableDatabase.execSQL("DROP TRIGGER fail_image_host")
    }

    fun testChangedExactModelDoesNotReuseCapabilityDeclaration() {
        setDraft("保留")
        pick(1); waitImages(1); authorize()
        settings.save(settings.load().copy(model = "different-exact-model"))
        node("image-host-send").performClick()
        ui.waitUntil(10_000) { !host().sending }
        assertDraft("保留")
        assertEquals(1, host().session.attachments.state.value.ready.size)
        assertEquals(0, calls.get())
    }

    fun testRecreationRestoresSameUnsentOwnerAndReferencesWithoutSending() {
        pick(1); waitImages(1)
        val oldOwner: ImageAttachmentOwner = host().session.owner
        val images = host().session.attachments.state.value.ready.map { it.attachment }
        activity = recreateActivity(activity)
        assertEquals(oldOwner, host().session.owner)
        assertEquals(images, host().session.attachments.state.value.ready.map { it.attachment })
        assertEquals(images, controller.imageAttachments.store.list(oldOwner))
        assertEquals(0, calls.get())
    }

    /** Holds only Controller -> main notifications/receipts, never the DB transaction itself. */
    private class ReceiptQueue : android.os.Handler(android.os.Looper.getMainLooper()) {
        private val queued = mutableListOf<android.os.Message>()
        private var hold = true
        override fun sendMessageAtTime(message: android.os.Message, uptimeMillis: Long): Boolean = synchronized(queued) {
            if (!hold) return super.sendMessageAtTime(message, uptimeMillis)
            queued += android.os.Message.obtain(message)
            true
        }
        fun release() = synchronized(queued) {
            hold = false
            for (message in queued) super.sendMessageAtTime(message, android.os.SystemClock.uptimeMillis())
            queued.clear()
        }
    }

    fun testNewChatCommitThenRecreationBeforeReceiptDoesNotReuseAcceptedOwner() {
        assertNull(controller.preferences.getString("selected", null))
        assertTrue(controller.database.conversations().isEmpty())
        setDraft("新会话的非空正文")
        pick(1); waitImages(1); authorize()
        val acceptedOwner = host().session.owner
        val acceptedImages = host().session.attachments.state.value.ready.map { it.attachment }
        val conversationId = ConversationId(acceptedOwner.conversationId)
        val queue = ReceiptQueue()
        val handlerField = ChatController::class.java.getDeclaredField("handler").apply { isAccessible = true }
        val originalHandler = handlerField.get(controller)
        handlerField.set(controller, queue)
        try {
            node("image-host-send").performClick()
            // The real transaction, including clearing the global draft, precedes recreation.
            ui.waitUntil(10_000) { controller.database.messages(conversationId).size == 2 }
            assertEquals("", controller.database.draft())
            assertTrue(host().sending)
            assertDraft("新会话的非空正文")
            activity = recreateActivity(activity)
            assertTrue(host().sending)
            assertEquals(acceptedOwner, host().session.owner)
            assertDraft("")
            assertNull(controller.preferences.getString("selected", null))

            mainThread { queue.release() }
            ui.waitUntil(10_000) { !host().sending && controller.active == null && calls.get() == 1 }
            // A stale nonempty-text receipt must not navigate the recreated empty editor.
            assertNull(controller.preferences.getString("selected", null))
            assertDraft("")
            assertTrue(host().session.attachments.state.value.items.isEmpty())
            assertFalse(host().fenced)
            val newOwner = host().session.owner
            assertFalse(acceptedOwner.conversationId == newOwner.conversationId)
            assertFalse(acceptedOwner.draftId == newOwner.draftId)
            ui.waitUntil(10_000) { controller.imageAttachments.store.list(acceptedOwner).isEmpty() }

            // This is a new user send, not a retry of the already committed request.
            setDraft("后续新会话正文")
            pick(1); waitImages(1); authorize()
            assertEquals(newOwner, host().session.owner)
            val newImages = host().session.attachments.state.value.ready.map { it.attachment }
            assertTrue(newImages.all { it.owner == newOwner })
            node("image-host-send").performClick()
            ui.waitUntil(10_000) { !host().sending && controller.active == null && calls.get() == 2 }
            assertFalse(host().fenced)
            assertDraft("")
            assertTrue(host().session.attachments.state.value.items.isEmpty())
            assertEquals(2, controller.database.conversations().size)
            val firstUser = controller.database.messages(conversationId).single { it.second.role == Message.USER_ROLE }.second
            val secondUser = controller.database.messages(ConversationId(newOwner.conversationId))
                .single { it.second.role == Message.USER_ROLE }.second
            assertEquals("新会话的非空正文", (firstUser.blocks.first() as MessageBlock.Text).text)
            assertEquals("后续新会话正文", (secondUser.blocks.first() as MessageBlock.Text).text)
            assertEquals(acceptedImages.map { it.id }, firstUser.blocks.filterIsInstance<MessageBlock.Image>().map { it.reference.id })
            assertEquals(newImages.map { it.id }, secondUser.blocks.filterIsInstance<MessageBlock.Image>().map { it.reference.id })
            assertFalse(firstUser.id == secondUser.id)
            assertEquals(newOwner.conversationId, controller.preferences.getString("selected", null))
        } finally {
            queue.release()
            handlerField.set(controller, originalHandler)
        }
    }

    fun testCancellationLosesToCommitThenReceiptSettlesReselectedEditorBeforeGateOpens() {
        val conversation = controller.database.create()
        mainThread { activity.select(conversation) }
        setDraft("仅发送一次")
        pick(1); waitImages(1); authorize()
        val queue = ReceiptQueue()
        val handlerField = ChatController::class.java.getDeclaredField("handler").apply { isAccessible = true }
        val originalHandler = handlerField.get(controller)
        handlerField.set(controller, queue)
        try {
            node("image-host-send").performClick()
            ui.waitUntil(10_000) { controller.database.messages(conversation.id).size == 2 }
            assertTrue(host().sending)
            val other = controller.database.create()
            mainThread {
                // Leaving cancels the handle, but DB beginCommit has already won.
                activity.select(other)
                activity.select(conversation)
            }
            assertTrue(host().sending)
            activity = recreateActivity(activity)
            assertTrue(host().sending)
            mainThread { queue.release() }
            ui.waitUntil(10_000) { !host().sending }
            assertDraft("")
            assertEquals("", controller.database.conversations().single { it.id == conversation.id }.draft)
            assertEquals(1, controller.database.messages(conversation.id).count { it.second.role == Message.USER_ROLE })
            assertTrue(host().session.attachments.state.value.items.isEmpty())
            setDraft("结算后的新草稿")
            assertDraft("结算后的新草稿")
        } finally {
            queue.release()
            handlerField.set(controller, originalHandler)
        }
    }
}
