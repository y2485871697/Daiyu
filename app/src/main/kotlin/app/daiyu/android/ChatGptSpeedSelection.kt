package app.daiyu.android

import app.daiyu.android.model.GptServiceTier
import app.daiyu.android.model.GptSpeedMode
import app.daiyu.android.model.MessagesRequest
import app.daiyu.android.model.ProviderProtocol
import app.daiyu.android.storage.EditingSettings
import app.daiyu.android.storage.ProviderEditorSlot

internal data class ChatGptSpeedSelection(val editor: ProviderEditorSlot, val editing: EditingSettings) {
    val mode: GptSpeedMode? get() {
        if (editing.credentialUnavailable || editing.requestOptionsUnavailable || editing.headersUnavailable) return null
        val settings = editing.settings
        if (settings.protocol != ProviderProtocol.RESPONSES) return null
        val body = settings.requestOptions.customBody
        val model = if (body.isEmpty()) settings.model else try {
            val json = MessagesRequest.jsonObject(body)
            if (json.has("model")) json.opt("model") as? String ?: return null else settings.model
        } catch (_: Exception) { return null }
        if (!GptServiceTier.isGptSpeedModel(model)) return null
        return settings.requestOptions.speedMode ?: GptSpeedMode.NORMAL
    }

    fun next(): GptSpeedMode {
        val next = when (checkNotNull(mode) { "当前模型不支持 GPT 速度" }) {
            GptSpeedMode.NORMAL -> GptSpeedMode.FAST
            GptSpeedMode.FAST -> GptSpeedMode.ULTRA_FAST
            GptSpeedMode.ULTRA_FAST -> GptSpeedMode.NORMAL
        }
        check(!(editing.settings.oauth != null || editing.settings.requestOptions.codex) || next != GptSpeedMode.ULTRA_FAST) {
            "Codex 不支持极速速度"
        }
        return next
    }
}

internal fun chatGptSpeedToast(mode: GptSpeedMode): String = when (mode) {
    GptSpeedMode.NORMAL -> "已切换标准速度"
    GptSpeedMode.FAST -> "已切换快速速度"
    GptSpeedMode.ULTRA_FAST -> "已切换极速速度"
}
