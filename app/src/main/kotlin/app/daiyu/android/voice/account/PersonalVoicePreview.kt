package app.daiyu.android.voice.account

import app.daiyu.android.voice.providers.DoubaoPersonalVoice
import app.daiyu.android.voice.providers.SpeechProviderConfig
import java.net.URI
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Mandatory parent injection: implementation must acquire the EXISTING VoiceSessionGate before
 * fetching/playing, cancel on gate revocation, and report stopped after releasing that lease.
 * Never instantiate a second gate, MediaPlayer or synthesis client in this leaf.
 * Callbacks and methods are main-thread confined. stop() must cancel preparing as well as playback.
 */
internal fun interface VoiceAccountPreviewPort {
    fun play(request: AccountPreviewRequest, listener: Listener): Lease
    fun interface Lease { fun stop() }
    interface Listener {
        fun started()
        fun stopped()
        fun failed()
    }
}
internal class AccountPreviewRequest(
    val configuration: SpeechProviderConfig,
    val voice: DoubaoPersonalVoice,
    val demoUrl: String,
    val demoOnly: Boolean = false,
) {
    override fun toString() = "AccountPreviewRequest(redacted)"
}

/** Generation ownership retained from frozen PersonalVoicePreview; actual output delegated to gate. */
internal class PersonalVoicePreview(private val port: VoiceAccountPreviewPort) {
    data class State(val account: String = "", val id: String = "", val loading: Boolean = false, val error: String? = null) {
        val active get() = id.isNotEmpty()
        fun matches(account: String, id: String) = active && this.account == account && this.id == id
    }
    private val mutable = MutableStateFlow(State())
    val state = mutable.asStateFlow()
    private var generation = 0
    private var lease: VoiceAccountPreviewPort.Lease? = null
    fun toggle(config: SpeechProviderConfig, voice: PersonalVoices.Voice) {
        if (mutable.value.matches(voice.account, voice.id)) { stop(); return }
        stop()
        val identity = VoiceAccountIdentity.from(config)
        require(identity.fingerprint == voice.account && voice.tts) { "请先确认此账户音色可用" }
        val url = try { URI(voice.demo) } catch (_: Exception) { throw IllegalArgumentException("试听地址无效") }
        require(url.scheme == "https" && !url.host.isNullOrBlank() && url.rawUserInfo == null && url.rawFragment == null) { "试听需要 HTTPS 地址" }
        val token = ++generation
        mutable.value = State(voice.account, voice.id, loading = true)
        try {
            val owned = port.play(AccountPreviewRequest(config, voice.providerVoice(), voice.demo, demoOnly = true), object : VoiceAccountPreviewPort.Listener {
                override fun started() {
                    if (token == generation) mutable.value = State(voice.account, voice.id)
                }
                override fun stopped() { if (token == generation) stop() }
                override fun failed() {
                    if (token != generation) return
                    stop(); mutable.value = State(error = "试听失败，请查询音色后重试")
                }
            })
            if (token == generation) lease = owned else owned.stop()
        } catch (_: Exception) {
            if (token == generation) { stop(); mutable.value = State(error = "试听未启动") }
        }
    }
    fun stop() {
        generation++
        val old = lease; lease = null
        mutable.value = State()
        runCatching { old?.stop() }
    }
    companion object {
        // Preserved bounded tail policy for the parent's port implementation; not a second player.
        fun drainDelayMs(duration: Int, position: Int): Long =
            (if (duration > 0 && position >= 0) (duration.toLong() - position).coerceIn(0L, 2000L) else 0L) + 150L
    }
}
