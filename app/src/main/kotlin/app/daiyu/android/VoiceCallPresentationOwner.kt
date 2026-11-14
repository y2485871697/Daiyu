package app.daiyu.android

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.daiyu.android.ui.screens.chat.conversation.*
import app.daiyu.android.voice.control.*

internal class VoiceCallPresentationOwner {
    var state by mutableStateOf<DoubaoCallUi?>(null)
        private set
    private var serial = -1L
    private var permit: String? = null
    private var initialCompression: String? = null
    private var compressionStarted = false
    fun begin(conversation: String?, permitId: String, now: Long, completedCompression: String?, modelName: String) {
        permit = permitId
        initialCompression = completedCompression
        compressionStarted = false
        state = DoubaoCallUi(--serial, conversation, startedAtMillis = now,
            title = voiceCallModelShortName(modelName), modelSelectable = true, responseLabel = voiceCallModelShortName(modelName))
    }
    fun update(value: VoiceControlState, speaker: Boolean, compressing: Boolean, completedCompression: String?, now: Long) {
        val previous = state ?: return
        if (previous.presentation == DoubaoCallPresentation.CLOSING || value.permitId != permit) return
        if (compressing && !compressionStarted) initialCompression = completedCompression
        compressionStarted = compressing
        state = previous.copy(
            visualState = when {
                value.phase == VoiceControlPhase.STOPPED -> if (value.reason in setOf(VoiceControlStopReason.USER,
                    VoiceControlStopReason.REVOKED, VoiceControlStopReason.CLOSED)) DoubaoCallVisualState.ENDED else DoubaoCallVisualState.ERROR
                value.muted -> DoubaoCallVisualState.MUTED
                value.phase in setOf(VoiceControlPhase.SPEAKING, VoiceControlPhase.DRAINING, VoiceControlPhase.WAITING_FOR_BODY) -> DoubaoCallVisualState.RESPONDING
                else -> DoubaoCallVisualState.LISTENING
            },
            transcript = value.transcript, reply = value.reply, captionEpoch = value.captionEpoch, muted = value.muted, speaker = speaker,
            compressing = compressing,
            compressionCompletion = completedCompression?.takeUnless { it == initialCompression },
            endedAtMillis = if (value.active) null else previous.endedAtMillis ?: now,
            reason = when (value.phase) {
                VoiceControlPhase.PERMISSION_REQUIRED -> "等待麦克风授权"
                VoiceControlPhase.MUTED -> if (value.muted) "麦克风已关闭" else "选择模型后继续通话"
                VoiceControlPhase.SUBMITTING -> "正在准备回答"
                VoiceControlPhase.STOPPED -> when (value.reason) {
                    VoiceControlStopReason.RECOGNITION_FAILED -> "语音识别失败，请重新拨打"
                    VoiceControlStopReason.RELEASE_FAILED -> "语音资源释放未确认"
                    VoiceControlStopReason.CONFIGURATION_CHANGED -> "请检查语音配置"
                    VoiceControlStopReason.RESPONSE_FAILED, VoiceControlStopReason.REJECTED -> "回答未完成，请重新拨打"
                    else -> null
                }
                else -> null
            })
    }
    fun setModelName(name: String) {
        state?.let { state = it.copy(title = voiceCallModelShortName(name), responseLabel = voiceCallModelShortName(name)) }
    }
    fun present(key: Long, presentation: DoubaoCallPresentation) {
        state?.takeIf { it.sessionKey == key && it.presentation != DoubaoCallPresentation.CLOSING }
            ?.let { state = it.copy(presentation = presentation) }
    }
    fun toggleCaptions(key: Long) {
        state?.takeIf { it.sessionKey == key }?.let { state = it.copy(captionsEnabled = !it.captionsEnabled) }
    }
    fun beginClose(key: Long, now: Long): Boolean {
        val shown = state?.takeIf { it.sessionKey == key && it.presentation != DoubaoCallPresentation.CLOSING } ?: return false
        state = shown.copy(presentation = DoubaoCallPresentation.CLOSING, endedAtMillis = shown.endedAtMillis ?: now)
        return true
    }
    fun finishClose(key: Long) {
        if (state?.sessionKey == key && state?.presentation == DoubaoCallPresentation.CLOSING) clear()
    }
    fun clear() { state = null; permit = null; initialCompression = null }
}

internal fun voiceCallModelShortName(name: String): String {
    val normalized = name.substringAfterLast('/').trim()
    if (Regex("^(chat)?gpt", RegexOption.IGNORE_CASE).containsMatchIn(normalized)) return "GPT"
    return when (app.daiyu.android.ui.icons.modelBrand(name)) {
        app.daiyu.android.ui.icons.ModelBrand.OPENAI -> "GPT"
        app.daiyu.android.ui.icons.ModelBrand.CLAUDE -> "Claude"
        app.daiyu.android.ui.icons.ModelBrand.GEMINI -> "Gemini"
        app.daiyu.android.ui.icons.ModelBrand.DEEPSEEK -> "DeepSeek"
        app.daiyu.android.ui.icons.ModelBrand.QWEN -> "Qwen"
        app.daiyu.android.ui.icons.ModelBrand.KIMI -> "Kimi"
        app.daiyu.android.ui.icons.ModelBrand.GROK -> "Grok"
        app.daiyu.android.ui.icons.ModelBrand.ZHIPU -> "GLM"
        app.daiyu.android.ui.icons.ModelBrand.DOUBAO -> "豆包"
        app.daiyu.android.ui.icons.ModelBrand.MINIMAX -> "MiniMax"
        null -> normalized.ifEmpty { "模型" }
    }
}
