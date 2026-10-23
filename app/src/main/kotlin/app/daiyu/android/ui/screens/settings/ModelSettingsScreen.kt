package app.daiyu.android.ui.screens.settings

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.daiyu.android.model.GptSpeedMode
import app.daiyu.android.model.ProviderProtocol
import app.daiyu.android.ui.components.DaiyuChoiceSheet
import app.daiyu.android.ui.icons.ModelBrandIcon
import app.daiyu.android.ui.screens.assistants.assistantPreviewPalette
import app.daiyu.android.ui.haptics.miuix.TextButton
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * In-memory editing state only. Do not put this draft in saved state, logs or backups.
 * The host must validate and authorize save/fetch; displaying a checkbox is not authorization.
 * savedProtocol stays fixed for the editing session (copy() preserves it).
 */
internal data class ModelSettingsDraftUi(
    val providerName: String? = null,
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
    val openAiWebSearchEnabled: Boolean = false,
    val sessionGateway: app.daiyu.android.model.sessiongateway.SessionGatewayRule = app.daiyu.android.model.sessiongateway.SessionGatewayRule(),
    val headerFieldsValid: Boolean = true,
    val bodyFieldsValid: Boolean = true,
    val headerFieldRows: List<ProviderFieldRow>? = null,
    val bodyFieldRows: List<ProviderFieldRow>? = null,
    val headerFieldsError: String? = null,
    val bodyFieldsError: String? = null,
    val providerEnabled: Boolean = false,
    val balanceOption: app.daiyu.android.balance.BalanceOption = app.daiyu.android.balance.BalanceOption(),
    val balanceConfiguration: app.daiyu.android.balance.BalanceConfiguration? = null,
) {
    override fun toString(): String = "ModelSettingsDraftUi(protocol=$protocol, model=$model, apiKey=<redacted>, customBody=<redacted>)"
}

internal val RetainedModelSaveAction = SemanticsPropertyKey<() -> Unit>("RetainedModelSaveAction")

internal data class ModelCatalogEntryUi(val id: String, val displayName: String = id, val apiModelId: String = id)

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
        openAiWebSearchEnabled = false,
        headerFieldsValid = true,
        bodyFieldsValid = true,
        headerFieldRows = null, bodyFieldRows = null, headerFieldsError = null, bodyFieldsError = null,
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
    providerName: String? = null,
    testBalance: ProviderBalanceTester? = null,
    onDelete: (() -> Unit)? = null,
    listState: LazyListState = rememberLazyListState(),
) {
    val latestDraft by rememberUpdatedState(draft)
    val latestEnabled by rememberUpdatedState(enabled)
    val connection = rememberSettingsConnectionTest(draft, enabled, testConnection)
    val balance = rememberProviderBalanceTest(draft, enabled, testBalance)
    val submit: () -> Unit = { if (latestEnabled && latestDraft === draft && draft.headerFieldsValid && draft.bodyFieldsValid) { connection.cancel(); balance.cancel(); onSave(draft) } }
    val changeDraft: (ModelSettingsDraftUi) -> Unit = { if (latestEnabled && latestDraft === draft) { connection.cancel(); balance.cancel(); onDraftChange(it) } }
    val catalogIsCurrent = catalogForDraft?.let {
        it.endpoint == draft.endpoint && it.responsesPath == draft.responsesPath &&
            it.protocol == draft.protocol && it.apiKey == draft.apiKey && it.model == draft.model &&
            it.anthropicVersion == draft.anthropicVersion && it.customHeaders == draft.customHeaders &&
            it.openAiWebSearchEnabled == draft.openAiWebSearchEnabled
    } == true
    SettingsScaffoldPage(title = pageTitle, onBack = { connection.cancel(); balance.cancel(); onBack() }, modifier = modifier, listState = listState) {
        headerContent()
        item(key = "model_connection") {
            SettingsSectionTitle("连接配置")
            SettingsCard {
                if (providerName != null) SettingsTextField("名称", draft.providerName ?: providerName,
                    { changeDraft(draft.copy(providerName = it)) }, enabled = enabled, compact = true, tag = "provider-name")
                SettingsTextField("Base URL", draft.endpoint, { changeDraft(draft.copy(endpoint = it)) }, enabled = enabled, compact = true)
                SettingsTextField("API Key", draft.apiKey, { key ->
                    changeDraft(draft.copy(apiKey = key,
                        protocolChangeAuthorized = draft.protocol != draft.savedProtocol && key.isNotBlank()))
                }, password = true, enabled = enabled, compact = true)
                ProviderProtocolFields(draft, enabled, changeDraft)
                if (credentialUnavailable) SettingsNote("密钥失效，请重输", error = true)
                if (draft.savedProtocol == ProviderProtocol.UNKNOWN) SettingsNote("协议无效，请重选", error = true)
                if (providerName == null && draft.protocol == ProviderProtocol.RESPONSES) {
                    ProviderChoiceRow("Responses 路径", draft.responsesPath.ifBlank { "/v1/responses" },
                        "responses-path-select", enabled, listOf("/v1/responses", "/responses"),
                        listOf("prompt-choice:0", "prompt-choice:1"),
                        listOf("/v1/responses", "/responses").indexOf(draft.responsesPath.ifBlank { "/v1/responses" })) { index ->
                        changeDraft(draft.copy(responsesPath = if (index == 0) "/v1/responses" else "/responses"))
                    }
                }
                if (draft.protocol == ProviderProtocol.MESSAGES) {
                    SettingsTextField("Anthropic 版本", draft.anthropicVersion,
                        { changeDraft(draft.copy(anthropicVersion = it)) },
                        hint = "留空使用 2023-06-01；格式 YYYY-MM-DD", enabled = enabled, compact = true)
                }
                SettingsSwitchRow("Provider 托管网页搜索", draft.openAiWebSearchEnabled,
                    summary = "允许模型使用供应商提供的网页搜索",
                    enabled = enabled && draft.protocol == ProviderProtocol.RESPONSES) {
                    changeDraft(draft.copy(openAiWebSearchEnabled = it))
                }
                if (testConnection != null) {
                    if (providerName != null) {
                        ProviderActionButton(text = if (connection.running) "正在测试连接" else "测试连接",
                            enabled = enabled && !connection.running, onClick = connection.start,
                            modifier = Modifier.testTag("model-connection-test").padding(horizontal = 12.dp, vertical = 8.dp))
                    } else {
                        TextButton(text = if (connection.running) "正在测试连接" else "测试连接",
                            enabled = enabled && !connection.running, onClick = connection.start,
                            modifier = Modifier.testTag("model-connection-test").fillMaxWidth().padding(12.dp))
                    }
                    connection.status?.let { SettingsNote(it) }
                }
                if (requestOptionsUnavailable) {
                    SettingsNote("原请求策略不可用；替换需要明确确认", error = true)
                    SettingsSwitchRow("授权替换策略", draft.requestOptionsReplacementAuthorized, enabled = enabled) {
                        changeDraft(draft.copy(requestOptionsReplacementAuthorized = it))
                    }
                }
                if (headersUnavailable) {
                    SettingsNote("原请求头不可用；请重新输入或明确清除", error = true)
                    SettingsSwitchRow("授权替换或清除请求头", draft.headersReplacementAuthorized, enabled = enabled) {
                        changeDraft(draft.copy(headersReplacementAuthorized = it))
                    }
                }
            }
        }
        if (providerName == null) item(key = "model_current") {
            SettingsSectionTitle("当前模型")
            SettingsCard {
                SettingsTextField("模型名", draft.model, { changeDraft(draft.copy(model = it)) }, enabled = enabled, compact = true)
            }
        }
        item(key = "provider_gateway") {
            SettingsSectionTitle("会话 ID")
            SettingsCard {
                ProviderGatewayEditor(draft.sessionGateway, enabled) { changeDraft(draft.copy(sessionGateway = it)) }
            }
        }
        item(key = "provider_headers") {
            SettingsSectionTitle("自定义请求头")
            SettingsCard { ProviderHeadersEditor(draft, enabled, changeDraft) }
        }
        item(key = "provider_body") {
            SettingsSectionTitle("自定义请求体")
            SettingsCard { RequestBodyFieldsEditor(draft, enabled, engineeringUi, changeDraft) }
        }
        if (providerName != null) {
            item(key = "provider_enabled") {
                SettingsSectionTitle("偏好与策略")
                SettingsCard {
                    SettingsSwitchRow("启用此 Provider", draft.providerEnabled, enabled = enabled) {
                        changeDraft(draft.copy(providerEnabled = it))
                    }
                }
            }
            item(key = "provider_balance") {
                ProviderBalanceFields(draft, enabled, changeDraft, testBalance, balance)
            }
        }
        if (showCatalog && onManageLocalModels != null) item(key = "local_models") {
            SettingsCard { SettingsRow("本地模型", onClick = { if (enabled) { connection.cancel(); balance.cancel(); onManageLocalModels() } },
                tag = "model-local-manage") }
        }
        if (showCatalog) {
        item(key = "model_catalog") {
            SettingsSectionTitle("远端模型目录")
            SettingsCard {
                TextButton(
                    text = if (isFetchingCatalog) "获取中" else "获取模型",
                    onClick = { connection.cancel(); balance.cancel(); onFetchCatalog(draft) },
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
                                ModelBrandIcon(entry.id, Modifier.size(24.dp), displayName = entry.displayName,
                                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                            },
                        )
                    }
                }
            }
        }
        }
        item(key = "model_save") {
            if (!errorText.isNullOrBlank()) SettingsNote(errorText, error = true)
            val saveModifier = Modifier.testTag("model-save")
                .semantics { this[RetainedModelSaveAction] = submit }
            if (providerName != null) {
                ProviderActionButton(
                    text = "保存配置",
                    onClick = submit,
                    enabled = enabled && draft.headerFieldsValid && draft.bodyFieldsValid,
                    modifier = saveModifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
            } else {
                TextButton(
                    text = "保存",
                    onClick = submit,
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    enabled = enabled && draft.headerFieldsValid && draft.bodyFieldsValid,
                    modifier = saveModifier.padding(horizontal = 20.dp, vertical = 8.dp)
                        .fillMaxWidth().heightIn(min = 48.dp),
                )
            }
        }
        if (onDelete != null) item(key = "provider_delete") {
            ProviderActionButton(text = "删除提供商", enabled = enabled, onClick = {
                if (latestEnabled && latestDraft === draft) {
                    connection.cancel(); balance.cancel(); onDelete()
                }
            }, colors = ButtonDefaults.textButtonColorsPrimary(
                color = MiuixTheme.colorScheme.surfaceVariant,
                textColor = assistantPreviewPalette().danger),
                modifier = Modifier.testTag("provider-delete").padding(horizontal = 20.dp, vertical = 4.dp))
        }
    }
}

@Composable
internal fun ResponsesPathRow(title: String, path: String, enabled: Boolean, onSelect: (String) -> Unit) {
    var choosing by remember { mutableStateOf(false) }
    val options = listOf("/v1/responses", "/responses")
    SettingsRow(title, summary = path.ifBlank { options.first() },
        tag = "responses-path-select:$title", showChevron = true, onClick = if (enabled) ({ choosing = true }) else null)
    if (choosing) DaiyuChoiceSheet(title = title, options = options,
        onSelect = { index -> if (enabled) onSelect(options[index]); choosing = false },
        onDismiss = { choosing = false })
}
