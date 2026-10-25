package app.daiyu.android.ui.screens.chat.conversation

import app.daiyu.android.ui.components.imeCursorBrush
import app.daiyu.android.ui.components.managedInput

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import app.daiyu.android.ui.haptics.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import app.daiyu.android.ui.haptics.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.daiyu.android.ui.icons.modelNameOpticalAlignment
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
import app.daiyu.android.balance.BalanceState
import app.daiyu.android.balance.ui.ProviderBalanceIndicator
import app.daiyu.android.ui.components.usagePalette
import app.daiyu.android.ui.icons.ModelBrandIcon
import app.daiyu.android.ui.icons.DaiyuIcons
import app.daiyu.android.ui.screens.settings.ModelCatalogEntryUi
import top.yukonga.miuix.kmp.basic.Text

internal data class ChatModelProviderUi(
    val id: String,
    val name: String,
    val models: List<ModelCatalogEntryUi>,
    val balance: BalanceState? = null,
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
    title: String = "选择模型",
    modifier: Modifier = Modifier,
    showStatusMessages: Boolean = true,
    onClearSelection: (() -> Unit)? = null,
) {
    var query by remember { mutableStateOf("") }
    var searchFocused by remember { mutableStateOf(false) }
    var expanded by remember(state.providerId) { mutableStateOf<String?>(state.providerId) }
    var searchCollapsed by remember(query) { mutableStateOf(emptySet<String>()) }
    val colors = usagePalette()
    val selectedBackground = if (colors.dark) colors.bubble else Color(0xFFEFEDFB)
    val search = query.trim()
    val listState = rememberLazyListState()
    LaunchedEffect(search) { listState.scrollToItem(0) }
    val groups = remember(state.providers, state.models, state.providerId, search) {
        state.providers.ifEmpty {
            listOf(ChatModelProviderUi(state.providerId, "当前供应商", state.models))
        }.mapNotNull { provider ->
            val models = provider.models.filter {
                provider.name.contains(search, true) || it.apiModelId.contains(search, true) || it.displayName.contains(search, true)
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
    ChatSelectionSheet(height = 500.dp, title = title, showCloseButton = false, onDismiss = onDismiss) {
        Box(modifier.fillMaxSize()) {
            Column(Modifier.testTag("chat-model-picker").semantics {
                stateDescription = if (state.loading) "正在读取模型目录" else "模型目录读取完成"
            }.fillMaxSize()) {
                BasicTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    textStyle = TextStyle(color = colors.text, fontSize = 14.sp, lineHeight = 22.sp),
                    cursorBrush = imeCursorBrush(colors.accent),
                    modifier = Modifier.managedInput().testTag("chat-model-search").fillMaxWidth()
                        .semantics { contentDescription = "搜索模型或供应商" }
                        .onFocusChanged { searchFocused = it.isFocused }
                        .heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp)).background(colors.background)
                        .border(1.dp, if (searchFocused) colors.accent else colors.line, RoundedCornerShape(14.dp)),
                    decorationBox = { input ->
                        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(DaiyuIcons.Search, null, Modifier.size(20.dp), tint = colors.muted)
                            Box(Modifier.weight(1f)) {
                                if (query.isEmpty()) Text("搜索模型 / 供应商", color = colors.muted, fontSize = 14.sp, lineHeight = 22.sp)
                                input()
                            }
                        }
                    })
                Spacer(Modifier.height(16.dp))
                if (showStatusMessages && status != null) Text(status, color = colors.muted, fontSize = 13.sp, lineHeight = 20.sp,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp))
                LazyColumn(Modifier.testTag("chat-model-list").fillMaxWidth().weight(1f).padding(end = 2.dp),
                    state = listState) {
                    if (onClearSelection != null && search.isEmpty()) item(key = "clear-selection") {
                        Row(Modifier.testTag("chat-model-clear").fillMaxWidth().heightIn(min = 48.dp)
                            .clickable(enabled = canSelect, role = Role.Button,
                                interactionSource = remember { MutableInteractionSource() }, indication = null,
                                onClick = onClearSelection).padding(horizontal = 14.dp, vertical = 12.dp)) {
                            Text("无", color = colors.muted, fontSize = 14.sp, lineHeight = 22.sp)
                        }
                    }
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
                                .heightIn(min = 56.dp).padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(provider.name, color = colors.toolbar, fontSize = 14.sp, lineHeight = 20.sp,
                                        fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.testTag("chat-model-provider-name:${provider.id}"))
                                    Text("${provider.models.size} 个模型", color = colors.muted, fontSize = 12.sp,
                                        lineHeight = 18.sp, maxLines = 1,
                                        modifier = Modifier.testTag("chat-model-count:${provider.id}"))
                                }
                                modelPickerProviderBalance(provider)?.let { balance ->
                                    ProviderBalanceIndicator(state = balance,
                                        modifier = Modifier.testTag("chat-model-provider-balance:${provider.id}"),
                                        textStyle = TextStyle(fontSize = 12.sp, lineHeight = 18.sp))
                                }
                                Icon(DaiyuIcons.ChevronDown, null, Modifier.testTag("chat-model-provider-expand:${provider.id}").size(18.dp).rotate(if (open) 0f else -90f), tint = colors.muted)
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
                                    .clip(RoundedCornerShape(12.dp)).background(if (selected) selectedBackground else Color.Transparent)
                                    .selectable(selected = selected, enabled = canSelect, role = Role.RadioButton,
                                        interactionSource = remember { MutableInteractionSource() }, indication = null,
                                        onClick = {
                                            if (provider.id == state.providerId) onSelect(model.id)
                                            else onSelectOtherProvider(provider.id, model.id)
                                        })
                                    .heightIn(min = 56.dp).padding(horizontal = 12.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    ModelBrandIcon(model.apiModelId, Modifier.size(22.dp).testTag("chat-model-brand:${provider.id}:${model.id}"), displayName = model.displayName, tint = colors.accent)
                                    Column(Modifier.weight(1f).modelNameOpticalAlignment()) {
                                        Text(model.displayName, color = if (selected) colors.accent else colors.text,
                                            fontSize = 16.sp, lineHeight = 24.sp,
                                            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.testTag("chat-model-name:${provider.id}:${model.id}"))
                                        if (model.displayName != model.apiModelId) Text(model.apiModelId, color = colors.muted,
                                            fontSize = 12.sp, lineHeight = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.padding(top = 3.dp).testTag("chat-model-id:${provider.id}:${model.id}"))
                                    }
                                    Box(Modifier.size(22.dp).testTag("chat-model-indicator:${provider.id}:${model.id}").clip(CircleShape)
                                        .background(if (selected) colors.accent else Color.Transparent), contentAlignment = Alignment.Center) {
                                        if (selected) Icon(DaiyuIcons.Check, "当前模型", Modifier.size(14.dp),
                                            tint = if (colors.dark) Color(0xFF211735) else Color.White)
                                    }
                                }
                            }
                        }
                        item(key = "gap:${provider.id}") { Spacer(Modifier.height(12.dp)) }
                    }
                    if (groups.isEmpty() && search.isNotEmpty() && !state.loading) item(key = "empty") {
                        Text("没有找到匹配的模型", color = colors.muted, fontSize = 14.sp, lineHeight = 22.sp,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp))
                    }
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
