package app.daiyu.android.ui.screens.settings

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import app.daiyu.android.tasksurface.AndroidTaskSurfaceStore
import app.daiyu.android.tasksurface.TaskSurfacePreference
import app.daiyu.android.tasksurface.TaskSurfaceSettings
import app.daiyu.android.tasksurface.TaskSurfaceSettingsEditor
import app.daiyu.android.tasksurface.TaskSurfaceStore
import app.daiyu.android.ui.components.MiuixScaffoldPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun TaskSurfaceSettingsPanel(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    store: TaskSurfaceStore = AndroidTaskSurfaceStore.get(LocalContext.current),
) {
    val visit = remember(store) { TaskSurfaceSettingsVisit() }
    DisposableEffect(visit) { onDispose { visit.active = false } }
    val scope = rememberCoroutineScope()
    var editor by remember(store) { mutableStateOf<TaskSurfaceSettingsEditor?>(null) }
    var draft by remember(store) { mutableStateOf<TaskSurfacePreference?>(null) }
    var busy by remember(store) { mutableStateOf(false) }
    var failed by remember(store) { mutableStateOf(false) }
    var saved by remember(store) { mutableStateOf(false) }
    LaunchedEffect(store) {
        val loaded = withContext(Dispatchers.IO) { TaskSurfaceSettingsEditor(store) }
        if (visit.active) { editor = loaded; draft = loaded.draft }
    }
    fun saveSelection(value: TaskSurfacePreference) {
        val current = editor ?: return
        if (!visit.active || busy) return
        current.choose(value); draft = value
        busy = true; failed = false; saved = false
        scope.launch {
            val success = withContext(Dispatchers.IO) { if (visit.active) current.save() else false }
            if (visit.active) { busy = false; failed = !success; saved = success }
        }
    }
    val back = { if (visit.active) onBack() }
    BackHandler { back() }
    MiuixScaffoldPage(title = "Agent任务偏好", onBack = back, modifier = modifier) {
        item { SettingsNote("仅影响之后开始的任务，当前任务不变。选择执行位置不等于授权；具体操作仍需相应权限和确认。") }
        item {
            SettingsCard {
                TaskSurfacePreference.entries.forEach { value ->
                    SettingsRadioRow(title = when (value) {
                        TaskSurfacePreference.FOREGROUND -> "前台执行"
                        TaskSurfacePreference.BACKGROUND -> "后台执行"
                        TaskSurfacePreference.ASK -> "每次运行询问"
                    }, selected = draft == value, enabled = editor != null && !busy) {
                        if (visit.active && !busy) {
                            saveSelection(value)
                        }
                    }
                }
            }
        }
        item { SettingsNote("后台不可用时不会执行，也不会自动切到前台。操作代鱼自身时使用前台，但不改变这里的设置。") }
        if (editor == null) item { SettingsNote("正在读取任务位置设置…") }
        if (editor?.saved === TaskSurfaceSettings.Unavailable) item {
            SettingsNote("无法确认已保存的位置；执行入口将拒绝执行，不会默认为前台。可重新选择并保存。", error = true)
        }
        if (failed) item { SettingsNote("保存失败，未确认新偏好。后续执行将拒绝使用未确认的位置；请重试保存。", error = true) }
        if (saved) item { SettingsNote("任务位置偏好已保存；当前运行的决定保持不变。") }
        if (busy) item { SettingsNote("正在保存…") }
        if (failed) item {
            SettingsCard(Modifier.testTag("task-surface-save")) {
                SettingsRow("重试保存", onClick = { draft?.let(::saveSelection) })
            }
        }
    }
}

private class TaskSurfaceSettingsVisit { @Volatile var active = true }
