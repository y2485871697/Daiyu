package app.daiyu.android

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import app.daiyu.android.voice.*
import app.daiyu.android.voice.duplex.*
import app.daiyu.android.voice.network.VoiceNetworkAdapters
import app.daiyu.android.voice.providers.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import java.util.UUID

/** Activity-bound product entry. Never starts on load/resume, never retries UNKNOWN. */
internal class ActivityRemoteVoiceHost(
    private val context: Context,
    private val scope: CoroutineScope,
    private val runtime: ControllerVoiceRuntime,
    private val isEditorCurrent: (DictationDraftTarget) -> Boolean,
    private val applyDraft: (DictationDraftTarget, String) -> Unit,
    private val requestPermission: () -> Unit,
) : AutoCloseable {
    private val gate = ActivityVoiceAudio.gate
    private val mutable = MutableStateFlow("")
    val status = mutable.asStateFlow()
    // Published only after the owned Android capture has actually started, never on a tap/connect.
    private val mutableListeningTarget = MutableStateFlow<DictationDraftTarget?>(null)
    val listeningTarget = mutableListeningTarget.asStateFlow()
    private var attempt: Attempt? = null
    private var duplex: DoubaoDuplexSession? = null
    private var observation: Job? = null
    private var epoch = 0L
    internal val operationToken: Long get() = epoch
    private fun permission(): Boolean {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) return true
        mutable.value = "需要麦克风权限；授权后请再次点击开始"
        requestPermission(); return false
    }
    fun dictate(target: DictationDraftTarget) {
        val prior = attempt
        if (prior != null) {
            try {
                if (prior.target == target) {
                    prior.input.repeatClick(
                        cancel = {
                            invalidate()
                            mutable.value = "已取消连接豆包听写，草稿未更改"
                        },
                        finish = { prior.finishInput() },
                    )
                } else invalidate()
            } catch (_: Exception) {
                try { invalidate() } catch (_: Exception) { /* The shared gate retains failed release. */ }
                mutable.value = "语音资源释放未确认，新的音频操作已阻止"
            }
            return
        }
        try {
            val config = runtime.snapshot()
            check(config.dictation == "DOUBAO" && config.asrKey.isNotBlank() && config.asrResource in DoubaoAsrProtocol.resources)
            check(isEditorCurrent(target))
            if (!permission()) return
            invalidate()
            val next = Attempt(target, config)
            attempt = next
            next.start()
        } catch (_: Exception) {
            try { invalidate() } catch (_: Exception) { /* gate remains poisoned on unconfirmed release */ }
            mutable.value = "豆包听写未启动：检查独立语音设置或音频占用"
        }
    }
    fun startDuplex(admission: () -> Boolean) {
        try {
            val config = runtime.snapshot()
            check(config.duplexKey.isNotBlank() && admission())
            if (!permission()) return
            invalidate()
            val token = ++epoch
            val next = DoubaoDuplexSession(VoiceOwner("voice-call", UUID.randomUUID().toString()),
                DoubaoDuplexConfig(config.duplexKey, config.duplexVoice, config.instructions),
                VoiceNetworkAdapters.duplex(), AndroidDuplexAudio(), gate)
            duplex = next
            observation = scope.launch {
                next.state.collect { value ->
                    if (epoch == token && duplex === next) mutable.value = when (value.phase) {
                        DuplexPhase.UNKNOWN -> "双工结果未知，已停止；不会自动重连"
                        DuplexPhase.FAILED -> "双工失败，已停止；检查独立配置后手动重试"
                        else -> "双工 ${value.phase}\n${value.transcript}\n${value.reply}"
                    }
                }
            }
            next.start { admission() && runtime.current(config) && epoch == token }
        } catch (_: Exception) {
            try { invalidate() } catch (_: Exception) { /* strict release failure keeps gate closed */ }
            mutable.value = "双工未启动：检查独立密钥、麦克风权限或音频占用"
        }
    }
    fun invalidate() {
        mutableListeningTarget.value = null // Fence before cancelling/draining old callbacks.
        mutable.value = ""
        epoch++
        observation?.cancel(); observation = null
        val prior = attempt; attempt = null
        val call = duplex; duplex = null
        // Both close paths fence callbacks before synchronously draining Android audio.
        try { prior?.cancel() } finally { call?.close() }
    }
    override fun close() = invalidate()

    private inner class Attempt(val target: DictationDraftTarget, val config: VoiceConfiguration) {
        val owner = VoiceOwner(target.conversationId ?: "unsaved-draft", UUID.randomUUID().toString())
        val audio = AndroidDuplexAudio()
        val frames = Channel<ByteArray>(64)
        var lease: AudioOwnerGate.Lease? = null
        var job: Job? = null
        val input = DictationInputState()
        val ended get() = input.ended
        val finishing get() = input.finishing
        fun current() = !ended && attempt === this && isEditorCurrent(target) && runtime.current(config) &&
            lease?.let(gate::current) == true
        fun start() {
            lease = gate.acquire(owner, { !ended && isEditorCurrent(target) && runtime.current(config) }) { cleanup() }
            mutable.value = "正在连接豆包听写…"
            job = scope.launch {
                try {
                    withTimeout(120_000) {
                        withContext(Dispatchers.IO) {
                            val connection = DoubaoAsrProtocol.open(DoubaoAsrConfig(config.asrKey, config.asrResource), VoiceNetworkAdapters.asr())
                            try {
                                withContext(Dispatchers.Main.immediate) {
                                    check(current())
                                    audio.open { scope.launch { fail() } }
                                    audio.startCapture { pcm ->
                                        if (!frames.trySend(pcm).isSuccess && !finishing && !ended)
                                            scope.launch { fail() }
                                    }
                                    input.capturing()
                                    if (current()) mutableListeningTarget.value = target
                                    mutable.value = "正在豆包听写；再次点击语音结束录音，结果仅填入草稿"
                                }
                                coroutineScope {
                                    val sender = launch {
                                        for (frame in frames) connection.sendPcm(frame)
                                        connection.sendPcm(ByteArray(0), last = true)
                                    }
                                    try {
                                        while (true) {
                                            val result = connection.receive() ?: error("asr_closed")
                                            check(result.text.length <= 100_000) { "asr_text_limit" }
                                            withContext(Dispatchers.Main.immediate) {
                                                check(current())
                                                mutable.value = "豆包听写：${result.text}"
                                                if (result.final) {
                                                    complete()
                                                    // complete() fences audio/callbacks first. Its ended flag must not
                                                    // hide a rejected editor commit in the outer coroutine catch.
                                                    mutable.value = finalDraftStatus(result.text,
                                                        isCurrent = { isEditorCurrent(target) },
                                                        commit = { applyDraft(target, result.text) })
                                                }
                                            }
                                            if (result.final) break
                                        }
                                    } finally { sender.cancelAndJoin() }
                                }
                            } finally { connection.close() }
                        }
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { if (!ended) fail() }
                finally { if (!ended) complete() }
            }
        }
        fun finishInput() {
            if (!current() || !input.finishCapture()) return
            // Stop capture before closing input; sender sends one final marker after draining frames.
            mutableListeningTarget.value = null
            audio.closeAndDrain(); frames.close()
            mutable.value = "录音已结束，正在等待最终结果…"
        }
        fun fail() {
            if (ended) return
            complete(); mutable.value = "豆包听写失败或超时，草稿未更改；不会自动重试"
        }
        fun complete() {
            if (ended) return
            val token = lease
            if (token == null || !gate.releaseAfter(token) { cleanup() }) cleanup()
        }
        fun cancel() {
            val token = lease
            if (token == null || !gate.cancel(owner)) cleanup()
        }
        fun cleanup() {
            if (ended) return
            input.end()
            if (attempt === this) {
                mutableListeningTarget.value = null
                attempt = null
            }
            frames.cancel(); job?.cancel()
            audio.closeAndDrain()
        }
    }

    /** Local input lifecycle only; ownership remains exclusively with ActivityVoiceAudio.gate. */
    internal class DictationInputState {
        private enum class Phase { CONNECTING, CAPTURING, FINISHING, ENDED }
        @Volatile private var phase = Phase.CONNECTING
        val ended get() = phase == Phase.ENDED
        val finishing get() = phase == Phase.FINISHING

        fun capturing() {
            check(phase == Phase.CONNECTING)
            phase = Phase.CAPTURING
        }
        fun repeatClick(cancel: () -> Unit, finish: () -> Unit) {
            when (phase) {
                Phase.CONNECTING -> cancel()
                Phase.CAPTURING -> finish()
                Phase.FINISHING, Phase.ENDED -> Unit
            }
        }
        fun finishCapture(): Boolean {
            if (phase != Phase.CAPTURING) return false
            phase = Phase.FINISHING
            return true
        }
        fun end() { phase = Phase.ENDED }
    }

    internal companion object {
        /** Unit callback contract: the editor must throw if it cannot confirm the draft commit. */
        fun finalDraftStatus(text: String, isCurrent: () -> Boolean, commit: () -> Unit): String {
            if (text.isBlank()) return "未识别到有效内容，草稿未更改"
            return try {
                check(isCurrent()) { "dictation_editor_changed" }
                commit()
                "听写已填入草稿，请检查后手动发送"
            } catch (_: Exception) {
                // Never retry or overwrite the editor to compensate for an unconfirmed commit.
                "听写未填入草稿，原草稿已保留；请手动重试"
            }
        }
    }
}
