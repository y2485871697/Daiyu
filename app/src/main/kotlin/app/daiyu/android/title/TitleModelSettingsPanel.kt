package app.daiyu.android.title

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.daiyu.android.model.ProviderProtocol
import app.daiyu.android.storage.ModelSettings

/** The host creates a new opaque binding ONCE per edit draft, not on every recomposition.
 * No rememberSaveable, main-model copy, credential logging, provider probe or network action. */
data class TitleModelDraft(
    val binding: TitleModelBinding,
    val endpoint: String = "",
    val model: String = "",
    val apiKey: String = "",
    val protocol: ProviderProtocol = ProviderProtocol.RESPONSES,
    val responsesPath: String = "",
    val protocolChangeAuthorized: Boolean = false,
    val requestOptionsReplacementAuthorized: Boolean = false,
) {
    fun profile(): TitleModelProfile = TitleModelProfile(binding, ModelSettings(endpoint, model, apiKey,
        batteryEnabled = false, responsesPath = responsesPath, deviceStatusEnabled = false, protocol = protocol))
    override fun toString(): String = "TitleModelDraft(binding=$binding, protocol=$protocol, apiKey=<redacted>)"
}

/** Stateless settings surface. onSave/onDisable must be admitted by the parent's owner/revision
 * gate and run on its worker. No preferences/keystore read or write occurs during composition.
 * The host clears the key draft after success/disposal and ignores revoked page callbacks. */
@Composable
fun TitleModelSettingsPanel(
    settings: TitleModelSettings,
    draft: TitleModelDraft,
    onDraftChange: (TitleModelDraft) -> Unit,
    onSave: (TitleModelDraft) -> Unit,
    onDisable: () -> Unit,
    busy: Boolean,
    notice: String? = null,
    modifier: Modifier = Modifier,
) {
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("独立标题模型")
        Text(if (settings.enabled) "已启用独立固定绑定" else "默认关闭；不会使用聊天主模型")
        Text("手动改名优先。模型失绑定会明确拒绝；保存仅写本地配置，不发送供应商请求。")
        OutlinedTextField(draft.endpoint, { onDraftChange(draft.copy(endpoint = it)) },
            label = { Text("独立服务 HTTPS 地址") }, enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(draft.model, { onDraftChange(draft.copy(model = it)) },
            label = { Text("固定模型名称") }, enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(draft.apiKey, { onDraftChange(draft.copy(apiKey = it)) },
            label = { Text("独立密钥（重新保存需明确填写）") }, enabled = !busy, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        Text("协议：${draft.protocol.wireName}")
        listOf(ProviderProtocol.RESPONSES, ProviderProtocol.MESSAGES, ProviderProtocol.CHAT_COMPLETIONS, ProviderProtocol.GEMINI).forEach { protocol ->
            OutlinedButton(onClick = {
                if (protocol != draft.protocol) onDraftChange(draft.copy(protocol = protocol, apiKey = "",
                    responsesPath = if (protocol == ProviderProtocol.RESPONSES) draft.responsesPath else "", protocolChangeAuthorized = false))
            }, enabled = !busy) { Text(protocol.wireName) }
        }
        if (draft.protocol == ProviderProtocol.RESPONSES) {
            OutlinedTextField(draft.responsesPath, { onDraftChange(draft.copy(responsesPath = it)) },
                label = { Text("Responses 路径（可留空）") }, enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        // These are explicit authorizations, not a guessed migration or automatic repair.
        OutlinedButton(onClick = { onDraftChange(draft.copy(protocolChangeAuthorized = !draft.protocolChangeAuthorized)) }, enabled = !busy) {
            Text(if (draft.protocolChangeAuthorized) "已明确允许协议替换" else "允许协议替换（需明确确认）")
        }
        OutlinedButton(onClick = { onDraftChange(draft.copy(requestOptionsReplacementAuthorized = !draft.requestOptionsReplacementAuthorized)) }, enabled = !busy) {
            Text(if (draft.requestOptionsReplacementAuthorized) "已明确允许请求策略替换" else "允许请求策略替换（需明确确认）")
        }
        Button(onClick = { onSave(draft) }, enabled = !busy) { Text("保存并启用独立标题模型") }
        OutlinedButton(onClick = onDisable, enabled = !busy) { Text("关闭自动标题") }
        notice?.let { Text(it) }
    }
}
