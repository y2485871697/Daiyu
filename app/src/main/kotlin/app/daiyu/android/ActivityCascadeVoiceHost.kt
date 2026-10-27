package app.daiyu.android

import android.content.Context
import android.os.Handler
import android.os.Looper
import app.daiyu.android.domain.Message
import app.daiyu.android.domain.MessageBlock
import app.daiyu.android.domain.RunStatus
import app.daiyu.android.voice.*
import app.daiyu.android.voice.personal.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One explicit, manually confirmed text send. No chat client, reasoning observer or auto-submit. */
internal class ActivityCascadeVoiceHost(
    private val context: Context,
    private val runtime: ControllerVoiceRuntime,
    private val gate: AudioOwnerGate = ActivityVoiceAudio.gate,
    private val outputFactory: ((VoiceOwner, FrozenVoiceSpeech) -> SpeechOutput)? = null,
    private val admission: () -> Boolean,
) : AutoCloseable {
    private var armed: FrozenVoiceSpeech? = null
    private var armedPersonal: ExplicitPersonalCascadePermission? = null
    private var pendingOffer: PersonalCascadeOffer? = null
    private var ticket: Any? = null
    private var bound: Message? = null
    private var owner: VoiceOwner? = null
    private var chain: VoiceChain? = null
    private var received = ""
    private var completed = false
    private var expiry: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @Volatile private var epoch = 0L
    @Volatile private var personalSession: PersonalSession? = null
    internal val operationToken: Long get() = epoch
    private val mutable = MutableStateFlow("")
    val status = mutable.asStateFlow()

    @Volatile private var disposed = false
    private val unobserve = runtime.observeInvalidation { profile ->
        if (profile == null || profile == VoiceProfile.CONVERSATION)
            releaseOnMain(Handler(Looper.getMainLooper())) { close() }
    }
    /** Permanent Activity teardown. close() alone intentionally remains a reusable Stop. */
    fun dispose() {
        if (disposed) return
        disposed = true
        unobserve()
        try { close() } finally { scope.cancel() }
    }
    /** Ordinary explicit permission. Never implies permission for personal multi-upload synthesis. */
    fun arm() {
        check(!disposed) { "cascade_host_disposed" }
        close()
        val frozen = runtime.prepareSpeech(VoiceProfile.CONVERSATION)
        require(!frozen.isPersonal) { "cascade_personal_requires_explicit_finite_permission" }
        check(admission())
        armed = frozen
        mutable.value = "级联已授权下一条回答：点击听写、检查草稿后手动发送；不会自动发送。回答按完整句子朗读，云端可能按句收费。"
    }
    /** Preparation reads local references only. A requested cascade may use trusted finite default permission. */
    suspend fun preparePersonalConsent(): PersonalCascadeOffer? {
        check(!disposed) { "cascade_host_disposed" }
        close()
        check(admission())
        val token = epoch
        var frozen = runtime.prepareSpeech(VoiceProfile.CONVERSATION)
        if (!frozen.isPersonal) return null
        if (frozen.configuration.voice.startsWith(MimoPersonalVoices.PREFIX)) frozen = runtime.authorizeReference(frozen)
        check(epoch == token && admission() && runtime.current(frozen))
        val binding = checkNotNull(runtime.personalBinding(frozen)) { "personal_voice_not_available" }
        return PersonalCascadeOffer(token, frozen, binding,
            PersonalVoiceCascadeLimits(maxSentences = 8, maxCharacters = 1600,
                maxAudioDurationMs = 120_000, validForMs = 300_000), perRequestAudioMs = 15_000)
            .also { pendingOffer = it }
    }
    fun consentCurrent(offer: PersonalCascadeOffer): Boolean = !disposed && pendingOffer === offer && epoch == offer.hostToken &&
        admission() && runtime.personalBinding(offer.frozen) == offer.binding
    fun cancelPersonalConsent(offer: PersonalCascadeOffer) {
        // A delayed dismiss from an old dialog must never cancel a newer arm/answer.
        if (pendingOffer === offer && epoch == offer.hostToken) close()
    }
    fun armPersonal(permission: ExplicitPersonalCascadePermission) {
        val offer = permission.offer
        check(consentCurrent(offer)) { "personal_consent_stale" }
        check(nowMs() - permission.acceptedAtMs in 0 until offer.limits.validForMs)
        pendingOffer = null
        armed = offer.frozen
        armedPersonal = permission
        val token = epoch
        expiry = scope.launch {
            delay(offer.limits.validForMs)
            if (epoch == token) { close(); mutable.value = "个人级联授权已过期，未重试" }
        }
        mutable.value = "有限个人级联已明确授权：仅下一次手动发送受理的回答；最多 8 次上传 / 1600 字符 / 120 秒音频预约，5 分钟有效，可能逐次收费。"
    }
    fun reserve(sendTicket: Any) {
        val frozen = armed ?: return
        if (ticket != null || !admission() || !runtime.current(frozen)) { close(); return }
        ticket = sendTicket
        val permission = armedPersonal ?: return
        val offer = permission.offer
        val remaining = offer.limits.validForMs - (nowMs() - permission.acceptedAtMs)
        if (remaining <= 0 || runtime.personalBinding(frozen) != offer.binding) { close(); return }
        val session = PersonalSession(sendTicket, frozen, offer)
        personalSession = session
        try {
            session.grant = PersonalVoiceCascadeGrant.authorize(offer.binding, offer.limits.copy(validForMs = remaining), sendTicket,
                currentBinding = { runtime.personalBinding(frozen) },
                // Callbacks run under grant lock: ONLY volatile immutable state, no host lock/I/O/re-entry.
                ownerIsCurrent = { candidate -> personalSession === session && session.admitted && session.answer == candidate })
        } catch (_: Exception) { close(); mutable.value = "个人级联额度或绑定已失效，未上传" }
    }
    fun rejected(sendTicket: Any) {
        if (ticket === sendTicket) { close(); mutable.value = "级联发送未确认，未启动朗读；不会自动重试" }
    }
    fun accepted(sendTicket: Any, assistant: Message?) {
        if (ticket !== sendTicket) return
        var frozen = armed
        val hadPersonalPermission = armedPersonal != null
        ticket = null; armed = null; armedPersonal = null
        if (frozen == null || assistant == null || assistant.role != Message.ASSISTANT_ROLE || !admission() || !runtime.current(frozen)) {
            close(); mutable.value = "级联未绑定：发送身份或语音配置已失效"; return
        }
        bound = assistant
        completed = false
        val identity = VoiceOwner(assistant.conversationId.value, assistant.runId.value)
        owner = identity
        val token = epoch // The original consent/send epoch remains valid until retirement.
        if (hadPersonalPermission) {
            val session = personalSession
            val answer = PersonalVoiceCascadeOwner(assistant.conversationId.value, assistant.runId.value, assistant.id.value)
            if (session == null) { close(); return }
            session.answer = answer; session.admitted = true
            if (session.grant?.bindAnswer(sendTicket, answer) != true) {
                close(); mutable.value = "个人级联未绑定原始发送，未上传"; return
            }
            frozen = frozen.withUpload(PersonalCascadeUpload { requestId, text ->
                check(epoch == token && personalSession === session && admission() && session.admitted)
                val use = checkNotNull(session.grant?.consume(session.sendTicket, answer, requestId, text, session.offer.perRequestAudioMs)) {
                    "personal_cascade_budget_or_binding_denied"
                }
                use.reservedAudioDurationMs
            }, session.offer.perRequestAudioMs)
        }
        val selected = frozen
        val base: SpeechOutput = outputFactory?.invoke(identity, selected)
            ?: if (selected.configuration.engine == "SYSTEM") ProductionSpeechOutput(context, identity)
            else remoteOutput(identity, selected)
        val next = VoiceChain(gate, base) { value ->
            if (epoch == token) mutable.value = "级联 ${value.phase}（${value.playbackEvidence}）；失败或结果未知不重试"
        }
        chain = next
        next.begin(identity) { admission() && runtime.current(selected) && epoch == token }
        update(assistant, listOf(assistant), assistant.conversationId.value)
    }
    /** Feed only the exact persisted/admitted assistant owner, and only Text body snapshots. */
    fun update(active: Message?, messages: List<Message>, conversation: String?) {
        val expected = bound ?: return
        if (!admission() || conversation != expected.conversationId.value ||
            (active != null && (active.id != expected.id || active.runId != expected.runId))) { close(); return }
        val value = (listOfNotNull(active) + messages).firstOrNull {
            it.id == expected.id && it.runId == expected.runId && it.conversationId == expected.conversationId &&
                it.role == Message.ASSISTANT_ROLE
        } ?: run { close(); return }
        val session = personalSession
        if (session != null && runtime.personalBinding(session.frozen) != session.offer.binding) { close(); return }
        val identity = owner ?: return
        val output = chain ?: return
        val snapshot = value.blocks.filterIsInstance<MessageBlock.Text>().joinToString("") { it.text }
        try {
            when (value.status) {
                RunStatus.RUNNING -> {
                    if (completed || !snapshot.startsWith(received)) { close(); mutable.value = "正文非追加修订，级联已停止；请手动朗读最终回答"; return }
                    if (snapshot.length > received.length) output.delta(identity, snapshot.substring(received.length))
                    received = snapshot
                }
                RunStatus.COMPLETED -> if (!completed) { completed = true; received = snapshot; output.done(identity, snapshot) }
                else -> { close(); mutable.value = "聊天运行已停止或失败，级联朗读已取消" }
            }
        } catch (_: Exception) { close(); mutable.value = "级联朗读失败，未回退或重试" }
    }
    private fun remoteOutput(identity: VoiceOwner, frozen: FrozenVoiceSpeech): SpeechOutput {
        val outputScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val handler = Handler(Looper.getMainLooper())
        val delegate = ProductionSpeechOutput(identity, {
            val player = RemoteVoiceHostPlayer(context, outputScope, runtime, frozen)
            object : ProductionSpeechPlayer {
                override val state get() = player.state
                override fun bindOwner(owner: String) = player.bindOwner(owner)
                override fun play(owner: String, requestId: String, text: String) = player.play(owner, requestId, text)
                override fun close() = player.closeStrict()
            }
        }, outputScope, { action -> check(handler.post { action() }) },
            { action -> releaseOnMain(handler, action) }, ::voiceEvent)
        return object : SpeechOutput by delegate { override val startedEvidence = PlaybackEvidence.MEDIA_PLAYER_START }
    }
    override fun close() {
        epoch++
        // Revoke/fence before cancel/drain. No host control lock is used by this adapter.
        val session = personalSession; personalSession = null
        session?.admitted = false
        session?.grant?.revoke()
        armed = null; armedPersonal = null; pendingOffer = null; ticket = null
        bound = null; owner = null; received = ""; completed = false
        val priorExpiry = expiry; expiry = null
        val prior = chain; chain = null
        priorExpiry?.cancel()
        prior?.close()
        mutable.value = ""
    }
    private class PersonalSession(val sendTicket: Any, val frozen: FrozenVoiceSpeech, val offer: PersonalCascadeOffer) {
        @Volatile var answer: PersonalVoiceCascadeOwner? = null
        @Volatile var admitted = false
        @Volatile var grant: PersonalVoiceCascadeGrant? = null
    }
    private fun nowMs() = System.nanoTime() / 1_000_000L
}
