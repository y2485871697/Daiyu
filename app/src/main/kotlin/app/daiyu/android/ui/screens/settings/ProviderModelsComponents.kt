package app.daiyu.android.ui.screens.settings

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.daiyu.android.ui.icons.DaiyuIcon
import app.daiyu.android.ui.icons.DaiyuIcons

@Composable
internal fun ProviderModelManagementCard(endpoint: String, loading: Boolean = false,
    onFetch: (() -> Unit)? = null, onAdd: (() -> Unit)? = null) {
    SettingsSectionTitle("模型管理")
    SettingsCard {
        SettingsRow(if (loading) "正在拉取模型" else "从远端自动拉取",
            summary = if (endpoint.isBlank()) "读取供应商的 /models 列表" else "读取当前供应商的 /models 列表",
            tag = "model-catalog-fetch", leading = { DaiyuIcon(DaiyuIcons.Import, null) },
            onClick = onFetch, trailing = {})
        SettingsRow("添加自定义模型", summary = "手动填写展示名称与 Model ID",
            tag = "local-model-add", leading = { DaiyuIcon(DaiyuIcons.Add, null) },
            onClick = onAdd, trailing = { DaiyuIcon(DaiyuIcons.ChevronRight, null) })
    }
}

@Composable
internal fun ProviderEmptyModelsPage(title: String, onBack: () -> Unit,
    tabs: LazyListScope.() -> Unit, notice: String,
    listState: LazyListState = rememberLazyListState(),
    queryState: MutableState<String> = remember { mutableStateOf("") },
) {
    var query by queryState
    SettingsScaffoldPage(title, onBack, listState = listState) {
        tabs()
        item("model-management") { ProviderModelManagementCard("") }
        item("model-search") { SettingsSearch(query, { query = it }, "搜索模型", "local-model-search") }
        item("model-empty") {
            SettingsSectionTitle("模型列表（共 0 个）")
            SettingsNote(notice)
        }
    }
}
