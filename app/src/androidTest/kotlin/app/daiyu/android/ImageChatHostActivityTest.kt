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
    private lateinit var model: ModelPort
    private var beforeFactory: () -> Unit = {}
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
        model = object : ModelPort {
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
            { error("text-only factory must not be used") }, { beforeFactory(); model })
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

    fun testPureImageMainSendUsesRealImageDraftAndAcceptsExactlyOnce() {
        assertDraft("")
        pick(1); waitImages(1); authorize()
        val owner = host().session.owner
        node("chat-send").assertIsEnabled().performClick()
        // Retained callbacks cannot enqueue a second send while the original is preparing.
        mainThread { retainedControls(activity).onSend("") }
        ui.waitUntil(10_000) { !host().sending && calls.get() == 1 && controller.active == null }
        val conversation = controller.database.conversations().single()
        assertEquals(owner.conversationId, conversation.id.value)
        val rows = controller.database.messages(conversation.id).map { it.second }
        assertEquals(2, rows.size)
        val user = rows.single { it.role == Message.USER_ROLE }
        assertEquals(1, user.blocks.size)
        assertTrue(user.blocks.single() is MessageBlock.Image)
        assertTrue(host().session.attachments.state.value.items.isEmpty())
        assertDraft("")
        node("chat-send").assertIsNotEnabled()
        assertEquals(1, calls.get())
    }

    fun testCurrentPageCancelPreparationWinsBeforeCommitAndStaleAttemptCannotCancelNext() {
        setDraft("取消前的草稿")
        pick(1); waitImages(1); authorize()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        beforeFactory = { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
        val session = host().session
        node("image-host-send").performClick()
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        val oldAttempt = checkNotNull(session.attempt)
        try { node("image-host-cancel").assertIsEnabled().performClick() }
        finally { release.countDown() }
        ui.waitUntil(10_000) { !host().sending }
        assertDraft("取消前的草稿")
        assertTrue(controller.database.conversations().isEmpty())
        assertEquals(0, calls.get())
        assertEquals(1, session.attachments.state.value.ready.size)
        val nextEntered = CountDownLatch(1); val nextRelease = CountDownLatch(1)
        beforeFactory = { nextEntered.countDown(); check(nextRelease.await(10, TimeUnit.SECONDS)) }
        node("image-host-send").performClick()
        assertTrue(nextEntered.await(10, TimeUnit.SECONDS))
        try {
            mainThread {
                assertNull(host().cancelPreparation(session, oldAttempt))
                assertFalse(checkNotNull(session.attempt).cancelled)
            }
        } finally { nextRelease.countDown() }
        ui.waitUntil(10_000) { !host().sending && calls.get() == 1 && controller.active == null }
        assertDraft("")
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
        node("chat-send").assertIsNotEnabled()
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
        node("chat-send").assertIsNotEnabled()
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
        fun discard() = synchronized(queued) { queued.clear(); hold = false }
        fun release() = synchronized(queued) {
            hold = false
            for (message in queued) super.sendMessageAtTime(message, android.os.SystemClock.uptimeMillis())
            queued.clear()
        }
    }

    fun testLostReceiptNewControllerAndHostRecoverWithoutRepostOrClearingNewDraft() {
        setDraft("原进程正文")
        pick(2); waitImages(2); authorize()
        val oldHost = host()
        val owner = oldHost.session.owner
        val ordered = oldHost.session.attachments.state.value.ready.map { it.attachment.id }
        val queue = ReceiptQueue()
        val handlerField = ChatController::class.java.getDeclaredField("handler").apply { isAccessible = true }
        val oldController = controller
        val originalHandler = handlerField.get(oldController)
        handlerField.set(oldController, queue)
        try {
            node("chat-send").performClick()
            val conversationId = ConversationId(owner.conversationId)
            ui.waitUntil(10_000) { oldController.database.messages(conversationId).size == 2 && calls.get() == 1 && oldController.active == null }
            val originalIdentity = checkNotNull(oldHost.session.frozen).identity
            val rows = oldController.database.messages(conversationId)
            mainThread { activity.finish() }
            instrumentation.waitForIdleSync()
            oldController.close()
            queue.discard() // The old callback is lost, not delivered to the replacement host.
            handlerField.set(oldController, originalHandler)
            controller = ChatController(app, ConversationDatabase(app), settings,
                app.getSharedPreferences("conversation-ui", 0),
                { error("read-only recovery cannot create text models") },
                { error("read-only recovery cannot create image models") })
            awaitControl("replacement controller ready") { controller.ready }
            mainThread { (instrumentation.targetContext.applicationContext as ChatApplication).controller = controller }
            activity = startMain()
            assertTrue(host() !== oldHost)
            assertEquals(owner, host().session.owner)
            assertEquals(originalIdentity, checkNotNull(host().session.frozen).identity)
            assertTrue(host().fenced)
            setDraft("原进程正文")
            assertTrue(host().session.editorReplaced)
            assertTrue(ImageHostDraftStore(controller.preferences).read().single { it.owner == owner }.editorReplaced)
            val firstRebuiltHost = host()
            mainThread { activity.finish() }
            controller.close()
            controller = ChatController(app, ConversationDatabase(app), settings,
                app.getSharedPreferences("conversation-ui", 0),
                { error("second recovery cannot create text models") },
                { error("second recovery cannot create image models") })
            awaitControl("second replacement controller ready") { controller.ready }
            mainThread { (instrumentation.targetContext.applicationContext as ChatApplication).controller = controller }
            activity = startMain()
            assertTrue(host() !== firstRebuiltHost)
            assertTrue(host().session.editorReplaced) // persisted across TWO real Controller/Host rebuilds
            assertTrue(ImageHostDraftStore(controller.preferences).read().single { it.owner == owner }.editorReplaced)
            val frozenRevision = checkNotNull(host().session.frozen).revision
            // Force the documented collision; do not merely assume Activity counters happen to match.
            val revisionField = MainActivity::class.java.getDeclaredField("draftRevision").apply { isAccessible = true }
            mainThread { revisionField.setLong(activity, frozenRevision) }
            assertEquals(frozenRevision, revisionField.getLong(activity))
            assertEquals(frozenRevision, host().session.attachments.state.value.draft.revision)
            assertDraft("原进程正文")
            setSelection(1, 4)
            mainThread { retainedControls(activity).onMenuAction(ChatMenuAction.ATTACH_IMAGE) }
            node("image-host-recover").assertIsEnabled().performClick()
            ui.waitUntil(10_000) { !host().sending && !host().fenced }
            assertDraft("原进程正文")
            assertSelection(1, 4)
            assertEquals("原进程正文", controller.database.draft())
            assertNull(controller.preferences.getString("selected", null))
            assertTrue(host().session.attachments.state.value.items.isEmpty())
            assertEquals(rows, controller.database.messages(conversationId))
            val user = rows.single { it.second.role == Message.USER_ROLE }.second
            assertEquals(originalIdentity.userId, user.id)
            assertEquals(ordered, user.blocks.filterIsInstance<MessageBlock.Image>().map { it.reference.id })
            assertEquals(1, calls.get()); assertEquals(2, documents.opens.get())
        } finally {
            queue.discard()
            handlerField.set(oldController, originalHandler)
        }
    }

    fun testExistingConversationLostReceiptRecoveryThenUneditedPureImageUsesDurableNextOwner() {
        val conversation = controller.database.create("default")
        mainThread { activity.select(conversation) }
        setDraft("已有会话中已接受的正文")
        pick(1); waitImages(1); authorize()
        val oldHost = host()
        val oldOwner = oldHost.session.owner
        val queue = ReceiptQueue()
        val handlerField = ChatController::class.java.getDeclaredField("handler").apply { isAccessible = true }
        val oldController = controller
        val originalHandler = handlerField.get(oldController)
        handlerField.set(oldController, queue)
        try {
            node("chat-send").performClick()
            ui.waitUntil(10_000) {
                oldController.database.messages(conversation.id).size == 2 && calls.get() == 1 && oldController.active == null
            }
            val frozen = checkNotNull(oldHost.session.frozen)
            val originalRows = oldController.database.messages(conversation.id)
            assertEquals("", oldController.database.conversations().single().draft)
            mainThread { activity.finish() }
            oldController.close()
            queue.discard()
            handlerField.set(oldController, originalHandler)
            val recoveryFactories = AtomicInteger()
            beforeFactory = { recoveryFactories.incrementAndGet(); error("recovery must not prepare or create models") }
            controller = ChatController(app, ConversationDatabase(app), settings,
                app.getSharedPreferences("conversation-ui", 0),
                { error("image-only fixture") }, { beforeFactory(); model })
            awaitControl("existing conversation replacement ready") { controller.ready }
            mainThread { (instrumentation.targetContext.applicationContext as ChatApplication).controller = controller }
            activity = startMain()
            assertTrue(host() !== oldHost)
            assertEquals(conversation.id.value, controller.preferences.getString("selected", null))
            assertEquals(oldOwner, host().session.owner)
            assertEquals(frozen, host().session.frozen)
            assertDraft("") // SQLite accepted the text before its callback was lost.
            val revisionField = MainActivity::class.java.getDeclaredField("draftRevision").apply { isAccessible = true }
            assertEquals(frozen.revision, revisionField.getLong(activity))
            node("image-host-recover").performClick()
            ui.waitUntil(10_000) { !host().sending && !host().fenced }
            assertDraft("")
            assertEquals(frozen.revision, revisionField.getLong(activity)) // Main did NOT clear/increment it.
            assertEquals(conversation.id.value, controller.preferences.getString("selected", null))
            assertEquals(originalRows, controller.database.messages(conversation.id))
            assertEquals(1, calls.get()); assertEquals(0, recoveryFactories.get())
            val successor = host().session.owner
            assertEquals(oldOwner.conversationId, successor.conversationId)
            assertFalse(oldOwner.draftId == successor.draftId)
            assertEquals(1L, host().session.generation)
            val saved = ImageHostDraftStore(controller.preferences).read().single { it.conversation == conversation.id.value }
            assertEquals(successor, saved.owner); assertEquals(1L, saved.generation)
            assertFalse(saved.retired)

            // Another real process-boundary equivalent proves that the successor is durable,
            // not an Activity-only workaround. Do not edit the empty text before sending.
            mainThread { activity.finish() }
            controller.close()
            beforeFactory = {}
            controller = ChatController(app, ConversationDatabase(app), settings,
                app.getSharedPreferences("conversation-ui", 0),
                { error("image-only fixture") }, { beforeFactory(); model })
            awaitControl("successor controller ready") { controller.ready }
            mainThread { (instrumentation.targetContext.applicationContext as ChatApplication).controller = controller }
            activity = startMain()
            assertDraft("")
            assertEquals(successor, host().session.owner)
            assertEquals(frozen.revision, revisionField.getLong(activity))
            pick(1); waitImages(1); authorize()
            val newImage = host().session.attachments.state.value.ready.single().attachment
            node("chat-send").performClick()
            ui.waitUntil(10_000) { !host().sending && controller.active == null && calls.get() == 2 }
            assertFalse(host().fenced)
            assertDraft("")
            val rows = controller.database.messages(conversation.id)
            assertEquals(4, rows.size)
            assertEquals(originalRows, rows.take(2))
            val users = rows.map { it.second }.filter { it.role == Message.USER_ROLE }
            assertEquals(2, users.size)
            assertEquals(frozen.identity.userId, users.first().id)
            assertFalse(frozen.identity.userId == users.last().id)
            assertTrue(users.last().blocks.single() is MessageBlock.Image)
            assertEquals(newImage.id, (users.last().blocks.single() as MessageBlock.Image).reference.id)
            assertEquals(2, documents.opens.get())
            assertEquals(2L, host().session.generation)
            assertEquals(conversation.id.value, controller.preferences.getString("selected", null))
        } finally {
            queue.discard()
            handlerField.set(oldController, originalHandler)
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
        val conversation = controller.database.create("default")
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
            node("image-host-cancel").assertIsEnabled().performClick()
            assertTrue(host().sending) // No fabricated Rejected/Cancelled receipt after the gate.
            assertFalse(checkNotNull(host().session.attempt).cancelled)
            assertDraft("仅发送一次")
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
