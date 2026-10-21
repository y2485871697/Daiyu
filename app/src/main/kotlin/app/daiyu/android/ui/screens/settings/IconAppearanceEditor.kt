package app.daiyu.android.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.daiyu.android.appearance.AppCatIconView
import app.daiyu.android.appearance.IconAppearanceCoordinator
import app.daiyu.android.appearance.iconSyncFailureMessage
import app.daiyu.android.domain.appearance.IconAppearance
import app.daiyu.android.domain.appearance.IconThemeMode
import app.daiyu.android.domain.appearance.Task17IconCatalog
import app.daiyu.android.ui.icons.DaiyuIcon
import app.daiyu.android.ui.icons.DaiyuIcons
import app.daiyu.android.ui.screens.assistants.AssistantPreviewButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val IconDraftSaver = listSaver<IconAppearance, Int>(
    save = { listOf(it.themeMode.ordinal, it.iconLightColor, it.iconDarkColor, it.iconCatColor) },
    restore = { IconAppearance(IconThemeMode.entries[it[0]], it[1], it[2], it[3]) },
)
private class IconEditorVisit { var active = true }

@Composable
internal fun IconAppearanceEditor(
    coordinator: IconAppearanceCoordinator,
    expanded: Boolean,
    ownerCurrent: () -> Boolean = { true },
) {
    val initial = remember(coordinator) { runCatching { coordinator.store.load() } }
    var draft by rememberSaveable(coordinator, stateSaver = IconDraftSaver) {
        mutableStateOf(initial.getOrDefault(IconAppearance()))
    }
    var confirmed by remember(coordinator) { mutableStateOf(initial.getOrNull()) }
    var error by rememberSaveable(coordinator) { mutableStateOf(if (initial.isFailure) "无法读取图标设置，请重新选择并确认。" else null) }
    val visit = remember(coordinator) { IconEditorVisit() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentOwner by rememberUpdatedState(ownerCurrent)
    val currentExpanded by rememberUpdatedState(expanded)
    DisposableEffect(visit) { onDispose { visit.active = false } }
    fun current() = visit.active && currentExpanded && currentOwner() && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
    fun confirm() {
        if (current() && (draft != confirmed || error != null)) {
            val failure = iconSyncFailureMessage(runCatching { coordinator.saveAndApply(draft) })
            error = failure?.let { "未能完成图标应用：$it" }
            if (failure == null) confirmed = draft
        }
    }
    if (!expanded) return

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp)
        .testTag("icon-appearance-editor"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally)) {
            IconPreview("浅色图标预览", "icon-preview:light", draft.iconLightColor, draft.iconCatColor)
            IconPreview("暗色图标预览", "icon-preview:dark", draft.iconDarkColor, draft.iconCatColor)
        }
        IconSwatches("浅色", "light", draft.iconLightColor) { if (current()) draft = draft.copy(iconLightColor = it) }
        IconSwatches("暗色", "dark", draft.iconDarkColor) { if (current()) draft = draft.copy(iconDarkColor = it) }
        IconSwatches("猫咪颜色", "cat", draft.iconCatColor) { if (current()) draft = draft.copy(iconCatColor = it) }
        AssistantPreviewButton("确定", onClick = { confirm() },
            modifier = Modifier.fillMaxWidth().testTag("icon-appearance-confirm").semantics {
                onClick { confirm(); true }
            },
            primary = true, enabled = draft != confirmed || error != null)
        error?.let { Text(it, color = MiuixTheme.colorScheme.error, style = MiuixTheme.textStyles.body2,
            modifier = Modifier.testTag("icon-appearance-error")) }
    }
}

@Composable
private fun IconPreview(description: String, tag: String, background: Int, cat: Int) {
    AndroidView(factory = { AppCatIconView(it) }, modifier = Modifier.size(64.dp).testTag(tag), update = {
        it.contentDescription = description
        it.backgroundColorValue = background
        it.catColor = cat
    })
}

@Composable
private fun IconSwatches(title: String, id: String, selected: Int, onSelect: (Int) -> Unit) {
    val colors = MiuixTheme.colorScheme
    val names = listOf("橙色", "紫色", "粉色", "绿色", "黄色", "蓝色", "白色", "黑色")
    Column {
        Text(title, color = colors.onSurfaceVariantSummary, style = MiuixTheme.textStyles.body2)
        Row(Modifier.fillMaxWidth().selectableGroup()) {
            Task17IconCatalog.swatches.forEachIndexed { index, value ->
                val checked = Task17IconCatalog.nearest(selected) == value
                Box(Modifier.weight(1f).heightIn(min = 40.dp)
                    .selectable(checked, role = Role.RadioButton, onClick = { onSelect(value) })
                    .semantics { contentDescription = "$title：${names[index]}" }
                    .testTag("icon-swatch:$id:$index"), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(if (checked) 26.dp else 22.dp).background(Color(value), CircleShape)
                        .border(if (checked) 2.dp else 1.dp, if (checked) colors.primary else colors.outline, CircleShape),
                        contentAlignment = Alignment.Center) {
                        if (checked) DaiyuIcon(DaiyuIcons.Check, null, size = 13.dp,
                            tint = if (Color(value).luminance() > .5f) Color.Black else Color.White)
                    }
                }
            }
        }
    }
}
