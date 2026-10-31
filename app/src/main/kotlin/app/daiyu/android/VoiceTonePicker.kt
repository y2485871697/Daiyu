package app.daiyu.android

import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.daiyu.android.ui.components.GroupedPickerSearch
import app.daiyu.android.ui.components.pickerGroupSurface
import app.daiyu.android.ui.components.usagePalette
import app.daiyu.android.ui.haptics.clickable
import app.daiyu.android.ui.haptics.selectable
import app.daiyu.android.ui.icons.DaiyuIcons
import app.daiyu.android.ui.screens.chat.conversation.ChatSelectionSheet
import app.daiyu.android.ui.screens.settings.settingsDisclosureRotation
import app.daiyu.android.voice.providers.DoubaoVoiceIdentity
import top.yukonga.miuix.kmp.basic.Text

internal data class VoiceToneOption(
    val id: String,
    val name: String,
    val category: String = voiceToneCategory(id),
    val summary: String? = null,
    val enabled: Boolean = true,
    val tag: String = "voice-tone-option:$id",
)

internal fun voiceToneCategory(id: String): String = when {
    id.startsWith("mimo-local-") || DoubaoVoiceIdentity.isPersonal(id) -> "个人音色"
    id.contains("female", ignoreCase = true) -> "女声"
    id.contains("male", ignoreCase = true) -> "男声"
    else -> "其他音色"
}

@Composable
internal fun VoiceTonePicker(
    title: String,
    selectedId: String,
    options: List<VoiceToneOption>,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    message: String? = null,
    extraContent: @Composable ColumnScope.() -> Unit = {},
) {
    val catalog = remember(options) { options.distinctBy { it.id } }
    val selectedGroup = catalog.firstOrNull { it.id == selectedId }?.category ?: catalog.firstOrNull()?.category
    var query by remember { mutableStateOf("") }
    var expanded by remember(selectedGroup) { mutableStateOf(selectedGroup) }
    var searchCollapsed by remember(query) { mutableStateOf(emptySet<String>()) }
    val search = query.trim()
    val groups = remember(catalog, search) {
        catalog.filter { it.name.contains(search, true) || it.id.contains(search, true) || it.category.contains(search, true) }
            .groupBy { it.category }
            .toSortedMap(compareBy<String> { listOf("女声", "男声", "其他音色", "个人音色").indexOf(it) }.thenBy { it })
    }
    val listState = rememberLazyListState()
    LaunchedEffect(search) { listState.scrollToItem(0) }
    val colors = usagePalette()
    ChatSelectionSheet(title = title, height = 500.dp, onDismiss = onDismiss,
        showCloseButton = false, titleTextAlign = TextAlign.Start) {
        Column(modifier.fillMaxSize()) {
            GroupedPickerSearch(query, { query = it }, "搜索音色", "搜索音色",
                Modifier.testTag("voice-tone-search"))
            Spacer(Modifier.height(16.dp))
            message?.let { Text(it, color = colors.muted, fontSize = 13.sp, lineHeight = 20.sp,
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)) }
            extraContent()
            LazyColumn(Modifier.testTag("voice-tone-list").fillMaxWidth().weight(1f)
                .padding(end = 2.dp).selectableGroup(), state = listState) {
                groups.forEach { (category, rows) ->
                    val open = if (search.isNotEmpty()) category !in searchCollapsed else expanded == category
                    val surface = Color.White.copy(alpha = if (colors.dark) .012f else .14f)
                    item(key = "heading:$category") {
                        Row(Modifier.animateItem(fadeInSpec = null, placementSpec = tween(220), fadeOutSpec = null)
                            .testTag("voice-tone-group:$category").fillMaxWidth()
                            .pickerGroupSurface(top = true, bottom = !open, surface = surface, line = colors.line)
                            .clickable(role = Role.Button, interactionSource = remember { MutableInteractionSource() }, indication = null) {
                                if (search.isNotEmpty()) searchCollapsed = if (open) searchCollapsed + category else searchCollapsed - category
                                else expanded = if (open) null else category
                            }.semantics { heading(); stateDescription = if (open) "已展开" else "已折叠" }
                            .heightIn(min = 56.dp).padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(category, color = colors.toolbar, fontSize = 14.sp, lineHeight = 20.sp,
                                    fontWeight = FontWeight.Medium, textAlign = TextAlign.Start,
                                    modifier = Modifier.fillMaxWidth().testTag("voice-tone-group-name:$category"))
                                Text("${rows.size} 个音色", color = colors.muted, fontSize = 12.sp, lineHeight = 18.sp)
                            }
                            Icon(DaiyuIcons.ChevronRight, null,
                                Modifier.size(18.dp).settingsDisclosureRotation(open), tint = colors.muted)
                        }
                    }
                    if (open) itemsIndexed(rows, key = { _, voice -> "voice:${voice.id}" }) { index, voice ->
                        val selected = voice.id == selectedId
                        Box(Modifier.animateItem(fadeInSpec = tween(220), placementSpec = tween(220), fadeOutSpec = null)
                            .fillMaxWidth().pickerGroupSurface(top = false, bottom = index == rows.lastIndex,
                                surface = surface, line = colors.line)
                            .padding(start = 4.dp, end = 4.dp, bottom = if (index == rows.lastIndex) 4.dp else 2.dp)) {
                            Row(Modifier.testTag(voice.tag).fillMaxWidth().alpha(if (voice.enabled) 1f else .45f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(when { selected -> colors.selectionContainer; colors.dark -> colors.background; else -> Color.Transparent })
                                .selectable(selected = selected, enabled = voice.enabled, role = Role.RadioButton,
                                    interactionSource = remember { MutableInteractionSource() }, indication = null,
                                    onClick = { onSelect(voice.id) })
                                .heightIn(min = 56.dp).padding(horizontal = 12.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Column(Modifier.weight(1f)) {
                                    Text(voice.name, color = if (selected) colors.selectionContent else colors.text,
                                        fontSize = 16.sp, lineHeight = 24.sp, textAlign = TextAlign.Start,
                                        fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    voice.summary?.let { Text(it, color = colors.muted, fontSize = 12.sp, lineHeight = 18.sp,
                                        maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp)) }
                                }
                                Box(Modifier.size(22.dp).testTag("voice-tone-indicator:${voice.id}").clip(CircleShape)
                                    .background(if (selected) colors.accent else Color.Transparent), contentAlignment = Alignment.Center) {
                                    if (selected) Icon(DaiyuIcons.Check, "已选择", Modifier.size(14.dp), tint = colors.onPrimary)
                                }
                            }
                        }
                    }
                    item(key = "gap:$category") {
                        Spacer(Modifier.animateItem(fadeInSpec = null, placementSpec = tween(220), fadeOutSpec = null).height(12.dp))
                    }
                }
                if (groups.isEmpty() && (search.isNotEmpty() || message == null)) item(key = "empty") {
                    Text(if (search.isNotEmpty()) "没有找到匹配的音色" else "暂无可选音色", color = colors.muted, fontSize = 14.sp, lineHeight = 22.sp,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp))
                }
            }
        }
    }
}
