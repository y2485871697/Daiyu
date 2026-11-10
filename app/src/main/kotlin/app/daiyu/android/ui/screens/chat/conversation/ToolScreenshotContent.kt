package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.daiyu.android.ChatController
import top.yukonga.miuix.kmp.basic.Text
import app.daiyu.android.ui.screens.chat.conversation.ConversationTextButton as TextButton
import top.yukonga.miuix.kmp.window.WindowDialog

/** Reads the already committed screenshot; never captures a new screen. */
@Composable
internal fun ToolScreenshotContent(controller: ChatController, step: WorkStepUi) {
    val owner = step.owner ?: return
    if (!controller.ready || controller.dataBusy) return
    val dataRevision = controller.dataRevision
    val screenshotRevision = controller.screenScreenshotRevision
    val initial = remember(controller, owner, step.toolCallId, dataRevision, screenshotRevision) {
        controller.cachedToolScreenshot(owner, step.toolCallId)
    }
    var image by remember(controller, owner, step.toolCallId, dataRevision, screenshotRevision) {
        mutableStateOf(initial?.image)
    }
    var expanded by remember(controller, owner, step.toolCallId, dataRevision, screenshotRevision) { mutableStateOf(false) }
    var cleared by remember(controller, owner, step.toolCallId, dataRevision, screenshotRevision) { mutableStateOf(initial?.cleared == true) }
    var loading by remember(controller, owner, step.toolCallId, dataRevision, screenshotRevision) { mutableStateOf(initial == null) }
    var revision by remember(owner, step.toolCallId) { mutableStateOf(0) }
    DisposableEffect(controller, owner, step.toolCallId, step.state, controller.ready, controller.dataBusy,
        dataRevision, screenshotRevision, revision) {
        var visible = true
        val cached = controller.cachedToolScreenshot(owner, step.toolCallId)
        image = cached?.image; loading = cached == null; cleared = cached?.cleared == true
        if (cached == null && !controller.loadToolScreenshot(owner, step.toolCallId) { bitmap, cleaned ->
            if (visible) { image = bitmap; loading = false; cleared = cleaned; if (cleaned) expanded = false }
        }) loading = false
        onDispose { visible = false }
    }
    if (image == null) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(if (cleared) "临时截图已清理" else if (loading) "正在加载…" else "读取失败", color = conversationColors().muted,
            style = MiuixTheme.textStyles.footnote1.copy(fontSize = 14.sp, lineHeight = 21.sp))
        if (!loading && !cleared) TextButton(text = "重新读取", modifier = Modifier.widthIn(min = 48.dp).heightIn(min = 48.dp), onClick = { revision++ })
    }
    image?.let { bitmap ->
        Column(Modifier.fillMaxWidth().testTag("tool-screenshot:${step.toolCallId}")) {
            Text("屏幕截图", color = conversationColors().muted,
                style = MiuixTheme.textStyles.footnote1.copy(fontSize = 12.sp, lineHeight = 18.sp))
            val shape = RoundedCornerShape(8.dp)
            Image(bitmap.asImageBitmap(), "该调用已确认的屏幕截图", Modifier.fillMaxWidth().heightIn(min = 48.dp, max = 140.dp)
                .clip(shape).border(1.dp, conversationColors().line, shape)
                .conversationClickable(onClickLabel = "放大已保存截图") { expanded = true }, contentScale = ContentScale.Crop)
            if (expanded) WindowDialog(show = true, title = "已保存截图", onDismissRequest = { expanded = false }) {
                app.daiyu.android.diagnostics.ObserveDialogFrames("dialog")
                Image(bitmap.asImageBitmap(), "放大已保存截图", Modifier.fillMaxWidth().heightIn(max = 520.dp), contentScale = ContentScale.Fit)
                TextButton(text = "关闭", modifier = Modifier.widthIn(min = 48.dp).heightIn(min = 48.dp), onClick = { expanded = false })
            }
        }
    }
}
