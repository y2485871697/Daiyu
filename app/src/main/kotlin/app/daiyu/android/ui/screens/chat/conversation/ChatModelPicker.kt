package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.daiyu.android.ui.components.usagePalette
import app.daiyu.android.ui.icons.ModelBrandIcon
import app.daiyu.android.ui.icons.DaiyuIcons
import app.daiyu.android.ui.screens.settings.ModelCatalogEntryUi
import top.yukonga.miuix.kmp.basic.Text

internal data class ChatModelProviderUi(
    val id: String,
    val name: String,
    val models: List<ModelCatalogEntryUi>,
    val balance: String? = null,
)

internal data class ChatModelPickerState(
    val current: String,
    val models: List<ModelCatalogEntryUi>,
    val loading: Boolean = false,
    val message: String? = null,
    val enabled: Boolean = true,
    val providerId: String = "current",
    val providers: List<ChatModelProviderUi> = emptyList(),
)

@Composable
internal fun ChatModelPicker(
    state: ChatModelPickerState,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    onSelectOtherProvider: (String, String) -> Unit = { _, _ -> },
) {
    var query by remember { mutableStateOf("") }
    var searchFocused by remember { mutableStateOf(false) }
    var expanded by remember(state.providerId) { mutableStateOf<String?>(state.providerId) }
    var searchCollapsed by remember(query) { mutableStateOf(emptySet<String>()) }
    val colors = usagePalette()
    val search = query.trim()
    val groups = remember(state.providers, state.models, state.providerId, search) {
        state.providers.ifEmpty {
            listOf(ChatModelProviderUi(state.providerId, "当前供应商", state.models))
        }.mapNotNull { provider ->
            val models = provider.models.filter {
                provider.name.contains(search, true) || it.id.contains(search, true) || it.displayName.contains(search, true)
            }
            if (models.isEmpty()) null else provider.copy(models = models)
        }
    }
    val canSelect = state.enabled && !state.loading
    val status = when {
        state.loading -> "正在读取模型目录…"
        state.message != null -> state.message
        !state.enabled -> "当前任务结束后可切换模型"
        else -> null
    }
    ChatSelectionSheet(height = 500.dp, title = "选择模型", showCloseButton = false, onDismiss = onDismiss) {
        Column(Modifier.testTag("chat-model-picker").semantics {
            stateDescription = if (state.loading) "正在读取模型目录" else "模型目录读取完成"
        }.fillMaxSize()) {
            BasicTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                textStyle = TextStyle(color = colors.text, fontSize = 11.sp, lineHeight = 18.15.sp),
                cursorBrush = SolidColor(colors.accent),
                modifier = Modifier.testTag("chat-model-search").fillMaxWidth()
                    .semantics { contentDescription = "搜索模型或供应商" }
                    .onFocusChanged { searchFocused = it.isFocused }
                    .heightIn(min = 36.dp).clip(RoundedCornerShape(12.dp)).background(colors.background)
                    .border(1.dp, if (searchFocused) colors.accent else colors.line, RoundedCornerShape(12.dp))
                    .padding(horizontal = 10.dp, vertical = 9.dp),
                decorationBox = { input ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(DaiyuIcons.Search, null, Modifier.size(14.dp), tint = colors.muted)
                        Box(Modifier.weight(1f)) {
                            if (query.isEmpty()) Text("搜索模型 / 供应商", color = colors.muted, fontSize = 11.sp)
                            input()
                        }
                    }
                },
            )
            Spacer(Modifier.height(13.dp))
            if (status != null) Text(status, color = colors.muted, fontSize = 10.sp,
                modifier = Modifier.fillMaxWidth().padding(bottom = 7.dp))
            LazyColumn(Modifier.testTag("chat-model-list").fillMaxWidth().weight(1f).padding(end = 2.dp)) {
                groups.forEach { provider ->
                    val open = if (search.isNotEmpty()) provider.id !in searchCollapsed else expanded == provider.id
                    val surface = Color.White.copy(alpha = if (colors.dark) .012f else .14f)
                    item(key = "heading:${provider.id}") {
                        Row(Modifier.testTag("chat-model-provider:${provider.id}").fillMaxWidth()
                            .modelGroupSurface(top = true, bottom = !open, surface = surface, line = colors.line)
                            .clickable(role = Role.Button, interactionSource = remember { MutableInteractionSource() }, indication = null) {
                                if (search.isNotEmpty()) searchCollapsed = if (open) searchCollapsed + provider.id else searchCollapsed - provider.id
                                else expanded = if (open) null else provider.id
                            }
                            .semantics { heading(); stateDescription = if (open) "已展开" else "已折叠" }
                            .padding(start = 11.dp, end = 11.dp, top = 12.dp, bottom = if (open) 9.dp else 13.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(provider.name, color = colors.toolbar, fontSize = 10.sp, modifier = Modifier.weight(1f),
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${provider.models.size}", color = colors.muted, fontSize = 9.sp,
                                modifier = Modifier.testTag("chat-model-count:${provider.id}"))
                            provider.balance?.let { balance ->
                                Icon(DaiyuIcons.Balance, null, Modifier.size(11.dp), tint = colors.muted)
                                Text(balance, color = colors.muted, fontSize = 9.sp,
                                    modifier = Modifier.widthIn(max = 80.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Icon(DaiyuIcons.ChevronDown, null, Modifier.size(10.dp).rotate(if (open) 0f else -90f), tint = colors.muted)
                        }
                    }
                    if (open) itemsIndexed(provider.models, key = { _, model -> "model:${provider.id}:${model.id}" }) { index, model ->
                        val selected = provider.id == state.providerId && model.id == state.current
                        val tag = if (provider.id == state.providerId) "chat-model-option:${model.id}"
                            else "chat-model-option:${provider.id}:${model.id}"
                        Box(Modifier.fillMaxWidth()
                            .modelGroupSurface(top = false, bottom = index == provider.models.lastIndex, surface = surface, line = colors.line)
                            .padding(start = 4.dp, end = 4.dp, bottom = if (index == provider.models.lastIndex) 4.dp else 2.dp)) {
                            Row(Modifier.testTag(tag).fillMaxWidth().alpha(if (canSelect) 1f else .45f)
                                .clip(RoundedCornerShape(10.dp)).background(if (selected) colors.bubble else Color.Transparent)
                                .selectable(selected = selected, enabled = canSelect, role = Role.RadioButton,
                                    interactionSource = remember { MutableInteractionSource() }, indication = null,
                                    onClick = {
                                        if (provider.id == state.providerId) onSelect(model.id)
                                        else onSelectOtherProvider(provider.id, model.id)
                                    })
                                .heightIn(min = 44.dp).padding(horizontal = 9.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                                ModelBrandIcon(model.id, Modifier.size(17.dp), displayName = model.displayName, tint = colors.accent)
                                Column(Modifier.weight(1f)) {
                                    Text(model.displayName, color = if (selected) colors.accent else colors.text,
                                        fontSize = 11.sp, lineHeight = 16.5.sp,
                                        fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    if (model.displayName != model.id) Text(model.id, color = colors.muted,
                                        fontSize = 9.sp, lineHeight = 13.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(top = 1.dp))
                                }
                                Box(Modifier.size(17.dp).clip(CircleShape)
                                    .background(if (selected) colors.accent else Color.Transparent), contentAlignment = Alignment.Center) {
                                    if (selected) Icon(DaiyuIcons.Check, "当前模型", Modifier.size(10.dp),
                                        tint = if (colors.dark) Color(0xFF211735) else Color.White)
                                }
                            }
                        }
                    }
                    item(key = "gap:${provider.id}") { Spacer(Modifier.height(9.dp)) }
                }
                if (groups.isEmpty() && search.isNotEmpty() && !state.loading) item(key = "empty") {
                    Text("没有找到匹配的模型", color = colors.muted, fontSize = 11.sp,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp))
                }
            }
        }
    }
}

// Draw a continuous card across lazy rows without composing an entire large catalog at once.
private fun Modifier.modelGroupSurface(top: Boolean, bottom: Boolean, surface: Color, line: Color) = drawBehind {
    val inset = .5.dp.toPx()
    val left = inset; val right = size.width - inset
    val start = if (top) inset else 0f; val end = if (bottom) size.height - inset else size.height
    val radius = 14.dp.toPx().coerceAtMost((end - start) / 2)
    val path = Path().apply {
        moveTo(left, if (top) start + radius else start)
        if (top) { quadraticBezierTo(left, start, left + radius, start); lineTo(right - radius, start)
            quadraticBezierTo(right, start, right, start + radius) } else lineTo(right, start)
        lineTo(right, if (bottom) end - radius else end)
        if (bottom) { quadraticBezierTo(right, end, right - radius, end); lineTo(left + radius, end)
            quadraticBezierTo(left, end, left, end - radius) } else lineTo(left, end)
        close()
    }
    drawPath(path, surface)
    val edge = Path().apply {
        moveTo(left, if (bottom) end - radius else end)
        lineTo(left, if (top) start + radius else start)
        if (top) { quadraticBezierTo(left, start, left + radius, start); lineTo(right - radius, start)
            quadraticBezierTo(right, start, right, start + radius) } else moveTo(right, start)
        lineTo(right, if (bottom) end - radius else end)
        if (bottom) { quadraticBezierTo(right, end, right - radius, end); lineTo(left + radius, end)
            quadraticBezierTo(left, end, left, end - radius) }
    }
    drawPath(edge, line, style = Stroke(1.dp.toPx()))
}
