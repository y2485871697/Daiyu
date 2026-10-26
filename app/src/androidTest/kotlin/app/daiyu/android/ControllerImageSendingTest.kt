package app.daiyu.android

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.test.InstrumentationTestCase
import app.daiyu.android.domain.*
import app.daiyu.android.media.ImageAttachment
import app.daiyu.android.media.ImageAttachmentOwner
import app.daiyu.android.media.MessageImageAssetStore
import app.daiyu.android.model.ProviderProtocol
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
        override fun getApplicationContext(): Context = this
    }
    private class Probe : CatalogFixturePort {
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
    private fun fixture(protocol: ProviderProtocol = ProviderProtocol.RESPONSES): Fixture {
        val id = UUID.randomUUID().toString()
        val app = FixtureApplication(testContext(instrumentation.targetContext, "image-$id-"))
        val db = ConversationDatabase(app)
        val alias = "app.daiyu.android.test.image.$id"
        val settings = ModelSettingsStore(app, alias)
        settings.save(ModelSettings("https://example.invalid", "image-fixture", "fixture-key", false, protocol = protocol),
            protocolChangeAuthorized = protocol != ProviderProtocol.RESPONSES)
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
    private fun runProvider(f: Fixture): ProviderSnapshot =
        ProviderSnapshot.capture(f.controller.providers.freezeActive().settings)
    private fun submit(f: Fixture, conversation: Conversation?, owner: ImageAttachmentOwner, revision: Long,
        parts: List<ImageSendPart>, provider: ProviderSnapshot = runProvider(f),
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

    fun testNewControllerAndHostReadOnlyRecoveryUsesFrozenIdentityWithoutSettingsOrModel() {
        val f = fixture(); val conversation = f.db.create("default")
        val owner = ImageAttachmentOwner(conversation.id.value, "lost-process-editor")
        val first = image(f, owner); val second = image(f, owner)
        val parts = listOf(ImageSendPart.Text("before"), ImageSendPart.Image(first),
            ImageSendPart.Text("between"), ImageSendPart.Image(second))
        val identity = ImageSendIdentity.create()
        val frozen = FrozenImageSend(owner, 17, "original composer", parts, identity)
        val journal = f.app.getSharedPreferences("image-send-identities", 0)
        val attempt = ControllerImageAttempt(owner, 17, parts, provider(f), ImageSendHandle(owner, 17),
            journal, frozen.text, identity = identity)
        assertNull(attempt.reserve(f.db))
        val user = attempt.prepare(f.controller.imageAttachments.store,
            MessageImageAssetStore(File(f.app.filesDir, "media-message-images")), 123L)
        val assistant = Message(identity.assistantId, conversation.id, identity.runId,
            Message.ASSISTANT_ROLE, emptyList(), RunStatus.RUNNING, createdAt = 123L)
        f.db.send(user, assistant)
        f.db.save(assistant.copy(status = RunStatus.COMPLETED))
        // Simulate accepted SQLite pair with no delivered callback / tombstone confirmation.
        val store = ImageHostDraftStore(f.controller.preferences)
        assertTrue(store.write(listOf(ImageHostDraftStore.Draft(conversation.id.value, owner, 17,
            listOf(first, second), true, frozen))))
        lateinit var oldHost: ImageChatHost
        main {
            oldHost = ImageChatHost.obtain(f.controller, {}, {}, { _, _ -> fail("lost callback") })
            oldHost.enter(conversation.id.value)
            assertTrue(oldHost.fenced)
            oldHost.close()
        }
        f.controller.close()
        val db = ConversationDatabase(f.app)
        // Recovery must not consume a newer persisted editor, even if the owner is unchanged.
        db.draft(conversation.id, "newer draft must survive")
        f.app.getSharedPreferences("model-settings", 0).edit().clear().commit()
        val factories = AtomicInteger()
        val replacement = ChatController(f.app, db, f.settings, f.app.getSharedPreferences("conversation-ui", 0),
            { factories.incrementAndGet(); error("recovery cannot create a model") },
            { factories.incrementAndGet(); error("recovery cannot create an image model") })
        val next = f.copy(db = db, controller = replacement)
        fixtures.remove(f); fixtures.add(next)
        await(next) { replacement.ready }
        val rows = db.messages(conversation.id)
        // Receipt lookup must not freeze/reopen the old draft bytes (published assets remain).
        listOf(first, second).forEach { replacement.imageAttachments.store.remove(owner, it) }
        assertTrue(replacement.imageAttachments.store.list(owner).isEmpty())
        val journalBefore = journal.all.toMap()
        val direct = replacement.readImageReceipt(frozen) as ImageSendResult.Accepted
        assertEquals(identity.userId, direct.userId); assertEquals(identity.assistantId, direct.assistantId)
        assertEquals(identity.runId, direct.runId)
        assertEquals(journalBefore, journal.all) // receipt query is read-only, even advisory state
        assertEquals(rows, db.messages(conversation.id))
        val done = CountDownLatch(1)
        lateinit var host: ImageChatHost
        main {
            host = ImageChatHost.obtain(replacement, {}, {}, { receipt, text ->
                assertEquals(identity.userId, receipt.userId)
                assertEquals(frozen.text, text)
                done.countDown()
            })
            host.enter(conversation.id.value)
            assertTrue(host !== oldHost)
            assertEquals(owner, host.session.owner)
            assertEquals(frozen, host.session.frozen)
            assertTrue(host.canRecover)
            host.recover()
        }
        assertTrue(done.await(10, TimeUnit.SECONDS))
        main {
            assertFalse(host.fenced)
            assertTrue(host.session.attachments.state.value.items.isEmpty())
            assertEquals(1L, host.session.generation)
            assertFalse(owner == host.session.owner)
            assertEquals(owner.conversationId, host.session.owner.conversationId)
            host.close()
        }
        val successor = store.read().single { it.conversation == conversation.id.value }
        assertEquals(host.session.owner, successor.owner)
        assertEquals(1L, successor.generation)
        assertFalse(successor.retired)
        assertEquals("newer draft must survive", db.conversations().single().draft)
        assertEquals(rows, db.messages(conversation.id))
        assertTrue(journal.all.values.all { org.json.JSONObject(it as String).getBoolean("confirmed") })
        // A genuinely new image uses the durable successor even with EXACTLY the old revision.
        val nextParts = listOf(ImageSendPart.Image(image(next, successor.owner)))
        val later = ControllerImageAttempt(successor.owner, 17, nextParts, null,
            ImageSendHandle(successor.owner, 17), journal, "newer draft must survive")
        assertNull(later.reserve(db))
        assertTrue(later.rejected("test does not submit the new generation") is ImageSendResult.Rejected)
        // Simulate death after the retirement commit but before the successor-owner save.
        assertTrue(store.write(listOf(successor.copy(owner = owner, retired = true))))
        val retired = store.read().single()
        assertTrue(retired.retired)
        val restored = ImageChatHost.Session(conversation.id.value, retired)
        assertEquals(1L, restored.generation)
        assertEquals(17L, restored.attachments.state.value.draft.revision)
        assertFalse(owner == restored.owner)
        assertFalse(restored.retired)
        assertTrue(restored.attachments.state.value.items.isEmpty())
        assertEquals(0, factories.get()); assertEquals(0, f.probe.opens.get())
    }

    fun testMissingContradictoryAndUnreadableReceiptsRemainUnknownWithoutWrites() {
        val f = fixture(); val conversation = f.db.create("default")
        val owner = ImageAttachmentOwner(conversation.id.value, "read-only-editor")
        val attachment = image(f, owner)
        val parts = listOf(ImageSendPart.Image(attachment), ImageSendPart.Text("after"))
        val identity = ImageSendIdentity.create()
        val frozen = FrozenImageSend(owner, 4, "draft", parts, identity)
        val journal = f.app.getSharedPreferences("image-send-identities", 0)
        val attempt = ControllerImageAttempt(owner, 4, parts, provider(f), ImageSendHandle(owner, 4),
            journal, frozen.text, identity = identity)
        assertNull(attempt.reserve(f.db))
        val pending = journal.all.toMap()
        repeat(2) { assertTrue(f.controller.readImageReceipt(frozen) is ImageSendResult.Unknown) }
        assertEquals(pending, journal.all)
        assertTrue(f.db.messages(conversation.id).isEmpty())
        val store = ImageHostDraftStore(f.controller.preferences)
        assertTrue(store.write(listOf(ImageHostDraftStore.Draft(conversation.id.value, owner, 4,
            listOf(attachment), true, frozen))))
        val lookedUp = CountDownLatch(1)
        lateinit var host: ImageChatHost
        main {
            host = ImageChatHost.obtain(f.controller, { if (!host.sending) lookedUp.countDown() }, {},
                { _, _ -> fail("missing receipt is not acceptance") })
            host.enter(conversation.id.value)
            host.send(conversation, 999, "must not enqueue", { true }) // busy UNKNOWN rejects send
            assertEquals(frozen, host.session.frozen)
            host.recover()
        }
        assertTrue(lookedUp.await(10, TimeUnit.SECONDS))
        main {
            assertTrue(host.fenced); assertTrue(host.canRecover)
            assertEquals(frozen, host.session.frozen)
            assertEquals(listOf(attachment), host.session.attachments.state.value.ready.map { it.attachment })
            host.close()
        }
        assertEquals(frozen, store.read().single { it.owner == owner }.frozen)
        assertEquals(pending, journal.all)
        val user = attempt.prepare(f.controller.imageAttachments.store,
            MessageImageAssetStore(File(f.app.filesDir, "media-message-images")), 0L)
        val assistant = Message(identity.assistantId, conversation.id, identity.runId,
            Message.ASSISTANT_ROLE, emptyList(), RunStatus.RUNNING, createdAt = 0L)
        f.db.send(user, assistant)
        f.db.save(assistant.copy(status = RunStatus.COMPLETED))
        assertTrue(f.controller.readImageReceipt(frozen.copy(parts = parts.reversed())) is ImageSendResult.Unknown)
        assertTrue(f.controller.readImageReceipt(frozen.copy(identity = identity.copy(runId = RunId("wrong")))) is ImageSendResult.Unknown)
        val originalBlocks = f.db.readableDatabase.rawQuery("SELECT blocks FROM messages WHERE id=?", arrayOf(user.id.value)).use {
            check(it.moveToFirst()); it.getString(0)
        }
        try {
            f.db.writableDatabase.execSQL("UPDATE messages SET blocks=? WHERE id=?",
                arrayOf("[]", user.id.value))
            assertTrue(f.controller.readImageReceipt(frozen) is ImageSendResult.Unknown)
        } finally {
            f.db.writableDatabase.execSQL("UPDATE messages SET blocks=? WHERE id=?", arrayOf(originalBlocks, user.id.value))
        }
        assertTrue(f.controller.readImageReceipt(frozen) is ImageSendResult.Accepted)
        // Loss of the advisory journal is not loss of the host's original durable identity.
        journal.edit().clear().commit()
        assertTrue(f.controller.readImageReceipt(frozen) is ImageSendResult.Accepted)
        val key = pending.keys.single()
        journal.edit().putString(key, "invalid-json").commit()
        assertTrue(f.controller.readImageReceipt(frozen) is ImageSendResult.Unknown)
        assertEquals("invalid-json", journal.getString(key, null))
        journal.edit().clear().commit()
        f.db.writableDatabase.execSQL("ALTER TABLE messages RENAME TO unavailable_image_receipt")
        try { assertTrue(f.controller.readImageReceipt(frozen) is ImageSendResult.Unknown) }
        finally { f.db.writableDatabase.execSQL("ALTER TABLE unavailable_image_receipt RENAME TO messages") }
        assertEquals(2, f.db.messages(conversation.id).size)
        assertEquals(0, f.probe.opens.get())
    }

    fun testImageHandleCancelWinsOnlyBeforeCommitGate() {
        val owner = ImageAttachmentOwner("gate-conversation", "gate-draft")
        val callbacks = AtomicInteger()
        val cancelled = ImageSendHandle(owner, 1) { callbacks.incrementAndGet() }
        assertTrue(cancelled.cancel()); assertFalse(cancelled.beginCommit())
        val committed = ImageSendHandle(owner, 2) { callbacks.incrementAndGet() }
        assertTrue(committed.beginCommit()); assertFalse(committed.cancel())
        assertFalse(committed.isCancelled()); assertEquals(1, callbacks.get())
    }

    fun testPreparedImageTimeAndReceiptReconstructionUseOriginalRowIncludingLegacyNullAndEpoch() {
        val f = fixture()
        val journal = f.app.getSharedPreferences("image-send-identities", 0)
        for (time in listOf<Long?>(null, 0L, 123456789L)) {
            val conversation = f.db.create("default")
            val owner = ImageAttachmentOwner(conversation.id.value, "time-editor")
            val parts = listOf(ImageSendPart.Text("body"), ImageSendPart.Image(image(f, owner)))
            fun attempt() = ControllerImageAttempt(owner, 1, parts, provider(f),
                ImageSendHandle(owner, 1), journal)
            val original = attempt()
            assertNull(original.reserve(f.db))
            val user = original.prepare(f.controller.imageAttachments.store,
                MessageImageAssetStore(File(f.app.filesDir, "time-assets")), time)
            assertEquals(time, user.createdAt)
            val assistant = Message(original.assistantId, conversation.id, original.runId,
                Message.ASSISTANT_ROLE, emptyList(), RunStatus.RUNNING, createdAt = time)
            f.db.send(user, assistant)
            f.db.save(assistant.copy(status = RunStatus.COMPLETED))
            // Recovery reconstructs full user equality using this original row's time, not now.
            val recovered = attempt().reserve(f.db) as ImageSendResult.Accepted
            assertEquals(original.userId, recovered.userId)
            assertEquals(original.assistantId, recovered.assistantId)
            assertEquals(listOf(time, time), f.db.messages(conversation.id).map { it.second.createdAt })
            assertEquals(user, f.db.messages(conversation.id).first().second)
        }
        assertEquals(0, f.probe.opens.get())
    }

    fun testProductionImagePairSharesFrozenTimeWithRunnerAndStreamingCompletion() {
        val f = fixture(); val conversation = f.db.create("default")
        val owner = ImageAttachmentOwner(conversation.id.value, "production-time-editor")
        val attachment = image(f, owner)
        val before = System.currentTimeMillis()
        val receipt = submit(f, conversation, owner, 1,
            listOf(ImageSendPart.Text("body"), ImageSendPart.Image(attachment))) as ImageSendResult.Accepted
        val after = System.currentTimeMillis()
        await(f) { f.controller.active == null }
        val rows = f.db.messages(conversation.id).map { it.second }
        val user = rows.single { it.id == receipt.userId }
        val assistant = rows.single { it.id == receipt.assistantId }
        assertTrue(checkNotNull(user.createdAt) in before..after)
        assertEquals(user.createdAt, assistant.createdAt)
        assertEquals(user, f.probe.histories.single().single { it.id == user.id })
        assertTrue(assistant.historySegments.isNotEmpty())
        assertTrue(assistant.historySegments.all { it.createdAt == assistant.createdAt })
        val replay = submit(f, conversation, owner, 1,
            listOf(ImageSendPart.Text("body"), ImageSendPart.Image(attachment))) as ImageSendResult.Accepted
        assertEquals(receipt.userId, replay.userId)
        assertEquals(1, f.probe.opens.get())
        assertEquals(rows, f.db.messages(conversation.id).map { it.second })
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

    fun testAcceptedOrderedBlocksExactReceiptAndHistoryAfterDraftRemoval() =
        acceptedOrderedBlocksExactReceiptAndHistoryAfterDraftRemoval(ProviderProtocol.RESPONSES)

    fun testChatCompletionsAcceptedOrderedBlocksExactReceiptAndHistoryAfterDraftRemoval() =
        acceptedOrderedBlocksExactReceiptAndHistoryAfterDraftRemoval(ProviderProtocol.CHAT_COMPLETIONS)

    private fun acceptedOrderedBlocksExactReceiptAndHistoryAfterDraftRemoval(protocol: ProviderProtocol) {
        val f = fixture(protocol); val conversation = f.db.create("default")
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

    fun testExplicitUnsupportedCapabilityRejectsBeforeFactoryAndKeepsDraft() {
        for (supported in listOf(false)) {
            val f = fixture(); val conversation = f.db.create("default")
            val owner = ImageAttachmentOwner(conversation.id.value, "editor")
            val image = image(f, owner); f.db.draft(conversation.id, "keep")
            f.settings.modelCatalogSnapshot()
            f.settings.modelCatalog.updateRegisteredModel(f.settings.load().model) { current ->
                val record = checkNotNull(current)
                record.copy(settings = record.settings.copy(visionOverride = supported))
            }
            val selected = runProvider(f)
            assertEquals(false, selected.imageInputSupported)
            val result = submit(f, conversation, owner, 1, listOf(ImageSendPart.Image(image)), selected)
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
            listOf(ImageSendPart.Image(image)), runProvider(f), "") { result.set(it); done.countDown() }) }
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
        assertEquals("编辑草稿已变化；保留当前草稿，未发起请求", (rejected as ImageSendResult.Rejected).reason)
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

    fun testChangedProviderCannotUseOldImageHistoryWithoutCurrentModelCatalogCapability() {
        val f = fixture(); val conversation = f.db.create("default")
        val owner = ImageAttachmentOwner(conversation.id.value, "editor")
        val image = image(f, owner)
        assertTrue(submit(f, conversation, owner, 1, listOf(ImageSendPart.Image(image))) is ImageSendResult.Accepted)
        await(f) { f.controller.active == null }
        f.settings.save(f.settings.load().copy(model = "another-exact-model"))
        f.settings.modelCatalogSnapshot()
        f.db.draft(conversation.id, "keep this text")
        f.settings.modelCatalog.updateRegisteredModel(f.settings.load().model) { current ->
            val record = checkNotNull(current)
            record.copy(settings = record.settings.copy(visionOverride = false))
        }
        assertEquals(false, f.controller.providers.freezeActive().settings.visionOverride)
        assertNull(f.controller.send(conversation, "keep this text"))
        assertEquals(1, f.probe.opens.get()); assertEquals(2, f.db.messages(conversation.id).size)
        assertEquals("keep this text", f.db.conversations().single().draft)
        for ((index, supported) in listOf<Boolean?>(null, true).withIndex()) {
            f.settings.modelCatalog.updateRegisteredModel(f.settings.load().model) { current ->
                val record = checkNotNull(current)
                record.copy(settings = record.settings.copy(visionOverride = supported))
            }
            val frozen = f.controller.providers.freezeActive().settings
            assertEquals(supported, frozen.visionOverride)
            assertEquals(true, frozen.requestSnapshot().imageInputSupported)
            assertNotNull(f.controller.send(conversation, "keep this text"))
            await(f) { f.controller.active == null }
            assertEquals(index + 2, f.probe.opens.get())
        }
    }

    fun testCloseDuringPreparationRetainsDraftAndDrainsBeforeStorageClose() {
        val f = fixture(); val conversation = f.db.create("default")
        val owner = ImageAttachmentOwner(conversation.id.value, "editor")
        val image = image(f, owner)
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val done = CountDownLatch(1)
        val result = AtomicReference<ImageSendResult>()
        f.probe.beforeFactory = { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
        main { assertNotNull(f.controller.sendImages(conversation, owner, 1,
            listOf(ImageSendPart.Image(image)), runProvider(f), "") { result.set(it); done.countDown() }) }
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
