package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.daiyu.android.ui.icons.DaiyuIcons
import app.daiyu.android.ui.screens.settings.ModelCatalogEntryUi
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

internal data class ChatModelPickerState(
    val current: String,
    val models: List<ModelCatalogEntryUi>,
    val loading: Boolean = false,
    val message: String? = null,
    val enabled: Boolean = true,
)

@Composable
internal fun ChatModelPicker(
    state: ChatModelPickerState,
    onSelect: (String) -> Unit,
    onRefresh: () -> Unit,
    onSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val colors = MiuixTheme.colorScheme
    val visible = state.models.filter {
        it.id.contains(query.trim(), true) || it.displayName.contains(query.trim(), true)
    }
    WindowDialog(show = true, title = "选择模型", onDismissRequest = onDismiss) {
        Column(Modifier.testTag("chat-model-picker").fillMaxWidth()
            .heightIn(max = minOf(520.dp, (LocalConfiguration.current.screenHeightDp * 0.72f).dp))) {
            TextField(value = query, onValueChange = { query = it }, label = "搜索模型",
                useLabelAsPlaceholder = true, singleLine = true,
                modifier = Modifier.testTag("chat-model-search").fillMaxWidth())
            val status = when {
                state.loading -> "正在读取模型目录…"
                state.message != null -> state.message
                !state.enabled -> "当前任务结束后可切换模型"
                else -> null
            }
            if (status != null) Text(status, Modifier.padding(vertical = 12.dp),
                color = colors.onSurfaceVariantSummary, style = MiuixTheme.textStyles.footnote1)
            LazyColumn(Modifier.testTag("chat-model-list").fillMaxWidth().weight(1f, fill = false)
                .padding(top = 8.dp)) {
                items(visible, key = { it.id }) { model ->
                    Row(Modifier.testTag("chat-model-option:${model.id}").fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp)).selectable(
                            selected = model.id == state.current, enabled = state.enabled,
                            role = Role.RadioButton, onClick = { onSelect(model.id) })
                        .heightIn(min = 56.dp).padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(model.displayName, color = colors.onSurface, maxLines = 2,
                                overflow = TextOverflow.Ellipsis, style = MiuixTheme.textStyles.body1)
                            if (model.displayName != model.id) Text(model.id,
                                color = colors.onSurfaceVariantSummary, maxLines = 1,
                                overflow = TextOverflow.Ellipsis, style = MiuixTheme.textStyles.footnote1)
                        }
                        if (model.id == state.current) Icon(DaiyuIcons.Check, "当前模型",
                            Modifier.size(20.dp), tint = colors.primary)
                    }
                }
            }
            if (visible.isEmpty() && !state.loading) Text(
                if (query.isBlank()) "暂无可选模型" else "没有匹配的模型",
                Modifier.padding(vertical = 16.dp), color = colors.onSurfaceVariantSummary)
            Row(Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(text = "模型设置", onClick = onSettings, enabled = !state.loading)
                TextButton(text = "刷新", onClick = onRefresh, enabled = !state.loading,
                    modifier = Modifier.testTag("chat-model-refresh"))
                TextButton(text = "取消", onClick = onDismiss)
            }
        }
    }
}
