package app.daiyu.android.ui.screens.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
    onChooseProtocol: (ProviderProtocol) -> Unit = {},
) {
    var showProtocol by remember { mutableStateOf(false) }
    MiuixScaffoldPage(title = "上下文压缩", onBack = onBack, modifier = modifier) {
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
                SettingsRow("请求接口", summary = protocolLabel(draft.provider.protocol),
                    tag = "compression-protocol", onClick = if (enabled) ({ showProtocol = true }) else null)
            }
        }
        item("compression-profile") {
            SettingsCard(Modifier.padding(top = 12.dp)) {
                SettingsSwitchRow("自定义压缩模型", draft.configureSummary, enabled,
                    tag = "settings-switch:使用独立摘要配置") {
                    if (it && modelLabel == null) onChooseModel() else onSave(draft.copy(configureSummary = it))
                }
                SettingsRow("压缩模型", summary = modelLabel ?: "选择模型", tag = "compression-summary-config",
                    onClick = if (enabled) onChooseModel else null)
            }
        }
    }
    if (showProtocol) app.daiyu.android.ui.components.DaiyuChoiceSheet("请求接口",
        listOf("OpenAI Responses", "Anthropic Messages"), onSelect = { index ->
            val selected = if (index == 0) ProviderProtocol.RESPONSES else ProviderProtocol.MESSAGES
            if (selected != draft.provider.protocol) onChooseProtocol(selected)
            showProtocol = false
        }, onDismiss = { showProtocol = false })
}

private fun protocolLabel(protocol: ProviderProtocol): String = when (protocol) {
    ProviderProtocol.RESPONSES -> "OpenAI Responses"
    ProviderProtocol.MESSAGES -> "Anthropic Messages"
    else -> protocol.wireName
}
