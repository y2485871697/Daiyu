package app.daiyu.android

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.test.InstrumentationTestCase
import app.daiyu.android.context.ContextService
import app.daiyu.android.domain.*
import app.daiyu.android.media.AndroidImageInspector
import app.daiyu.android.media.ImageAttachmentOwner
import app.daiyu.android.media.ImageAttachmentStore
import app.daiyu.android.media.MessageImageAssetStore
import app.daiyu.android.media.MessageImageOwner as MediaOwner
import app.daiyu.android.media.MessageImageReference as MediaReference
import app.daiyu.android.storage.ConversationDatabase
import app.daiyu.android.storage.MessageActionStore
import app.daiyu.android.storage.MessageActionTarget
import app.daiyu.android.storage.ModelSettings
import app.daiyu.android.storage.ModelSettingsStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*

/** Real SQLite authorization/filtering and controller parameter wiring; no external transport. */
@Suppress("DEPRECATION")
class EditingAttachmentSelectionTest : InstrumentationTestCase() {
    private class App(base: Context) : Application() {
        val root = File(base.cacheDir, "editing-selection-${UUID.randomUUID()}").apply { mkdirs() }
        val balances = fixtureProviderBalances()
        init { attachBaseContext(base) }
        override fun getFilesDir() = root
        override fun getApplicationContext(): Context = this
    }
    private data class Fixture(val app: App, val db: ConversationDatabase, val conversation: Conversation,
        val user: Message, val assets: MessageImageAssetStore, val drafts: ImageAttachmentStore,
        val store: MessageActionStore, val target: MessageActionTarget, val bytes: ByteArray) {
        val refs get() = user.blocks.filterIsInstance<MessageBlock.Image>().map { it.reference }
        fun history() = ContextService.read(db.readableDatabase, conversation.id)
    }
    private val fixtures = mutableListOf<Fixture>()
    private val controllers = mutableListOf<ChatController>()
    private val aliases = mutableListOf<String>()
    private fun fresh(count: Int = 3): Fixture {
        val base = testContext(instrumentation.targetContext, "editing-selection-${UUID.randomUUID()}-")
        val app = App(base)
        val db = ConversationDatabase(app)
        val conversation = db.create("default")
        val assets = MessageImageAssetStore(File(app.filesDir, "original-images"))
        val drafts = ImageAttachmentStore(File(app.filesDir, "added-images"), AndroidImageInspector())
        val bitmap = Bitmap.createBitmap(2, 3, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        bitmap.recycle()
        val id = MessageId(UUID.randomUUID().toString())
        val draftOwner = ImageAttachmentOwner(conversation.id.value, "seed")
        val imported = List(count) { drafts.import(draftOwner, bytes.inputStream()) }
        val media = assets.prepare(MediaOwner(conversation.id.value, id.value), draftOwner,
            drafts.freeze(draftOwner, imported, true))
        val blocks = listOf(MessageBlock.Text("text", "original")) + media.map {
            MessageBlock.Image(MessageImageReference(MessageImageOwner(it.owner.conversationId, it.owner.messageId),
                it.id, it.mime, it.bytes, it.sha256, it.width, it.height))
        }
        val user = Message(id, conversation.id, RunId(UUID.randomUUID().toString()), Message.USER_ROLE, blocks, RunStatus.COMPLETED)
        val assistant = user.copy(id = MessageId(UUID.randomUUID().toString()), role = Message.ASSISTANT_ROLE,
            blocks = listOf(MessageBlock.Text("answer", "old answer")), status = RunStatus.RUNNING)
        db.send(user, assistant); db.save(assistant.copy(status = RunStatus.COMPLETED))
        db.draft(conversation.id, "untouched ordinary draft")
        val store = MessageActionStore(db, ContextService(app, db), assets, draftImages = drafts)
        return Fixture(app, db, conversation, user, assets, drafts, store,
            store.snapshot(conversation.id).targets.getValue(id.value), bytes).also { fixtures.add(it) }
    }
    private fun assertOriginalAssets(f: Fixture) {
        f.refs.forEach { ref ->
            val media = MediaReference(MediaOwner(ref.owner.conversationId, ref.owner.messageId), ref.id,
                ref.mime, ref.bytes, ref.sha256, ref.width, ref.height)
            assertTrue(f.bytes.contentEquals(f.assets.resolver.read(media.owner, media)))
        }
    }
    private fun assertUntouched(f: Fixture, before: List<Message>) {
        assertEquals(before, f.history())
        assertEquals("untouched ordinary draft", f.db.conversations().single().draft)
        assertOriginalAssets(f)
    }

    fun testNullSubsetAndEmptyAreDistinctAndPreparationDoesNotMutateHistory() {
        val f = fresh(); val before = f.history()
        val all = f.store.prepareRegeneration(f.target, "edited")
        assertEquals(f.refs, all.user.blocks.filterIsInstance<MessageBlock.Image>().map { it.reference })
        val subset = f.store.prepareRegeneration(f.target, "", retainedImages = listOf(f.refs[0], f.refs[2]))
        assertEquals(listOf(f.user.blocks[1], f.user.blocks[3]), subset.user.blocks)
        assertEquals(f.user.id, subset.user.id)
        val none = f.store.prepareRegeneration(f.target, "text only", retainedImages = emptyList())
        assertTrue(none.user.blocks.none { it is MessageBlock.Image })
        assertTrue(runCatching { f.store.prepareRegeneration(f.target, "", retainedImages = emptyList()) }.isFailure)
        assertUntouched(f, before)
    }

    fun testForgedMetadataIdentityDuplicatesAndReorderingFailClosed() {
        val f = fresh(); val before = f.history(); val first = f.refs.first()
        val invalid = listOf(
            listOf(first.copy(owner = first.owner.copy(messageId = "foreign-message"))),
            listOf(first.copy(owner = first.owner.copy(conversationId = "foreign-conversation"))),
            listOf(first.copy(id = UUID.randomUUID().toString())),
            listOf(first.copy(width = first.width + 1)),
            listOf(first.copy(height = first.height + 1)),
            listOf(first.copy(bytes = first.bytes + 1)),
            listOf(first.copy(mime = "image/jpeg")),
            listOf(first.copy(sha256 = "0".repeat(64))),
            listOf(first, first), listOf(f.refs[2], first))
        invalid.forEach { retained ->
            assertTrue(runCatching { f.store.prepareRegeneration(f.target, "edited", retainedImages = retained) }.isFailure)
            assertUntouched(f, before)
        }
        val other = fresh()
        assertTrue(runCatching { f.store.prepareRegeneration(f.target, "edited", retainedImages = other.refs) }.isFailure)
        val assistant = f.store.snapshot(f.conversation.id).targets.values.single { it.role == Message.ASSISTANT_ROLE }
        assertTrue(runCatching { f.store.prepareRegeneration(assistant, retainedImages = emptyList()) }.isFailure)
        val row = before.last()
        f.db.save(row.copy(blocks = listOf(MessageBlock.Text("changed", "revision changed"))))
        val changed = f.history()
        assertTrue(runCatching { f.store.prepareRegeneration(f.target, "edited", retainedImages = listOf(first)) }.isFailure)
        assertUntouched(f, changed)
    }

    fun testRemovingOriginalReleasesEightImageCapacityAndCopiesOnlyRetainedAtSave() {
        val f = fresh(8); val before = f.history()
        val owner = ImageAttachmentOwner(f.conversation.id.value, "edit-visit")
        val added = f.drafts.import(owner, f.bytes.inputStream())
        assertTrue(runCatching { f.store.prepareRegeneration(f.target, "edited", listOf(added)) }.isFailure)
        val kept = f.refs.drop(1)
        val revision = f.store.prepareRegeneration(f.target, "", listOf(added), retainedImages = kept)
        val refs = revision.user.blocks.filterIsInstance<MessageBlock.Image>().map { it.reference }
        assertEquals(kept.map { it.id } + added.id, refs.map { it.id })
        assertTrue(revision.user.id != f.user.id)
        refs.forEach { ref ->
            assertEquals(revision.user.id.value, ref.owner.messageId)
            val media = MediaReference(MediaOwner(ref.owner.conversationId, ref.owner.messageId), ref.id,
                ref.mime, ref.bytes, ref.sha256, ref.width, ref.height)
            assertTrue(f.bytes.contentEquals(f.assets.resolver.read(media.owner, media)))
        }
        assertTrue(f.bytes.contentEquals(f.drafts.read(owner, added)))
        assertUntouched(f, before)
    }

    fun testDraftCapacityTracksRemainingOriginalsAndRejectsLateOversizedPicker() {
        val f = fresh(8)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val reports = mutableListOf<String>()
        val draft = MessageEditImageDraft(f.conversation.id.value, 8,
            app.daiyu.android.media.AndroidImageAttachments(f.app.contentResolver, f.drafts),
            scope, {}, { reports.add(it) })
        try {
            main {
                assertNull(draft.select())
                draft.setRetainedImageCount(7)
                val request = requireNotNull(draft.select())
                // A returned picker is revalidated with the current count, not its admission count.
                draft.setRetainedImageCount(8)
                draft.picked(request, listOf(android.net.Uri.parse("content://unused/not-imported")))
                assertNull(draft.attachments.state.value.selection)
                assertTrue(draft.attachments.state.value.items.isEmpty())
                draft.setRetainedImageCount(0)
                val next = requireNotNull(draft.select())
                draft.cancelSelection(next)
                assertEquals(2, reports.size)
            }
            assertOriginalAssets(f)
        } finally {
            main { draft.close() }
            runBlocking { scope.coroutineContext[Job]?.children?.toList()?.forEach { it.join() } }
            scope.cancel()
        }
    }

    private fun main(work: () -> Unit) {
        val error = AtomicReference<Throwable?>()
        instrumentation.runOnMainSync { try { work() } catch (t: Throwable) { error.set(t) } }
        error.get()?.let { throw it }
    }
    private fun await(latch: CountDownLatch) = assertTrue("controller timed out", latch.await(15, TimeUnit.SECONDS))
    private fun controller(f: Fixture, opens: AtomicInteger): ChatController {
        val alias = "app.daiyu.android.editing-selection.${UUID.randomUUID()}".also { aliases.add(it) }
        val settings = ModelSettingsStore(f.app, alias)
        settings.save(ModelSettings("https://example.invalid", "selection-fixture", "synthetic-key", false))
        val controller = ChatController(f.app, f.db, settings, f.app.getSharedPreferences("conversation-ui", 0), { _ ->
            CatalogFixturePort { _ ->
                opens.incrementAndGet()
                object : ModelSession {
                    override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                        sink(ModelEvent.TextFinal("answer", "new answer"))
                    }
                    override fun cancel() {}
                }
            }
        }, providerBalanceHost = { f.app.balances }).also { controllers.add(it) }
        val ready = CountDownLatch(1)
        val listener: () -> Unit = { if (controller.ready) ready.countDown() }
        main { controller.observe(listener); listener() }; await(ready)
        main { controller.unobserve(listener) }
        assertNull(controller.problem)
        return controller
    }
    private fun action(controller: ChatController, target: MessageActionTarget, text: String,
        retained: List<MessageImageReference>): MessageActionResult {
        val receipt = CountDownLatch(1); val result = AtomicReference<MessageActionResult>()
        main { assertTrue(controller.messageAction(MessageAction.SAVE_EDIT, target, replacementText = text,
            retainedImages = retained) { result.set(it); receipt.countDown() }) }
        await(receipt)
        return result.get()
    }

    fun testControllerForwardsExplicitEmptyAndRejectsForgedSelectionBeforeTransport() {
        val f = fresh(); val opens = AtomicInteger(); val controller = controller(f, opens)
        val before = f.history()
        assertTrue(action(controller, f.target, "edited", listOf(f.refs[0].copy(width = 999))) is MessageActionResult.Rejected)
        assertEquals(0, opens.get()); assertUntouched(f, before)
        assertTrue(action(controller, f.target, "", emptyList()) is MessageActionResult.Rejected)
        assertEquals(0, opens.get()); assertUntouched(f, before)
        val saved = action(controller, f.target, "text only", emptyList()) as MessageActionResult.Regenerated
        assertEquals(RegenerationSendOutcome.ACCEPTED, saved.outcome)
        val user = f.history().first { it.role == Message.USER_ROLE }
        assertTrue(user.blocks.none { it is MessageBlock.Image })
        assertEquals("text only", user.blocks.filterIsInstance<MessageBlock.Text>().single().text)
        assertOriginalAssets(f)
    }

    override fun tearDown() {
        try {
            controllers.forEach { controller ->
                main { controller.stop(); controller.close() }
                val closed = ChatController::class.java.getDeclaredField("closeDone").apply { isAccessible = true }
                    .get(controller) as CountDownLatch
                await(closed)
            }
            fixtures.forEach { it.app.balances.close(); it.db.close(); it.app.deleteDatabase("conversations.db"); it.app.root.deleteRecursively() }
            val keys = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            aliases.forEach { alias ->
                java.util.Collections.list(keys.aliases()).filter { it.startsWith("$alias.") }.forEach(keys::deleteEntry)
            }
        } finally { super.tearDown() }
    }
}
