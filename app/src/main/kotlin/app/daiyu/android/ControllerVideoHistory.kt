package app.daiyu.android

import android.database.sqlite.SQLiteDatabase
import app.daiyu.android.context.DatabaseTransactions
import app.daiyu.android.domain.*
import app.daiyu.android.media.video.MessageVideoOwner
import app.daiyu.android.media.video.VideoAssetStore
import app.daiyu.android.model.ProviderSnapshot
import app.daiyu.android.model.ResponsesModel
import app.daiyu.android.model.images.FrozenMessageImagePlan
import app.daiyu.android.model.videos.*
import app.daiyu.android.storage.ConversationDatabase
import app.daiyu.android.storage.MessageVideoInputCodec
import app.daiyu.android.storage.SQLiteMessageVideoStore
import java.util.Collections

/** Physical rows, not display/context projections. Canonical sidecars participate in the CAS even
 * when no videos existed at capture time. No distinctBy/associateBy may hide duplicate ownership.
 */
internal class ControllerVideoHistorySnapshot internal constructor(
    val conversation: ConversationId,
    val exists: Boolean,
    rows: List<Message>,
    sidecars: List<List<MessageVideoInput>>,
) {
    val rows: List<Message> = physicalVideoHistoryCopy(rows)
    private val sidecars = sidecars.map { inputs -> inputs.map { MessageVideoInput(it.owner, it.references) } }
    private val canonical = this.rows.zip(sidecars).map { (row, inputs) ->
        MessageVideoInputCodec.encode(row, inputs)
    }
    val inputs: List<MessageVideoInput> = Collections.unmodifiableList(sidecars.flatten().map {
        MessageVideoInput(it.owner, it.references)
    })
    init {
        require(rows.size == sidecars.size)
        require(rows.all { it.conversationId == conversation })
        validateVideoHistory(this.rows, inputs)
    }
    internal fun appended(user: Message, assistant: Message, inputs: List<MessageVideoInput>) =
        ControllerVideoHistorySnapshot(conversation, true, rows + listOf(user, assistant),
            sidecars + listOf(inputs, emptyList()))

    internal fun same(other: ControllerVideoHistorySnapshot): Boolean =
        conversation == other.conversation && exists == other.exists && rows == other.rows && canonical == other.canonical
}

internal fun physicalVideoHistoryCopy(rows: List<Message>): List<Message> = Collections.unmodifiableList(rows.map { row ->
    row.copy(blocks = Collections.unmodifiableList(row.blocks.toList()),
        protocolItems = Collections.unmodifiableList(row.protocolItems.toList()),
        historySegments = Collections.unmodifiableList(row.historySegments.map { segment ->
            segment.copy(blocks = Collections.unmodifiableList(segment.blocks.toList()),
                protocolItems = Collections.unmodifiableList(segment.protocolItems.toList()))
        }))
})

internal fun validateVideoHistory(rows: List<Message>, inputs: List<MessageVideoInput>) {
    require(rows.map { it.conversationId to it.id }.toSet().size == rows.size) { "video_duplicate_physical_row" }
    MessageHistory.validate(rows)
    val expanded = MessageHistory.expand(rows)
    val users = expanded.filter { it.role == Message.USER_ROLE }
    require(users.map { it.conversationId to it.id }.toSet().size == users.size) { "video_duplicate_expanded_user" }
    require(inputs.map { it.owner }.toSet().size == inputs.size) { "video_duplicate_sidecar_owner" }
    inputs.forEach { input ->
        val matches = expanded.filter {
            it.conversationId.value == input.owner.conversationId && it.id.value == input.owner.messageId
        }
        require(matches.size == 1 && matches.single().role == Message.USER_ROLE &&
            matches.single().protocolItems.isEmpty()) { "video_sidecar_owner_missing_or_ambiguous" }
        require(input.references.isNotEmpty() && input.references.all { it.owner == input.owner })
        require(input.references.map { it.id }.toSet().size == input.references.size)
    }
}

/** Read synchronously on the parent's existing worker. Use assertCurrent IN the acceptance
 * transaction, immediately before database.send, not in a preceding read-only transaction.
 */
internal class ControllerVideoHistory(private val database: ConversationDatabase) {
    fun capture(conversation: ConversationId): ControllerVideoHistorySnapshot =
        DatabaseTransactions { database.readableDatabase }.run { capture(it, conversation) }

    internal fun capture(db: SQLiteDatabase, conversation: ConversationId): ControllerVideoHistorySnapshot {
        check(db.inTransaction())
        SQLiteMessageVideoStore.assertNoOrphans(db)
        val exists = db.rawQuery("SELECT 1 FROM conversations WHERE id=?", arrayOf(conversation.value)).use { it.moveToFirst() }
        val ids = db.rawQuery("SELECT id FROM messages WHERE conversation_id=? ORDER BY seq", arrayOf(conversation.value)).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }
        val rows = ids.map { database.storedMessage(db, conversation, it) }
        val sidecars = rows.map { row ->
            SQLiteMessageVideoStore.read(db, row).also { inputs ->
                // This independent durable expectation predates this run's first capture.
                // Missing metadata/witness is never repaired into a text-only history here.
                ControllerVideoManifestWitness.verify(db, row, inputs)
            }
        }
        return ControllerVideoHistorySnapshot(conversation, exists, rows, sidecars)
    }

    fun assertCurrent(db: SQLiteDatabase, expected: ControllerVideoHistorySnapshot) {
        check(expected.same(capture(db, expected.conversation))) { "video_history_changed" }
    }

    fun assertAppended(db: SQLiteDatabase, expected: ControllerVideoHistorySnapshot,
        user: Message, assistant: Message, inputs: List<MessageVideoInput>) {
        check(expected.appended(user, assistant, inputs).same(capture(db, expected.conversation))) {
            "video_history_append_unconfirmed"
        }
    }
}

internal class ControllerVideoModelPlan internal constructor(
    val history: List<Message>,
    val videos: FrozenMessageVideoPlan?,
    val images: FrozenMessageImagePlan,
    inputs: List<MessageVideoInput>,
) {
    internal val inputs = Collections.unmodifiableList(inputs.map { MessageVideoInput(it.owner, it.references) })
}

/** Mandatory for ALL sends on a video-bearing conversation, including ordinary text continuation.
 * Capability must be explicitly supplied by the parent from trusted evidence + upload permission.
 * This adapter never grants it, never falls back to another provider and never opens transport in
 * preflight. The runtime cancellation predicate must outlive finishPreparation (not its token).
 */
internal class ControllerMessageVideos(
    private val database: ConversationDatabase,
    private val assets: VideoAssetStore,
    private val images: ControllerMessageImages,
) {
    val history = ControllerVideoHistory(database)

    fun preflight(snapshot: ControllerVideoHistorySnapshot, provider: ProviderSnapshot,
        capability: OpenRouterVideoCapability?, pending: PreparedControllerVideoSend? = null,
        cancelled: () -> Boolean): ControllerVideoModelPlan {
        require(pending == null || pending.user.conversationId == snapshot.conversation)
        val rows = snapshot.rows + listOfNotNull(pending?.user)
        val inputs = snapshot.inputs + pending?.inputs.orEmpty()
        validateVideoHistory(rows, inputs)
        return freeze(rows, inputs, provider, capability, pending?.user, cancelled)
    }

    /** Ordinary text sends must use this before DB acceptance too; a text-only draft does not
     * waive capability/asset validation for any retained historical original video. */
    fun preflightText(snapshot: ControllerVideoHistorySnapshot, provider: ProviderSnapshot,
        capability: OpenRouterVideoCapability?, pendingUser: Message,
        cancelled: () -> Boolean): ControllerVideoModelPlan {
        require(pendingUser.conversationId == snapshot.conversation && pendingUser.role == Message.USER_ROLE &&
            pendingUser.status == RunStatus.COMPLETED && pendingUser.historySegments.isEmpty() &&
            pendingUser.protocolItems.isEmpty() && pendingUser.blocks.all { it is MessageBlock.Text })
        val rows = snapshot.rows + pendingUser
        validateVideoHistory(rows, snapshot.inputs)
        return freeze(rows, snapshot.inputs, provider, capability, pendingUser, cancelled)
    }

    private fun freeze(rows: List<Message>, inputs: List<MessageVideoInput>, provider: ProviderSnapshot,
        capability: OpenRouterVideoCapability?, candidate: Message?, cancelled: () -> Boolean): ControllerVideoModelPlan {
        if (cancelled()) throw InterruptedException("video_model_cancelled")
        val frozenRows = physicalVideoHistoryCopy(rows)
        val videoPlan = if (inputs.isEmpty()) null else {
            val permission = requireNotNull(capability) { "video_capability_required" }
            FrozenMessageVideoPlan.freeze(frozenRows, provider, permission, inputs, assets, { row ->
                val owner = MessageVideoOwner(row.conversationId.value, row.id.value)
                check(inputs.count { it.owner == owner } == 1)
                owner
            }, cancelled)
        }
        val imagePlan = images.freeze(frozenRows, provider, candidate, cancelled)
        if (cancelled()) throw InterruptedException("video_model_cancelled")
        return ControllerVideoModelPlan(frozenRows, videoPlan, imagePlan, inputs)
    }

    /** The initial empty active assistant is not model input. On later Runner opens its sealed
     * segments may be supplied as COMPLETED while SQLite still owns RUNNING; only that status/error
     * projection is tolerated. All other physical history and every video user must be present.
     * modelFactory is a test seam; production constructs ResponsesModel with the SAME permission
     * and frozen assistant instructions. No caller-supplied generic ModelPort can drop videos.
     */
    fun wrap(provider: ProviderSnapshot, capability: OpenRouterVideoCapability?, conversation: ConversationId,
        activeAssistant: MessageId, baseline: ControllerVideoModelPlan,
        assistantInstructions: String?, runtimeCancelled: () -> Boolean,
        modelFactory: (ProviderSnapshot, String?, OpenRouterVideoCapability?) -> ResponsesModel = { p, i, c ->
            ResponsesModel(p, i, c)
        }): ModelPort = object : ModelPort {
        private fun openPlan(source: List<Message>, definitions: List<ToolDefinition>, supplied: ModelImagePlan? = null): ModelSession {
            val snapshot = history.capture(conversation)
            check(snapshot.exists) { "video_conversation_missing" }
            require(baseline.history.all { it.conversationId == conversation })
            validateVideoHistory(baseline.history, baseline.inputs)
            // Keep the pre-accept manifest as a read-only witness. Re-reading SQLite alone
            // must not silently erase a video if its sidecar disappears during this run.
            val baselineUsers = MessageHistory.expand(baseline.history).filter { it.role == Message.USER_ROLE }
                .map { MessageVideoOwner(it.conversationId.value, it.id.value) }.toSet()
            fun signature(inputs: List<MessageVideoInput>) = inputs.associate { it.owner to it.references }
            check(signature(baseline.inputs) == signature(snapshot.inputs.filter { it.owner in baselineUsers })) {
                "video_baseline_sidecar_changed"
            }
            val rows = physicalVideoHistoryCopy(source)
            validateVideoHistory(rows, emptyList())
            fun normalized(values: List<Message>): List<Message> = MessageHistory.expand(values).map { row ->
                if (row.id == activeAssistant && row.role == Message.ASSISTANT_ROLE)
                    row.copy(status = RunStatus.COMPLETED, error = null) else row
            }
            val active = snapshot.rows.singleOrNull { it.id == activeAssistant && it.role == Message.ASSISTANT_ROLE }
            val expected = if (active != null && active.blocks.isEmpty() && active.protocolItems.isEmpty() && active.historySegments.isEmpty())
                snapshot.rows.filterNot { it.id == activeAssistant } else snapshot.rows
            check(normalized(rows) == normalized(expected)) { "video_model_requires_full_physical_history" }
            validateVideoHistory(rows, snapshot.inputs)
            val plan = freeze(rows, snapshot.inputs, provider, capability, null, runtimeCancelled)
            // An explicit image overload must not discard or silently accept an unrelated plan.
            if (supplied != null) {
                require(supplied is FrozenMessageImagePlan) { "video_unknown_image_plan" }
                supplied.validate(provider.asModelSettings(), provider.protocol, provider.imageInputSupported, rows)
            }
            history.assertUnchanged(snapshot)
            if (runtimeCancelled()) throw InterruptedException("video_model_cancelled")
            val delegate = modelFactory(provider, assistantInstructions, capability)
            val session = if (plan.videos != null) delegate.open(plan.history, definitions, plan.videos, plan.images)
                else delegate.open(plan.history, definitions, plan.images)
            return object : ModelSession {
                private fun checkRuntime() {
                    if (runtimeCancelled()) { session.cancel(); throw InterruptedException("video_model_cancelled") }
                }
                override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                    checkRuntime()
                    session.next(outputs) { event -> checkRuntime(); sink(event) }
                }
                override fun cancel() = session.cancel()
            }
        }
        override fun open(history: List<Message>): ModelSession = open(history, provider.asModelSettings().enabledTools())
        override fun open(history: List<Message>, tools: Set<ReadOnlyTool>): ModelSession = openPlan(history, tools.map {
            ToolDefinition(it.toolName, it.description,
                "{\"type\":\"object\",\"properties\":{},\"required\":[],\"additionalProperties\":false}")
        })
        override fun open(history: List<Message>, definitions: List<ToolDefinition>): ModelSession = openPlan(history, definitions)
        override fun open(history: List<Message>, definitions: List<ToolDefinition>, images: ModelImagePlan): ModelSession =
            openPlan(history, definitions, images)
    }

    private fun ControllerVideoHistory.assertUnchanged(snapshot: ControllerVideoHistorySnapshot) {
        DatabaseTransactions { database.readableDatabase }.run { assertCurrent(it, snapshot) }
    }
}
