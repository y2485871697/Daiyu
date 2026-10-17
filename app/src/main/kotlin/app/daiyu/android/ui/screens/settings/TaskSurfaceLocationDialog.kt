package app.daiyu.android.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import app.daiyu.android.tasksurface.TaskSurface
import app.daiyu.android.tasksurface.TaskSurfaceRouteAdapter
import app.daiyu.android.tasksurface.TaskSurfaceRunDecision

/** Optional parent-owned location dialog. Merely composing it never asks/allocates anything:
 * the parent must explicitly obtain the exact one-shot question from adapter.askFromUser.
 * Not an ask_user renderer and not an R08 whole-plan approval. This host is not yet mounted
 * by MainActivity; the parent must retain the SAME decision on the original run. */
@Composable
internal fun TaskSurfaceLocationDialog(
    adapter: TaskSurfaceRouteAdapter,
    request: TaskSurfaceRouteAdapter.Request,
    question: TaskSurfaceRunDecision.LocationQuestion,
    onResolved: (TaskSurface?) -> Unit,
) {
    val visit = remember(adapter, question) { TaskSurfaceLocationVisit() }
    val currentResolved = rememberUpdatedState(onResolved)
    fun answer(value: TaskSurface?) {
        if (!visit.active || visit.answered) return
        visit.answered = true
        val accepted = adapter.answerFromUser(request, question, value)
        currentResolved.value(if (accepted) value else null)
    }
    DisposableEffect(adapter, question) {
        onDispose {
            visit.active = false
            // Activity/owner/dialog loss is cancellation of THIS run, not permission to ask again.
            if (!visit.answered) adapter.answerFromUser(request, question, null)
        }
    }
    AlertDialog(onDismissRequest = { answer(null) },
        modifier = Modifier.testTag("task-surface-location-dialog"),
        title = { Text("选择本次运行的执行位置") },
        text = { Text("只选择位置，不授予屏幕读取或动作权限，也不批准后台整计划。后台不支持时本次不执行，不回退前台。请不要清理应用。取消后本运行不会再次询问。") },
        confirmButton = {
            Column {
                TextButton(onClick = { answer(TaskSurface.FOREGROUND) }, modifier = Modifier.testTag("task-surface-location-foreground")) { Text("本次使用前台") }
                TextButton(onClick = { answer(TaskSurface.BACKGROUND) }, modifier = Modifier.testTag("task-surface-location-background")) { Text("本次使用后台") }
            }
        },
        dismissButton = {
            TextButton(onClick = { answer(null) }, modifier = Modifier.testTag("task-surface-location-cancel")) { Text("取消本次位置选择") }
        })
}

private class TaskSurfaceLocationVisit { var active = true; var answered = false }
