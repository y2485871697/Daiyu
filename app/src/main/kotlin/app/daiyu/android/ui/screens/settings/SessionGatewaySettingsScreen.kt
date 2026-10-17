package app.daiyu.android.ui.screens.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.daiyu.android.model.sessiongateway.SessionGatewayRule
import app.daiyu.android.ui.components.MiuixScaffoldPage
import top.yukonga.miuix.kmp.basic.TextButton

/** Independent controlled editor. The host owns persistence/admission and keeps all five fields. */
@Composable
internal fun SessionGatewaySettingsScreen(
    draft: SessionGatewayRule,
    onDraftChange: (SessionGatewayRule) -> Unit,
    onSave: (SessionGatewayRule) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    errorText: String? = null,
    enabled: Boolean = true,
    requestOptionsUnavailable: Boolean = false,
    replacementAuthorized: Boolean = false,
    onReplacementAuthorization: (Boolean) -> Unit = {},
) {
    val validationError = runCatching { draft.validate() }.exceptionOrNull()?.message
    MiuixScaffoldPage(title = "保留会话网关", onBack = onBack, modifier = modifier) {
        item(key = "session_gateway_rules") {
            SettingsSectionTitle("Responses 会话匹配")
            SettingsCard {
                SettingsTextField("模型正则（全匹配）", draft.modelPattern,
                    { onDraftChange(draft.copy(modelPattern = it)) }, enabled = enabled)
                SettingsTextField("完整 URL 正则（包含匹配）", draft.pathPattern,
                    { onDraftChange(draft.copy(pathPattern = it)) }, enabled = enabled)
                SettingsTextField("retention", draft.retention,
                    { onDraftChange(draft.copy(retention = it)) }, enabled = enabled)
                SettingsTextField("keySource", draft.keySource,
                    { onDraftChange(draft.copy(keySource = it)) }, enabled = enabled)
                SettingsTextField("顶层身份键", draft.keyField,
                    { onDraftChange(draft.copy(keyField = it)) }, enabled = enabled)
            }
            SettingsNote("坏正则不命中。身份键按字面写入顶层；不能覆盖运行时协议字段。")
            SettingsNote("仅默认继承 / gjson 有已核实的执行语义；不发送 TTL、store 或 project。会话身份不保证服务端缓存留存。")
            draft.unsupportedSemantics()?.let { SettingsNote(it, error = true) }
            if (validationError != null) SettingsNote(validationError, error = true)
        }
        item(key = "session_gateway_save") {
            if (requestOptionsUnavailable) {
                SettingsNote("原请求策略不可读；只有明确授权才可用当前默认策略替换。凭据或协议不可用时请先到模型设置修复。", error = true)
                SettingsCard {
                    SettingsSwitchRow("授权替换策略", replacementAuthorized, enabled = enabled,
                        onCheckedChange = onReplacementAuthorization)
                }
            }
            if (!errorText.isNullOrBlank()) SettingsNote(errorText, error = true)
            TextButton(text = "保存", onClick = { onSave(draft) },
                enabled = enabled && validationError == null && (!requestOptionsUnavailable || replacementAuthorized),
                modifier = Modifier.testTag("session-gateway-save").fillMaxWidth().padding(12.dp))
        }
    }
}
