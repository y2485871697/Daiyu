package app.daiyu.android

import android.test.InstrumentationTestCase
import app.daiyu.android.domain.*
import app.daiyu.android.media.video.*
import app.daiyu.android.model.videos.MessageVideoInput
import app.daiyu.android.storage.ConversationDatabase
import app.daiyu.android.ui.screens.chat.conversation.ChatTurnMapper
import app.daiyu.android.ui.screens.chat.conversation.ChatTurnUi
import app.daiyu.android.ui.screens.chat.conversation.MessageContentUi
import java.io.File
import java.util.UUID

/** Real SQLite/witness + asset-fd integration. Deliberately fake video bytes/inspector: this is
 * evidence of authorization and exact download bytes, not Android playback/codec coverage.
 */
@Suppress("DEPRECATION")
class MessageMediaOriginalProjectionTest : InstrumentationTestCase() {
    private lateinit var context: android.content.Context
    private lateinit var database: ConversationDatabase
    private lateinit var root: File
    private lateinit var assets: VideoAssetStore
    private lateinit var media: ControllerMessageMedia
    private lateinit var user: Message
    private lateinit var reference: MessageVideoReference
    private val bytes = ByteArray(12000) { (it % 239).toByte() }

    override fun setUp() {
        super.setUp()
        context = testContext(instrumentation.targetContext, "media-preview-${UUID.randomUUID()}-")
        database = ConversationDatabase(context)
        root = File(context.cacheDir, "media-preview-${UUID.randomUUID()}")
        assets = VideoAssetStore(root, VideoAttachmentLimits(), VideoInspector { _, _, _ ->
            VideoMetadata("video/mp4", 1000, 32, 24)
        })
        val conversation = database.create(database.selectedAssistantId()).id
        user = Message(MessageId("user"), conversation, RunId("run"), Message.USER_ROLE,
            listOf(MessageBlock.Text("body", "video caption")), RunStatus.COMPLETED)
        val owner = MessageVideoOwner(conversation.value, user.id.value)
        val draft = assets.import(VideoAttachmentOwner(conversation.value, "draft"), bytes.inputStream())
        reference = assets.prepare(owner, draft)
        database.send(user, user.copy(id = MessageId("assistant"), role = Message.ASSISTANT_ROLE,
            blocks = emptyList(), status = RunStatus.RUNNING), videoInputs = listOf(MessageVideoInput(owner, listOf(reference))))
        media = ControllerMessageMedia(database, assets)
    }

    override fun tearDown() {
        try {
            database.close()
            context.deleteDatabase("conversations.db")
            context.getSharedPreferences("daiyu-assistants", 0).edit().clear().commit()
            root.deleteRecursively()
        } finally { super.tearDown() }
    }

    fun testProjectionAndDownloadUsePhysicalSidecarOwnerAndExactOriginalBytes() {
        val snapshot = media.capture(user.conversationId) { false }
        val turn = ChatTurnMapper.map(listOf(user), messageVideos = snapshot::videos).single() as ChatTurnUi.User
        val video = turn.content.filterIsInstance<MessageContentUi.Video>().single().video
        assertEquals(reference.owner, video.expectedOwner)
        val binding = checkNotNull(snapshot.binding(video))
        assertEquals(user.id.value, binding.physicalMessageId)
        media.openOriginal(user.conversationId, binding) { false }.use { stream ->
            assertEquals(0L, stream.channel.position())
            assertTrue(bytes.contentEquals(stream.readBytes()))
        }
        rejects { media.openOriginal(ConversationId("another-conversation"), binding) { false }.close() }
    }

    fun testSaveRechecksWitnessAfterGalleryProjectionAndMissingSidecarNeverBecomesTextOnlyGrant() {
        val snapshot = media.capture(user.conversationId) { false }
        val binding = checkNotNull(snapshot.binding(snapshot.videos(user).single()))
        database.writableDatabase.delete("message_video_inputs", "message_id=?", arrayOf(user.id.value))
        rejects { media.openOriginal(user.conversationId, binding) { false }.close() }
        rejects { media.capture(user.conversationId) { false } }
    }

    fun testOriginalMutationAndCancellationCannotProduceAnOpenDownload() {
        val snapshot = media.capture(user.conversationId) { false }
        val binding = checkNotNull(snapshot.binding(snapshot.videos(user).single()))
        rejects { media.openOriginal(user.conversationId, binding) { true }.close() }
        val original = File(File(File(root, reference.owner.directoryName()), reference.id), "original.video")
        original.writeBytes(ByteArray(bytes.size) { 7 })
        rejects { media.openOriginal(user.conversationId, binding) { false }.close() }
    }

    fun testViewerDisplayCanExceedThumbnailLimitWithoutChangingOriginalBytes() {
        val bitmap = android.graphics.Bitmap.createBitmap(3072, 1536, android.graphics.Bitmap.Config.ARGB_8888)
        val buffer = java.io.ByteArrayOutputStream()
        try { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, buffer)) }
        finally { bitmap.recycle() }
        val original = buffer.toByteArray()
        val owner = app.daiyu.android.media.MessageImageOwner("conversation", "image-user")
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(original)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        val reference = app.daiyu.android.media.MessageImageReference(owner, UUID.randomUUID().toString(),
            "image/png", original.size, digest, 3072, 1536)
        val resolver = object : app.daiyu.android.media.MessageImageResolver {
            override fun read(expected: app.daiyu.android.media.MessageImageOwner,
                ref: app.daiyu.android.media.MessageImageReference): ByteArray {
                check(expected == owner && ref == reference)
                return original.copyOf()
            }
            override fun freeze(expected: app.daiyu.android.media.MessageImageOwner,
                refs: List<app.daiyu.android.media.MessageImageReference>, imageInputSupported: Boolean,
                isCancelled: () -> Boolean): List<app.daiyu.android.media.FrozenImageAttachment> =
                error("Viewer must not acquire upload authority")
        }
        val display = decodeMessageMediaDisplay(resolver, owner, reference, 4096)
        try {
            assertEquals(3072, display.width)
            assertEquals(1536, display.height)
            assertTrue(original.contentEquals(resolver.read(owner, reference)))
        } finally { display.recycle() }
    }

    private fun rejects(action: () -> Unit) {
        try { action() } catch (_: Exception) { return }
        fail("Expected media authorization/verification rejection")
    }
}
