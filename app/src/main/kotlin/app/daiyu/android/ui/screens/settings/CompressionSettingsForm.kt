package app.daiyu.android.ui.screens.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.daiyu.android.context.ProductionCompressionSettings
import app.daiyu.android.context.SummaryModelBinding
import app.daiyu.android.context.SummaryProfileBudgets
import app.daiyu.android.context.SummaryProfileSettings
import app.daiyu.android.model.ProviderProtocol
import app.daiyu.android.model.ProviderRequestOptions
import app.daiyu.android.storage.ModelSettings

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

/** The form only presents original controls; providers own connection and model settings. */
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
    modelLabel: String? = null,
    onChooseModel: () -> Unit = {},
) {
    SettingsScaffoldPage(title = "上下文压缩", onBack = onBack, modifier = modifier) {
        notice?.let { item("compression-notice") { SettingsNote(it, error = error) } }
        item("compression-automatic") {
            SettingsCard(Modifier.padding(top = 12.dp)) {
                SettingsSwitchRow("自动压缩上下文", draft.automatic, enabled,
                    tag = "settings-switch:自动压缩", summary = "达到阈值时，自动摘要较早对话。") {
                    onSave(draft.copy(automatic = it))
                }
            }
        }
        item("compression-protocol") {
            SettingsCard(Modifier.padding(top = 12.dp)) {
                // Endpoint preference is not wired yet; never reuse the provider/model selection action.
                ProviderChoiceRow("请求接口", "Chat Completions API", "compression-protocol", enabled,
                    listOf("Chat Completions API", "Responses API"),
                    listOf("compression-endpoint:chat-completions", "compression-endpoint:responses"),
                    selectedIndex = -1, onSelect = {})
                SettingsNote("仅用于摘要。默认 Chat Completions；模型需要时选 Responses。Anthropic / Codex 不受此项影响。")
            }
        }
        item("compression-profile") {
            SettingsCard(Modifier.padding(top = 12.dp)) {
                SettingsSwitchRow("自定义压缩模型", draft.configureSummary, enabled,
                    tag = "settings-switch:使用独立摘要配置", summary = "关闭时沿用对话模型。") {
                    if (it && modelLabel == null) onChooseModel() else onSave(draft.copy(configureSummary = it))
                }
                SettingsRow("压缩模型", summary = modelLabel ?: "选择模型", tag = "compression-summary-config",
                    onClick = if (enabled) onChooseModel else null)
            }
        }
    }
}
