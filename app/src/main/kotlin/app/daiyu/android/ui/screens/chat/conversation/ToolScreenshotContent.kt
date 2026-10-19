package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.daiyu.android.ChatController
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.window.WindowDialog

/** Reads the already committed screenshot; never captures a new screen. */
@Composable
internal fun ToolScreenshotContent(controller: ChatController, step: WorkStepUi) {
    val owner = step.owner ?: return
    var image by remember(owner, step.toolCallId) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var expanded by remember(owner, step.toolCallId) { mutableStateOf(false) }
    DisposableEffect(controller, owner, step.toolCallId, step.state) {
        var visible = true
        image = null
        controller.loadToolScreenshot(owner, step.toolCallId) { if (visible) image = it }
        onDispose { visible = false }
    }
    image?.let { bitmap ->
        Column(Modifier.fillMaxWidth().testTag("tool-screenshot:${step.toolCallId}")) {
            Text("已保存的屏幕截图 · 历史画面，不授予操作权")
            Image(bitmap.asImageBitmap(), "该调用已确认的屏幕截图", Modifier.fillMaxWidth().heightIn(max = 240.dp)
                .clickable(role = Role.Button, onClickLabel = "放大已保存截图") { expanded = true }, contentScale = ContentScale.Fit)
            if (expanded) WindowDialog(show = true, title = "已保存截图", onDismissRequest = { expanded = false }) {
                Image(bitmap.asImageBitmap(), "放大已保存截图", Modifier.fillMaxWidth().heightIn(max = 520.dp), contentScale = ContentScale.Fit)
                TextButton(text = "关闭", onClick = { expanded = false })
            }
        }
    }
}
