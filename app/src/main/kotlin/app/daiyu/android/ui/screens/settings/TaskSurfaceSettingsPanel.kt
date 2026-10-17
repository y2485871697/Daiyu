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
    val back = { if (visit.active) onBack() }
    BackHandler { back() }
    MiuixScaffoldPage(title = "Agent任务偏好", onBack = back, modifier = modifier) {
        item { SettingsNote("基础执行位置只影响后续运行；每个运行冻结一次决定。位置选择不等于无障碍授权、具体动作确认或后台整计划审批。") }
        item {
            SettingsCard {
                TaskSurfacePreference.entries.forEach { value ->
                    SettingsRadioRow(title = when (value) {
                        TaskSurfacePreference.FOREGROUND -> "前台执行"
                        TaskSurfacePreference.BACKGROUND -> "后台执行"
                        TaskSurfacePreference.ASK -> "每次运行询问"
                    }, selected = draft == value, enabled = editor != null && !busy) {
                        if (visit.active && !busy) {
                            editor?.choose(value); draft = value; failed = false; saved = false
                        }
                    }
                }
            }
        }
        item { SettingsNote("操作本应用时仅当前有效路由使用前台，不改基础偏好、不弹位置询问、不创建副屏，也不解除已取消的询问。后台不受支持时明确不执行，不会回退到前台。请不要清理应用。") }
        if (editor == null) item { SettingsNote("正在读取任务位置设置…") }
        if (editor?.saved === TaskSurfaceSettings.Unavailable) item {
            SettingsNote("无法确认已保存的位置；执行入口将拒绝执行，不会默认为前台。可重新选择并保存。", error = true)
        }
        if (failed) item { SettingsNote("保存失败，未确认新偏好。后续执行将拒绝使用未确认的位置；请重试保存。", error = true) }
        if (saved) item { SettingsNote("任务位置偏好已保存；当前运行的决定保持不变。") }
        item {
            SettingsCard(Modifier.testTag("task-surface-save")) {
                SettingsRow(if (busy) "正在保存…" else "保存任务位置", onClick = {
                    val current = editor
                    if (visit.active && !busy && current != null && draft != null) {
                        busy = true; failed = false; saved = false
                        scope.launch {
                            val success = withContext(Dispatchers.IO) { current.save() }
                            if (visit.active) { busy = false; failed = !success; saved = success }
                        }
                    }
                })
            }
        }
    }
}

private class TaskSurfaceSettingsVisit { var active = true }
