package app.daiyu.android.title

import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.*
import app.daiyu.android.ui.components.MiuixScaffoldPage
import app.daiyu.android.ui.screens.settings.*
import androidx.compose.ui.Modifier
import app.daiyu.android.model.ProviderProtocol
import app.daiyu.android.storage.ModelSettings

/** The host creates a new opaque binding ONCE per edit draft, not on every recomposition.
 * No rememberSaveable, main-model copy, credential logging, provider probe or network action. */
data class TitleModelDraft(
    val binding: TitleModelBinding,
    val endpoint: String = "",
    val model: String = "",
    val apiKey: String = "",
    val protocol: ProviderProtocol = ProviderProtocol.RESPONSES,
    val responsesPath: String = "",
    val protocolChangeAuthorized: Boolean = false,
    val requestOptionsReplacementAuthorized: Boolean = false,
) {
    fun profile(): TitleModelProfile = TitleModelProfile(binding, ModelSettings(endpoint, model, apiKey,
        batteryEnabled = false, responsesPath = responsesPath, deviceStatusEnabled = false, protocol = protocol))
    override fun toString(): String = "TitleModelDraft(binding=$binding, protocol=$protocol, apiKey=<redacted>)"
}

@Composable
fun TitleModelSettingsPanel(
    settings: TitleModelSettings,
    draft: TitleModelDraft,
    onDraftChange: (TitleModelDraft) -> Unit,
    onSave: (TitleModelDraft) -> Unit,
    onDisable: () -> Unit,
    busy: Boolean,
    notice: String? = null,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    modelLabel: String? = null,
    onChooseModel: () -> Unit = {},
) {
    MiuixScaffoldPage(title = "标题模型", onBack = onBack, modifier = modifier) {
        item("title-model-intro") { SettingsNote("为新会话自动生成简短标题。") }
        notice?.let { item("title-model-status") { SettingsNote(it) } }
        item("title-model-summary") {
            SettingsCard(Modifier.padding(top = 12.dp)) {
                SettingsSwitchRow("使用自定义模型", settings.enabled, !busy,
                    tag = "title-model-enabled") { requested -> if (requested) onChooseModel() else onDisable() }
                SettingsRow("选择模型", summary = modelLabel ?: "选择模型", tag = "title-model-configure",
                    onClick = if (busy) null else onChooseModel)
            }
        }
    }
}
