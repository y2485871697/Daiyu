package app.daiyu.android

import android.content.Context
import android.os.Handler
import android.os.Looper
import app.daiyu.android.domain.Message
import app.daiyu.android.domain.MessageBlock
import app.daiyu.android.domain.RunStatus
import app.daiyu.android.voice.*
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
    private var ticket: Any? = null
    private var bound: Message? = null
    private var owner: VoiceOwner? = null
    private var chain: VoiceChain? = null
    private var received = ""
    @Volatile private var epoch = 0L
    internal val operationToken: Long get() = epoch
    private val mutable = MutableStateFlow("")
    val status = mutable.asStateFlow()
    fun arm() {
        close()
        val frozen = runtime.prepareSpeech()
        // Personal references require a separate one-request confirmation; never turn selection
        // into blanket permission for an unknown number of billable sentence requests.
        require(!frozen.configuration.voice.startsWith("mimo-local-")) { "cascade_reference_requires_manual_read" }
        check(admission())
        armed = frozen
        mutable.value = "级联已授权下一条回答：点击听写、检查草稿后手动发送；不会自动发送。回答按完整句子朗读，云端可能按句收费。"
    }
    fun reserve(sendTicket: Any) {
        if (armed != null && admission()) ticket = sendTicket
    }
    fun rejected(sendTicket: Any) { if (ticket === sendTicket) { ticket = null; armed = null; mutable.value = "级联发送未确认，未启动朗读；不会自动重试" } }
    fun accepted(sendTicket: Any, assistant: Message?) {
        if (ticket !== sendTicket) return
        val frozen = armed
        ticket = null; armed = null
        if (frozen == null || assistant == null || !admission() || !runtime.current(frozen.configuration)) {
            mutable.value = "级联未绑定：发送身份或语音配置已失效"; return
        }
        check(assistant.role == Message.ASSISTANT_ROLE)
        bound = assistant
        val identity = VoiceOwner(assistant.conversationId.value, assistant.runId.value)
        owner = identity
        val token = ++epoch
        val base: SpeechOutput = outputFactory?.invoke(identity, frozen)
            ?: if (frozen.configuration.engine == "SYSTEM") ProductionSpeechOutput(context, identity)
            else remoteOutput(identity, frozen)
        val next = VoiceChain(gate, base) { value ->
            if (epoch == token) mutable.value = "级联 ${value.phase}（${value.playbackEvidence}）"
        }
        chain = next
        next.begin(identity) { admission() && runtime.current(frozen.configuration) && epoch == token }
        update(assistant, listOf(assistant), assistant.conversationId.value)
    }
    /** Feed only the exact persisted/admitted assistant owner, and only Text body snapshots. */
    fun update(active: Message?, messages: List<Message>, conversation: String?) {
        val expected = bound ?: return
        if (!admission() || conversation != expected.conversationId.value) { close(); return }
        val value = (listOfNotNull(active) + messages).firstOrNull {
            it.id == expected.id && it.runId == expected.runId && it.conversationId == expected.conversationId &&
                it.role == Message.ASSISTANT_ROLE
        } ?: return
        val identity = owner ?: return
        val output = chain ?: return
        val snapshot = value.blocks.filterIsInstance<MessageBlock.Text>().joinToString("") { it.text }
        try {
            when (value.status) {
                RunStatus.RUNNING -> {
                    if (!snapshot.startsWith(received)) { close(); mutable.value = "正文非追加修订，级联已停止；请手动朗读最终回答"; return }
                    if (snapshot.length > received.length) output.delta(identity, snapshot.substring(received.length))
                    received = snapshot
                }
                RunStatus.COMPLETED -> { bound = null; output.done(identity, snapshot) }
                else -> { close(); mutable.value = "聊天运行已停止或失败，级联朗读已取消" }
            }
        } catch (_: Exception) { close(); mutable.value = "级联朗读失败，未回退或重试" }
    }
    private fun remoteOutput(identity: VoiceOwner, frozen: FrozenVoiceSpeech): SpeechOutput {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val handler = Handler(Looper.getMainLooper())
        val delegate = ProductionSpeechOutput(identity, {
            val player = RemoteVoiceHostPlayer(context, scope, runtime, frozen)
            object : ProductionSpeechPlayer {
                override val state get() = player.state
                override fun bindOwner(owner: String) = player.bindOwner(owner)
                override fun play(owner: String, requestId: String, text: String) = player.play(owner, requestId, text)
                override fun close() = player.closeStrict()
            }
        }, scope, { action -> check(handler.post { action() }) },
            { action -> releaseOnMain(handler, action) }, ::voiceEvent)
        return object : SpeechOutput by delegate { override val startedEvidence = PlaybackEvidence.MEDIA_PLAYER_START }
    }
    override fun close() {
        epoch++; armed = null; ticket = null; bound = null; owner = null; received = ""
        val prior = chain; chain = null
        prior?.close()
        mutable.value = ""
    }
}
