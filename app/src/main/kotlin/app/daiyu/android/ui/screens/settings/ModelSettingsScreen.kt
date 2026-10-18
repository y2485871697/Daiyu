package app.daiyu.android.ui.screens.settings

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.daiyu.android.model.GptSpeedMode
import app.daiyu.android.model.ProviderProtocol
import app.daiyu.android.ui.components.MiuixScaffoldPage
import app.daiyu.android.ui.icons.DaiyuIcon
import app.daiyu.android.ui.icons.DaiyuIcons
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * In-memory editing state only. Do not put this draft in saved state, logs or backups.
 * The host must validate and authorize save/fetch; displaying a checkbox is not authorization.
 * savedProtocol stays fixed for the editing session (copy() preserves it).
 */
internal data class ModelSettingsDraftUi(
    val protocol: ProviderProtocol = ProviderProtocol.RESPONSES,
    val endpoint: String = "",
    val responsesPath: String = "",
    val apiKey: String = "",
    val model: String = "",
    val batteryEnabled: Boolean = false,
    val deviceStatusEnabled: Boolean = false,
    val customBody: String = "",
    val speedMode: GptSpeedMode? = null,
    val codex: Boolean = false,
    val savedProtocol: ProviderProtocol = protocol,
    val protocolChangeAuthorized: Boolean = false,
    val requestOptionsReplacementAuthorized: Boolean = false,
    val anthropicVersion: String = "",
    val customHeaders: String = "",
    val headersReplacementAuthorized: Boolean = false,
) {
    override fun toString(): String = "ModelSettingsDraftUi(protocol=$protocol, model=$model, apiKey=<redacted>, customBody=<redacted>)"
}

internal val RetainedModelSaveAction = SemanticsPropertyKey<() -> Unit>("RetainedModelSaveAction")

internal data class ModelCatalogEntryUi(val id: String, val displayName: String = id)

/** Changing protocols deliberately clears the key and the previous binding authorization. */
internal fun ModelSettingsDraftUi.selectProtocol(selected: ProviderProtocol): ModelSettingsDraftUi =
    if (selected == protocol) this else copy(
        protocol = selected,
        apiKey = "",
        customHeaders = "",
        responsesPath = if (selected != ProviderProtocol.RESPONSES) "" else responsesPath,
        protocolChangeAuthorized = false,
        headersReplacementAuthorized = false,
        anthropicVersion = if (selected == ProviderProtocol.MESSAGES) anthropicVersion else "",
    )

/**
 * Controlled UI: no store access, networking or background work. The host owns draft and catalog
 * lifecycle, cancels outdated requests and supplies errors. Tag results with catalogForDraft to
 * hide stale results after editing connection credentials. Selecting a model only edits the draft.
 */
@Composable
internal fun ModelSettingsScreen(
    draft: ModelSettingsDraftUi,
    onDraftChange: (ModelSettingsDraftUi) -> Unit,
    onSave: (ModelSettingsDraftUi) -> Unit,
    onFetchCatalog: (ModelSettingsDraftUi) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    errorText: String? = null,
    credentialUnavailable: Boolean = false,
    requestOptionsUnavailable: Boolean = false,
    headersUnavailable: Boolean = false,
    catalog: List<ModelCatalogEntryUi> = emptyList(),
    isFetchingCatalog: Boolean = false,
    catalogStatusText: String? = null,
    catalogForDraft: ModelSettingsDraftUi? = null,
    catalogEnabled: Boolean = true,
    enabled: Boolean = true,
    engineeringUi: Boolean = false,
    testConnection: ModelConnectionTester? = null,
    onManageLocalModels: (() -> Unit)? = null,
    pageTitle: String = "模型设置",
    headerContent: LazyListScope.() -> Unit = {},
    showCatalog: Boolean = true,
) {
    val connection = rememberSettingsConnectionTest(draft, enabled, testConnection)
    val submit: () -> Unit = { connection.cancel(); onSave(draft) }
    val changeDraft: (ModelSettingsDraftUi) -> Unit = { connection.cancel(); onDraftChange(it) }
    var bodyExpanded by remember { mutableStateOf(false) }
    val catalogIsCurrent = catalogForDraft?.let {
        it.endpoint == draft.endpoint && it.responsesPath == draft.responsesPath &&
            it.protocol == draft.protocol && it.apiKey == draft.apiKey && it.model == draft.model &&
            it.anthropicVersion == draft.anthropicVersion && it.customHeaders == draft.customHeaders
    } == true
    MiuixScaffoldPage(title = pageTitle, onBack = { connection.cancel(); onBack() }, modifier = modifier) {
        headerContent()
        item(key = "model_protocol") {
            SettingsSectionTitle("服务协议")
            SettingsCard {
                SettingsRadioRow("OpenAI Responses", draft.protocol == ProviderProtocol.RESPONSES, enabled = enabled) {
                    changeDraft(draft.selectProtocol(ProviderProtocol.RESPONSES))
                }
                SettingsRadioRow("Anthropic Messages", draft.protocol == ProviderProtocol.MESSAGES, enabled = enabled) {
                    changeDraft(draft.selectProtocol(ProviderProtocol.MESSAGES))
                }
                SettingsRadioRow("OpenAI Chat Completions", draft.protocol == ProviderProtocol.CHAT_COMPLETIONS, enabled = enabled) {
                    changeDraft(draft.selectProtocol(ProviderProtocol.CHAT_COMPLETIONS))
                }
                SettingsRadioRow("Google Gemini", draft.protocol == ProviderProtocol.GEMINI, enabled = enabled) {
                    changeDraft(draft.selectProtocol(ProviderProtocol.GEMINI))
                }
                if (draft.protocol != draft.savedProtocol) {
                    SettingsSwitchRow("重输密钥并绑定", draft.protocolChangeAuthorized, enabled = enabled) {
                        changeDraft(draft.copy(protocolChangeAuthorized = it))
                    }
                }
            }
            if (credentialUnavailable) SettingsNote("密钥失效，请重输", error = true)
            if (draft.savedProtocol == ProviderProtocol.UNKNOWN) SettingsNote("协议无效，请重选", error = true)
        }
        item(key = "model_connection") {
            SettingsSectionTitle("连接与模型")
            SettingsCard {
                SettingsTextField("Base URL", draft.endpoint, { changeDraft(draft.copy(endpoint = it)) }, enabled = enabled)
                SettingsTextField("Responses 路径", draft.responsesPath, { changeDraft(draft.copy(responsesPath = it)) },
                    hint = "/responses", enabled = enabled && draft.protocol == ProviderProtocol.RESPONSES)
                if (draft.protocol == ProviderProtocol.MESSAGES) {
                    SettingsTextField("Anthropic 版本", draft.anthropicVersion,
                        { changeDraft(draft.copy(anthropicVersion = it)) },
                        hint = "留空使用 2023-06-01；格式 YYYY-MM-DD", enabled = enabled)
                }
                SettingsTextField("模型名", draft.model, { changeDraft(draft.copy(model = it)) }, enabled = enabled)
                SettingsTextField("API Key", draft.apiKey, { changeDraft(draft.copy(apiKey = it)) }, password = true, enabled = enabled)
                SettingsTextField("自定义请求头", draft.customHeaders, { changeDraft(draft.copy(customHeaders = it)) },
                    password = true, hint = "JSON 对象；加密保存，不覆盖鉴权或会话字段", enabled = enabled)
                if (headersUnavailable) {
                    SettingsNote("原请求头不可用；请重新输入或明确清除", error = true)
                    SettingsSwitchRow("授权替换或清除请求头", draft.headersReplacementAuthorized, enabled = enabled) {
                        changeDraft(draft.copy(headersReplacementAuthorized = it))
                    }
                }
            }
        }
        item(key = "model_device_tools") {
            SettingsSectionTitle("设备工具")
            SettingsCard {
                SettingsSwitchRow("电量查询", draft.batteryEnabled, enabled = enabled) { changeDraft(draft.copy(batteryEnabled = it)) }
                SettingsSwitchRow("设备状态查询", draft.deviceStatusEnabled, enabled = enabled) { changeDraft(draft.copy(deviceStatusEnabled = it)) }
            }
        }
        item(key = "model_advanced") {
            SettingsCard {
                SettingsRow("自定义请求体", tag = "model-custom-body",
                    summary = if (draft.customBody.isBlank()) "未设置" else "已设置",
                    onClick = if (enabled) ({ bodyExpanded = !bodyExpanded }) else null)
                if (engineeringUi || bodyExpanded) SettingsTextField("自定义请求体", draft.customBody,
                    { changeDraft(draft.copy(customBody = it)) }, multiline = true, enabled = enabled)
            }
        }
        if (draft.protocol != ProviderProtocol.RESPONSES &&
            (draft.speedMode != null || draft.codex)) item(key = "model_clear_incompatible_options") {
            SettingsCard {
                SettingsRow("清除不兼容的请求档位", tag = "model-clear-incompatible-options",
                    summary = "原请求档位仅适用于 Responses，清除后可保存当前协议",
                    onClick = if (enabled) ({ changeDraft(draft.copy(speedMode = null, codex = false)) }) else null)
            }
        }
        item(key = "model_speed") {
            SettingsSectionTitle("服务档位")
            SettingsCard {
                listOf(
                    null to "不指定",
                    GptSpeedMode.NORMAL to "普通",
                    GptSpeedMode.FAST to "快速",
                    GptSpeedMode.ULTRA_FAST to "超快速",
                ).forEach { (mode, title) ->
                    SettingsRadioRow(title, draft.speedMode == mode, enabled = enabled) { changeDraft(draft.copy(speedMode = mode)) }
                }
                SettingsSwitchRow("Codex 档位", draft.codex, enabled = enabled) { changeDraft(draft.copy(codex = it)) }
                if (requestOptionsUnavailable) {
                    SettingsSwitchRow("授权替换策略", draft.requestOptionsReplacementAuthorized, enabled = enabled) {
                        changeDraft(draft.copy(requestOptionsReplacementAuthorized = it))
                    }
                }
            }
            if (requestOptionsUnavailable) SettingsNote("原策略不可用", error = true)
        }
        if (testConnection != null) item(key = "model_connection_test") {
            SettingsCard {
                TextButton(text = if (connection.running) "正在测试连接" else "测试连接",
                    enabled = enabled && !connection.running, onClick = connection.start,
                    modifier = Modifier.testTag("model-connection-test").fillMaxWidth().padding(12.dp))
                connection.status?.let { SettingsNote(it) }
            }
        }
        if (showCatalog && onManageLocalModels != null) item(key = "local_models") {
            SettingsCard { SettingsRow("本地模型", onClick = { if (enabled) { connection.cancel(); onManageLocalModels() } },
                tag = "model-local-manage") }
        }
        if (showCatalog) {
        item(key = "model_catalog") {
            SettingsSectionTitle("远端模型目录")
            SettingsCard {
                TextButton(
                    text = if (isFetchingCatalog) "获取中" else "获取模型",
                    onClick = { connection.cancel(); onFetchCatalog(draft) },
                    enabled = enabled && catalogEnabled && !isFetchingCatalog,
                    modifier = Modifier.testTag("model-catalog-fetch").fillMaxWidth().padding(12.dp),
                )
            }
            if (catalogIsCurrent && !catalogStatusText.isNullOrBlank()) SettingsNote(catalogStatusText)
        }
        if (catalogIsCurrent) {
            // One lazy item per result: large supplier catalogs do not inflate one giant card.
            catalog.forEachIndexed { index, entry ->
                item(key = "model_catalog_${index}_${entry.id}") {
                    SettingsCard {
                        SettingsRow(
                            title = entry.displayName,
                            summary = entry.id.takeIf { it != entry.displayName },
                            onClick = { changeDraft(draft.copy(model = entry.id)) },
                            leading = {
                                DaiyuIcon(DaiyuIcons.Model, contentDescription = null,
                                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary, size = 24.dp)
                            },
                        )
                    }
                }
            }
        }
        }
        item(key = "model_save") {
            if (!errorText.isNullOrBlank()) SettingsNote(errorText, error = true)
            TextButton(
                text = "保存",
                onClick = submit,
                enabled = enabled,
                modifier = Modifier.testTag("model-save").semantics { this[RetainedModelSaveAction] = submit }.fillMaxWidth().padding(horizontal = 12.dp, vertical = 24.dp),
            )
        }
    }
}
