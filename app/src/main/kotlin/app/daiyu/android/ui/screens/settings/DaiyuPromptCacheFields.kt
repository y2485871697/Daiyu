package app.daiyu.android.ui.screens.settings

import androidx.compose.runtime.Composable
import app.daiyu.android.model.DaiyuPromptCacheRetention

@Composable
internal fun DaiyuPromptCacheFields(draft: ModelSettingsDraftUi, enabled: Boolean,
    onDraftChange: (ModelSettingsDraftUi) -> Unit) {
    val values = DaiyuPromptCacheRetention.entries
    val labels = listOf("不保留", "5分钟", "1小时")
    val selected = values.indexOf(draft.promptCacheRetention)
    ProviderChoiceRow("缓存保留时长", labels[selected], "provider-cache-retention", enabled,
        labels, values.map { "provider-cache-retention:${it.wireValue}" }, selected) { index ->
        onDraftChange(draft.copy(promptCacheRetention = values[index]))
    }
}
