package app.daiyu.android

import app.daiyu.android.context.ContextService
import app.daiyu.android.domain.*
import app.daiyu.android.media.ImageAttachmentOwner
import app.daiyu.android.media.ImageAttachmentStore
import app.daiyu.android.media.AndroidImageInspector
import app.daiyu.android.media.MessageImageAssetStore
import app.daiyu.android.media.MessageImageOwner as AssetOwner
import app.daiyu.android.media.MessageImageReference as AssetReference
import app.daiyu.android.storage.ConversationDatabase
import app.daiyu.android.storage.MessageActionStore
import app.daiyu.android.storage.MessageActionTarget
import java.io.File
import java.nio.file.Files
import java.util.Base64
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Real SQLite selection authorization with a decodable PNG fixture; no network or controller. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class EditingImageSelectionStoreTest {
    private lateinit var root: File
    private lateinit var db: ConversationDatabase
    private lateinit var drafts: ImageAttachmentStore
    private lateinit var assets: MessageImageAssetStore
    private lateinit var store: MessageActionStore
    private lateinit var user: Message
    private lateinit var target: MessageActionTarget
    private val bytes = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAIAAAADCAYAAAC56t6BAAAAC0lEQVR4nGNgwAkAABsAAco8Sg0AAAAASUVORK5CYII=",
    )
    private val refs get() = user.blocks.filterIsInstance<MessageBlock.Image>().map { it.reference }

    @Before fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("conversations.db")
        root = Files.createTempDirectory("editing-image-selection").toFile()
        db = ConversationDatabase(context)
        drafts = ImageAttachmentStore(File(root, "drafts"), AndroidImageInspector())
        assets = MessageImageAssetStore(File(root, "images"))
        val conversation = db.create("default")
        val id = MessageId(UUID.randomUUID().toString())
        val owner = ImageAttachmentOwner(conversation.id.value, "seed")
        val imported = List(3) { drafts.import(owner, bytes.inputStream()) }
        val originals = assets.prepare(AssetOwner(conversation.id.value, id.value), owner,
            drafts.freeze(owner, imported, true))
        user = Message(id, conversation.id, RunId(UUID.randomUUID().toString()), Message.USER_ROLE,
            listOf(MessageBlock.Text("text", "original")) + originals.map {
                MessageBlock.Image(MessageImageReference(MessageImageOwner(it.owner.conversationId, it.owner.messageId),
                    it.id, it.mime, it.bytes, it.sha256, it.width, it.height))
            }, RunStatus.COMPLETED)
        val assistant = user.copy(id = MessageId(UUID.randomUUID().toString()), role = Message.ASSISTANT_ROLE,
            blocks = listOf(MessageBlock.Text("answer", "answer")), status = RunStatus.RUNNING)
        db.send(user, assistant); db.save(assistant.copy(status = RunStatus.COMPLETED))
        db.draft(conversation.id, "ordinary unsent draft")
        store = MessageActionStore(db, ContextService(context, db), assets, draftImages = drafts)
        target = store.snapshot(conversation.id).targets.getValue(id.value)
    }

    @After fun tearDown() {
        db.close(); RuntimeEnvironment.getApplication().deleteDatabase("conversations.db"); root.deleteRecursively()
    }
    private fun selection(value: List<MessageImageReference>?) =
        store.prepareRegeneration(target, "edited", retainedImages = value).user.blocks.filterIsInstance<MessageBlock.Image>()
    private fun rejected(block: () -> Unit) {
        assertNotNull(runCatching(block).exceptionOrNull())
    }
    private fun assertUntouched() {
        assertEquals("ordinary unsent draft", db.conversations().single().draft)
        refs.forEach {
            val owner = AssetOwner(it.owner.conversationId, it.owner.messageId)
            assertArrayEquals(bytes, assets.resolver.read(owner, AssetReference(owner, it.id, it.mime,
                it.bytes, it.sha256, it.width, it.height)))
        }
    }

    @Test fun nullSubsetAndEmptyRetainOnlyOriginalBlocksWithoutMutatingAssets() {
        assertEquals(refs, selection(null).map { it.reference })
        val kept = selection(listOf(refs[1]))
        assertEquals(listOf(refs[1]), kept.map { it.reference })
        assertTrue(selection(emptyList()).isEmpty())
        assertUntouched()
    }
    @Test fun forgedMetadataForeignOwnerDuplicatesAndReorderingFailClosed() {
        val ref = refs.first()
        listOf(listOf(ref.copy(bytes = ref.bytes + 1)), listOf(ref.copy(sha256 = "b".repeat(64))),
            listOf(ref.copy(owner = MessageImageOwner("other", "other"))),
            listOf(ref, ref), refs.reversed()).forEach { requested -> rejected { selection(requested) } }
        assertUntouched()
    }
    @Test fun emptyImageOnlyEditIsRejectedButRemainingImageCanSubmit() {
        rejected { store.prepareRegeneration(target, "", retainedImages = emptyList()) }
        assertEquals(1, store.prepareRegeneration(target, "", retainedImages = listOf(refs[0]))
            .user.blocks.filterIsInstance<MessageBlock.Image>().size)
        assertUntouched()
    }
    @Test fun removingOriginalReleasesCapacityForAddedImagesAndUsesNewOwner() {
        val owner = ImageAttachmentOwner(user.conversationId.value, "edit")
        val added = List(6) { drafts.import(owner, bytes.inputStream()) }
        rejected { store.prepareRegeneration(target, "edited", added, null) }
        val edited = store.prepareRegeneration(target, "edited", added, listOf(refs[0])).user
        val selected = edited.blocks.filterIsInstance<MessageBlock.Image>().map { it.reference }
        assertEquals(7, selected.size)
        assertNotEquals(user.id, edited.id)
        assertTrue(selected.all { it.owner.messageId == edited.id.value })
        assertUntouched()
    }
}
