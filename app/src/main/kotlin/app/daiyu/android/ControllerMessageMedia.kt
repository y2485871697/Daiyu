package app.daiyu.android

import app.daiyu.android.context.DatabaseTransactions
import app.daiyu.android.domain.ConversationId
import app.daiyu.android.domain.Message
import app.daiyu.android.media.video.MessageVideoOwner
import app.daiyu.android.media.video.MessageVideoReference
import app.daiyu.android.media.video.VideoAssetStore
import app.daiyu.android.storage.ConversationDatabase
import app.daiyu.android.storage.SQLiteMessageVideoStore
import app.daiyu.android.ui.screens.chat.conversation.MessageVideoUi
import java.io.FileInputStream

/** The physical row is captured on IO, never recovered from a projected turn/list key. */
internal data class MessageMediaVideoBinding(
    val physicalMessageId: String,
    val runId: String,
    val video: MessageVideoUi,
)

internal class ControllerMessageMediaSnapshot internal constructor(
    val conversation: ConversationId,
    bindings: List<MessageMediaVideoBinding>,
    images: Set<app.daiyu.android.ui.screens.chat.conversation.MessageImageUi> = emptySet(),
) {
    private val bindings = bindings.toList()
    private val images = images.toSet()
    fun containsImage(image: app.daiyu.android.ui.screens.chat.conversation.MessageImageUi): Boolean = image in images
    fun videos(message: Message): List<MessageVideoUi> = bindings.filter {
        it.runId == message.runId.value && it.video.expectedOwner ==
            MessageVideoOwner(message.conversationId.value, message.id.value)
    }.map { it.video }
    fun binding(video: MessageVideoUi): MessageMediaVideoBinding? =
        bindings.singleOrNull { it.video == video }
}

/** No writes, import, provider grant, generation identity or private path escapes this adapter. */
internal class ControllerMessageMedia(
    private val database: ConversationDatabase,
    private val assets: VideoAssetStore,
    private val imageResolver: app.daiyu.android.media.MessageImageResolver? = null,
) {
    fun capture(conversation: ConversationId, cancelled: () -> Boolean): ControllerMessageMediaSnapshot =
        DatabaseTransactions { database.readableDatabase }.run { db ->
            val ids = db.rawQuery("SELECT id FROM messages WHERE conversation_id=? ORDER BY seq",
                arrayOf(conversation.value)).use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
            }
            val images = mutableSetOf<app.daiyu.android.ui.screens.chat.conversation.MessageImageUi>()
            val bindings = ids.flatMap { id ->
                check(!cancelled()) { "media_projection_cancelled" }
                val row = database.storedMessage(db, conversation, id)
                app.daiyu.android.domain.MessageHistory.expand(listOf(row)).forEach { source ->
                    val owner = app.daiyu.android.domain.MessageImageOwner(source.conversationId.value, source.id.value)
                    source.blocks.filterIsInstance<app.daiyu.android.domain.MessageBlock.Image>().forEach { block ->
                        val image = app.daiyu.android.ui.screens.chat.conversation.MessageImageUi(owner, block.reference)
                        val resolver = imageResolver
                        if (resolver != null && image !in images && owner == block.reference.owner) {
                            val reference = block.reference
                            val mediaOwner = app.daiyu.android.media.MessageImageOwner(owner.conversationId, owner.messageId)
                            // Read authority checks the persisted owner, manifest, digest and original bytes.
                            resolver.read(mediaOwner, app.daiyu.android.media.MessageImageReference(mediaOwner,
                                reference.id, reference.mime, reference.bytes, reference.sha256, reference.width, reference.height))
                            check(!cancelled()) { "media_projection_cancelled" }
                            images += image
                        }
                    }
                }
                SQLiteMessageVideoStore.readVerified(db, row).flatMap { input ->
                    input.references.map { reference ->
                        assets.openOriginal(input.owner, reference, cancelled).use { }
                        MessageMediaVideoBinding(id, row.runId.value, MessageVideoUi(input.owner, reference))
                    }
                }
            }
            check(bindings.map { it.video.expectedOwner to it.video.reference.id }.distinct().size == bindings.size)
            ControllerMessageMediaSnapshot(conversation, bindings, images)
        }

    /** Revalidate the physical sidecar witness at EVERY open, including a later save. The returned
     * stream is the exact read-only fd hashed/rewound by VideoAssetStore, not a thumbnail/reopen.
     */
    fun openOriginal(conversation: ConversationId, binding: MessageMediaVideoBinding,
        cancelled: () -> Boolean): FileInputStream {
        var opened: FileInputStream? = null
        try {
            return DatabaseTransactions { database.readableDatabase }.run { db ->
            val video = binding.video
            require(video.expectedOwner.conversationId == conversation.value && video.reference.owner == video.expectedOwner)
            val row = database.storedMessage(db, conversation, binding.physicalMessageId)
            check(row.runId.value == binding.runId)
            val inputs = SQLiteMessageVideoStore.readVerified(db, row)
            check(inputs.singleOrNull { it.owner == video.expectedOwner }?.references?.contains(video.reference) == true) {
                "media_original_owner_changed"
            }
            assets.openOriginal(video.expectedOwner, video.reference, cancelled).also { opened = it }
            }
        } catch (failure: Throwable) {
            try { opened?.close() } catch (close: Exception) { failure.addSuppressed(close) }
            throw failure
        }
    }
}
