package app.daiyu.android

import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import app.daiyu.android.context.DatabaseTransactions
import app.daiyu.android.domain.*
import app.daiyu.android.media.video.*
import app.daiyu.android.model.ProviderSnapshot
import app.daiyu.android.model.videos.MessageVideoInput
import app.daiyu.android.model.videos.OpenRouterVideoCapability
import app.daiyu.android.storage.ConversationDatabase
import app.daiyu.android.storage.MessageVideoInputCodec
import app.daiyu.android.storage.SQLiteMessageVideoStore
import app.daiyu.android.storage.SQLiteText
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

/** Prepared files + pending sidecar, NOT acceptance. Originals are never removed by this helper. */
internal class PreparedControllerVideoSend internal constructor(
    val user: Message,
    val assistant: Message,
    inputs: List<MessageVideoInput>,
) {
    val inputs: List<MessageVideoInput> = java.util.Collections.unmodifiableList(inputs.map { MessageVideoInput(it.owner, it.references) })
}

/** Parent-owned receiving journal, separate from the host draft journal. Keep one live writer per
 * preferences instance and retain tombstones. Only reserve() creates an identity; recover() NEVER
 * prepares assets, sends, removes a draft, or infers rejection from a missing row/journal/file.
 */
internal class ControllerVideoJournal(private val preferences: SharedPreferences) {
    internal fun key(request: VideoDraftRequest): String = key(request.owner, request.revision)

    private fun key(owner: VideoAttachmentOwner, revision: Long): String = PREFIX + videoDigest(
        JSONArray().put(owner.conversationId).put(owner.draftId).toString()) + ":" + revision

    internal fun reserve(attempt: ControllerVideoAttempt): Boolean = synchronized(preferences) {
        val request = attempt.request
        val key = key(request)
        if (preferences.contains(key)) return@synchronized false
        val ownerPrefix = key.substringBeforeLast(':') + ":"
        check(preferences.all.none { (k, v) ->
            k.startsWith(ownerPrefix) && (v !is String || JSONObject(v).getString("phase") !in setOf("ACCEPTED", "REJECTED"))
        }) { "video_owner_unresolved" }
        check(preferences.edit().putString(key, attempt.record("PREPARING").toString()).commit()) {
            "video_identity_not_durable"
        }
        true
    }

    internal fun phase(attempt: ControllerVideoAttempt, phase: String) = synchronized(preferences) {
        val stored = read(attempt.request) ?: error("video_identity_missing")
        check(attempt.matches(stored)) { "video_identity_changed" }
        check(preferences.edit().putString(key(attempt.request), attempt.record(phase).toString()).commit()) {
            "video_journal_not_durable"
        }
    }

    internal fun read(request: VideoDraftRequest): JSONObject? = read(request.owner, request.revision)

    private fun read(owner: VideoAttachmentOwner, revision: Long): JSONObject? = synchronized(preferences) {
        preferences.getString(key(owner, revision), null)?.let { encoded ->
            require(encoded.length <= 64 * 1024)
            JSONObject(encoded).also { require(it.getInt("version") == 1) }
        }
    }

    /** Compatibility entry: still checks the caller's original text, never the composer. */
    fun recover(request: VideoDraftRequest, database: ConversationDatabase): VideoDraftReceipt =
        recoverDetails(VideoHostDraftStore.Draft(request.conversation, request.owner, request.revision,
            request.videos, true), database, request.text).receipt

    /** Restart entry. Draft deliberately has NO text. The journal's original IDs locate the DB
     * user; only that authoritative original body may satisfy its fingerprint. No new identity,
     * assets, composer read, send or deletion is involved. Missing evidence always stays UNKNOWN.
     */
    fun recover(draft: VideoHostDraftStore.Draft, database: ConversationDatabase): VideoDraftReceipt =
        recoverDetails(draft, database).receipt

    internal data class Recovery(val receipt: VideoDraftReceipt, val conversation: Conversation? = null,
        val userId: MessageId? = null, val assistantId: MessageId? = null, val runId: RunId? = null)

    internal fun recoverDetails(draft: VideoHostDraftStore.Draft, database: ConversationDatabase,
        expectedText: String? = null): Recovery {
        fun receipt(outcome: VideoDraftOutcome) = VideoDraftReceipt(draft.conversation, draft.owner,
            draft.revision, draft.videos, outcome,
            if (outcome == VideoDraftOutcome.ACCEPTED) draft.owner.conversationId else null)
        return try {
            VideoHostDraftStore.validate(listOf(draft))
            check(draft.fenced && draft.videos.isNotEmpty())
            val saved = read(draft.owner, draft.revision) ?: return Recovery(receipt(VideoDraftOutcome.UNKNOWN))
            check(saved.has("slot") && (if (saved.isNull("slot")) null else saved.getString("slot")) == draft.conversation)
            check(saved.getString("conversation") == draft.owner.conversationId &&
                saved.getString("draft") == draft.owner.draftId && saved.getLong("revision") == draft.revision &&
                saved.getJSONArray("references").toString() == videoReferenceWire(draft.videos).toString())
            check(saved.getString("phase") in setOf("PREPARING", "DB_ENTERED", "ACCEPTED", "REJECTED"))
            val conversationId = ConversationId(draft.owner.conversationId)
            val userId = MessageId(saved.getString("user"))
            val assistantId = MessageId(saved.getString("assistant"))
            val runId = RunId(saved.getString("run"))
            check(userId != assistantId)
            val conversation = DatabaseTransactions { database.readableDatabase }.run { db ->
                val user = database.storedMessage(db, conversationId, userId.value)
                val assistant = database.storedMessage(db, conversationId, assistantId.value)
                val body = (user.blocks.singleOrNull() as? MessageBlock.Text)?.text
                    ?: error("video_original_body_missing")
                check(expectedText == null || expectedText == body)
                check(user == Message(userId, conversationId, runId, Message.USER_ROLE,
                    listOf(MessageBlock.Text("user", body)), RunStatus.COMPLETED, createdAt = user.createdAt))
                check(saved.getString("fingerprint") == videoFingerprint(body, draft.videos))
                check(assistant.id == assistantId && assistant.conversationId == conversationId &&
                    assistant.role == Message.ASSISTANT_ROLE && assistant.runId == runId)
                val owner = MessageVideoOwner(conversationId.value, userId.value)
                val expected = listOf(MessageVideoInput(owner, draft.videos.map {
                    MessageVideoReference(owner, it.id, it.mime, it.bytes, it.sha256, it.durationMs, it.width, it.height)
                }))
                val actual = SQLiteMessageVideoStore.read(db, user)
                ControllerVideoManifestWitness.verify(db, user, actual)
                ControllerVideoManifestWitness.verify(db, assistant, SQLiteMessageVideoStore.read(db, assistant))
                check(MessageVideoInputCodec.encode(user, actual) == MessageVideoInputCodec.encode(user, expected))
                db.rawQuery("SELECT title,draft,updated,folder,pinned,assistant_id FROM conversations WHERE id=?",
                    arrayOf(conversationId.value)).use { c ->
                    check(c.moveToFirst())
                    Conversation(conversationId, c.getString(0), c.getString(1), c.getLong(2), c.getString(3), c.getInt(4) != 0,
                        if (c.isNull(5)) null else c.getString(5))
                }
            }
            Recovery(receipt(VideoDraftOutcome.ACCEPTED), conversation, userId, assistantId, runId)
        } catch (_: Exception) { Recovery(receipt(VideoDraftOutcome.UNKNOWN)) }
    }

    companion object { private const val PREFIX = "controller-video-receipt-v1:" }
}

/** Integration order on the existing Controller preparation lane:
 *  1. Construct, reserve(database); a non-null receipt is REPLAY, return it without preparing.
 *  2. Capture ControllerVideoHistory, prepare originals, preflight ALL physical rows + pending.
 *  3. Build the existing Runner/tools with ControllerMessageVideos.wrap; give it a run-lifetime
 *     cancellation predicate, never preparationCancelled(token) after finishPreparation.
 *  4. commit() performs final physical+sidecar CAS and exact draft CAS in the SAME transaction as
 *     database.send(..., videoInputs = prepared.inputs). beforeSend is for parent binding/policy
 *     gates only, not I/O/dispatch/model calls. Install the existing Runner only after ACCEPTED.
 *  5. Pass this receipt to VideoChatHost; only its owner/revision reconciliation deletes drafts.
 * Exceptions before database entry are rejected; after entry they are UNKNOWN, with no retry.
 */
internal class ControllerVideoAttempt(
    val request: VideoDraftRequest,
    private val journal: ControllerVideoJournal,
) {
    private data class Identity(val user: MessageId, val assistant: MessageId, val run: RunId)
    private var identity: Identity? = null
    private fun freshIdentity(): Identity = identity ?: Identity(MessageId(UUID.randomUUID().toString()),
        MessageId(UUID.randomUUID().toString()), RunId(UUID.randomUUID().toString())).also { identity = it }
    val userId: MessageId get() = freshIdentity().user
    val assistantId: MessageId get() = freshIdentity().assistant
    val runId: RunId get() = freshIdentity().run
    val conversationId = ConversationId(request.owner.conversationId)
    private val fingerprint = videoFingerprint(request.text, request.videos)
    private var reserved = false
    private var settled = false
    private var databaseEntered = false
    // Supplied by the candidate creator; never read the clock in constructors or recovery.
    private var createdAt: Long? = null
    private var timeFrozen = false
    var acceptedConversation: Conversation? = null; private set

    init {
        VideoHostDraftStore.validate(listOf(VideoHostDraftStore.Draft(request.conversation, request.owner,
            request.revision, request.videos, true)))
        require(request.videos.isNotEmpty())
    }

    private fun referenceWire(): JSONArray = videoReferenceWire(request.videos)

    internal fun record(phase: String): JSONObject = JSONObject().put("version", 1)
        .put("slot", request.conversation ?: JSONObject.NULL).put("conversation", conversationId.value)
        .put("draft", request.owner.draftId).put("revision", request.revision)
        .put("fingerprint", fingerprint).put("references", referenceWire())
        .put("user", userId.value).put("assistant", assistantId.value).put("run", runId.value).put("phase", phase)

    internal fun matches(saved: JSONObject): Boolean = saved.getInt("version") == 1 && saved.has("slot") &&
        (if (saved.isNull("slot")) null else saved.getString("slot")) == request.conversation &&
        saved.getString("conversation") == conversationId.value && saved.getString("draft") == request.owner.draftId &&
        saved.getLong("revision") == request.revision && saved.getString("fingerprint") == fingerprint &&
        saved.getJSONArray("references").toString() == referenceWire().toString() &&
        saved.getString("user") == userId.value && saved.getString("assistant") == assistantId.value && saved.getString("run") == runId.value

    /** null is a fresh, durable identity. Any receipt means no second prepare/send is permitted. */
    @Synchronized fun reserve(database: ConversationDatabase): VideoDraftReceipt? {
        check(!reserved && !settled)
        return try {
            if (journal.reserve(this)) { reserved = true; null }
            else { settled = true; recover(database) }
        } catch (_: Exception) { settled = true; receipt(VideoDraftOutcome.UNKNOWN) }
    }

    fun prepare(assets: VideoAssetStore, provider: ProviderSnapshot, capability: OpenRouterVideoCapability,
        createdAt: Long? = null, cancelled: () -> Boolean): PreparedControllerVideoSend {
        check(reserved && !settled && !databaseEntered) { "video_attempt_not_fresh" }
        require(createdAt == null || createdAt >= 0)
        check(!timeFrozen || this.createdAt == createdAt) { "video_candidate_time_changed" }
        this.createdAt = createdAt
        timeFrozen = true
        capability.validate(provider.asModelSettings())
        if (cancelled()) throw VideoAttachmentException(VideoAttachmentFailure.CANCELLED)
        // Enforce provider limits before publishing any original, not only during base64 freezing.
        require(request.videos.size <= capability.maxVideos && request.videos.all {
            it.mime == "video/mp4" && it.bytes <= capability.maxVideoBytes
        } && request.videos.sumOf { it.bytes } <= capability.maxTotalBytes) { "video_capability_budget" }
        val owner = MessageVideoOwner(conversationId.value, userId.value)
        val refs = request.videos.map { assets.prepare(owner, it, cancelled) }
        val inputs = listOf(MessageVideoInput(owner, refs))
        val user = expectedUser()
        check(MessageVideoInputCodec.encode(user, inputs) == MessageVideoInputCodec.encode(user, expectedInputs()))
        return PreparedControllerVideoSend(user, expectedAssistant(), inputs)
    }

    private fun expectedUser() = Message(userId, conversationId, runId, Message.USER_ROLE,
        listOf(MessageBlock.Text("user", request.text)), RunStatus.COMPLETED, createdAt = createdAt)
    private fun expectedAssistant() = Message(assistantId, conversationId, runId, Message.ASSISTANT_ROLE,
        emptyList(), RunStatus.RUNNING, createdAt = createdAt)
    private fun expectedInputs(): List<MessageVideoInput> {
        val owner = MessageVideoOwner(conversationId.value, userId.value)
        return listOf(MessageVideoInput(owner, request.videos.map {
            MessageVideoReference(owner, it.id, it.mime, it.bytes, it.sha256, it.durationMs, it.width, it.height)
        }))
    }

    /** Synchronous DB gate. Do not enclose in a further transaction: ACCEPTED is returned only
     * after the OUTERMOST commit. expectedDraftText is explicit (may differ from message body).
     */
    @Synchronized fun commit(database: ConversationDatabase, snapshot: ControllerVideoHistorySnapshot,
        prepared: PreparedControllerVideoSend, plan: ControllerVideoModelPlan,
        expectedAssistantId: String?, expectedDraftText: String,
        cancelled: () -> Boolean, beforeSend: (SQLiteDatabase) -> Unit = {},
        replyIdentity: app.daiyu.android.assistants.AssistantReplyIdentity? = null): VideoDraftReceipt {
        check(reserved && !settled && !databaseEntered)
        check(!database.writableDatabase.inTransaction()) { "video_outer_transaction_forbidden" }
        return try {
            check(prepared.user == expectedUser() && prepared.assistant == expectedAssistant())
            check(plan.history == snapshot.rows + prepared.user && plan.videos != null &&
                plan.inputs.map { it.owner to it.references } == (snapshot.inputs + prepared.inputs).map { it.owner to it.references }) {
                "video_preflight_required"
            }
            check(MessageVideoInputCodec.encode(prepared.user, prepared.inputs) ==
                MessageVideoInputCodec.encode(expectedUser(), expectedInputs()))
            check(snapshot.conversation == conversationId && snapshot.exists == (request.conversation != null))
            val saved = DatabaseTransactions { database.writableDatabase }.run { db ->
                if (cancelled()) throw InterruptedException("video_send_cancelled")
                beforeSend(db)
                ControllerVideoHistory(database).assertCurrent(db, snapshot)
                val draft = if (request.conversation == null) SQLiteText.read(db, "composer", "draft", "id", "1")
                    else SQLiteText.read(db, "conversations", "draft", "id", conversationId.value)
                check(draft == expectedDraftText) { "video_draft_changed" }
                if (cancelled()) throw InterruptedException("video_send_cancelled")
                // Durable uncertainty BEFORE database.send. A crash here cannot become a replay.
                journal.phase(this, "DB_ENTERED")
                databaseEntered = true
                val conversation = database.send(prepared.user, prepared.assistant, request.conversation == null,
                    expectedAssistantId, videoInputs = prepared.inputs, replyIdentity = replyIdentity)
                // Same OUTERMOST transaction. Parent must also wire all ordinary sends/imports;
                // insert-or-verify permits parent DB wiring without replacing existing evidence.
                ControllerVideoManifestWitness.insert(db, prepared.user, prepared.inputs)
                ControllerVideoManifestWitness.insert(db, prepared.assistant, emptyList())
                conversation
            }
            acceptedConversation = saved
            settled = true
            runCatching { journal.phase(this, "ACCEPTED") } // DB receipt, not this advisory write, is authority.
            receipt(VideoDraftOutcome.ACCEPTED)
        } catch (_: Exception) {
            if (databaseEntered) { settled = true; receipt(VideoDraftOutcome.UNKNOWN) } else reject()
        }
    }

    @Synchronized fun reject(): VideoDraftReceipt {
        if (databaseEntered || settled || !reserved) return receipt(VideoDraftOutcome.UNKNOWN)
        settled = true
        runCatching { journal.phase(this, "REJECTED") }
        return receipt(VideoDraftOutcome.REJECTED)
    }

    /** Compatibility path for an already allocated attempt, never a second reservation/send. */
    internal fun recover(database: ConversationDatabase): VideoDraftReceipt {
        val recovered = journal.recoverDetails(VideoHostDraftStore.Draft(request.conversation, request.owner,
            request.revision, request.videos, true), database, request.text)
        if (recovered.receipt.outcome == VideoDraftOutcome.ACCEPTED) {
            identity = Identity(checkNotNull(recovered.userId), checkNotNull(recovered.assistantId),
                checkNotNull(recovered.runId))
            acceptedConversation = recovered.conversation
        }
        return recovered.receipt
    }

    private fun receipt(outcome: VideoDraftOutcome) = VideoDraftReceipt(request.conversation, request.owner,
        request.revision, request.videos, outcome, if (outcome == VideoDraftOutcome.ACCEPTED) conversationId.value else null)
}

private fun videoReferenceWire(videos: List<VideoAttachment>): JSONArray = JSONArray().also { array ->
    videos.forEach { video ->
        array.put(JSONObject().put("id", video.id).put("mime", video.mime).put("bytes", video.bytes)
            .put("sha256", video.sha256).put("durationMs", video.durationMs).put("width", video.width).put("height", video.height))
    }
}

private fun videoFingerprint(text: String, videos: List<VideoAttachment>): String =
    videoDigest(JSONArray().put(text).put(videoReferenceWire(videos)).toString())

private fun videoDigest(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
