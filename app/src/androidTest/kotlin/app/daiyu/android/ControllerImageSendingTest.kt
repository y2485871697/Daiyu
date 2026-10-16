package app.daiyu.android

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.test.InstrumentationTestCase
import app.daiyu.android.domain.*
import app.daiyu.android.media.ImageAttachment
import app.daiyu.android.media.ImageAttachmentOwner
import app.daiyu.android.media.MessageImageAssetStore
import app.daiyu.android.model.ProviderSnapshot
import app.daiyu.android.model.images.FrozenMessageImagePlan
import app.daiyu.android.storage.ConversationDatabase
import app.daiyu.android.storage.ModelSettings
import app.daiyu.android.storage.ModelSettingsStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Real Controller -> asset publication -> SQLite pair receipt -> Runner -> explicit image open.
 * No HTTP, no UI, no altered schema. Tests intentionally do not erase retained unknown assets.
 */
@Suppress("DEPRECATION")
class ControllerImageSendingTest : InstrumentationTestCase() {
    private class FixtureApplication(base: Context) : Application() {
        private val privateFiles = File(base.cacheDir, "controller-image-${UUID.randomUUID()}")
            .apply { check(mkdirs()) }
        init { attachBaseContext(base) }
        override fun getFilesDir(): File = privateFiles
    }
    private class Probe : ModelPort {
        val opens = AtomicInteger()
        val histories = java.util.concurrent.CopyOnWriteArrayList<List<Message>>()
        var beforeFactory: () -> Unit = {}
        override fun open(history: List<Message>): ModelSession = error("image plan must not be lost")
        override fun open(history: List<Message>, definitions: List<ToolDefinition>, images: ModelImagePlan): ModelSession {
            check(images is FrozenMessageImagePlan && images.hasImages)
            histories.add(history); opens.incrementAndGet()
            return object : ModelSession {
                override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                    sink(ModelEvent.TextFinal("answer", "done"))
                }
                override fun cancel() {}
            }
        }
    }
    private data class Fixture(val app: FixtureApplication, val db: ConversationDatabase,
        val settings: ModelSettingsStore, val controller: ChatController, val probe: Probe, val alias: String)
    private val fixtures = mutableListOf<Fixture>()
    private fun main(work: () -> Unit) {
        val failure = AtomicReference<Throwable?>()
        instrumentation.runOnMainSync { try { work() } catch (t: Throwable) { failure.set(t) } }
        failure.get()?.let { throw it }
    }
    private fun await(f: Fixture, predicate: () -> Boolean) {
        val done = CountDownLatch(1)
        val listener: () -> Unit = { if (predicate()) done.countDown() }
        main { f.controller.observe(listener); listener() }
        try { assertTrue("controller timeout", done.await(10, TimeUnit.SECONDS)) }
        finally { main { f.controller.unobserve(listener) } }
    }
    private fun fixture(): Fixture {
        val id = UUID.randomUUID().toString()
        val app = FixtureApplication(testContext(instrumentation.targetContext, "image-$id-"))
        val db = ConversationDatabase(app)
        val alias = "app.daiyu.android.test.image.$id"
        val settings = ModelSettingsStore(app, alias)
        settings.save(ModelSettings("https://example.invalid", "image-fixture", "fixture-key", false))
        val probe = Probe()
        lateinit var controller: ChatController
        controller = ChatController(app, db, settings, app.getSharedPreferences("conversation-ui", 0),
            { error("must use snapshot preserving image factory") }, {
                check(!Thread.holdsLock(controller))
                probe.beforeFactory(); probe
            })
        return Fixture(app, db, settings, controller, probe, alias).also {
            fixtures.add(it); await(it) { controller.ready }
        }
    }
    private fun png(): ByteArray {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        return try { ByteArrayOutputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)); it.toByteArray()
        } } finally { bitmap.recycle() }
    }
    private fun image(f: Fixture, owner: ImageAttachmentOwner): ImageAttachment =
        f.controller.imageAttachments.store.import(owner, png().inputStream())
    private fun provider(f: Fixture, supported: Boolean? = true): ProviderSnapshot =
        ProviderSnapshot.capture(f.settings.load(), f.settings.load().protocol, supported)
    private fun submit(f: Fixture, conversation: Conversation?, owner: ImageAttachmentOwner, revision: Long,
        parts: List<ImageSendPart>, provider: ProviderSnapshot = provider(f),
        expectedDraft: String = if (conversation == null) f.db.draft() else
            f.db.conversations().single { it.id == conversation.id }.draft): ImageSendResult {
        val done = CountDownLatch(1); val result = AtomicReference<ImageSendResult>()
        main { assertNotNull(f.controller.sendImages(conversation, owner, revision, parts, provider, expectedDraft) {
            result.set(it); done.countDown()
        }) }
        assertTrue(done.await(10, TimeUnit.SECONDS))
        return result.get()
    }
    override fun tearDown() {
        try { fixtures.forEach { f ->
            f.controller.close()
            f.app.deleteDatabase("conversations.db"); f.app.filesDir.deleteRecursively()
            listOf("model-settings", "conversation-ui", "image-send-identities").forEach {
                f.app.getSharedPreferences(it, 0).edit().clear().commit()
            }
            val keys = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            java.util.Collections.list(keys.aliases()).filter { it.startsWith("${f.alias}.") }
                .forEach { keys.deleteEntry(it) }
        } } finally { super.tearDown() }
    }

    fun testImageAttemptFreezesWithActualAuxiliaryRouteWithoutClaimingMainSupportsImages() {
        val f = fixture(); val conversation = f.db.create("default")
        val owner = ImageAttachmentOwner(conversation.id.value, "independent-route-editor")
        val attachment = image(f, owner)
        val main = provider(f, null)
        val actual = ProviderSnapshot.capture(ModelSettings("https://auxiliary.invalid", "independent-vision", "other-key", false),
            app.daiyu.android.model.ProviderProtocol.RESPONSES, true)
        val attempt = ControllerImageAttempt(owner, 1, listOf(ImageSendPart.Image(attachment)), main,
            ImageSendHandle(owner, 1), f.app.getSharedPreferences("image-send-identities", 0),
            imageRoute = actual, auxiliaryGeneration = 17)
        val row = attempt.prepare(f.controller.imageAttachments.store,
            MessageImageAssetStore(File(f.app.filesDir, "independent-route-assets")))
        assertNull(attempt.provider.imageInputSupported)
        assertEquals("independent-vision", attempt.imageRoute.model)
        assertEquals(1, row.blocks.filterIsInstance<MessageBlock.Image>().size)
        assertEquals(0, f.probe.opens.get())
        assertEquals(1, f.controller.imageAttachments.store.list(owner).size)
    }

    fun testAcceptedOrderedBlocksExactReceiptAndHistoryAfterDraftRemoval() {
        val f = fixture(); val conversation = f.db.create("default")
        val owner = ImageAttachmentOwner(conversation.id.value, "editor-one")
        val first = image(f, owner); val second = image(f, owner)
        val parts = listOf(ImageSendPart.Text("before"), ImageSendPart.Image(first),
            ImageSendPart.Text("between"), ImageSendPart.Image(second), ImageSendPart.Text("after"))
        f.db.draft(conversation.id, "before between after")
        val receipt = submit(f, conversation, owner, 7, parts) as ImageSendResult.Accepted
        assertEquals(owner, receipt.owner); assertEquals(7L, receipt.revision)
        assertEquals(listOf(first, second), receipt.images)
        assertFalse(f.controller.imageAttachments.store.list(owner).isEmpty()) // Controller never deletes.
        await(f) { f.controller.active == null }
        val user = f.db.messages(conversation.id).first().second
        assertEquals(receipt.userId, user.id)
        assertEquals(listOf("text", "image", "text", "image", "text"), user.blocks.map {
            when (it) { is MessageBlock.Text -> "text"; is MessageBlock.Image -> "image"; else -> "tool" }
        })
        assertEquals("", f.db.conversations().single().draft)
        val ref = user.blocks.filterIsInstance<MessageBlock.Image>().first().reference
        val mediaOwner = app.daiyu.android.media.MessageImageOwner(conversation.id.value, user.id.value)
        val mediaRef = app.daiyu.android.media.MessageImageReference(mediaOwner, ref.id, ref.mime,
            ref.bytes, ref.sha256, ref.width, ref.height)
        assertEquals(first.bytes, f.controller.messageImageResolver(conversation.id).read(mediaOwner, mediaRef).size)
        try {
            f.controller.messageImageResolver(ConversationId("different-conversation")).read(mediaOwner, mediaRef)
            fail("preview must not read across its authorized conversation")
        } catch (_: IllegalArgumentException) { }
        receipt.images.forEach { f.controller.imageAttachments.store.remove(receipt.owner, it) }
        assertNotNull(f.controller.send(conversation, "follow up"))
        await(f) { f.controller.active == null }
        assertEquals(2, f.probe.opens.get())
        assertTrue(f.probe.histories.last().any { row -> row.id == receipt.userId && row.blocks.any { it is MessageBlock.Image } })
        // Repeating an accepted revision reconciles the pair, never opens a third model session.
        val replay = submit(f, conversation, owner, 7, parts,
            expectedDraft = "before between after") as ImageSendResult.Accepted
        assertEquals(receipt.userId, replay.userId); assertEquals(2, f.probe.opens.get())
    }

    fun testUnsupportedOrUnknownCapabilityRejectsBeforeFactoryAndKeepsDraft() {
        for (supported in listOf<Boolean?>(null, false)) {
            val f = fixture(); val conversation = f.db.create("default")
            val owner = ImageAttachmentOwner(conversation.id.value, "editor")
            val image = image(f, owner); f.db.draft(conversation.id, "keep")
            val result = submit(f, conversation, owner, 1, listOf(ImageSendPart.Image(image)), provider(f, supported))
            assertTrue(result is ImageSendResult.Rejected)
            assertEquals(0, f.probe.opens.get()); assertTrue(f.db.messages(conversation.id).isEmpty())
            assertEquals(listOf(image), f.controller.imageAttachments.store.list(owner))
            assertEquals("keep", f.db.conversations().single().draft)
        }
    }

    fun testRealPairTransactionFailureRetainsPublishedAssetsAndFencesRetry() {
        val f = fixture(); val conversation = f.db.create("default")
        val owner = ImageAttachmentOwner(conversation.id.value, "editor")
        val image = image(f, owner); f.db.draft(conversation.id, "keep")
        f.db.writableDatabase.execSQL("CREATE TRIGGER fail_image_pair BEFORE INSERT ON messages WHEN NEW.role='assistant' BEGIN SELECT RAISE(IGNORE); END")
        val parts = listOf(ImageSendPart.Image(image))
        val result = submit(f, conversation, owner, 1, parts) as ImageSendResult.Unknown
        assertEquals(0, f.probe.opens.get()); assertTrue(f.db.messages(conversation.id).isEmpty())
        assertEquals("keep", f.db.conversations().single().draft)
        val assets = MessageImageAssetStore(File(f.app.filesDir, "media-message-images"))
        assertEquals(image.id, assets.preparedReferences(app.daiyu.android.media.MessageImageOwner(
            owner.conversationId, result.userId!!.value)).single().id)
        assertEquals(listOf(image), f.controller.imageAttachments.store.list(owner))
        f.db.writableDatabase.execSQL("DROP TRIGGER fail_image_pair")
        assertTrue(submit(f, conversation, owner, 1, parts) is ImageSendResult.Unknown)
        assertTrue(submit(f, conversation, owner, 2, parts) is ImageSendResult.Unknown)
        assertEquals(0, f.probe.opens.get()); assertTrue(f.db.messages(conversation.id).isEmpty())
    }

    fun testCancelDuringPreparationIsOutsideControllerMonitorAndDoesNotConsumeDraft() {
        val f = fixture(); val conversation = f.db.create("default")
        val owner = ImageAttachmentOwner(conversation.id.value, "editor")
        val image = image(f, owner)
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val done = CountDownLatch(1)
        val result = AtomicReference<ImageSendResult>()
        f.probe.beforeFactory = { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
        lateinit var handle: ImageSendHandle
        main { handle = checkNotNull(f.controller.sendImages(conversation, owner, 3,
            listOf(ImageSendPart.Image(image)), provider(f), "") { result.set(it); done.countDown() }) }
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            main { assertTrue(handle.cancel()); assertNull(f.controller.active) }
        } finally { release.countDown() }
        assertTrue(done.await(10, TimeUnit.SECONDS)); assertTrue(result.get() is ImageSendResult.Rejected)
        assertTrue(f.db.messages(conversation.id).isEmpty()); assertEquals(0, f.probe.opens.get())
        assertEquals(listOf(image), f.controller.imageAttachments.store.list(owner))
    }

    fun testChangedDraftFailsAtomicallyAndCanRetryOnlyAsANewRevision() {
        val f = fixture(); val conversation = f.db.create("default")
        val owner = ImageAttachmentOwner(conversation.id.value, "editor")
        val image = image(f, owner); val parts = listOf(ImageSendPart.Image(image))
        f.db.draft(conversation.id, "old draft")
        f.probe.beforeFactory = { f.db.draft(conversation.id, "new draft") }
        val rejected = submit(f, conversation, owner, 4, parts, expectedDraft = "old draft")
        assertTrue(rejected is ImageSendResult.Rejected)
        assertEquals("new draft", f.db.conversations().single().draft)
        assertTrue(f.db.messages(conversation.id).isEmpty()); assertEquals(0, f.probe.opens.get())
        assertEquals(listOf(image), f.controller.imageAttachments.store.list(owner))
        f.probe.beforeFactory = {}
        assertTrue(submit(f, conversation, owner, 5, parts) is ImageSendResult.Accepted)
        await(f) { f.controller.active == null }
        assertEquals(1, f.probe.opens.get())
    }

    fun testNewConversationUsesPersistedOwnerAndRecreatedAttemptNeverReopensModel() {
        val f = fixture()
        val owner = ImageAttachmentOwner(UUID.randomUUID().toString(), "new-editor")
        val image = image(f, owner); val parts = listOf(ImageSendPart.Image(image))
        f.db.draft("composer draft")
        val receipt = submit(f, null, owner, 1, parts) as ImageSendResult.Accepted
        await(f) { f.controller.active == null }
        assertEquals(owner.conversationId, receipt.conversation.id.value)
        assertEquals("", f.db.draft())
        f.controller.imageAttachments.store.remove(owner, image)
        // A newly constructed attempt reads the durable identities, not the deleted draft bytes.
        val replay = ControllerImageAttempt(owner, 1, parts, provider(f), ImageSendHandle(owner, 1),
            f.app.getSharedPreferences("image-send-identities", 0), "composer draft").reserve(f.db)
        assertTrue(replay is ImageSendResult.Accepted)
        assertEquals(receipt.userId, (replay as ImageSendResult.Accepted).userId)
        assertEquals(1, f.probe.opens.get())
        assertEquals(2, f.db.messages(receipt.conversation.id).size)
    }

    fun testChangedProviderCannotConnectOrAcceptTextWithOldImageHistoryUntilAuthorized() {
        val f = fixture(); val conversation = f.db.create("default")
        val owner = ImageAttachmentOwner(conversation.id.value, "editor")
        val image = image(f, owner)
        assertTrue(submit(f, conversation, owner, 1, listOf(ImageSendPart.Image(image))) is ImageSendResult.Accepted)
        await(f) { f.controller.active == null }
        f.settings.save(f.settings.load().copy(model = "another-exact-model"))
        f.db.draft(conversation.id, "keep this text")
        assertNull(f.controller.send(conversation, "keep this text"))
        assertEquals(1, f.probe.opens.get()); assertEquals(2, f.db.messages(conversation.id).size)
        assertEquals("keep this text", f.db.conversations().single().draft)
        main { assertTrue(f.controller.authorizeImageProvider(provider(f))) }
        assertNotNull(f.controller.send(conversation, "keep this text"))
        await(f) { f.controller.active == null }
        assertEquals(2, f.probe.opens.get())
    }

    fun testCloseDuringPreparationRetainsDraftAndDrainsBeforeStorageClose() {
        val f = fixture(); val conversation = f.db.create("default")
        val owner = ImageAttachmentOwner(conversation.id.value, "editor")
        val image = image(f, owner)
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val done = CountDownLatch(1)
        val result = AtomicReference<ImageSendResult>()
        f.probe.beforeFactory = { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
        main { assertNotNull(f.controller.sendImages(conversation, owner, 1,
            listOf(ImageSendPart.Image(image)), provider(f), "") { result.set(it); done.countDown() }) }
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            main { f.controller.close() } // Must not wait on the factory under the monitor.
        } finally { release.countDown() }
        assertTrue(done.await(10, TimeUnit.SECONDS)); assertTrue(result.get() is ImageSendResult.Rejected)
        f.controller.close() // Wait for actual resource/storage drain off the main thread.
        assertEquals(0, f.probe.opens.get())
        assertEquals(listOf(image), f.controller.imageAttachments.store.list(owner))
    }

    fun testResolverDoesNotAuthorizeAnUnpersistedSelfConsistentImageRow() {
        val f = fixture(); val conversation = f.db.create("default")
        val owner = ImageAttachmentOwner(conversation.id.value, "editor")
        val attachment = image(f, owner)
        val attempt = ControllerImageAttempt(owner, 1, listOf(ImageSendPart.Image(attachment)), provider(f),
            ImageSendHandle(owner, 1), f.app.getSharedPreferences("image-send-identities", 0))
        assertNull(attempt.reserve(f.db))
        val assets = MessageImageAssetStore(File(f.app.filesDir, "media-message-images"))
        val row = attempt.prepare(f.controller.imageAttachments.store, assets)
        try {
            ControllerMessageImages(f.db, assets).wrap(f.probe, provider(f)).open(listOf(row))
            fail("reference owner must not authorize its own unpersisted row")
        } catch (_: IllegalStateException) { }
        assertEquals(0, f.probe.opens.get())
    }
}
