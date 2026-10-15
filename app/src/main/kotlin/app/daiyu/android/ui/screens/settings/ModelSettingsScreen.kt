package app.daiyu.android.ui.screens.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.daiyu.android.R
import app.daiyu.android.model.GptSpeedMode
import app.daiyu.android.model.ProviderProtocol
import app.daiyu.android.ui.components.MiuixScaffoldPage
import app.daiyu.android.ui.icons.DaiyuIcon
import app.daiyu.android.ui.icons.DaiyuIcons
import top.yukonga.miuix.kmp.basic.ButtonDefaults
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
) {
    val catalogIsCurrent = catalogForDraft == null || (
        catalogForDraft.endpoint == draft.endpoint && catalogForDraft.responsesPath == draft.responsesPath &&
            catalogForDraft.protocol == draft.protocol && catalogForDraft.apiKey == draft.apiKey &&
            catalogForDraft.model == draft.model
        )
    MiuixScaffoldPage(title = "模型设置", onBack = onBack, modifier = modifier) {
        item(key = "model_protocol") {
            SettingsSectionTitle("服务协议")
            SettingsCard {
                SettingsRadioRow("OpenAI Responses", draft.protocol == ProviderProtocol.RESPONSES) {
                    onDraftChange(draft.selectProtocol(ProviderProtocol.RESPONSES))
                }
                SettingsRadioRow("Anthropic Messages", draft.protocol == ProviderProtocol.MESSAGES) {
                    onDraftChange(draft.selectProtocol(ProviderProtocol.MESSAGES))
                }
                if (draft.protocol != draft.savedProtocol) {
                    SettingsSwitchRow("我已重新输入密钥，确认将其绑定到所选协议", draft.protocolChangeAuthorized) {
                        onDraftChange(draft.copy(protocolChangeAuthorized = it))
                    }
                }
            }
            SettingsNote(stringResource(R.string.model_protocol_note))
            if (credentialUnavailable) SettingsNote("已保存密钥不可用；原配置已保留，请重新输入密钥", error = true)
            if (draft.savedProtocol == ProviderProtocol.UNKNOWN) SettingsNote("已保存协议无效；请选择协议并重新输入密钥", error = true)
        }
        item(key = "model_connection") {
            SettingsSectionTitle("连接与模型")
            SettingsCard {
                SettingsTextField("Base URL", draft.endpoint, { onDraftChange(draft.copy(endpoint = it)) },
                    hint = if (draft.protocol == ProviderProtocol.MESSAGES) "HTTPS 基础地址或完整 Messages 地址" else "HTTPS 服务基础地址或完整 Responses 地址")
                SettingsTextField("Responses 路径", draft.responsesPath, { onDraftChange(draft.copy(responsesPath = it)) },
                    hint = "可选，如 /responses", enabled = draft.protocol != ProviderProtocol.MESSAGES)
                SettingsTextField("模型名", draft.model, { onDraftChange(draft.copy(model = it)) }, hint = "模型标识")
                SettingsTextField("API Key", draft.apiKey, { onDraftChange(draft.copy(apiKey = it)) },
                    hint = "API 密钥（加密保存）", password = true)
            }
        }
        item(key = "model_device_tools") {
            SettingsSectionTitle("只读设备工具")
            SettingsCard {
                SettingsSwitchRow("允许模型查询只读电量", draft.batteryEnabled) { onDraftChange(draft.copy(batteryEnabled = it)) }
                SettingsSwitchRow("允许模型查询只读设备状态", draft.deviceStatusEnabled) { onDraftChange(draft.copy(deviceStatusEnabled = it)) }
            }
            SettingsNote("设备状态包括电量、内存、应用存储所在卷、系统与开机时长；结果发送给模型服务，不含设备标识。")
        }
        item(key = "model_advanced") {
            SettingsSectionTitle("高级请求选项")
            SettingsNote("高级请求选项保存到本机并发送给供应商，不进入非秘密备份，请勿填写凭据。JSON 不能覆盖消息、工具或流式控制字段；Messages 不支持 GPT/Codex 档位。Codex 不支持超快速，供应商实际支持需另行确认。")
            SettingsCard {
                SettingsTextField("自定义请求体", draft.customBody, { onDraftChange(draft.copy(customBody = it)) },
                    hint = "自定义请求 JSON 对象（可留空，最多 64 KiB）", multiline = true)
            }
        }
        item(key = "model_speed") {
            SettingsSectionTitle("GPT fast / 服务档位")
            SettingsCard {
                listOf(
                    null to "不指定 GPT 服务档位",
                    GptSpeedMode.NORMAL to "GPT 普通档位",
                    GptSpeedMode.FAST to "GPT 快速档位",
                    GptSpeedMode.ULTRA_FAST to "GPT 超快速档位",
                ).forEach { (mode, title) ->
                    SettingsRadioRow(title, draft.speedMode == mode) { onDraftChange(draft.copy(speedMode = mode)) }
                }
                SettingsSwitchRow("使用 Codex 服务档位规则", draft.codex) { onDraftChange(draft.copy(codex = it)) }
                if (requestOptionsUnavailable) {
                    SettingsSwitchRow("原请求策略不可用；我确认用当前高级选项替换原值", draft.requestOptionsReplacementAuthorized) {
                        onDraftChange(draft.copy(requestOptionsReplacementAuthorized = it))
                    }
                }
            }
            if (requestOptionsUnavailable) SettingsNote("原请求策略不可用；原值尚未替换。请检查高级选项，并明确开启替换授权。", error = true)
        }
        item(key = "model_catalog") {
            SettingsSectionTitle("远端模型目录")
            SettingsCard {
                TextButton(
                    text = if (isFetchingCatalog) "正在获取模型目录…" else "获取模型目录（使用当前草稿）",
                    onClick = { onFetchCatalog(draft) },
                    enabled = !isFetchingCatalog,
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                )
            }
            if (catalogIsCurrent && !catalogStatusText.isNullOrBlank()) SettingsNote(catalogStatusText)
            if (catalogIsCurrent && catalog.isNotEmpty()) SettingsNote("选择模型仅修改草稿，仍需点击保存")
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
                                    tint = MiuixTheme.colorScheme.primary, size = 24.dp)
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
                colors = ButtonDefaults.textButtonColorsPrimary(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 20.dp),
            )
        }
    }
}
