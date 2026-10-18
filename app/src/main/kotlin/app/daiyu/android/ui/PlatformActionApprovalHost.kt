package app.daiyu.android.ui

import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.daiyu.android.ChatController
import app.daiyu.android.ControllerPlatformActionPreparation
import app.daiyu.android.domain.Conversation
import app.daiyu.android.platform.actions.PlatformActionKind
import app.daiyu.android.tasksurface.*
import app.daiyu.android.ui.screens.settings.TaskSurfaceLocationDialog
import org.json.JSONObject

/** Dedicated, opt-in composer adjunct. Settings/capability switches never issue a WRITE grant. */
@Composable internal fun PlatformActionApprovalHost(
    controller: ChatController,
    conversation: Conversation?, text: String, owner: ChatController.TextSendOwner, visit: Any,
    enabled: Boolean,
    isCurrent: (ChatController.TextSendOwner, String) -> Boolean,
    onAccepted: (ControllerPlatformActionPreparation, Conversation) -> Unit,
    showEntry: Boolean = true,
) {
    var open by remember(owner, visit) { mutableStateOf(false) }
    var kind by remember(owner, visit) { mutableStateOf<PlatformActionKind?>(null) }
    var first by remember(owner, visit) { mutableStateOf("") }
    var second by remember(owner, visit) { mutableStateOf("") }
    var label by remember(owner, visit) { mutableStateOf("") }
    var stream by remember(owner, visit) { mutableStateOf("music") }
    var media by remember(owner, visit) { mutableStateOf("pause") }
    var wifi by remember(owner, visit) { mutableStateOf(false) }
    var preview by remember(owner, visit) { mutableStateOf<ControllerPlatformActionPreparation?>(null) }
    var error by remember(owner, visit) { mutableStateOf<String?>(null) }
    var sending by remember(owner, visit) { mutableStateOf(false) }
    var locationState by remember(owner, visit) { mutableStateOf<TaskSurfaceDecision?>(null) }
    var locationQuestion by remember(owner, visit) { mutableStateOf<TaskSurfaceRunDecision.LocationQuestion?>(null) }
    val active = remember(owner, visit) { java.util.concurrent.atomic.AtomicBoolean(true) }
    val currentState by rememberUpdatedState(isCurrent)
    fun current(expected: ChatController.TextSendOwner, value: String) = active.get() && currentState(expected, value)
    val accepted by rememberUpdatedState(onAccepted)
    DisposableEffect(controller, owner, visit) {
        onDispose { active.set(false); preview?.let(controller::rejectPlatformAction) }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (showEntry) TextButton(enabled = enabled && preview == null, onClick = { open = !open },
            modifier = Modifier.testTag("platform-action-entry")) {
            Text("专用系统操作：闹钟 / 计时器 / 音量 / 媒体 / 联网（单次批准）")
        }
        if (open) {
            Text("仅本次精确动作，普通应用身份；无自动接管、Root、Shizuku 或 GUI 回退。停止不会撤销已提交效果。闹钟/计时器仅应用前台可见时提交，系统时钟可能显示界面，不自动返回或暗切主屏。")
            PlatformActionKind.entries.forEach { option ->
                TextButton(enabled = enabled && preview == null, onClick = {
                    kind = option; first = ""; second = ""; error = null
                }, modifier = Modifier.testTag("platform-action-${option.name.lowercase()}")) {
                    Text(if (kind == option) "已选择：${option.title}" else option.title)
                }
            }
            val selected = kind
            val editable = enabled && preview == null
            when (selected) {
                PlatformActionKind.ALARM -> {
                    OutlinedTextField(first, { first = it }, enabled = editable, label = { Text("小时 0–23（本地时间）") })
                    OutlinedTextField(second, { second = it }, enabled = editable, label = { Text("分钟 0–59") })
                    OutlinedTextField(label, { label = it }, enabled = editable, label = { Text("闹钟标签") })
                    Text("交给系统默认时钟处理；下一次该时间，无重复日期。提交不等于已设定。")
                }
                PlatformActionKind.TIMER -> {
                    OutlinedTextField(first, { first = it }, enabled = editable, label = { Text("计时秒数 1–86400") })
                    OutlinedTextField(label, { label = it }, enabled = editable, label = { Text("计时器标签") })
                }
                PlatformActionKind.VOLUME -> {
                    Row { listOf("music" to "媒体", "alarm" to "闹钟").forEach { (value, title) ->
                        TextButton(enabled = editable, onClick = { stream = value }) { Text(if (stream == value) "已选$title" else title) }
                    } }
                    OutlinedTextField(first, { first = it }, enabled = editable, label = { Text("绝对音量级数（非百分比；须在设备实际范围内）") })
                    Text("只有读回实际级数与批准值相同才确认；不申请勿扰访问，不调整铃声策略。")
                }
                PlatformActionKind.MEDIA -> {
                    listOf("play" to "播放", "pause" to "暂停", "stop" to "停止", "next" to "下一首", "previous" to "上一首").forEach { (value, title) ->
                        TextButton(enabled = editable, onClick = { media = value }) { Text(if (media == value) "已选$title" else title) }
                    }
                    Text("发送给系统当前媒体会话，非指定应用；按键提交结果一律 UNKNOWN，不声称已播放或暂停。")
                }
                PlatformActionKind.WIFI -> {
                    if (Build.VERSION.SDK_INT >= 29) Text("当前 Android 10+ 普通应用不能切换 Wi-Fi；本入口不执行，不能批准，不回退 shell/系统设置。移动数据、飞行模式和蓝牙切换也未实现。")
                    else Row { Switch(checked = wifi, onCheckedChange = { wifi = it }, enabled = editable); Text(if (wifi) "开启 Wi-Fi" else "关闭 Wi-Fi") }
                }
                null -> Unit
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("platform-action-preview-error")) }
            Button(enabled = editable && selected != null && text.isNotBlank() &&
                !(selected == PlatformActionKind.WIFI && Build.VERSION.SDK_INT >= 29),
                modifier = Modifier.testTag("platform-action-preview"), onClick = {
                    if (!current(owner, text)) { error = "原会话或草稿已变化，未准备"; return@Button }
                    val arguments = runCatching {
                        val json = JSONObject()
                        when (checkNotNull(selected)) {
                            PlatformActionKind.ALARM -> json.put("hour", first.toLong()).put("minute", second.toLong()).put("label", label)
                            PlatformActionKind.TIMER -> json.put("seconds", first.toLong()).put("label", label)
                            PlatformActionKind.VOLUME -> json.put("stream", stream).put("index", first.toLong())
                            PlatformActionKind.MEDIA -> json.put("key", media)
                            PlatformActionKind.WIFI -> json.put("enabled", wifi)
                        }.toString()
                    }.getOrNull()
                    sending = false
                    preview = arguments?.let { controller.preparePlatformAction(conversation, text, checkNotNull(selected), it) }
                    error = if (preview == null) "参数、系统能力或运行状态不允许；未执行。请检查精确值。" else null
                    locationState = null; locationQuestion = null
                    preview?.takeIf { it.requiresLocation }?.let { frozen ->
                        if (!controller.preparePlatformActionLocation(frozen) { route ->
                            if (preview !== frozen || !current(owner, text)) controller.rejectPlatformAction(frozen)
                            else {
                                locationState = route
                                if (route === TaskSurfaceDecision.AskRequired) {
                                    val location = frozen.location
                                    locationQuestion = location?.let { it.adapter?.askFromUser(it.request) }
                                }
                            }
                        }) locationState = TaskSurfaceDecision.NotExecuted("CLOCK_LOCATION_UNAVAILABLE", "位置准备不可用；未执行")
                    }
                }) { Text("预览本次精确动作") }
        }
    }
    preview?.let { frozen ->
        val location = frozen.location
        val question = locationQuestion
        val adapter = location?.adapter
        if (question != null && location != null && adapter != null) {
            TaskSurfaceLocationDialog(adapter, location.request, question, onResolved = {
                locationQuestion = null
                locationState = location.decision()
            })
        } else AlertDialog(onDismissRequest = { controller.rejectPlatformAction(frozen); preview = null },
            title = { Text("确认单次系统操作") },
            text = { Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                Text("会话 ${frozen.conversationId.value}\n运行 ${frozen.runId.value}\n${frozen.action.preview}")
                Text("以下为完整发送内容。仅此动作可获写授权；更换参数会被拒绝。Intent/媒体键不能验证效果，UNKNOWN 将保留原运行且禁止自动重试。")
                Text(frozen.messageText)
                if (frozen.requiresLocation) {
                    val state = locationState
                    if (state == null) Text("正在冻结原运行的执行位置；尚未批准动作。")
                    else if (state is TaskSurfaceDecision.NotExecuted) Text("${state.code}：${state.message}")
                    else if (state is TaskSurfaceDecision.Ready) Text("本次位置：前台。位置选择不授予动作权限；仍须下方单次批准。")
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            } },
            confirmButton = {
                val confirmEnabled = !sending && !frozen.submitted &&
                    (!frozen.requiresLocation || (locationState is TaskSurfaceDecision.Ready &&
                        location?.ready(TaskSurface.FOREGROUND) != null))
                val approve: () -> Unit = {
                    sending = true
                    if (!current(owner, text)) {
                        controller.rejectPlatformAction(frozen); preview = null; error = "草稿或页面已变化，未发送"
                    } else if (!controller.confirmPlatformAction(frozen) { saved ->
                        if (saved != null && current(owner, text) && saved.id == frozen.conversationId) {
                            preview = null // Ownership transfers to RunResources; disposal must not stop acceptance.
                            accepted(frozen, saved)
                        } else {
                            controller.rejectPlatformAction(frozen); preview = null
                            error = "未受理或原编辑器已变化；未清除新草稿，未自动重发"
                        }
                    }) error = "批准已失效；未重复发送"
                }
                Button(enabled = confirmEnabled,
                    modifier = Modifier.testTag("platform-action-confirm").semantics {
                        // Keep the UI callback independent of ClickableNode attachment.
                        onClick {
                            if (confirmEnabled) { approve(); true } else false
                        }
                    }, onClick = approve) { Text("批准精确动作并发送") }
            },
            dismissButton = { TextButton(onClick = { controller.rejectPlatformAction(frozen); preview = null }) { Text("拒绝 / 停止原准备") } })
    }
}
