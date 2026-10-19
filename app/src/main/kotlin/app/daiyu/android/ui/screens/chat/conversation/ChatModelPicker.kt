package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.daiyu.android.ui.icons.DaiyuIcons
import app.daiyu.android.ui.components.subagent.ConversationAgentButton
import androidx.compose.material3.HorizontalDivider
import app.daiyu.android.ui.components.usagePalette
import app.daiyu.android.ui.screens.settings.ModelCatalogEntryUi
import top.yukonga.miuix.kmp.basic.Text

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
    var searchFocused by remember { mutableStateOf(false) }
    val colors = usagePalette()
    val search = query.trim()
    val visible = remember(state.models, search) {
        state.models.filter { it.id.contains(search, true) || it.displayName.contains(search, true) }
    }
    val duplicateNames = remember(state.models) {
        state.models.groupingBy { it.displayName }.eachCount().filterValues { it > 1 }.keys
    }
    val currentModel = state.models.firstOrNull { it.id == state.current }
    val canSelect = state.enabled && !state.loading
    val status = when {
        state.loading -> "正在读取模型目录…"
        state.message != null -> state.message
        !state.enabled -> "当前任务结束后可切换模型"
        else -> null
    }

    // Use the same bottom glass sheet/backdrop and tokens as preview-agent-cards, not a
    // platform alert. The host still owns the local snapshot, selection ticket and CAS.
    ChatSelectionSheet(
        height = 500.dp,
        title = "选择模型",
        onDismiss = onDismiss,
        footer = {
            ConversationAgentButton("模型设置", enabled = !state.loading, modifier = Modifier.weight(1f), onClick = onSettings)
            ConversationAgentButton("刷新", enabled = !state.loading,
                modifier = Modifier.weight(1f).testTag("chat-model-refresh"), onClick = onRefresh)
            ConversationAgentButton("取消", modifier = Modifier.weight(1f), onClick = onDismiss)
        },
    ) {
        Column(Modifier.testTag("chat-model-picker").fillMaxSize()) {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                .background(colors.bubble).border(1.dp, colors.line, RoundedCornerShape(14.dp))
                .padding(horizontal = 11.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(29.dp).clip(RoundedCornerShape(10.dp)).background(colors.background),
                    contentAlignment = Alignment.Center) {
                    Icon(DaiyuIcons.Model, null, Modifier.size(17.dp), tint = colors.accent)
                }
                Column(Modifier.weight(1f)) {
                    Text("当前模型", color = colors.muted, fontSize = 9.sp, modifier = Modifier.padding(bottom = 3.dp))
                    Text(currentModel?.displayName ?: state.current.ifBlank { if (state.loading) "读取中…" else "未选择模型" },
                        color = colors.accent, fontSize = 12.sp, fontWeight = FontWeight(550),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (state.current.isNotBlank()) Icon(DaiyuIcons.Check, "当前模型", Modifier.size(14.dp), tint = colors.accent)
            }
            Spacer(Modifier.height(12.dp))
            // Match the shared preview field tokens, but keep the tag on the editable node.
            // AssistantPreviewField exposes its modifier on a wrapping Column, which would
            // make the existing search editing contract depend on semantics merging.
            BasicTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                textStyle = TextStyle(color = colors.text, fontSize = 11.sp,
                    lineHeight = 18.15.sp),
                cursorBrush = SolidColor(colors.accent),
                modifier = Modifier.testTag("chat-model-search").fillMaxWidth()
                    .semantics { contentDescription = "搜索模型" }
                    .onFocusChanged { searchFocused = it.isFocused }
                    .heightIn(min = 36.dp).clip(RoundedCornerShape(12.dp)).background(colors.background)
                    .border(1.dp, if (searchFocused) colors.accent else colors.line,
                        RoundedCornerShape(12.dp))
                    .padding(horizontal = 10.dp, vertical = 9.dp),
                decorationBox = { input ->
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(DaiyuIcons.Search, null, Modifier.size(14.dp), tint = colors.muted)
                        Box(Modifier.weight(1f)) {
                            if (query.isEmpty()) Text("搜索模型", color = colors.muted,
                                fontSize = 11.sp)
                            input()
                        }
                    }
                },
            )
            Spacer(Modifier.height(13.dp))
            if (!state.loading && state.current.isNotBlank() && currentModel == null) {
                Text("当前模型不在本地目录：${state.current}", color = colors.muted,
                    fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(bottom = 7.dp))
            }
            if (status != null) Text(status, color = colors.muted, fontSize = 10.sp,
                modifier = Modifier.fillMaxWidth().padding(bottom = 7.dp))
            LazyColumn(
                Modifier.testTag("chat-model-list").fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                // The production entry point supplies only the active provider's local catalog.
                // Do not invent the other providers/groups shown in the HTML fixture.
                item(key = "catalog-heading") {
                    Row(Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 5.dp)
                        .semantics { heading() }, verticalAlignment = Alignment.CenterVertically) {
                        Text("当前供应商 · 本地目录", color = colors.muted,
                            fontSize = 10.sp, modifier = Modifier.weight(1f))
                        Text("${visible.size}", color = colors.muted, fontSize = 9.sp)
                    }
                }
                items(visible, key = { "model:${it.id}" }) { model ->
                    val selected = model.id == state.current
                    val shape = RoundedCornerShape(10.dp)
                    Row(
                        Modifier.testTag("chat-model-option:${model.id}").fillMaxWidth()
                            .alpha(if (canSelect) 1f else .45f).clip(shape)
                            .background(if (selected) colors.bubble else Color.Transparent)
                            .border(1.dp, if (selected) colors.line else Color.Transparent, shape)
                            .selectable(
                                selected = selected, enabled = canSelect, role = Role.RadioButton,
                                interactionSource = remember { MutableInteractionSource() }, indication = null,
                                onClick = { onSelect(model.id) },
                            )
                            .heightIn(min = 44.dp)
                            .padding(horizontal = 9.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(9.dp),
                    ) {
                        Icon(DaiyuIcons.Model, null, Modifier.size(17.dp), tint = colors.accent)
                        Column(Modifier.weight(1f)) {
                            Text(model.displayName, color = if (selected) colors.accent else colors.text,
                                fontSize = 11.sp,
                                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (model.displayName in duplicateNames && model.displayName != model.id) {
                                Text(model.id, color = colors.muted, fontSize = 9.sp,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        Box(
                            Modifier.size(17.dp).clip(CircleShape)
                                .background(if (selected) colors.accent else Color.Transparent),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (selected) Icon(DaiyuIcons.Check, "当前模型",
                                Modifier.size(10.dp), tint = if (colors.dark) Color(0xFF211735) else Color.White)
                        }
                    }
                }
                if (visible.isEmpty() && !state.loading) item(key = "empty") {
                    Text(if (query.isBlank()) "暂无可选模型" else "没有匹配的模型",
                        color = colors.muted, fontSize = 11.sp,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp))
                }
            }
            HorizontalDivider(Modifier.padding(top = 10.dp), color = colors.line)
            Text("仅显示当前供应商的本地目录 · 刷新不会联网", color = colors.muted,
                fontSize = 9.sp, modifier = Modifier.fillMaxWidth().padding(top = 9.dp, bottom = 8.dp),
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
