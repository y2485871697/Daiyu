package app.daiyu.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.daiyu.android.ui.haptics.clickable
import app.daiyu.android.ui.screens.chat.conversation.ChatAnchoredMenu
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text

@Stable
internal class ConversationDrawerControlsState(
    filterRunning: Boolean = false,
    filterCompleted: Boolean = false,
    sortByLastOutput: Boolean = false,
    reverse: Boolean = false,
) {
    var filterRunning by mutableStateOf(filterRunning)
    var filterCompleted by mutableStateOf(filterCompleted)
    var sortByLastOutput by mutableStateOf(sortByLastOutput)
    var reverse by mutableStateOf(reverse)

    companion object {
        val Saver = listSaver<ConversationDrawerControlsState, Boolean>(
            save = { listOf(it.filterRunning, it.filterCompleted, it.sortByLastOutput, it.reverse) },
            restore = { ConversationDrawerControlsState(it[0], it[1], it[2], it[3]) },
        )
    }
}

@Composable
internal fun rememberConversationDrawerControlsState(): ConversationDrawerControlsState =
    rememberSaveable(saver = ConversationDrawerControlsState.Saver) { ConversationDrawerControlsState() }

@Composable
internal fun ConversationDrawerControls(
    running: Boolean,
    completed: Boolean,
    sortByLastOutput: Boolean,
    reverse: Boolean,
    onRunningChange: (Boolean) -> Unit,
    onCompletedChange: (Boolean) -> Unit,
    onReset: () -> Unit,
    onSortChange: (Boolean) -> Unit,
    onReverseChange: (Boolean) -> Unit,
    canOpen: () -> Boolean,
) {
    var menu by remember { mutableStateOf<String?>(null) }
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    LaunchedEffect(canOpen) {
        snapshotFlow { canOpen() }.collect { if (!it) menu = null }
    }
    LaunchedEffect(lifecycleState) {
        if (!lifecycleState.isAtLeast(Lifecycle.State.STARTED)) menu = null
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        DrawerControlButton("筛选", DrawerControlIcons.Filter, "filter", running || completed,
            { if (canOpen()) menu = if (menu == "filter") null else "filter" }) {
            DrawerControlsMenu(menu == "filter", { menu = null }, "filter") {
                DrawerControlChoice("正在运行", running, "running", checkbox = true) { onRunningChange(!running) }
                DrawerControlChoice("已完成", completed, "completed", checkbox = true) { onCompletedChange(!completed) }
                DrawerControlDivider()
                val colors = conversationPanePreviewPalette()
                Text("重置", color = colors.danger, fontSize = 13.sp, lineHeight = 20.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().testTag("drawer-filter-reset")
                        .padding(horizontal = 2.dp, vertical = 2.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onReset)
                        .heightIn(min = 40.dp).padding(horizontal = 12.dp, vertical = 10.dp))
            }
        }
        DrawerControlButton("排序", DrawerControlIcons.Sort, "sort", sortByLastOutput || reverse,
            { if (canOpen()) menu = if (menu == "sort") null else "sort" }) {
            DrawerControlsMenu(menu == "sort", { menu = null }, "sort") {
                DrawerControlChoice("按日期", !sortByLastOutput, "date") { onSortChange(false) }
                DrawerControlChoice("按最后输出", sortByLastOutput, "last-output") { onSortChange(true) }
                DrawerControlDivider()
                DrawerControlChoice("正序", !reverse, "forward") { onReverseChange(false) }
                DrawerControlChoice("倒序", reverse, "reverse") { onReverseChange(true) }
            }
        }
    }
}

@Composable
private fun DrawerControlButton(
    title: String,
    icon: ImageVector,
    tag: String,
    active: Boolean,
    onClick: () -> Unit,
    menu: @Composable () -> Unit,
) {
    val colors = conversationPanePreviewPalette()
    val scale = conversationPanePreviewScale()
    Box {
        Row(Modifier.testTag("drawer-$tag-button").clip(RoundedCornerShape(10.dp))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .heightIn(min = 38.dp * scale).padding(horizontal = 6.dp * scale),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp * scale)) {
            val tint = if (active) colors.accent else colors.muted
            Icon(icon, null, Modifier.size(15.dp * scale), tint = tint)
            Text(title, color = tint, fontSize = (13f * scale).sp, lineHeight = (19f * scale).sp, maxLines = 1)
        }
        menu()
    }
}

@Composable
private fun DrawerControlsMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    tag: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    ChatAnchoredMenu(expanded, onDismiss, preferAbove = true,
        offset = DpOffset(0.dp, 6.dp), minWidth = 0.dp) {
        Column(Modifier.width(IntrinsicSize.Max).testTag("drawer-$tag-menu"), content = content)
    }
}

@Composable
private fun DrawerControlChoice(
    title: String,
    selected: Boolean,
    tag: String,
    checkbox: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = usagePalette()
    Row(Modifier.fillMaxWidth().testTag("drawer-choice-$tag")
        .semantics { this.selected = selected; role = if (checkbox) Role.Checkbox else Role.RadioButton }
        .padding(horizontal = 2.dp, vertical = 2.dp)
        .clip(RoundedCornerShape(10.dp))
        .background(if (selected) colors.selectionContainer else Color.Transparent)
        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
        .heightIn(min = 40.dp).padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = if (selected) colors.selectionContent else colors.text,
            fontSize = 13.sp, lineHeight = 20.sp,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().testTag("drawer-label-$tag"))
    }
}

@Composable
private fun DrawerControlDivider() {
    Box(Modifier.padding(horizontal = 6.dp, vertical = 5.dp).fillMaxWidth().height(1.dp)
        .background(usagePalette().line).testTag("drawer-controls-divider"))
}

private object DrawerControlIcons {
    val Filter = icon("filter", "M4 5h16l-6 7v6l-4 2v-8Z")
    val Sort = icon("sort", "M8 4v16m-3-3 3 3 3-3M16 20V4m-3 3 3-3 3 3")
    private fun icon(name: String, path: String) = ImageVector.Builder("drawer-$name", 24.dp, 24.dp, 24f, 24f)
        .addPath(PathParser().parsePathString(path).toNodes(), fill = null, stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.7f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round).build()
}
