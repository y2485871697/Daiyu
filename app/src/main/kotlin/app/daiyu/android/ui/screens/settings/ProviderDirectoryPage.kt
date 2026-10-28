package app.daiyu.android.ui.screens.settings

import app.daiyu.android.ui.components.deletionButtonColors
import app.daiyu.android.ui.app.LocalAppearanceSettings
import app.daiyu.android.ui.app.LocalAppearanceDark
import app.daiyu.android.ui.app.resolveAppearanceActions
import app.daiyu.android.ui.haptics.clickable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import androidx.compose.foundation.ExperimentalFoundationApi
import app.daiyu.android.ui.haptics.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import app.daiyu.android.ui.haptics.miuix.TextButton
import app.daiyu.android.ui.layout.WidePageContent
import app.daiyu.android.ui.layout.horizontalCutoutPadding
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.daiyu.android.ui.icons.modelNameOpticalAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.daiyu.android.model.ProviderProtocol
import app.daiyu.android.storage.ProviderRegistrySnapshot
import app.daiyu.android.storage.RegisteredProvider
import app.daiyu.android.ui.icons.DaiyuIcon
import app.daiyu.android.ui.icons.DaiyuIcons
import top.yukonga.miuix.kmp.basic.Text

internal data class ProviderCardSummary(val endpoint: String, val protocol: ProviderProtocol, val modelCount: Int?) {
    fun description(enabled: Boolean): String = listOfNotNull(
        endpoint.takeIf { it.isNotBlank() },
        listOfNotNull(if (app.daiyu.android.voice.providers.DoubaoSpeech.isOpenspeech(endpoint)) "豆包语音" else when (protocol) {
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
    onOpen: (RegisteredProvider) -> Unit, onSelect: (RegisteredProvider) -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    onAddSpeech: () -> Unit = {},
    selectedSlots: Set<String>? = null,
    onSelectionChange: (Set<String>) -> Unit = {},
    onDeleteSelected: () -> Unit = {},
    onCancelSelection: () -> Unit = {},
    navigationEnabled: Boolean = enabled,
) {
    val rows = registry?.providers.orEmpty().filter { registration ->
        query.isBlank() || registration.name.contains(query.trim(), ignoreCase = true) ||
            summaries[registration.slotId]?.endpoint?.contains(query.trim(), ignoreCase = true) == true
    }
    Column(modifier.fillMaxSize().background(settingsPageBackground())) {
        SettingsScaffoldPage("模型提供商", onBack, Modifier.weight(1f), listState = listState) {
            item("provider-search") {
                SettingsSearch(query, onQuery, "搜索提供商", "provider-search")
            }
            notice?.let { item("provider-notice") { SettingsNote(it, error = true) } }
            item("provider-create") {
                SettingsSectionTitle("新增提供商")
                SettingsCard {
                    SettingsRow("新增 OpenAI-compatible", summary = "支持 ChatGPT、DeepSeek、Kimi、GLM、Qwen 等",
                        tag = "provider-add", leading = { DaiyuIcon(DaiyuIcons.Model, null, Modifier.testTag("provider-add-icon")) },
                        showChevron = true, onClick = if (enabled && selectedSlots == null) onAddOpenAi else null)
                    SettingsRow("新增 Anthropic", summary = "支持 Anthropic Claude 官方或兼容 API",
                        tag = "provider-add-anthropic", leading = {
                            Box(Modifier.size(24.dp).testTag("provider-add-anthropic-icon"), contentAlignment = Alignment.Center) {
                                Text("AI")
                            }
                        },
                        showChevron = true, onClick = if (enabled && selectedSlots == null) onAddAnthropic else null)
                    SettingsRow("新增豆包语音", summary = "仅用于朗读，填写语音控制台 API Key，不是方舟聊天 Key。",
                        tag = "provider-add-speech", leading = { DaiyuIcon(DaiyuIcons.Voice, null, Modifier.testTag("provider-add-speech-icon")) },
                        showChevron = true, onClick = if (enabled && selectedSlots == null) onAddSpeech else null)
                }
            }
            item("provider-count") {
                val grouped = LocalSettingsPageStyle.current
                val titleStyle = MiuixTheme.textStyles.body2.copy(
                    fontSize = if (grouped) 13.sp else 14.sp,
                    fontWeight = if (grouped) FontWeight.Medium else FontWeight.SemiBold)
                val titleColor = if (grouped) MiuixTheme.colorScheme.onSurfaceVariantSummary else MiuixTheme.colorScheme.onSurface
                Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp)
                    .padding(top = if (grouped) 16.dp else 20.dp, bottom = if (grouped) 1.dp else 2.dp)) {
                    Text("已配置提供商（共 ${registry?.providers?.size ?: 0} 个）", style = titleStyle,
                        color = titleColor, modifier = Modifier.weight(1f).alignByBaseline()
                            .testTag("provider-count-title").padding(vertical = 8.dp))
                    if (selectedSlots != null) {
                        val visibleIds = rows.map { it.slotId }.toSet()
                        val allSelected = visibleIds.isNotEmpty() && selectedSlots.containsAll(visibleIds)
                        Text(text = if (allSelected) "取消全选" else "全选", style = titleStyle,
                            color = titleColor.copy(alpha = if (enabled && visibleIds.isNotEmpty()) 1f else .38f),
                            modifier = Modifier.alignByBaseline().testTag("provider-select-all")
                                .clickable(enabled = enabled && visibleIds.isNotEmpty(), role = Role.Button,
                                    onClick = { onSelectionChange(if (allSelected) selectedSlots - visibleIds else selectedSlots + visibleIds) })
                                .padding(vertical = 8.dp))
                        Text(text = "反选", style = titleStyle,
                            color = titleColor.copy(alpha = if (enabled && visibleIds.isNotEmpty()) 1f else .38f),
                            modifier = Modifier.alignByBaseline().padding(start = 12.dp).testTag("provider-invert-selection")
                                .clickable(enabled = enabled && visibleIds.isNotEmpty(), role = Role.Button,
                                    onClick = { onSelectionChange((selectedSlots - visibleIds) + (visibleIds - selectedSlots)) })
                                .padding(vertical = 8.dp))
                    }
                }
            }
            rows.forEach { registration -> item(registration.slotId) {
                SettingsCard(Modifier.padding(bottom = 8.dp)) {
                    SettingsRow(registration.name, tag = "provider-open-${registration.slotId}",
                        textModifier = Modifier.modelNameOpticalAlignment(),
                        summary = summaries[registration.slotId]?.description(registration.enabled),
                        leading = { DaiyuIcon(DaiyuIcons.Provider, null) },
                        trailing = {
                            if (selectedSlots != null) {
                                val checked = registration.slotId in selectedSlots
                                val colors = MiuixTheme.colorScheme
                                Box(Modifier.size(24.dp).clip(CircleShape)
                                    .background(if (checked) colors.primary else colors.surfaceVariant)
                                    .testTag("provider-selection-${registration.slotId}"), contentAlignment = Alignment.Center) {
                                    if (checked) DaiyuIcon(DaiyuIcons.Check, null, tint = colors.onPrimary, size = 18.dp)
                                }
                            } else if (registry?.activeSlotId == registration.slotId) DaiyuIcon(DaiyuIcons.Check, "当前提供商")
                        },
                        modifier = Modifier.semantics {
                            if (selectedSlots != null) {
                                role = Role.Checkbox
                                toggleableState = if (registration.slotId in selectedSlots) ToggleableState.On else ToggleableState.Off
                            }
                        }.combinedClickable(enabled = if (selectedSlots == null) navigationEnabled else enabled,
                            onClick = {
                                if (selectedSlots == null) onOpen(registration)
                                else onSelectionChange(if (registration.slotId in selectedSlots) selectedSlots - registration.slotId
                                    else selectedSlots + registration.slotId)
                            }, onLongClickLabel = "选择提供商",
                            onLongClick = { if (enabled && selectedSlots == null) onSelect(registration) }))
                }
            } }
        }
        if (selectedSlots != null) WidePageContent(fillHeight = false) { sidePadding ->
            Row(Modifier.fillMaxWidth().horizontalCutoutPadding().padding(horizontal = sidePadding)
                .navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)
                .testTag("provider-selection-actions"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val accents = resolveAppearanceActions(LocalAppearanceSettings.current, LocalAppearanceDark.current)
                TextButton(text = "取消", enabled = enabled, onClick = onCancelSelection,
                    colors = ButtonDefaults.textButtonColors(
                        color = accents.secondaryContainer, textColor = accents.onSecondary),
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("provider-cancel-selection"))
                TextButton(text = "删除", enabled = enabled && selectedSlots.isNotEmpty(), onClick = onDeleteSelected,
                    colors = deletionButtonColors(),
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("provider-delete-selected"))
            }
        }
    }
}

@Composable
internal fun ProviderAuthenticationPage(enabled: Boolean, notice: String?, onBack: () -> Unit, onApiKey: () -> Unit, onOAuth: () -> Unit = {}) {
    SettingsScaffoldPage("认证方式", onBack) {
        notice?.let { item("auth-notice") { SettingsNote(it, error = true) } }
        item {
            SettingsSectionTitle("选择身份验证方式")
            SettingsCard {
                SettingsRow("API Key", summary = "OpenAI 官方接口或兼容的第三方站点",
                    tag = "provider-auth-api-key", leading = { DaiyuIcon(DaiyuIcons.Key, null) },
                    showChevron = true, onClick = if (enabled) onApiKey else null)
                SettingsRow("OAuth", summary = "使用 OpenAI Codex 登录", tag = "provider-auth-oauth",
                    leading = { DaiyuIcon(DaiyuIcons.Model, null) },
                    trailing = { DaiyuIcon(DaiyuIcons.ChevronRight, null) }, onClick = if (enabled) onOAuth else null)
            }
            SettingsNote("OAuth 通过服务商网页登录；API Key 使用您手动配置的密钥。")
        }
    }
}
