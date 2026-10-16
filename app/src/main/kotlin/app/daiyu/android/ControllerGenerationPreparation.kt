package app.daiyu.android

import android.database.sqlite.SQLiteDatabase
import app.daiyu.android.domain.ConversationId
import app.daiyu.android.domain.MessageId
import app.daiyu.android.domain.RunId
import app.daiyu.android.domain.runtime.RunExecutionOwner
import app.daiyu.android.domain.tools.TaskAuthorization
import app.daiyu.android.domain.tools.WriteEffect
import app.daiyu.android.integration.tools.*
import app.daiyu.android.media.GenerationAssetFence
import app.daiyu.android.media.GenerationAssetOwner
import app.daiyu.android.media.MessageGenerationAssets
import app.daiyu.android.model.generation.*
import app.daiyu.android.storage.GenerationAttemptBinding
import java.util.Collections

/** Reserved durable identities, not a manufactured Runner owner. The parent supplies all IDs. */
internal data class GenerationPreparationIdentity(
    val accountScope: String,
    val conversationId: ConversationId,
    val runId: RunId,
    val assistantMessageId: MessageId,
) {
    init { require(accountScope.isNotBlank() && accountScope.length <= 200) }
}

/** Increment revision for EVERY editor, attachment, parameter, profile/settings or navigation edit,
 * including edit-then-revert. Attachment IDs must name the parent's immutable draft snapshots.
 */
internal class GenerationEditorSnapshot(
    val revision: Long,
    val text: String,
    attachmentIds: List<String> = emptyList(),
) {
    val attachmentIds: List<String> = Collections.unmodifiableList(attachmentIds.toList())
    init { require(revision >= 0) }
    fun matches(other: GenerationEditorSnapshot): Boolean = revision == other.revision &&
        text == other.text && attachmentIds == other.attachmentIds
    override fun toString() = "GenerationEditorSnapshot(<redacted>)"
}

/** Only trusted profile IDs are accepted; this form has deliberately no endpoint/credential field. */
internal class GenerationBatchSelection(
    val batchId: String,
    val profileId: String,
    items: List<GenerationRunConfig.Item>,
) {
    val items: List<GenerationRunConfig.Item> = Collections.unmodifiableList(items.toList())
    override fun toString() = "GenerationBatchSelection(<redacted>)"
}

internal class GenerationPreparationPreview internal constructor(
    val identity: GenerationPreparationIdentity,
    val editor: GenerationEditorSnapshot,
    val config: GenerationRunConfig,
    profileLabels: Map<String, String>,
    val downloads: GenerationDownloadHosts,
    blockers: List<String>,
) {
    val profileLabels: Map<String, String> = Collections.unmodifiableMap(profileLabels.toMap())
    val blockers: List<String> = Collections.unmodifiableList(blockers.toList())
    override fun toString() = "GenerationPreparationPreview(<redacted>)"
}

/** Pure single-use preparation: no files, network, preflight, DB, owner or dispatcher creation. */
internal class ControllerGenerationPreparation private constructor(val preview: GenerationPreparationPreview) {
    private var consumed = false

    /** Only the explicit paid confirmation button may call this; toggles/preferences cannot. */
    @Synchronized fun confirm(
        displayed: GenerationPreparationPreview,
        currentEditor: GenerationEditorSnapshot,
        currentIdentity: GenerationPreparationIdentity,
    ): ConfirmedGenerationPreparation {
        check(!consumed) { "generation_preparation_consumed" }
        consumed = true
        require(displayed === preview) { "generation_preview_changed" }
        require(preview.identity == currentIdentity && preview.editor.matches(currentEditor)) {
            "generation_draft_changed"
        }
        require(preview.blockers.isEmpty()) { "generation_unsupported_parameters" }
        return ConfirmedGenerationPreparation(preview)
    }

    @Synchronized fun cancel() { consumed = true }

    companion object {
        fun prepare(
            identity: GenerationPreparationIdentity,
            editor: GenerationEditorSnapshot,
            profiles: List<GenerationModelProfile>,
            selections: List<GenerationBatchSelection>,
            maxPolls: Int,
            downloads: GenerationDownloadHosts,
        ): ControllerGenerationPreparation {
            require(profiles.map { it.id }.distinct().size == profiles.size) { "generation_duplicate_profile" }
            val available = profiles.associateBy { it.id }
            val labels = linkedMapOf<String, String>()
            val batches = selections.map { selected ->
                val profile = requireNotNull(available[selected.profileId]) { "generation_profile_required" }
                labels[selected.batchId] = profile.label
                profile.freeze(selected.batchId, selected.items)
            }
            val config = GenerationRunConfig(batches, maxPolls)
            // Preserve all raw values. The current client rejects ALL optional parameters, even
            // size. Show a blocked preview rather than silently dropping one or billing a fallback.
            val blockers = batches.flatMap { batch -> batch.items.filter { it.parameters.isNotEmpty() }
                .map { "${batch.id}/${it.id}: 当前生成客户端不支持附加参数（含 size、duration、quality、seed、n）；请明确移除后重新预览。" } }
            return ControllerGenerationPreparation(GenerationPreparationPreview(identity, editor, config,
                labels, downloads, blockers))
        }
    }
}

/** Revocable in-memory confirmation, never persisted as a reusable bearer authorization.
 * paidWrites is called only AFTER Runner supplies its real owner. Parent unions these exact
 * effects with other approved writes to construct its ONE TaskAuthorization/dispatcher.
 */
internal class ConfirmedGenerationPreparation internal constructor(val preview: GenerationPreparationPreview) {
    private var revoked = false
    private var boundOwner: RunExecutionOwner? = null
    private var boundAuthorization: TaskAuthorization? = null
    private var leaf: RunGenerationTools? = null
    private var cleanupResult: GenerationToolsCloseResult? = null
    private var cleanupStarted = false
    private var effects: Set<WriteEffect>? = null

    @Synchronized fun paidWrites(
        owner: RunExecutionOwner,
        currentEditor: GenerationEditorSnapshot,
        currentIdentity: GenerationPreparationIdentity,
    ): Set<WriteEffect> {
        check(!revoked) { "generation_confirmation_revoked" }
        require(currentIdentity == preview.identity && preview.editor.matches(currentEditor)) {
            "generation_draft_changed"
        }
        checkOwner(owner)
        boundOwner = owner
        return effects ?: Collections.unmodifiableSet(preview.config.batches.map {
            RunGenerationTools.writeEffect(preview.identity.accountScope, owner, preview.config, it)
        }.toSet()).also { effects = it }
    }

    /** Pure gate usable with the parent's existing leaf assembly. Does not execute anything. */
    @Synchronized fun approval(
        owner: RunExecutionOwner,
        authorization: TaskAuthorization,
        isCurrentOwner: () -> Boolean,
    ): GenerationRunApproval {
        pinTask(owner, authorization)
        return GenerationRunApproval { request ->
            // Parent callbacks may acquire the controller lock. Never call them with this
            // token's monitor held: cancellation takes controller -> token in that order.
            fun validLocked(): Boolean {
                val invocation = request.context.invocation
                val binding = GenerationAttemptBinding(owner.conversationId.value, owner.assistantMessageId.value,
                    owner.runId.value, invocation.id.value, invocation.name)
                val expectedTool = if (request.batch.protocol == GenerationProtocol.OPENAI_VIDEO_MULTIPART)
                    RunGenerationTools.VIDEO_TOOL else RunGenerationTools.IMAGE_TOOL
                return !revoked &&
                    request.context.owner === boundOwner && request.context.authorization === boundAuthorization &&
                    request.config === preview.config && preview.config.batches.any { it === request.batch } &&
                    invocation.name == expectedTool && request.plans.size == request.batch.items.size &&
                    request.plans.zip(request.batch.items).all { (plan, item) ->
                        plan.identity == binding.identity(item.id) && plan.protocol == request.batch.protocol &&
                            plan.endpoint == request.batch.endpoint
                    } && request.effect == RunGenerationTools.writeEffect(preview.identity.accountScope, owner,
                        preview.config, request.batch) && request.effect in checkNotNull(effects)
            }
            val initiallyValid = synchronized(this) { validLocked() }
            val current = initiallyValid && runCatching {
                !request.context.cancelled() && isCurrentOwner() && !request.context.cancelled()
            }.getOrDefault(false)
            // A callback can block while the parent revokes/replaces this token. Its earlier
            // true result is not admission: recheck all token-local state after it returns.
            if (current && synchronized(this) { validLocked() }) GenerationRunDecision.APPROVED
            else GenerationRunDecision.DENIED
        }
    }

    /** Additional mandatory parent network/DNS check, ANDed with reviewed exact HTTPS authorities.
     * The check must not permit redirects/retries. No default or allow-all policy is supplied.
     */
    @Synchronized fun downloadAuthorization(
        owner: RunExecutionOwner,
        networkPolicy: GenerationDownloadAuthorization,
        isCurrentOwner: () -> Boolean,
    ): GenerationDownloadAuthorization {
        checkOwner(owner)
        check(boundOwner === owner && !revoked) { "generation_owner_not_bound" }
        return GenerationDownloadAuthorization { assetOwner, url ->
            val permitted = synchronized(this) {
                !revoked && matchesAssetOwner(assetOwner) && preview.downloads.allows(url)
            }
            // A DNS/network policy may block. Never hold the revocation monitor while it runs.
            permitted && runCatching {
                isCurrentOwner() && networkPolicy.allows(assetOwner, url) && isCurrentOwner()
            }.getOrDefault(false) && synchronized(this) { !revoked }
        }
    }

    /** Convenience construction for the parent; no I/O until the existing dispatcher executes.
     * Default production HTTP denies redirects and retries. An injected transport must preserve
     * that contract (including DNS/address enforcement required by networkPolicy).
     */
    @Synchronized fun openLeaf(
        owner: RunExecutionOwner,
        authorization: TaskAuthorization,
        database: () -> SQLiteDatabase,
        assetsFactory: (GenerationAssetFence) -> MessageGenerationAssets,
        validateOwner: (GenerationAssetOwner) -> Boolean,
        networkPolicy: GenerationDownloadAuthorization,
        isCurrentOwner: () -> Boolean,
        http: GenerationHttp = UrlConnectionGenerationHttp(),
    ): RunGenerationTools {
        check(leaf == null && !revoked) { "generation_leaf_already_opened_or_revoked" }
        val approval = approval(owner, authorization, isCurrentOwner)
        val download = downloadAuthorization(owner, networkPolicy, isCurrentOwner)
        return RunGenerationTools(preview.config, owner, authorization, preview.identity.accountScope,
            database, assetsFactory, { assetOwner ->
                synchronized(this) { !revoked && matchesAssetOwner(assetOwner) } &&
                    runCatching { isCurrentOwner() && validateOwner(assetOwner) }.getOrDefault(false) &&
                    synchronized(this) { !revoked }
            },
            approval, download, http).also { leaf = it }
    }

    @Synchronized private fun pinTask(owner: RunExecutionOwner, authorization: TaskAuthorization) {
        check(!revoked && boundOwner === owner && effects != null) { "generation_owner_not_bound" }
        checkOwner(owner)
        require(authorization.taskId == preview.identity.runId.value &&
            authorization.allowedWrites.containsAll(checkNotNull(effects)) &&
            (boundAuthorization == null || boundAuthorization === authorization)) { "generation_task_mismatch" }
        boundAuthorization = authorization
    }

    private fun checkOwner(owner: RunExecutionOwner) {
        val identity = preview.identity
        require(owner.conversationId == identity.conversationId && owner.runId == identity.runId &&
            owner.assistantMessageId == identity.assistantMessageId && (boundOwner == null || boundOwner === owner)) {
            "generation_owner_mismatch"
        }
    }

    private fun matchesAssetOwner(owner: GenerationAssetOwner): Boolean = with(preview.identity) {
        owner.accountScope == accountScope && owner.binding.conversationId == conversationId.value &&
            owner.binding.runId == runId.value && owner.binding.assistantMessageId == assistantMessageId.value
    }

    /** Cheap admission/token fence, safe on UI or inside the parent's control lock.
     * Does not wait for asset validators, cancel HTTP, or claim that cleanup has completed.
     * Already admitted work still requires close/awaitClose on the parent's cleanup worker.
     */
    fun revokeAdmission() {
        val captured = synchronized(this) { revoked = true; leaf }
        captured?.revokeAdmission()
    }

    /** May block on publication fences/transport. NEVER call on UI or under parent locks.
     * Revocation alone must not skip this second cancellation phase, even after UNKNOWN.
     */
    fun close() {
        revokeAdmission()
        val captured = synchronized(this) {
            if (cleanupStarted) return
            cleanupStarted = true
            leaf
        }
        try {
            captured?.close()
        } catch (_: InterruptedException) {
            recordCleanup(GenerationToolsCloseResult.Unknown("generation_close_interrupted"))
            Thread.currentThread().interrupt()
        } catch (_: Exception) {
            recordCleanup(GenerationToolsCloseResult.Unknown("generation_close_failed"))
        }
    }

    /** UNKNOWN (including interruption/failure) is sticky. Parent retains this token AND its
     * reservation until Drained plus dispatcher receipt persistence, otherwise quarantines it.
     */
    fun awaitClose(timeoutMillis: Long): GenerationToolsCloseResult {
        require(timeoutMillis >= 0)
        val captured = synchronized(this) {
            cleanupResult?.let { return it }
            check(revoked) { "generation_close_required" }
            if (!cleanupStarted) return recordCleanup(
                GenerationToolsCloseResult.Unknown("generation_cleanup_not_started"))
            leaf
        }
        val result = try {
            captured?.awaitClose(timeoutMillis) ?: GenerationToolsCloseResult.Drained
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            GenerationToolsCloseResult.Unknown("generation_await_interrupted")
        } catch (_: Exception) {
            GenerationToolsCloseResult.Unknown("generation_await_failed")
        }
        return recordCleanup(result)
    }

    @Synchronized private fun recordCleanup(result: GenerationToolsCloseResult): GenerationToolsCloseResult {
        // A concurrent successful waiter must never overwrite a failure/timeout.
        if (cleanupResult !is GenerationToolsCloseResult.Unknown &&
            (cleanupResult == null || result is GenerationToolsCloseResult.Unknown)) cleanupResult = result
        return checkNotNull(cleanupResult)
    }
}

/** One-shot, parent-owned lifetime slot. Allocate/retain it BEFORE mounting the UI.
 * The token is stored BEFORE invoking parent code, even if that code starts a leaf then throws.
 * No private threads/queues: requestCleanup must only enqueue/notify the parent's existing bounded
 * cleanup worker, never run close inline. A failed notification leaves this slot visibly pending;
 * the parent must retry it, not drop the slot or replace the reservation.
 */
internal class GenerationConfirmationHandoff(
    private val requestCleanup: (GenerationConfirmationHandoff) -> Unit,
) {
    private var retained: ConfirmedGenerationPreparation? = null
    private var pendingCleanup = false
    val token: ConfirmedGenerationPreparation? get() = synchronized(this) { retained }
    val cleanupRequested: Boolean get() = synchronized(this) { pendingCleanup }

    fun confirm(
        preparation: ControllerGenerationPreparation,
        currentEditor: GenerationEditorSnapshot,
        currentIdentity: GenerationPreparationIdentity,
        onConfirmed: (ConfirmedGenerationPreparation) -> Boolean,
    ): Boolean {
        val confirmed = synchronized(this) {
            check(retained == null) { "generation_handoff_already_used" }
            preparation.confirm(preparation.preview, currentEditor, currentIdentity).also { retained = it }
        }
        val accepted = runCatching { onConfirmed(confirmed) }.getOrDefault(false)
        if (!accepted) {
            confirmed.revokeAdmission()
            synchronized(this) { pendingCleanup = true }
            // Parent still owns this slot if scheduling fails (e.g. bounded queue saturation).
            // The pending flag is intentionally not cleared by notification or UI disposal.
            runCatching { requestCleanup(this) }
        }
        return accepted
    }
}
