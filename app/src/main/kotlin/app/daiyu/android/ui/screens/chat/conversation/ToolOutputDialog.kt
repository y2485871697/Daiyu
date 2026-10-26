package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.daiyu.android.ui.components.PreviewDialog
import app.daiyu.android.ui.icons.DaiyuIcons
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal data class ToolOutputContext(val operation: String, val state: String)
internal val LocalToolOutputContext = staticCompositionLocalOf<ToolOutputContext?> { null }

internal data class ToolOutputColors(val card: Color, val text: Color, val muted: Color, val line: Color, val surface: Color)
internal fun toolOutputColors(dark: Boolean): ToolOutputColors = if (dark) {
    ToolOutputColors(Color(0xFF24212C), Color(0xFFEEE9F6), Color(0xFFB6A9C8), Color(0xFF443B50), Color(0xFF1C1923))
} else {
    ToolOutputColors(Color(0xFFFBF9FE), Color(0xFF302A3D), Color(0xFF81778E), Color(0xFFE6E0ED), Color(0xFFF3F0F8))
}

internal fun toolDocumentTitle(title: String): String = when (title) {
    "标准输出", "已收到输出" -> "输出"
    "标准错误" -> "错误输出"
    "会话输入" -> "输入"
    "文件内容", "读取内容", "资源内容" -> "内容"
    "提示摘要" -> "提示词"
    "文本摘要" -> "文本"
    "差异" -> "改动"
    else -> title
}

@Composable
internal fun ToolOutputDialog(document: ToolDisclosureUi, onDismiss: () -> Unit) {
    val owner = LocalToolOutputContext.current
    val dark = conversationColors().dark
    val colors = toolOutputColors(dark)
    val buttonSurface = if (dark) Color(0xFF292536) else colors.surface
    val buttonText = if (dark) Color(0xFFD5C8F5) else colors.text
    val buttonLine = if (dark) Color(0xFF4B435E) else colors.line
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var copied by remember(document) { mutableStateOf(false) }
    val title = owner?.operation?.let { "$it · 输出" } ?: "完整输出"
    val meta = "${document.text.count { it == '\n' } + 1} 行" + (owner?.state?.let { " · $it" } ?: "")
    PreviewDialog(onDismiss, scrimColor = Color(0xCC16101F), scrimBlurRadius = 4f) {
        val viewportHeight = maxHeight
        val shape = RoundedCornerShape(18.dp)
        Column(Modifier.align(Alignment.Center).width(minOf(maxWidth * .92f, 620.dp, (maxWidth - 24.dp).coerceAtLeast(0.dp)))
            .heightIn(max = viewportHeight * .82f).shadow(18.dp, shape, ambientColor = Color(0x38171126), spotColor = Color(0x38171126)).clip(shape).background(colors.card)
            .border(1.dp, colors.line, shape).pointerInput(Unit) { detectTapGestures { } }.padding(16.dp).testTag("tool-output-dialog"),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, fontSize = 16.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold, color = colors.text)
                Text(meta, Modifier.testTag("tool-output-meta"), fontSize = 12.sp, lineHeight = 18.sp, color = colors.muted)
            }
            val contentShape = RoundedCornerShape(10.dp)
            SelectionContainer(Modifier.weight(1f, fill = false)) {
                Box(Modifier.fillMaxWidth().heightIn(max = viewportHeight * .58f).clip(contentShape)
                    .background(colors.surface).border(1.dp, colors.line, contentShape)
                    .testTag("tool-output-content").verticalScroll(rememberScrollState()).padding(12.dp)) {
                    Text(document.text, Modifier.fillMaxWidth().testTag("tool-output-text"),
                        style = MiuixTheme.textStyles.footnote1.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 20.sp),
                        color = colors.text)
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (copied) "已复制" else "", Modifier.weight(1f).testTag("tool-output-copy-status").semantics { liveRegion = LiveRegionMode.Polite },
                    fontSize = 12.sp, lineHeight = 18.sp, color = colors.muted)
                val buttonShape = RoundedCornerShape(10.dp)
                Row(Modifier.heightIn(min = 48.dp).widthIn(min = 48.dp).clip(buttonShape)
                    .background(buttonSurface).border(1.dp, buttonLine, buttonShape).testTag("tool-output-copy")
                    .conversationClickable {
                        runCatching { clipboard.setText(AnnotatedString(document.text)) }
                            .onSuccess { copied = true }
                            .onFailure { android.widget.Toast.makeText(context, "复制失败，可长按选择文本", android.widget.Toast.LENGTH_SHORT).show() }
                    }.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(DaiyuIcons.Copy, null, Modifier.size(16.dp), tint = buttonText)
                    Text("复制全文", fontSize = 13.sp, lineHeight = 20.sp, color = buttonText)
                }
            }
        }
    }
}
