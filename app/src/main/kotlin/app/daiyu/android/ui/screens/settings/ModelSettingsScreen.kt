package app.daiyu.android.ui.screens.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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
) {
    override fun toString(): String = "ModelSettingsDraftUi(protocol=$protocol, model=$model, apiKey=<redacted>, customBody=<redacted>)"
}

internal data class ModelCatalogEntryUi(val id: String, val displayName: String = id)

/** Changing protocols deliberately clears the key and the previous binding authorization. */
internal fun ModelSettingsDraftUi.selectProtocol(selected: ProviderProtocol): ModelSettingsDraftUi =
    if (selected == protocol) this else copy(
        protocol = selected,
        apiKey = "",
        responsesPath = if (selected == ProviderProtocol.MESSAGES) "" else responsesPath,
        protocolChangeAuthorized = false,
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
    catalog: List<ModelCatalogEntryUi> = emptyList(),
    isFetchingCatalog: Boolean = false,
    catalogStatusText: String? = null,
    catalogForDraft: ModelSettingsDraftUi? = null,
    catalogEnabled: Boolean = true,
    enabled: Boolean = true,
) {
    val catalogIsCurrent = catalogForDraft?.let {
        it.endpoint == draft.endpoint && it.responsesPath == draft.responsesPath &&
            it.protocol == draft.protocol && it.apiKey == draft.apiKey && it.model == draft.model
    } == true
    MiuixScaffoldPage(title = "模型设置", onBack = onBack, modifier = modifier) {
        item(key = "model_protocol") {
            SettingsSectionTitle("服务协议")
            SettingsCard {
                SettingsRadioRow("OpenAI Responses", draft.protocol == ProviderProtocol.RESPONSES, enabled = enabled) {
                    onDraftChange(draft.selectProtocol(ProviderProtocol.RESPONSES))
                }
                SettingsRadioRow("Anthropic Messages", draft.protocol == ProviderProtocol.MESSAGES, enabled = enabled) {
                    onDraftChange(draft.selectProtocol(ProviderProtocol.MESSAGES))
                }
                if (draft.protocol != draft.savedProtocol) {
                    SettingsSwitchRow("重输密钥并绑定", draft.protocolChangeAuthorized, enabled = enabled) {
                        onDraftChange(draft.copy(protocolChangeAuthorized = it))
                    }
                }
            }
            if (credentialUnavailable) SettingsNote("密钥失效，请重输", error = true)
            if (draft.savedProtocol == ProviderProtocol.UNKNOWN) SettingsNote("协议无效，请重选", error = true)
        }
        item(key = "model_connection") {
            SettingsSectionTitle("连接与模型")
            SettingsCard {
                SettingsTextField("Base URL", draft.endpoint, { onDraftChange(draft.copy(endpoint = it)) }, enabled = enabled)
                SettingsTextField("Responses 路径", draft.responsesPath, { onDraftChange(draft.copy(responsesPath = it)) },
                    hint = "/responses", enabled = enabled && draft.protocol != ProviderProtocol.MESSAGES)
                SettingsTextField("模型名", draft.model, { onDraftChange(draft.copy(model = it)) }, enabled = enabled)
                SettingsTextField("API Key", draft.apiKey, { onDraftChange(draft.copy(apiKey = it)) }, password = true, enabled = enabled)
            }
        }
        item(key = "model_device_tools") {
            SettingsSectionTitle("设备工具")
            SettingsCard {
                SettingsSwitchRow("电量查询", draft.batteryEnabled, enabled = enabled) { onDraftChange(draft.copy(batteryEnabled = it)) }
                SettingsSwitchRow("设备状态查询", draft.deviceStatusEnabled, enabled = enabled) { onDraftChange(draft.copy(deviceStatusEnabled = it)) }
            }
        }
        item(key = "model_advanced") {
            SettingsSectionTitle("高级选项")
            SettingsCard {
                SettingsTextField("自定义请求体", draft.customBody, { onDraftChange(draft.copy(customBody = it)) }, multiline = true, enabled = enabled)
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
                    SettingsRadioRow(title, draft.speedMode == mode, enabled = enabled) { onDraftChange(draft.copy(speedMode = mode)) }
                }
                SettingsSwitchRow("Codex 档位", draft.codex, enabled = enabled) { onDraftChange(draft.copy(codex = it)) }
                if (requestOptionsUnavailable) {
                    SettingsSwitchRow("授权替换策略", draft.requestOptionsReplacementAuthorized, enabled = enabled) {
                        onDraftChange(draft.copy(requestOptionsReplacementAuthorized = it))
                    }
                }
            }
            if (requestOptionsUnavailable) SettingsNote("原策略不可用", error = true)
        }
        item(key = "model_catalog") {
            SettingsSectionTitle("远端模型目录")
            SettingsCard {
                TextButton(
                    text = if (isFetchingCatalog) "获取中" else "获取模型",
                    onClick = { onFetchCatalog(draft) },
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
                            onClick = { onDraftChange(draft.copy(model = entry.id)) },
                            leading = {
                                DaiyuIcon(DaiyuIcons.Model, contentDescription = null,
                                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary, size = 24.dp)
                            },
                        )
                    }
                }
            }
        }
        item(key = "model_save") {
            if (!errorText.isNullOrBlank()) SettingsNote(errorText, error = true)
            TextButton(
                text = "保存",
                onClick = { onSave(draft) },
                enabled = enabled,
                modifier = Modifier.testTag("model-save").fillMaxWidth().padding(horizontal = 12.dp, vertical = 24.dp),
            )
        }
    }
}
