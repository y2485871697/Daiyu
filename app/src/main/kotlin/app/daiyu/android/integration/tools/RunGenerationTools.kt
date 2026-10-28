package app.daiyu.android.integration.tools

import android.database.sqlite.SQLiteDatabase
import app.daiyu.android.domain.runtime.RunExecutionOwner
import app.daiyu.android.domain.tools.*
import app.daiyu.android.media.GenerationAssetFence
import app.daiyu.android.media.GenerationAssetOwner
import app.daiyu.android.media.MessageGenerationAssets
import app.daiyu.android.model.generation.*
import app.daiyu.android.storage.GenerationAttemptBinding
import app.daiyu.android.storage.GenerationOutputReference
import app.daiyu.android.storage.SQLiteGenerationAttemptStore
import app.daiyu.android.storage.SQLiteGenerationOutputStore
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.TimeUnit

/** Called on the execution worker AFTER all children passed the real client preflight.
 * Must explicitly review this original frozen config, real invocation and exact paid write.
 * There is deliberately no default approval or capability-only/READ_ONLY fallback.
 */
internal fun interface GenerationRunApproval {
    fun review(request: GenerationRunReview): GenerationRunDecision
}
internal enum class GenerationRunDecision { APPROVED, DENIED }
internal class GenerationRunReview(
    val context: ToolExecutionContext,
    val config: GenerationRunConfig,
    val batch: GenerationRunConfig.Batch,
    plans: List<GenerationRequestPlan>,
    val effect: WriteEffect,
) {
    val plans: List<GenerationRequestPlan> = Collections.unmodifiableList(plans.toList())
}

/** Must enforce host/DNS/redirect policy, including the authenticated video content URL.
 * No allow-all implementation is supplied. This grant is separate from paid generation approval.
 */
internal fun interface GenerationDownloadAuthorization {
    fun allows(owner: GenerationAssetOwner, url: String): Boolean
}

internal data class GenerationRunItemResult(
    val identity: GenerationIdentity,
    val submission: Submission,
    val references: List<GenerationOutputReference>,
    val failures: Map<Int, GenerationFailure>,
) {
    override fun toString() = "GenerationRunItemResult(<redacted>)"
}
internal data class GenerationRunOutcome(
    val binding: GenerationAttemptBinding,
    val items: List<GenerationRunItemResult>,
) {
    override fun toString() = "GenerationRunOutcome(<redacted>)"
}
internal sealed interface GenerationToolsCloseResult {
    data object Drained : GenerationToolsCloseResult
    data class Unknown(val reason: String) : GenerationToolsCloseResult
}

/** Leaf only: parent registers these tools with its EXISTING dispatcher/ledger.
 * All synchronous I/O runs on the parent-owned worker. Constructor/prepare/admit do no I/O.
 * Parent must persist the original tool call before dispatch, and own this leaf until drain.
 */
internal class RunGenerationTools(
    private val config: GenerationRunConfig?,
    private val owner: RunExecutionOwner,
    private val authorization: TaskAuthorization,
    private val accountScope: String,
    private val database: () -> SQLiteDatabase,
    private val assetsFactory: (GenerationAssetFence) -> MessageGenerationAssets,
    private val validateOwner: (GenerationAssetOwner) -> Boolean,
    private val approval: GenerationRunApproval,
    private val downloadAuthorization: GenerationDownloadAuthorization,
    private val http: GenerationHttp = UrlConnectionGenerationHttp(),
    // Exact-type test seam; production uses the same application DB for BOTH stores.
    private val attemptStoreFactory: (GenerationAttemptBinding) -> GenerationAttemptStore = {
        SQLiteGenerationAttemptStore(database, it)
    },
    // Production parent supplies Resources::createFence so readers share the owner gate.
    private val fenceFactory: (GenerationAssetOwner, (GenerationAssetOwner) -> Boolean) -> GenerationAssetFence =
        { assetOwner, validator -> GenerationAssetFence(assetOwner, validator) },
) : AutoCloseable {
    init {
        require(accountScope.isNotBlank() && accountScope.length <= 200)
        require(authorization.taskId == owner.runId.value)
    }
    private val lock = Object()
    private var closed = false
    private var closeStarted = false
    private var closing = false
    private var closeFailed = false
    private var workers = 0
    private val fences = mutableListOf<GenerationAssetFence>()
    private val outcomes = linkedMapOf<String, GenerationRunOutcome>()
    private val cancellation = GenerationCancellation()

    val registeredTools: List<RegisteredTool> = config?.let { frozen ->
        listOf(false, true).mapNotNull { video ->
            val batches = frozen.batches.filter {
                (it.protocol == GenerationProtocol.OPENAI_VIDEO_MULTIPART) == video
            }.associateBy { it.id }
            if (batches.isEmpty()) null else object : RegisteredTool {
                override val definition = ToolDefinition(
                    if (video) VIDEO_TOOL else IMAGE_TOOL, 1,
                    "Execute one parent-frozen, explicitly approved paid generation batch. " +
                        "Pending/unknown is not permission to resubmit under a new call ID.",
                    CAPABILITY, ToolEffect.WRITE,
                    ToolSchema(listOf(ArgumentField("batch_id", ArgumentType.Text(128, batches.keys))))
                )
                override fun prepare(arguments: ToolArguments): PreparedTool {
                    val batch = batches.getValue(arguments.text("batch_id"))
                    val canonical = arguments.canonicalJson()
                    val effect = writeEffect(accountScope, owner, frozen, batch)
                    require(effect in authorization.allowedWrites) { "generation_write_not_authorized" }
                    return PreparedTool.contextual(effect, admit = { context ->
                        admit(context, definition, canonical, effect)
                    }) { context ->
                        admit(context, definition, canonical, effect) ?: execute(context, frozen, batch, effect)
                    }
                }
            }
        }
    } ?: emptyList() // Unconfigured generation is never advertised.

    private fun admit(context: ToolExecutionContext, definition: ToolDefinition,
        arguments: String, effect: WriteEffect): ExecutionReceipt.NotExecuted? = when {
        context.owner !== owner -> ExecutionReceipt.NotExecuted("generation_owner_mismatch")
        context.authorization !== authorization -> ExecutionReceipt.NotExecuted("generation_authorization_mismatch")
        context.invocation.name != definition.name -> ExecutionReceipt.NotExecuted("generation_invocation_mismatch")
        runCatching { definition.schema.parse(context.invocation.arguments).canonicalJson() }.getOrNull() != arguments ->
            ExecutionReceipt.NotExecuted("generation_invocation_mismatch")
        effect !in context.authorization.allowedWrites -> ExecutionReceipt.NotExecuted("generation_write_not_authorized")
        synchronized(lock) { closed } -> ExecutionReceipt.NotExecuted("generation_closed")
        else -> null
    }

    /** Ordinary dynamic facade keeps the original call and the same exact invocation grant. */
    internal fun executeApproved(context: ToolExecutionContext, batch: GenerationRunConfig.Batch): ExecutionReceipt {
        val frozen = config ?: return ExecutionReceipt.NotExecuted("generation_unconfigured")
        val effect = writeEffect(accountScope, owner, frozen, batch)
        if (context.owner !== owner || context.authorization !== authorization ||
            context.authorization.allowedWrites != setOf(effect) ||
            context.invocation.name != if (batch.protocol == GenerationProtocol.OPENAI_VIDEO_MULTIPART) VIDEO_TOOL else IMAGE_TOOL)
            return ExecutionReceipt.NotExecuted("generation_invocation_mismatch")
        return execute(context, frozen, batch, effect)
    }

    private fun execute(context: ToolExecutionContext, frozen: GenerationRunConfig,
        batch: GenerationRunConfig.Batch, effect: WriteEffect): ExecutionReceipt {
        // Never use synthetic/default owner or a random operation/call ID.
        val binding = GenerationAttemptBinding(context.owner.conversationId.value,
            context.owner.assistantMessageId.value, context.owner.runId.value,
            context.invocation.id.value, context.invocation.name)
        val assetOwner = GenerationAssetOwner(accountScope, binding)
        val fence = fenceFactory(assetOwner, validateOwner)
        require(fence.owner.fields() == assetOwner.fields()) { "generation_fence_owner_mismatch" }
        synchronized(lock) {
            if (closed) return ExecutionReceipt.NotExecuted("generation_closed")
            fences.add(fence)
            workers++
        }
        try {
            if (context.cancelled()) return ExecutionReceipt.NotExecuted("generation_cancelled")
            if (!validateOwner(assetOwner)) return ExecutionReceipt.NotExecuted("generation_owner_rejected")
            val assets = assetsFactory(fence)
            val io = GenerationIo(assets, attemptStoreFactory(binding),
                { url -> downloadAuthorization.allows(assetOwner, url) }, http,
                { context.cancelled() || synchronized(lock) { closed } }, cancellation)
            val images = ImageGenerationClient(io)
            val videos = VideoGenerationClient(io)
            val isVideo = batch.protocol == GenerationProtocol.OPENAI_VIDEO_MULTIPART
            val requests = batch.requests(binding::identity)
            // No approval and no POST if even the last sibling fails media/protocol preflight.
            val plans = try {
                requests.map { if (isVideo) videos.prepare(it) else images.prepare(it) }
            } catch (failure: GenerationPreflightException) {
                return ExecutionReceipt.NotExecuted("generation_preflight_${failure.failure.name.lowercase()}")
            }
            if (context.cancelled() || cancellation.isCancellationRequested)
                return ExecutionReceipt.NotExecuted("generation_cancelled")
            if (approval.review(GenerationRunReview(context, frozen, batch, plans, effect)) != GenerationRunDecision.APPROVED)
                return ExecutionReceipt.NotExecuted("generation_approval_denied")
            if (context.cancelled() || cancellation.isCancellationRequested)
                return ExecutionReceipt.NotExecuted("generation_cancelled")
            // The original client also re-preflights the COMPLETE batch before its first POST.
            val submitted = if (isVideo) videos.submitBatch(requests) else images.submitBatch(requests)
            val outputStore = SQLiteGenerationOutputStore(database, assetOwner, assets)
            val items = submitted.mapIndexed { index, item ->
                val plan = plans[index]
                var result = item.submission
                var polls = 0
                while (isVideo && result is Submission.Pending && polls < frozen.maxPolls &&
                    !context.cancelled() && !cancellation.isCancellationRequested) {
                    // Only GET on the original handle. Unknown ends polling; NEVER a second POST.
                    result = videos.poll(plan, result.handle)
                    polls++
                }
                val refs = mutableListOf<GenerationOutputReference>()
                val failures = linkedMapOf<Int, GenerationFailure>()
                if (result is Submission.Completed) result.outputs.forEach { output ->
                    if (output.failure != null) failures[output.index] = output.failure
                    else when (val committed = io.downloadAndCommit(plan, output)) {
                        is GenerationAssetResult.Failed -> failures[output.index] = committed.reason
                        is GenerationAssetResult.Committed -> try {
                            // A local file receipt is NOT a registered reference. Only this successful
                            // same-DB read-back permits the asset ID to reach a tool payload.
                            val pending = assets.pending(committed.receipt)
                            refs.add(outputStore.register(GenerationAttempt(plan.identity, plan.fingerprint, plan.protocol), pending))
                        } catch (_: Exception) {
                            failures[output.index] = GenerationFailure.ASSET_REJECTED
                        }
                    }
                }
                GenerationRunItemResult(item.identity, result, refs.toList(), failures.toMap())
            }
            val outcome = GenerationRunOutcome(binding, items)
            synchronized(lock) { outcomes[binding.operationId] = outcome }
            // Keep partial references in the DB and host outcome snapshot, even on UNKNOWN.
            if (items.any { it.submission is Submission.Pending || it.submission is Submission.Unknown || it.failures.isNotEmpty() })
                return ExecutionReceipt.Unknown("generation_pending_or_unconfirmed")
            if (items.all { it.submission is Submission.NotExecuted })
                return ExecutionReceipt.NotExecuted("generation_not_executed")
            return ExecutionReceipt.Confirmed(payload(outcome))
        } catch (_: Exception) {
            // Conservative: after worker admission a store/approval/host error is not retry permission.
            return ExecutionReceipt.Unknown("generation_execution_unconfirmed")
        } finally {
            synchronized(lock) { workers--; lock.notifyAll() }
        }
    }

    /** Private host state: may contain signed provider URLs in Submission. Never log/export raw. */
    fun outcomes(): List<GenerationRunOutcome> = synchronized(lock) { outcomes.values.toList() }

    /** Synchronous local admission seal: no validator, transport, SQL or publication wait.
     * Never revokes the persisted owner's shared gate or accepted history readers.
     * Parent MUST then schedule close() on its cleanup worker and observe awaitClose().
     */
    fun revokeAdmission() {
        val captured = synchronized(lock) { closed = true; fences.toList() }
        captured.forEach { it.revokeAdmission() }
    }

    /** Seal admission, revoke EVERY publication fence, THEN abort transport; no external call under lock.
     * May wait for a publication/validator already inside a fence. Invoke off UI/controller locks.
     * Admission sealing is NOT cleanup: a preceding revokeAdmission() must not skip this work.
     */
    override fun close() {
        revokeAdmission()
        val captured = synchronized(lock) {
            closed = true
            if (closeStarted) return
            closeStarted = true
            closing = true
            fences.toList()
        }
        try {
            captured.forEach { it.revoke() }
            cancellation.cancel()
        } catch (_: Exception) {
            synchronized(lock) { closeFailed = true }
        } finally {
            synchronized(lock) { closing = false; lock.notifyAll() }
        }
    }

    /** Physical worker/registration exit AND HTTP cleanup; a cancel request alone is never drain.
     * Parent must separately wait for its dispatcher receipt persistence to finish.
     */
    fun awaitClose(timeoutMillis: Long): GenerationToolsCloseResult {
        require(timeoutMillis >= 0)
        val budget = TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        val start = System.nanoTime()
        synchronized(lock) {
            if (!closed) return GenerationToolsCloseResult.Unknown("generation_not_closed")
            if (!closeStarted) return GenerationToolsCloseResult.Unknown("generation_cleanup_not_started")
            while (closing || workers != 0) {
                val left = budget - (System.nanoTime() - start)
                if (left <= 0) return GenerationToolsCloseResult.Unknown("generation_worker_not_drained")
                TimeUnit.NANOSECONDS.timedWait(lock, left)
            }
            if (closeFailed) return GenerationToolsCloseResult.Unknown("generation_close_failed")
        }
        val remaining = (budget - (System.nanoTime() - start)).coerceAtLeast(0)
        val state = cancellation.awaitDrained(TimeUnit.NANOSECONDS.toMillis(remaining))
        return if (state.isDrained && state.cleanupFailures == 0) GenerationToolsCloseResult.Drained
        else GenerationToolsCloseResult.Unknown("generation_http_not_drained")
    }

    private fun payload(outcome: GenerationRunOutcome): String = JSONObject().put("items", JSONArray().also { array ->
        outcome.items.forEach { item ->
            array.put(JSONObject().put("item_id", item.identity.itemId)
                .put("status", if (item.submission is Submission.Completed) "completed" else "not_executed")
                .put("assets", JSONArray().also { assets -> item.references.forEach {
                    assets.put(JSONObject().put("asset_id", it.receipt.assetId).put("output_index", it.outputIndex)
                        .put("kind", it.kind.name.lowercase()).put("mime_type", it.mimeType))
                } }))
        }
    }).toString()

    companion object {
        const val IMAGE_TOOL = "generate_images"
        const val VIDEO_TOOL = "generate_videos"
        const val CAPABILITY = "generation.paid"
        /** Pure: obtain permission for this EXACT effect after the runner supplies its real owner.
         * Stable persisted scope, not owner object identity, authorization/session ID or model call ID:
         * the central ledger matches semantic effects across sessions/tasks. Never replay another
         * owner's asset IDs or UNKNOWN, but keep same-owner new-call retries protected.
         */
        fun writeEffect(accountScope: String, owner: RunExecutionOwner,
            config: GenerationRunConfig, batch: GenerationRunConfig.Batch): WriteEffect {
            require(accountScope.isNotBlank() && accountScope.length <= 200)
            require(config.batches.any { it === batch })
            val scope = MessageDigest.getInstance("SHA-256").run {
                listOf("generation-owner-scope-v2", accountScope, owner.conversationId.value,
                    owner.runId.value, owner.assistantMessageId.value, batch.id).forEach { value ->
                    val bytes = value.toByteArray(Charsets.UTF_8)
                    update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array())
                    update(bytes)
                }
                digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            }
            return WriteEffect("generation", "submit_batch", scope, config.fingerprint)
        }
    }
}
