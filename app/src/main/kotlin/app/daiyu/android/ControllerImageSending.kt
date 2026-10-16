package app.daiyu.android

import android.content.SharedPreferences
import app.daiyu.android.context.DatabaseTransactions
import app.daiyu.android.domain.*
import app.daiyu.android.media.ImageAttachment
import app.daiyu.android.media.ImageAttachmentOwner
import app.daiyu.android.media.ImageAttachmentStore
import app.daiyu.android.media.MessageImageAssetStore
import app.daiyu.android.model.ProviderSnapshot
import app.daiyu.android.model.images.FrozenMessageImagePlan
import app.daiyu.android.storage.ConversationDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

/** The host supplies an immutable editor revision, never a URI or a preview bitmap. */
internal sealed interface ImageSendPart {
    data class Text(val text: String) : ImageSendPart
    data class Image(val attachment: ImageAttachment) : ImageSendPart
}

internal sealed interface ImageSendResult {
    val owner: ImageAttachmentOwner
    val revision: Long
    /** Only this outcome authorizes removing exactly [images], never clearing a whole draft. */
    data class Accepted(override val owner: ImageAttachmentOwner, override val revision: Long,
        val conversation: Conversation, val userId: MessageId, val assistantId: MessageId,
        val runId: RunId, val images: List<ImageAttachment>) : ImageSendResult
    data class Rejected(override val owner: ImageAttachmentOwner, override val revision: Long,
        val reason: String) : ImageSendResult
    /** May have committed. Retain drafts AND published assets; do not retry the request. */
    data class Unknown(override val owner: ImageAttachmentOwner, override val revision: Long,
        val userId: MessageId?, val assistantId: MessageId?) : ImageSendResult
}

internal class ImageDraftChangedException : IllegalStateException("image_draft_changed")

/** cancel wins only before the DB acceptance gate. It never undoes an accepted send. */
internal class ImageSendHandle internal constructor(val owner: ImageAttachmentOwner, val revision: Long) {
    private var cancelled = false
    private var committing = false
    @Synchronized fun cancel(): Boolean {
        if (committing) return false
        cancelled = true
        return true
    }
    @Synchronized internal fun isCancelled(): Boolean = cancelled
    @Synchronized internal fun beginCommit(): Boolean {
        if (cancelled || committing) return false
        committing = true
        return true
    }
}

/** One host acceptance lane (Controller.preparing). No draft/asset deletion, even on DB errors.
 * The journal durably fixes identities BEFORE asset publication. A surviving journal can only
 * reconcile a receipt; it can never initiate a second request, including after process death.
 */
internal class ControllerImageAttempt(
    val owner: ImageAttachmentOwner,
    val revision: Long,
    parts: List<ImageSendPart>,
    val provider: ProviderSnapshot,
    val handle: ImageSendHandle,
    private val journal: SharedPreferences,
    val expectedDraftText: String = "",
) {
    val parts = parts.toList()
    val images = this.parts.filterIsInstance<ImageSendPart.Image>().map { it.attachment }
    private val ownerKey = digest("${owner.conversationId.length}:${owner.conversationId}${owner.draftId}")
    private val key = "$ownerKey:$revision"
    private val fingerprint = digest(expectedDraftText + "\u0000" + JSONArray().also { array -> this.parts.forEach { part ->
        array.put(when (part) {
            is ImageSendPart.Text -> JSONObject().put("text", part.text)
            is ImageSendPart.Image -> JSONObject().put("image", part.attachment.let {
                JSONObject().put("owner", it.owner.conversationId).put("draft", it.owner.draftId)
                    .put("id", it.id).put("mime", it.mime).put("bytes", it.bytes)
                    .put("sha256", it.sha256).put("width", it.width).put("height", it.height)
            })
        })
    } }.toString())
    var userId = MessageId(UUID.randomUUID().toString()); private set
    var assistantId = MessageId(UUID.randomUUID().toString()); private set
    var runId = RunId(UUID.randomUUID().toString()); private set
    var journaled = false; private set
    var commitAttempted = false; private set
    var result: ImageSendResult? = null

    init {
        require(revision >= 0 && images.isNotEmpty())
        require(images.all { it.owner == owner })
        require(images.map { it.id }.distinct().size == images.size)
    }

    /** null means a NEW durable identity. Non-null means replay: no preparation or network. */
    fun reserve(database: ConversationDatabase): ImageSendResult? {
        journal.getString(key, null)?.let { encoded ->
            val saved = JSONObject(encoded)
            // Another revision of an unresolved owner cannot bypass the no-replay fence.
            if (saved.getLong("revision") != revision || saved.getString("fingerprint") != fingerprint) {
                return ImageSendResult.Unknown(owner, revision, null, null)
            }
            userId = MessageId(saved.getString("user")); assistantId = MessageId(saved.getString("assistant"))
            runId = RunId(saved.getString("run"))
            return reconcile(database)
        }
        // Keep accepted revision tombstones so a delayed old editor cannot send again. A
        // different revision cannot bypass an unresolved outcome for this same draft owner.
        if (journal.all.any { (otherKey, value) -> otherKey.startsWith("$ownerKey:") &&
                !JSONObject(value as String).optBoolean("confirmed", false) }) {
            return ImageSendResult.Unknown(owner, revision, null, null)
        }
        val value = JSONObject().put("revision", revision).put("fingerprint", fingerprint)
            .put("confirmed", false)
            .put("user", userId.value).put("assistant", assistantId.value).put("run", runId.value)
        check(journal.edit().putString(key, value.toString()).commit()) { "image_identity_not_durable" }
        journaled = true
        return null
    }

    fun prepare(store: ImageAttachmentStore, assets: MessageImageAssetStore): Message {
        val frozen = store.freeze(owner, images, provider.imageInputSupported == true, handle::isCancelled)
        val refs = assets.prepare(app.daiyu.android.media.MessageImageOwner(owner.conversationId, userId.value),
            owner, frozen, handle::isCancelled)
        return user(refs.associateBy { it.id })
    }

    private fun user(refs: Map<String, app.daiyu.android.media.MessageImageReference>): Message =
        Message(userId, ConversationId(owner.conversationId), runId, Message.USER_ROLE,
            parts.mapIndexed { index, part -> when (part) {
                is ImageSendPart.Text -> MessageBlock.Text("user-$index", part.text)
                is ImageSendPart.Image -> refs.getValue(part.attachment.id).let { ref ->
                    MessageBlock.Image(MessageImageReference(MessageImageOwner(ref.owner.conversationId,
                        ref.owner.messageId), ref.id, ref.mime, ref.bytes, ref.sha256, ref.width, ref.height))
                }
            } }, RunStatus.COMPLETED)

    private fun expectedUser(): Message = user(images.associate { image -> image.id to
        app.daiyu.android.media.MessageImageReference(
            app.daiyu.android.media.MessageImageOwner(owner.conversationId, userId.value), image.id,
            image.mime, image.bytes, image.sha256, image.width, image.height) })

    private fun reconcile(database: ConversationDatabase): ImageSendResult = try {
        DatabaseTransactions { database.readableDatabase }.run { db ->
            val user = database.storedMessage(db, ConversationId(owner.conversationId), userId.value)
            val assistant = database.storedMessage(db, user.conversationId, assistantId.value)
            check(user == expectedUser() && assistant.runId == runId && assistant.role == Message.ASSISTANT_ROLE)
            val conversation = db.rawQuery("SELECT title,draft,updated,folder,pinned FROM conversations WHERE id=?",
                arrayOf(owner.conversationId)).use {
                check(it.moveToFirst())
                Conversation(user.conversationId, it.getString(0), it.getString(1), it.getLong(2), it.getString(3), it.getInt(4) != 0)
            }
            accepted(conversation)
        }
    } catch (_: Exception) { unknown() }

    fun beginCommit(): Boolean {
        if (!handle.beginCommit()) return false
        commitAttempted = true
        return true
    }
    fun accepted(conversation: Conversation): ImageSendResult.Accepted = ImageSendResult.Accepted(owner,
        revision, conversation, userId, assistantId, runId, images.toList()).also {
        result = it
        // The SQLite receipt is authoritative even if this advisory tombstone update fails.
        try { journal.getString(key, null)?.let { encoded ->
            journal.edit().putString(key, JSONObject(encoded).put("confirmed", true).toString()).commit()
        } } catch (_:Exception) { }
    }
    fun unknown(): ImageSendResult.Unknown = ImageSendResult.Unknown(owner, revision, userId, assistantId)
        .also { result = it }

    fun draftChanged(): ImageSendResult {
        // Called only for our own exception before database.send was invoked.
        commitAttempted = false
        return rejected("编辑草稿已变化；保留当前草稿，未发起请求")
    }

    fun rejected(reason: String): ImageSendResult {
        if (commitAttempted) return unknown()
        // The DB gate was never entered. Published assets are deliberately retained (no guessed GC).
        // If journal removal fails, subsequent attempts stay conservatively fenced as Unknown.
        if (journaled) journal.edit().remove(key).commit()
        return ImageSendResult.Rejected(owner, revision, reason).also { result = it }
    }

    companion object {
        private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}

/** Read-only model adapter. The reference itself is NEVER used as an authorization source.
 * Every open (including resumed Runner sessions) resolves against an independent SQLite snapshot
 * of persisted rows and their validated history segments. The one pre-accept candidate is only
 * admitted during preflight, and is not retained as an authorization shortcut in this adapter.
 */
internal class ControllerMessageImages(private val database: ConversationDatabase,
    private val assets: MessageImageAssetStore) {
    fun freeze(history: List<Message>, provider: ProviderSnapshot, candidate: Message? = null,
        cancelled: () -> Boolean = { false }): FrozenMessageImagePlan {
        val wanted = MessageHistory.expand(history).filter { row -> row.blocks.any { it is MessageBlock.Image } }
        val authorized = DatabaseTransactions { database.readableDatabase }.run { db ->
            val rows = wanted.map { it.conversationId }.distinct().flatMap { conversation ->
                check(db.rawQuery("SELECT 1 FROM conversations WHERE id=?", arrayOf(conversation.value)).use { it.moveToFirst() } ||
                    candidate?.conversationId == conversation) { "image_conversation_missing" }
                val ids = db.rawQuery("SELECT id FROM messages WHERE conversation_id=? ORDER BY seq",
                    arrayOf(conversation.value)).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
                MessageHistory.expand(ids.map { database.storedMessage(db, conversation, it) })
            }
            wanted.associateWith { row ->
                check(rows.any { it == row } || candidate == row) { "image_row_not_authorized" }
                MessageImageOwner(row.conversationId.value, row.id.value)
            }
        }
        return FrozenMessageImagePlan.freeze(history, provider, assets.resolver,
            { row -> checkNotNull(authorized[row]) { "image_owner_not_authorized" } }, isCancelled = cancelled)
    }

    /** History preview capability, scoped by a host-selected conversation. Revalidate the
     * persisted row/segment and exact reference before every read; no draft or URI fallback. */
    fun resolver(conversation: ConversationId): app.daiyu.android.media.MessageImageResolver =
        object : app.daiyu.android.media.MessageImageResolver {
            private fun authorize(owner: app.daiyu.android.media.MessageImageOwner,
                references: List<app.daiyu.android.media.MessageImageReference>) {
                require(owner.conversationId == conversation.value)
                DatabaseTransactions { database.readableDatabase }.run { db ->
                    check(db.rawQuery("SELECT 1 FROM conversations WHERE id=?", arrayOf(conversation.value)).use { it.moveToFirst() })
                    val ids = db.rawQuery("SELECT id FROM messages WHERE conversation_id=? ORDER BY seq",
                        arrayOf(conversation.value)).use { c -> buildList { while(c.moveToNext()) add(c.getString(0)) } }
                    val row = MessageHistory.expand(ids.map { database.storedMessage(db, conversation, it) })
                        .single { it.id.value == owner.messageId && it.role == Message.USER_ROLE }
                    val allowed = row.blocks.filterIsInstance<MessageBlock.Image>().map { it.reference }
                    require(references.all { ref -> ref.owner == owner && allowed.any {
                        it.owner == MessageImageOwner(owner.conversationId, owner.messageId) &&
                            it.id == ref.id && it.mime == ref.mime && it.bytes == ref.bytes &&
                            it.sha256 == ref.sha256 && it.width == ref.width && it.height == ref.height
                    } })
                }
            }
            override fun read(owner: app.daiyu.android.media.MessageImageOwner,
                reference: app.daiyu.android.media.MessageImageReference): ByteArray {
                authorize(owner, listOf(reference))
                return assets.resolver.read(owner, reference)
            }
            override fun freeze(owner: app.daiyu.android.media.MessageImageOwner,
                references: List<app.daiyu.android.media.MessageImageReference>, imageInputSupported: Boolean,
                isCancelled: () -> Boolean): List<app.daiyu.android.media.FrozenImageAttachment> {
                authorize(owner, references)
                return assets.resolver.freeze(owner, references, imageInputSupported, isCancelled)
            }
        }

    fun wrap(delegate: ModelPort, provider: ProviderSnapshot,
        conversation: ConversationId? = null): ModelPort = object : ModelPort {
        private fun plan(history: List<Message>): FrozenMessageImagePlan {
            require(conversation == null || MessageHistory.expand(history).all { it.conversationId == conversation }) {
                "model_history_conversation_mismatch"
            }
            return freeze(history, provider)
        }
        override fun open(history: List<Message>): ModelSession {
            val plan = plan(history)
            return if (plan.hasImages) delegate.open(history, emptyList<ToolDefinition>(), plan) else delegate.open(history)
        }
        override fun open(history: List<Message>, tools: Set<ReadOnlyTool>): ModelSession {
            val plan = plan(history)
            return if (plan.hasImages) delegate.open(history, tools.map {
                ToolDefinition(it.toolName, it.description, "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}")
            }, plan)
                else delegate.open(history, tools)
        }
        override fun open(history: List<Message>, definitions: List<ToolDefinition>): ModelSession {
            val plan = plan(history)
            return if (plan.hasImages) delegate.open(history, definitions, plan) else delegate.open(history, definitions)
        }
    }
}
