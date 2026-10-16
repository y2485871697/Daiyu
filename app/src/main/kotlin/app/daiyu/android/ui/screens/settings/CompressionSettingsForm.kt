package app.daiyu.android.ui.screens.settings

import androidx.compose.runtime.Composable
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
            apiKey = "", responsesPath = if (protocol == ProviderProtocol.MESSAGES) "" else provider.responsesPath),
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
    MiuixScaffoldPage(title = "上下文压缩", onBack = onBack, modifier = modifier) {
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
            SettingsNote("压缩阈值按窗口减去预留后计算。关闭自动压缩不会关闭已知窗口的发送预算检查。旧版消息数设置需明确改为完整轮数并保存。")
        }
        item(key = "compression-profile") {
            SettingsSectionTitle("独立摘要模型")
            SettingsCard {
                SettingsSwitchRow("使用独立摘要配置", draft.configureSummary, enabled) {
                    onChange(draft.copy(configureSummary = it))
                }
                if (draft.configureSummary) {
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
                    SettingsTextField("摘要 Responses 路径", draft.provider.responsesPath,
                        { onChange(draft.copy(provider = draft.provider.copy(responsesPath = it))) },
                        enabled = enabled && draft.provider.protocol == ProviderProtocol.RESPONSES)
                    SettingsTextField("摘要模型名", draft.provider.model,
                        { onChange(draft.copy(provider = draft.provider.copy(model = it))) }, enabled = enabled)
                    SettingsTextField("摘要 API Key", draft.provider.apiKey,
                        { onChange(draft.copy(provider = draft.provider.copy(apiKey = it))) }, password = true, enabled = enabled)
                    SettingsTextField("摘要窗口 tokens", draft.summaryWindow, { onChange(draft.copy(summaryWindow = it)) }, enabled = enabled)
                    SettingsTextField("摘要输入预算 tokens", draft.inputBudget, { onChange(draft.copy(inputBudget = it)) }, enabled = enabled)
                    SettingsTextField("摘要最大输出 tokens", draft.outputBudget, { onChange(draft.copy(outputBudget = it)) }, enabled = enabled)
                    SettingsTextField("摘要推理预留 tokens", draft.reasoningBudget, { onChange(draft.copy(reasoningBudget = it)) }, enabled = enabled)
                    SettingsTextField("摘要协议预留 tokens", draft.overheadBudget, { onChange(draft.copy(overheadBudget = it)) }, enabled = enabled)
                    SettingsTextField("摘要截止时间毫秒", draft.deadlineMillis, { onChange(draft.copy(deadlineMillis = it)) }, enabled = enabled)
                    SettingsRow("重置摘要请求策略并授权替换",
                        summary = if (draft.requestOptionsReplacementAuthorized) "已重置草稿；点击保存才写入" else "清除自定义请求体及 GPT 档位；点击保存才写入",
                        onClick = if (enabled) ({ onChange(draft.resetRequestOptions()) }) else null)

                }
            }
            SettingsNote("摘要模型与聊天模型独立保存，不回退到聊天密钥。输入预算必须等于摘要窗口减去输出、推理和协议预留；这些数值需按你的模型明确填写。保存只更新设置，不发起摘要请求。")
        }
        item(key = "compression-save") {
            SettingsCard { SettingsRow("保存并回读", tag = "compression-save",
                onClick = if (enabled) ({ onSave(draft) }) else null) }
        }
        notice?.let { item(key = "compression-notice") { SettingsNote(it, error = error) } }
    }
}
