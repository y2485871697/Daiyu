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

/** Allocated once, before host persistence or enqueue. Recovery always supplies this identity. */
internal data class ImageSendIdentity(val userId: MessageId, val assistantId: MessageId, val runId: RunId) {
    companion object {
        fun create() = ImageSendIdentity(MessageId(UUID.randomUUID().toString()),
            MessageId(UUID.randomUUID().toString()), RunId(UUID.randomUUID().toString()))
    }
}

internal data class FrozenImageSend(val owner: ImageAttachmentOwner, val revision: Long,
    val text: String, val parts: List<ImageSendPart>, val identity: ImageSendIdentity)

/** cancel wins only before the DB acceptance gate. It never undoes an accepted send. */
internal class ImageSendHandle internal constructor(val owner: ImageAttachmentOwner, val revision: Long,
    private val onCancel: () -> Unit = {}) {
    private var cancelled = false
    private var committing = false
    fun cancel(): Boolean {
        synchronized(this) {
            if (committing) return false
            cancelled = true
        }
        // The callback can cancel active summary transport; never invoke it under this monitor.
        onCancel()
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
    private val sendingProvider: ProviderSnapshot?,
    val handle: ImageSendHandle,
    private val journal: SharedPreferences,
    val expectedDraftText: String = "",
    imageRoute: ProviderSnapshot? = sendingProvider,
    val auxiliaryGeneration: Long? = null,
    identity: ImageSendIdentity? = null,
) {
    val provider: ProviderSnapshot get() = checkNotNull(sendingProvider)
    private val actualImageRoute = imageRoute
    val imageRoute: ProviderSnapshot get() = checkNotNull(actualImageRoute)
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
    private val originalIdentity = identity
    private val initialIdentity = identity ?: ImageSendIdentity.create()
    var userId = initialIdentity.userId; private set
    var assistantId = initialIdentity.assistantId; private set
    var runId = initialIdentity.runId; private set
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
            if (originalIdentity != null && !matchesIdentity(saved)) return unknown()
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

    fun prepare(store: ImageAttachmentStore, assets: MessageImageAssetStore, createdAt: Long? = null): Message {
        val frozen = store.freeze(owner, images, imageRoute.imageInputSupported == true, handle::isCancelled)
        val refs = assets.prepare(app.daiyu.android.media.MessageImageOwner(owner.conversationId, userId.value),
            owner, frozen, handle::isCancelled)
        return user(refs.associateBy { it.id }, createdAt)
    }

    private fun user(refs: Map<String, app.daiyu.android.media.MessageImageReference>, createdAt: Long?): Message =
        Message(userId, ConversationId(owner.conversationId), runId, Message.USER_ROLE,
            parts.mapIndexed { index, part -> when (part) {
                is ImageSendPart.Text -> MessageBlock.Text("user-$index", part.text)
                is ImageSendPart.Image -> refs.getValue(part.attachment.id).let { ref ->
                    MessageBlock.Image(MessageImageReference(MessageImageOwner(ref.owner.conversationId,
                        ref.owner.messageId), ref.id, ref.mime, ref.bytes, ref.sha256, ref.width, ref.height))
                }
            } }, RunStatus.COMPLETED, createdAt = createdAt)

    private fun expectedUser(createdAt: Long?): Message = user(images.associate { image -> image.id to
        app.daiyu.android.media.MessageImageReference(
            app.daiyu.android.media.MessageImageOwner(owner.conversationId, userId.value), image.id,
            image.mime, image.bytes, image.sha256, image.width, image.height) }, createdAt)

    private fun matchesIdentity(saved: JSONObject): Boolean = saved.getString("user") == userId.value &&
        saved.getString("assistant") == assistantId.value && saved.getString("run") == runId.value

    /** No reserve, UUID allocation, settings, model, asset publication, journal or database write.
     * Missing rows, unreadable journals and contradictory receipts never permit a retry.
     * A lost journal is recoverable from the host's durably frozen identity plus SQLite pair.
     */
    fun readReceipt(database: ConversationDatabase): ImageSendResult = try {
        check(originalIdentity != null)
        journal.getString(key, null)?.let { encoded ->
            val saved = JSONObject(encoded)
            check(saved.getLong("revision") == revision && saved.getString("fingerprint") == fingerprint &&
                matchesIdentity(saved))
        }
        reconcile(database, confirmJournal = false)
    } catch (_: Exception) { unknown() }

    /** Local settlement AFTER an authoritative read, separate from the read-only query.
     * Preserve a confirmed tombstone so a later, genuinely new revision is not fenced by this
     * lost callback. A failed/contradictory acknowledgement grants no host consumption authority.
     */
    fun confirmReceipt(receipt: ImageSendResult.Accepted): Boolean = try {
        check(originalIdentity != null && receipt.owner == owner && receipt.revision == revision &&
            receipt.conversation.id.value == owner.conversationId && receipt.userId == userId &&
            receipt.assistantId == assistantId && receipt.runId == runId && receipt.images == images)
        val encoded = journal.getString(key, null)
        val saved = if (encoded == null) JSONObject().put("revision", revision).put("fingerprint", fingerprint)
            .put("user", userId.value).put("assistant", assistantId.value).put("run", runId.value)
        else JSONObject(encoded).also {
            check(it.getLong("revision") == revision && it.getString("fingerprint") == fingerprint && matchesIdentity(it))
        }
        saved.optBoolean("confirmed", false) ||
            journal.edit().putString(key, saved.put("confirmed", true).toString()).commit()
    } catch (_: Exception) { false }

    private fun reconcile(database: ConversationDatabase, confirmJournal: Boolean = true): ImageSendResult = try {
        DatabaseTransactions { database.readableDatabase }.run { db ->
            val user = database.storedMessage(db, ConversationId(owner.conversationId), userId.value)
            val assistant = database.storedMessage(db, user.conversationId, assistantId.value)
            // Journals predate message times. The original row is the authority on replay,
            // including a legacy null; never turn an uncertain receipt into a fresh send.
            check(user == expectedUser(user.createdAt) && assistant.runId == runId && assistant.role == Message.ASSISTANT_ROLE)
            val conversation = db.rawQuery("SELECT title,draft,updated,folder,pinned,assistant_id,workspace_binding FROM conversations WHERE id=?",
                arrayOf(owner.conversationId)).use {
                check(it.moveToFirst())
                Conversation(user.conversationId, it.getString(0), it.getString(1), it.getLong(2), it.getString(3), it.getInt(4) != 0,
                    if (it.isNull(5)) null else it.getString(5), if (it.isNull(6)) null else it.getString(6))
            }
            accepted(conversation, confirmJournal)
        }
    } catch (_: Exception) { unknown() }

    fun beginCommit(): Boolean {
        if (!handle.beginCommit()) return false
        commitAttempted = true
        return true
    }
    fun accepted(conversation: Conversation, confirmJournal: Boolean = true): ImageSendResult.Accepted = ImageSendResult.Accepted(owner,
        revision, conversation, userId, assistantId, runId, images.toList()).also {
        result = it
        // The SQLite receipt is authoritative even if this advisory tombstone update fails.
        if (confirmJournal) try { journal.getString(key, null)?.let { encoded ->
            journal.edit().putString(key, JSONObject(encoded).put("confirmed", true).toString()).commit()
        } } catch (_:Exception) { }
    }
    fun unknown(): ImageSendResult.Unknown = ImageSendResult.Unknown(owner, revision, userId, assistantId)
        .also { result = it }

    fun draftChanged(): ImageSendResult {
        // Called only for our own exception before database.send was invoked.
        commitAttempted = false
        return rejected("编辑草稿已变化")
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
    /** Host-selected current message only; never resolve a model-provided path or owner. */
    fun generationInputs(message: Message): Map<String, app.daiyu.android.model.generation.GenerationInput> {
        require(message.role == Message.USER_ROLE)
        app.daiyu.android.domain.MessageImages.validate(message)
        val expected = app.daiyu.android.media.MessageImageOwner(message.conversationId.value, message.id.value)
        return message.blocks.filterIsInstance<MessageBlock.Image>().associate { block ->
            val ref = block.reference
            val stored = app.daiyu.android.media.MessageImageReference(expected, ref.id, ref.mime,
                ref.bytes, ref.sha256, ref.width, ref.height)
            ref.id to app.daiyu.android.model.generation.GenerationInput(assets.resolver.read(expected, stored), ref.mime)
        }
    }

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
            return if (plan.hasImages) delegate.open(history, provider.asModelSettings().enabledTools().map {
                ToolDefinition(it.toolName, it.description, "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}")
            }, plan) else delegate.open(history)
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
