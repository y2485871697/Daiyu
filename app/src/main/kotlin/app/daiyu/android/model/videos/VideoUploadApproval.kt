package app.daiyu.android.model.videos

import app.daiyu.android.VideoDraftRequest
import app.daiyu.android.domain.Message
import app.daiyu.android.media.video.VideoReference
import app.daiyu.android.model.ProviderSnapshot
import app.daiyu.android.model.catalog.OpenRouterVideoEvidence
import app.daiyu.android.model.images.FrozenMessageImagePlan
import java.util.Collections

/** 宿主先加载完整且已授权的展开历史及附加数据，包含保留分段，再冻结审批输入。 */
internal class FrozenVideoApprovalRequest private constructor(
    val runId: String,
    val executionOwner: String,
    val conversation: String,
    val editRevision: Long,
    val text: String,
    val provider: ProviderSnapshot,
    val configurationRevision: Long,
    private val draft: VideoDraftRequest?,
    history: List<Message>,
    inputs: List<MessageVideoInput>,
) {
    private val history = FrozenMessageImagePlan.snapshot(history)
    private val inputs = inputs.map { MessageVideoInput(it.owner, it.references) }
    val inventory: List<VideoReference> = Collections.unmodifiableList(
        this.inputs.flatMap { it.references } + draft?.videos.orEmpty())
    val totalBytes: Long = inventory.fold(0L) { total, ref -> Math.addExact(total, ref.bytes) }

    init {
        require(runId.isNotBlank() && executionOwner.isNotBlank() && conversation.isNotBlank())
        require(editRevision >= 0 && configurationRevision >= 0)
        require(provider.asModelSettings().requestEndpoint() == OpenRouterVideoCapability.ENDPOINT)
        VideoRequestPolicy.preflight(provider.asModelSettings())
        require(inventory.isNotEmpty() && inventory.size <= 4)
        require(inventory.all { it.mime == "video/mp4" && it.bytes in 1..MAX_BYTES &&
            it.sha256.matches(Regex("[0-9a-f]{64}")) })
        require(totalBytes in 1..MAX_BYTES)
        require(inventory.map { it.owner to it.id }.distinct().size == inventory.size)
        require(this.inputs.map { it.owner }.distinct().size == this.inputs.size)
        this.inputs.forEach { input ->
            require(input.references.isNotEmpty() && input.references.all { it.owner == input.owner })
            val row = this.history.single { it.conversationId.value == input.owner.conversationId &&
                it.id.value == input.owner.messageId }
            require(row.role == "user" && row.protocolItems.isEmpty())
        }
        draft?.let {
            require(it.owner.conversationId == conversation && it.revision == editRevision && it.text == text)
            require(it.videos.isNotEmpty() && it.videos.all { ref -> ref.owner == it.owner })
        }
    }

    internal fun sameAs(other: FrozenVideoApprovalRequest): Boolean =
        runId == other.runId && executionOwner == other.executionOwner && conversation == other.conversation &&
            editRevision == other.editRevision && text == other.text && provider === other.provider &&
            configurationRevision == other.configurationRevision && draft === other.draft &&
            history == other.history && inventory == other.inventory &&
            inputs.map { it.owner to it.references } == other.inputs.map { it.owner to it.references }

    override fun toString() = "FrozenVideoApprovalRequest(<private run input>)"

    companion object {
        private const val MAX_BYTES = 16L * 1024 * 1024

        fun forDraft(
            draft: VideoDraftRequest, runId: String, executionOwner: String,
            provider: ProviderSnapshot, configurationRevision: Long,
            fullHistory: List<Message>, allHistoryVideos: List<MessageVideoInput>,
        ) = FrozenVideoApprovalRequest(runId, executionOwner, draft.owner.conversationId, draft.revision,
            draft.text, provider, configurationRevision, draft, fullHistory, allHistoryVideos)

        /** Ordinary text sends also need approval when their retained history contains videos. */
        fun forText(
            conversation: String, runId: String, executionOwner: String, editRevision: Long, text: String,
            provider: ProviderSnapshot, configurationRevision: Long,
            fullHistory: List<Message>, allHistoryVideos: List<MessageVideoInput>,
        ) = FrozenVideoApprovalRequest(runId, executionOwner, conversation, editRevision, text,
            provider, configurationRevision, null, fullHistory, allHistoryVideos)
    }
}

/** 按本闸门登记的对象身份校验提示和票据；能力依据通过实时请求另行获取。 */
internal class VideoApprovalGate {
    class Prompt internal constructor(internal val request: FrozenVideoApprovalRequest) {
        val videoCount: Int get() = request.inventory.size
        val totalBytes: Long get() = request.totalBytes
        val inventory: List<VideoReference> get() = request.inventory
        val disclosure: String get() =
            "本次发送将向 OpenRouter 上传 $videoCount 个原视频，共 $totalBytes 字节。" +
                "本次 run 的工具续轮可能再次上传这些原件；此次确认不授权将来的新 run。"
        override fun toString() = "VideoUploadPrompt(<private inventory>)"
    }
    class Ticket internal constructor()
    class Consumption internal constructor(val request: FrozenVideoApprovalRequest)
    private var prompt: Prompt? = null
    private var ticket: Ticket? = null
    private var consumption: Consumption? = null

    fun offer(request: FrozenVideoApprovalRequest): Prompt {
        revoke()
        return Prompt(request).also { prompt = it }
    }
    fun confirm(expected: Prompt, current: FrozenVideoApprovalRequest): Ticket {
        check(prompt === expected && ticket == null && expected.request.sameAs(current)) {
            "Video approval is stale or already confirmed"
        }
        return Ticket().also { ticket = it }
    }
    fun consume(expected: Ticket, current: FrozenVideoApprovalRequest): Consumption {
        val offered = checkNotNull(prompt) { "No current video approval" }
        check(ticket === expected && offered.request.sameAs(current)) { "Video approval does not match this run" }
        revoke()
        return Consumption(offered.request).also { consumption = it }
    }
    fun authorizes(consumed: Consumption): Boolean = consumption === consumed
    fun revoke() { prompt = null; ticket = null; consumption = null }
}

/** 在宿主线程使用；编辑、归属、页面、生命周期或配置变化时，同步 revoke 并取消运行。
 * 确认、激活和续接前重读当前状态，返回能力仅限本轮使用，并保留使用前校验。
 */
internal class VideoUploadApprovalController {
    class Run internal constructor()
    private val thread = Thread.currentThread()
    private val gate = VideoApprovalGate()
    private var evidence: OpenRouterVideoEvidence? = null
    private var activeRun: Run? = null
    private var binding: FrozenVideoApprovalRequest? = null
    private var capability: OpenRouterVideoCapability? = null

    private fun checkThread() = check(Thread.currentThread() === thread) { "Video approval is host-thread confined" }

    fun present(request: FrozenVideoApprovalRequest, proof: OpenRouterVideoEvidence): VideoApprovalGate.Prompt {
        checkThread()
        revoke()
        check(proof.claim(request.provider, request.configurationRevision)) { "Video evidence is stale or already used" }
        evidence = proof
        return gate.offer(request)
    }

    /** Confirm only this fresh offer after current-run checks, including trusted default permission. */
    fun confirm(prompt: VideoApprovalGate.Prompt, current: FrozenVideoApprovalRequest): VideoApprovalGate.Ticket {
        checkThread()
        check(evidence?.matches(current.provider, current.configurationRevision) == true)
        return gate.confirm(prompt, current)
    }

    /** Consumes the ticket exactly once. Subsequent tool rounds use this same run, not the ticket. */
    fun activate(ticket: VideoApprovalGate.Ticket, current: FrozenVideoApprovalRequest): Run {
        checkThread()
        check(activeRun == null)
        val proof = checkNotNull(evidence)
        check(proof.matches(current.provider, current.configurationRevision))
        val consumed = gate.consume(ticket, current)
        val approved = consumed.request
        // Neither boolean comes from UI settings, a model label, or a persistent switch.
        capability = OpenRouterVideoCapability.grant(
            model = approved.provider.model,
            endpoint = approved.provider.asModelSettings().requestEndpoint(),
            verifiedInlineMp4Support = proof.matches(approved.provider, approved.configurationRevision),
            uploadEnabled = gate.authorizes(consumed), // only this gate's authentic, consumed ticket
        )
        binding = approved
        return Run().also { activeRun = it }
    }

    /** Same capability for freeze/model/tool rounds, with full frozen-run validation each time. */
    fun capabilityFor(run: Run, current: FrozenVideoApprovalRequest): OpenRouterVideoCapability {
        checkThread()
        check(activeRun === run && binding?.sameAs(current) == true &&
            evidence?.matches(current.provider, current.configurationRevision) == true) { "Video run is no longer authorized" }
        return checkNotNull(capability)
    }

    fun revoke() {
        checkThread()
        gate.revoke()
        evidence = null
        activeRun = null
        binding = null
        capability = null
    }
}
