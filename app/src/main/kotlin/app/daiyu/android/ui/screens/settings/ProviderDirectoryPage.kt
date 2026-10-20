package app.daiyu.android.ui.screens.settings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.daiyu.android.model.ProviderProtocol
import app.daiyu.android.storage.ProviderRegistrySnapshot
import app.daiyu.android.storage.RegisteredProvider
import app.daiyu.android.ui.components.MiuixScaffoldPage
import app.daiyu.android.ui.icons.DaiyuIcon
import app.daiyu.android.ui.icons.DaiyuIcons
import top.yukonga.miuix.kmp.basic.Text

internal data class ProviderCardSummary(val endpoint: String, val protocol: ProviderProtocol, val modelCount: Int?) {
    fun description(enabled: Boolean): String = listOfNotNull(
        endpoint.takeIf { it.isNotBlank() },
        listOfNotNull(when (protocol) {
            ProviderProtocol.RESPONSES, ProviderProtocol.CHAT_COMPLETIONS -> "Custom OpenAI-compatible"
            ProviderProtocol.MESSAGES -> "Anthropic"
            else -> null
        }, modelCount?.let { "$it 个模型" }, "禁用".takeUnless { enabled }).joinToString(" · ").takeIf { it.isNotEmpty() },
    ).joinToString("\n")
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ProviderDirectoryPage(
    registry: ProviderRegistrySnapshot?, summaries: Map<String, ProviderCardSummary>,
    query: String, onQuery: (String) -> Unit, enabled: Boolean, notice: String?,
    onBack: () -> Unit, onAddOpenAi: () -> Unit, onAddAnthropic: () -> Unit,
    onOpen: (RegisteredProvider) -> Unit, onManage: (RegisteredProvider) -> Unit,
    modifier: Modifier = Modifier,
) {
    MiuixScaffoldPage("模型提供商", onBack, modifier) {
        item("provider-search") {
            SettingsSearch(query, onQuery, "搜索提供商", "provider-search")
        }
        notice?.let { item("provider-notice") { SettingsNote(it, error = true) } }
        item("provider-create") {
            SettingsSectionTitle("新增提供商")
            SettingsCard {
                SettingsRow("新增 OpenAI-compatible", summary = "支持 ChatGPT、DeepSeek、Kimi、GLM、Qwen 等",
                    tag = "provider-add", leading = { DaiyuIcon(DaiyuIcons.Model, null) },
                    onClick = if (enabled) onAddOpenAi else null)
                SettingsRow("新增 Anthropic", summary = "支持 Anthropic Claude 官方或兼容 API",
                    tag = "provider-add-anthropic", leading = { Text("AI") },
                    onClick = if (enabled) onAddAnthropic else null)
                SettingsRow("新增豆包语音", summary = "仅用于朗读，填写语音控制台 API Key，不是方舟聊天 Key。",
                    tag = "provider-add-speech", leading = { DaiyuIcon(DaiyuIcons.Voice, null) },
                    trailing = { DaiyuIcon(DaiyuIcons.ChevronRight, null) })
            }
        }
        item("provider-count") { SettingsSectionTitle("已配置提供商（共 ${registry?.providers?.size ?: 0} 个）") }
        val rows = registry?.providers.orEmpty().filter { registration ->
            query.isBlank() || registration.name.contains(query.trim(), ignoreCase = true) ||
                summaries[registration.slotId]?.endpoint?.contains(query.trim(), ignoreCase = true) == true
        }
        rows.forEach { registration -> item(registration.slotId) {
            SettingsCard(Modifier.padding(bottom = 8.dp)) {
                SettingsRow(registration.name, tag = "provider-open-${registration.slotId}",
                    summary = summaries[registration.slotId]?.description(registration.enabled),
                    leading = { DaiyuIcon(DaiyuIcons.Provider, null) },
                    trailing = { if (registry?.activeSlotId == registration.slotId) DaiyuIcon(DaiyuIcons.Check, "当前提供商") },
                    modifier = Modifier.combinedClickable(enabled = enabled,
                        onClick = { onOpen(registration) }, onLongClickLabel = "管理提供商",
                        onLongClick = { onManage(registration) }))
            }
        } }
    }
}

@Composable
internal fun ProviderAuthenticationPage(enabled: Boolean, notice: String?, onBack: () -> Unit, onApiKey: () -> Unit) {
    MiuixScaffoldPage("认证方式", onBack) {
        notice?.let { item("auth-notice") { SettingsNote(it, error = true) } }
        item {
            SettingsSectionTitle("选择身份验证方式")
            SettingsCard {
                SettingsRow("API Key", summary = "OpenAI 官方接口或兼容的第三方站点",
                    tag = "provider-auth-api-key", leading = { DaiyuIcon(DaiyuIcons.Key, null) },
                    onClick = if (enabled) onApiKey else null)
                SettingsRow("OAuth", summary = "使用 OpenAI Codex 登录", tag = "provider-auth-oauth",
                    leading = { DaiyuIcon(DaiyuIcons.Model, null) },
                    trailing = { DaiyuIcon(DaiyuIcons.ChevronRight, null) })
            }
            SettingsNote("OAuth 通过服务商网页登录；API Key 使用您手动配置的密钥。")
        }
    }
}
