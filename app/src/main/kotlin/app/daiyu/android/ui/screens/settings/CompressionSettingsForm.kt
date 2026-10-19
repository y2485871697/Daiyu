package app.daiyu.android.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.testTag
import top.yukonga.miuix.kmp.basic.TextButton
import androidx.compose.ui.Modifier
import app.daiyu.android.context.ProductionCompressionSettings
import app.daiyu.android.context.SummaryModelBinding
import app.daiyu.android.context.SummaryProfileBudgets
import app.daiyu.android.context.SummaryProfileSettings
import app.daiyu.android.model.ProviderProtocol
import app.daiyu.android.model.ProviderRequestOptions
import app.daiyu.android.storage.ModelSettings
import app.daiyu.android.ui.components.MiuixScaffoldPage

/** In-memory draft only; the host supplies one stable binding for the page. */
internal data class CompressionSettingsDraftUi(
    val binding: SummaryModelBinding,
    val automatic: Boolean = false,
    val window: String = "",
    val reserve: String = "4096",
    val trigger: String = "80",
    val keepTurns: String = "4",
    val configureSummary: Boolean = false,
    val provider: ModelSettings = ModelSettings("", "", "", false),
    val savedProtocol: ProviderProtocol? = null,
    val summaryWindow: String = "",
    val inputBudget: String = "",
    val outputBudget: String = "",
    val reasoningBudget: String = "",
    val overheadBudget: String = "",
    val deadlineMillis: String = "",
    val protocolChangeAuthorized: Boolean = false,
    val requestOptionsReplacementAuthorized: Boolean = false,
) {
    val needsProtocolAuthorization: Boolean get() = savedProtocol == null || provider.protocol != savedProtocol

    fun resetRequestOptions(): CompressionSettingsDraftUi = copy(
        provider = provider.copy(requestOptions = ProviderRequestOptions()),
        requestOptionsReplacementAuthorized = true,
    )

    fun compression() = ProductionCompressionSettings(automatic,
        window.trim().takeIf { it.isNotEmpty() }?.toLong(), reserve.trim().toLong(),
        trigger.trim().toInt(), keepTurns.trim().toInt(), binding.takeIf { configureSummary })

    fun summary(): SummaryProfileSettings? = if (!configureSummary) null else SummaryProfileSettings(
        binding, provider, SummaryProfileBudgets(summaryWindow.trim().toLong(), inputBudget.trim().toLong(),
            outputBudget.trim().toInt(), reasoningBudget.trim().toLong(), overheadBudget.trim().toLong(),
            deadlineMillis.trim().toLong()))

    fun selectProtocol(protocol: ProviderProtocol): CompressionSettingsDraftUi =
        if (protocol == provider.protocol) this else copy(provider = provider.copy(protocol = protocol,
            apiKey = "", responsesPath = if (protocol != ProviderProtocol.RESPONSES) "" else provider.responsesPath),
            protocolChangeAuthorized = false)

    override fun toString(): String = "CompressionSettingsDraftUi(binding=$binding, automatic=$automatic, provider=<redacted>)"
}

/** Controlled form: no preferences, credentials, model calls, or automatic saves. */
@Composable
internal fun CompressionSettingsForm(
    draft: CompressionSettingsDraftUi,
    onChange: (CompressionSettingsDraftUi) -> Unit,
    onSave: (CompressionSettingsDraftUi) -> Unit,
    onBack: () -> Unit,
    enabled: Boolean,
    notice: String?,
    error: Boolean,
    modifier: Modifier = Modifier,
) {
    var summaryExpanded by remember { mutableStateOf(false) }
    var diagnosticsExpanded by remember { mutableStateOf(false) }
    MiuixScaffoldPage(title = "上下文压缩", onBack = onBack, modifier = modifier) {
        notice?.let { item(key = "compression-notice") { SettingsNote(it, error = error) } }
        if (!enabled) item(key = "compression-busy") { SettingsNote("正在读取或保存设置…") }
        item(key = "compression-policy") {
            SettingsSectionTitle("当前会话发送预算")
            SettingsCard {
                SettingsSwitchRow("自动压缩", draft.automatic, enabled) { onChange(draft.copy(automatic = it)) }
                SettingsTextField("上下文窗口 tokens", draft.window, { onChange(draft.copy(window = it)) },
                    hint = "留空表示未知；不会猜测模型容量", enabled = enabled)
                SettingsTextField("发送预留 tokens", draft.reserve, { onChange(draft.copy(reserve = it)) }, enabled = enabled)
                SettingsTextField("触发百分比", draft.trigger, { onChange(draft.copy(trigger = it)) }, enabled = enabled)
                SettingsTextField("保留完整轮数", draft.keepTurns, { onChange(draft.copy(keepTurns = it)) }, enabled = enabled)
            }
            SettingsNote("开启自动压缩后，达到阈值时压缩。关闭后仍会检查已知窗口的发送预算。")
        }
        item(key = "compression-profile") {
            SettingsSectionTitle("独立摘要模型")
            SettingsCard {
                SettingsSwitchRow("使用独立摘要配置", draft.configureSummary, enabled) {
                    summaryExpanded = it
                    onChange(draft.copy(configureSummary = it))
                }
                if (draft.configureSummary) {
                    SettingsRow("摘要模型配置",
                        summary = if (summaryExpanded) "收起连接与预算设置" else
                            draft.provider.model.takeIf { it.isNotBlank() }?.let { "模型：$it · 展开编辑" } ?: "尚未填写模型 · 展开编辑",
                        tag = "compression-summary-config", onClick = { summaryExpanded = !summaryExpanded })
                }
                if (draft.configureSummary && summaryExpanded) {
                    SettingsRadioRow("OpenAI Responses", draft.provider.protocol == ProviderProtocol.RESPONSES, enabled) {
                        onChange(draft.selectProtocol(ProviderProtocol.RESPONSES))
                    }
                    SettingsRadioRow("Anthropic Messages", draft.provider.protocol == ProviderProtocol.MESSAGES, enabled) {
                        onChange(draft.selectProtocol(ProviderProtocol.MESSAGES))
                    }
                    if (draft.needsProtocolAuthorization) {
                        SettingsSwitchRow("重输摘要密钥并绑定新协议", draft.protocolChangeAuthorized, enabled) {
                            onChange(draft.copy(protocolChangeAuthorized = it))
                        }
                    }
                    SettingsTextField("摘要 Base URL", draft.provider.endpoint,
                        { onChange(draft.copy(provider = draft.provider.copy(endpoint = it))) }, enabled = enabled)
                    if (draft.provider.protocol == ProviderProtocol.RESPONSES) {
                        ResponsesPathRow("摘要 Responses 路径", draft.provider.responsesPath, enabled) {
                            onChange(draft.copy(provider = draft.provider.copy(responsesPath = it)))
                        }
                    }
                    SettingsTextField("摘要模型名", draft.provider.model,
                        { onChange(draft.copy(provider = draft.provider.copy(model = it))) }, enabled = enabled)
                    SettingsTextField("摘要 API Key", draft.provider.apiKey,
                        { onChange(draft.copy(provider = draft.provider.copy(apiKey = it))) }, password = true, enabled = enabled)
                    SettingsNote("请按摘要模型容量填写预算：输入预算 = 摘要窗口 − 输出、推理及协议预留。")
                    SettingsTextField("摘要窗口 tokens", draft.summaryWindow, { onChange(draft.copy(summaryWindow = it)) }, enabled = enabled)
                    SettingsTextField("摘要输入预算 tokens", draft.inputBudget, { onChange(draft.copy(inputBudget = it)) }, enabled = enabled)
                    SettingsTextField("摘要最大输出 tokens", draft.outputBudget, { onChange(draft.copy(outputBudget = it)) }, enabled = enabled)
                    SettingsTextField("摘要推理预留 tokens", draft.reasoningBudget, { onChange(draft.copy(reasoningBudget = it)) }, enabled = enabled)
                    SettingsTextField("摘要协议预留 tokens", draft.overheadBudget, { onChange(draft.copy(overheadBudget = it)) }, enabled = enabled)
                    SettingsTextField("摘要截止时间毫秒", draft.deadlineMillis, { onChange(draft.copy(deadlineMillis = it)) }, enabled = enabled)
                    SettingsRow("重置摘要请求策略并授权替换",
                        summary = if (draft.requestOptionsReplacementAuthorized) "已重置草稿；点击保存才写入" else "清除自定义请求参数；点击保存才写入",
                        onClick = if (enabled) ({ onChange(draft.resetRequestOptions()) }) else null)

                }
            }
            SettingsNote("摘要配置与聊天配置独立，不会使用聊天模型密钥。保存不会发起摘要请求。")
        }
        item(key = "compression-save") {
            SettingsCard { TextButton(text = "保存配置", enabled = enabled,
                modifier = Modifier.testTag("compression-save"), onClick = { onSave(draft) }) }
        }
        item(key = "compression-diagnostics") {
            SettingsCard {
                SettingsRow("诊断详情", summary = if (diagnosticsExpanded) "收起阈值计算与兼容说明" else "展开阈值计算与兼容说明",
                    tag = "compression-diagnostics", onClick = { diagnosticsExpanded = !diagnosticsExpanded })
                if (diagnosticsExpanded) {
                    SettingsNote("压缩阈值按上下文窗口减去发送预留后计算；窗口留空表示容量未知，不会自动推测。")
                    SettingsNote("旧版消息数不会自动换算为完整轮数。请确认保留轮数后保存。")
                    SettingsNote("摘要协议、端点和预算可在「摘要模型配置」中编辑。更换协议需重输摘要密钥并授权；保存后会重新读取设置确认结果。")
                }
            }
        }
    }
}
