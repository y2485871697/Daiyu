package app.daiyu.android.voice

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One process-wide ownership barrier. Never reset it after a failed strict release/recreation. */
internal object ActivityVoiceAudio {
    val gate = AudioOwnerGate()
}

data class ComposerDictationState(
    val target: DictationDraftTarget? = null,
    val listening: Boolean = false,
    val preview: String = "",
    val status: String = "",
)

/**
 * Main-thread Activity policy over the existing one-shot Android bridge. The Activity owns the
 * editor and permission launcher; this host never sends a message or requests a second microphone
 * session implicitly. Partials update the editable draft without becoming a final result.
 */
internal class ComposerDictationHost(
    private val factory: (DictationDraftTarget, DraftDictationHost, (DictationDraftTarget) -> Boolean) -> DraftDictationSession,
    private val isEditorCurrent: (DictationDraftTarget) -> Boolean,
    private val requestPermission: (DictationDraftTarget) -> Unit,
    private val applyDraft: (DictationDraftTarget, String) -> Unit,
    private val applyPartialDraft: (DictationDraftTarget, String) -> Unit,
) : AutoCloseable {
    constructor(context: Context, gate: AudioOwnerGate,
        isEditorCurrent: (DictationDraftTarget) -> Boolean,
        requestPermission: (DictationDraftTarget) -> Unit,
        applyDraft: (DictationDraftTarget, String) -> Unit,
        applyPartialDraft: (DictationDraftTarget, String) -> Unit) : this(
        productionFactory(context, gate), isEditorCurrent, requestPermission, applyDraft, applyPartialDraft)

    private var active: DictationDraftTarget? = null
    private var session: DraftDictationSession? = null
    // Keep the launcher slot until its result arrives, even after editor invalidation. A stale
    // result must not be mistaken for a later attempt's grant/denial.
    private var permissionInFlight: DictationDraftTarget? = null
    private var closed = false
    private val mutableState = MutableStateFlow(ComposerDictationState())
    val state: StateFlow<ComposerDictationState> = mutableState.asStateFlow()
    private fun current(target: DictationDraftTarget) = !closed && active == target && isEditorCurrent(target)

    private val callbacks = object : DraftDictationHost {
        override fun permissionRequired(target: DictationDraftTarget) {
            if (!current(target) || permissionInFlight != null) return
            permissionInFlight = target
            mutableState.value = ComposerDictationState(target, status = "听写需要麦克风权限；授权后请再次点击语音")
            try { requestPermission(target) } catch (_: Exception) {
                permissionInFlight = null
                invalidate()
                failure("无法请求麦克风权限，请在系统设置中检查权限")
            }
        }
        override fun partial(target: DictationDraftTarget, text: String) {
            if (!current(target) || text.isBlank()) return
            try {
                applyPartialDraft(target, text)
                if (current(target)) mutableState.value = mutableState.value.copy(preview = text)
            } catch (_: Exception) { invalidate(); failure("听写草稿未能保存，请重试") }
        }
        override fun finalDraft(target: DictationDraftTarget, transcript: ConfirmableTranscript) {
            if (!current(target) || !transcript.isDone()) return
            val text = transcript.preview()
            if (text.isBlank()) return
            // Retire ALL old callbacks before editing/incrementing the Activity's draft revision.
            invalidate()
            try {
                applyDraft(target, text)
                mutableState.value = ComposerDictationState()
            } catch (_: Exception) { failure("听写草稿未能保存，请重试") }
        }
        override fun state(target: DictationDraftTarget, state: VoiceState) {
            if (!current(target)) return
            when (state.phase) {
                VoicePhase.PREPARING, VoicePhase.LISTENING -> mutableState.value = mutableState.value.copy(
                    target = target, listening = state.phase == VoicePhase.LISTENING,
                    status = if (state.detail == "recognition_final_pending") "正在等待最终识别结果…" else "正在听写；再次点击语音或停止按钮可取消")
                VoicePhase.COMPLETE -> mutableState.value = mutableState.value.copy(listening = false)
                    // finalDraft follows; partial must never substitute for it.
                VoicePhase.FAILED -> {
                    session = null
                    if (permissionInFlight != target) active = null
                    failure(when (state.detail) {
                        "recognizer_permission_denied" -> "未获得麦克风权限"
                        "recognizer_unavailable" -> "系统语音识别服务不可用"
                        "empty_recognition" -> "未识别到最终文本"
                        "recognizer_release_failed" -> "音频释放失败，请重启应用"
                        else -> "听写失败，请重试"
                    })
                }
                VoicePhase.CANCELLED -> {
                    active = null; session = null
                    mutableState.value = ComposerDictationState(status = "听写已停止")
                }
                else -> Unit
            }
        }
    }

    fun start(target: DictationDraftTarget) {
        if (closed || !isEditorCurrent(target)) return
        if (active != null && permissionInFlight == null) { invalidate(); return }
        if (permissionInFlight != null) return
        invalidate()
        active = target
        mutableState.value = ComposerDictationState(target, status = "正在准备听写…")
        try {
            val next = factory(target, callbacks, ::current)
            session = next
            next.start()
        } catch (_: Exception) {
            invalidate()
            failure("无法启动听写或音频未能释放；请检查权限及语音服务，必要时重启应用")
        }
    }

    fun permissionResult(granted: Boolean) {
        val target = permissionInFlight ?: return
        permissionInFlight = null
        if (!current(target)) return
        invalidate()
        mutableState.value = ComposerDictationState(status = if (granted)
            "麦克风权限已授予，请再次点击语音开始听写" else "麦克风权限被拒绝")
    }

    fun invalidate() {
        active = null // Fence before synchronous cancellation and all queued callbacks.
        val old = session; session = null
        mutableState.value = ComposerDictationState()
        try { old?.close() } catch (_: Exception) {
            // The underlying gate is poisoned. Do not replace it or treat this catch as release.
            failure("音频释放失败，请重启应用")
        }
    }

    private fun failure(message: String) { mutableState.value = ComposerDictationState(status = message) }
    override fun close() { if (!closed) { closed = true; invalidate() } }
}

private fun productionFactory(context: Context, gate: AudioOwnerGate):
    (DictationDraftTarget, DraftDictationHost, (DictationDraftTarget) -> Boolean) -> DraftDictationSession {
    val app = context.applicationContext
    return { target, host, current -> DraftDictationSession(app, gate, target, host, current) }
}
