package app.daiyu.android.ui.screens.chat.conversation

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.daiyu.android.MarkdownBlock
import app.daiyu.android.ui.icons.DaiyuIcons
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Icon

@Composable
internal fun MarkdownCodeBlock(block: MarkdownBlock.Code, visibleCharacters: Int, generationComplete: Boolean) {
    val colors = conversationColors()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var copied by remember(block.text) { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    var unlocked by remember { mutableStateOf(false) }
    var pendingDownload by remember { mutableStateOf<String?>(null) }
    val downloader = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val content = pendingDownload
        pendingDownload = null
        if (uri != null && content != null) scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    require(uri.scheme == "content")
                    checkNotNull(context.contentResolver.openOutputStream(uri, "wt")).use {
                        it.write(content.toByteArray(Charsets.UTF_8))
                        it.flush()
                    }
                }
                android.widget.Toast.makeText(context, "代码已保存", android.widget.Toast.LENGTH_SHORT).show()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                android.widget.Toast.makeText(context, "保存失败", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }
    LaunchedEffect(copied) {
        if (copied) { delay(1_400); copied = false }
    }
    val text = if (visibleCharacters >= block.text.length) block.text else {
        val boundaries = remember(block.text) { MarkdownTextBoundaries(block.text) }
        block.text.substring(0, boundaries.end(visibleCharacters))
    }
    val vertical = rememberScrollState()
    val horizontal = rememberScrollState()
    val overflowsVertically = vertical.viewportSize > 0 && vertical.maxValue > 0
    val verticalScrollEnabled = unlocked && overflowsVertically
    val producing = markdownCodeProducing(block, visibleCharacters, generationComplete)
    var sawOutput by remember { mutableStateOf(false) }
    LaunchedEffect(producing) {
        if (producing) {
            sawOutput = true
            snapshotFlow { vertical.maxValue }.collectLatest { bottom -> vertical.scrollTo(bottom) }
        } else if (sawOutput) {
            sawOutput = false
            // The final chunk can add lines and close the fence in the same composition.
            // Let that layout publish its scroll range before showing the last line and returning.
            withFrameNanos { }
            withFrameNanos { }
            vertical.scrollTo(vertical.maxValue)
            withFrameNanos { }
            coroutineScope {
                launch { vertical.animateScrollTo(0, tween(300)) }
                launch { horizontal.animateScrollTo(0, tween(300)) }
            }
        }
    }
    // Consume only the child's leftover motion: scrolling at either edge must not hand off to chat.
    val containedScroll = remember {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset = available
            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity = available
        }
    }
    val maxBodyHeight = (LocalConfiguration.current.screenHeightDp.dp * .25f).coerceIn(128.dp, 220.dp)
    val shape = RoundedCornerShape(8.dp)
    DisableSelection {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp).clip(shape)
                .background(colors.soft).border(1.dp, colors.line, shape).testTag("markdown-code-block")) {
                Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 2.dp).testTag("markdown-code-header"),
                    verticalAlignment = Alignment.CenterVertically) {
                    BasicText(block.language, Modifier.weight(1f).padding(end = 4.dp),
                        style = TextStyle(fontSize = 11.sp, lineHeight = 16.sp, color = colors.muted), maxLines = 1)
                    CodeAction(CodeBlockIcons.Download, "下载代码", "markdown-code-download", enabled = pendingDownload == null) {
                        pendingDownload = block.text
                        try { downloader.launch(markdownCodeFileName(block.language)) }
                        catch (_: Exception) {
                            pendingDownload = null
                            android.widget.Toast.makeText(context, "无法打开保存位置", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                    CodeAction(CodeBlockIcons.Expand, "查看完整代码", "markdown-code-expand") { expanded = true }
                    if (overflowsVertically) CodeAction(CodeBlockIcons.Scroll, if (unlocked) "锁定代码上下滑动" else "解锁代码上下滑动", "markdown-code-unlock",
                        modifier = Modifier.semantics { stateDescription = if (unlocked) "已解锁" else "已锁定" },
                        tint = if (unlocked) colors.accent else colors.muted) { unlocked = !unlocked }
                    CodeAction(if (copied) DaiyuIcons.Check else DaiyuIcons.Copy,
                        if (copied) "已复制代码" else "复制代码", "markdown-code-copy",
                        tint = if (copied) colors.success else colors.muted) {
                        runCatching { clipboard.setText(AnnotatedString(block.text)) }
                            .onSuccess { copied = true }
                            .onFailure { android.widget.Toast.makeText(context, "复制失败", android.widget.Toast.LENGTH_SHORT).show() }
                    }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(colors.line))
                Box(Modifier.fillMaxWidth().heightIn(max = maxBodyHeight).clip(RoundedCornerShape(bottomStart = 8.dp, bottomEnd = 8.dp))
                    .testTag("markdown-code-viewport")
                    .then(if (verticalScrollEnabled) Modifier.nestedScroll(containedScroll) else Modifier)
                    .verticalScroll(vertical, enabled = verticalScrollEnabled).horizontalScroll(horizontal)
                    .padding(12.dp)) {
                    BasicText(text, Modifier.testTag("markdown-code-text"), softWrap = false,
                        style = TextStyle(color = colors.text, fontFamily = FontFamily.Monospace,
                            fontSize = 14.sp, lineHeight = 22.sp, textAlign = TextAlign.Left))
                }
            }
        }
    }
    if (expanded) CompositionLocalProvider(LocalToolOutputContext provides null) {
        ToolOutputDialog(ToolDisclosureUi(block.language, block.text, code = true),
            onDismiss = { expanded = false })
    }
}

@Composable
private fun CodeAction(icon: ImageVector, description: String, tag: String, modifier: Modifier = Modifier,
    tint: Color = conversationColors().muted, enabled: Boolean = true, onClick: () -> Unit,
) {
    Box(modifier.size(36.dp).testTag(tag).conversationClickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center) {
        Icon(icon, description, Modifier.size(16.dp), tint = tint)
    }
}

private object CodeBlockIcons {
    private fun icon(name: String, draw: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, pathBuilder = draw)
        }.build()
    val Download by lazy { icon("Code.Download") {
        moveTo(12f, 3f); lineTo(12f, 15f); moveTo(8f, 11f); lineTo(12f, 15f); lineTo(16f, 11f)
        moveTo(5f, 16f); lineTo(5f, 20f); lineTo(19f, 20f); lineTo(19f, 16f)
    } }
    val Expand by lazy { icon("Code.Expand") {
        moveTo(9f, 4f); lineTo(4f, 4f); lineTo(4f, 9f); moveTo(15f, 4f); lineTo(20f, 4f); lineTo(20f, 9f)
        moveTo(4f, 15f); lineTo(4f, 20f); lineTo(9f, 20f); moveTo(15f, 20f); lineTo(20f, 20f); lineTo(20f, 15f)
    } }
    val Scroll by lazy { icon("Code.Scroll") {
        moveTo(8f, 8f); lineTo(12f, 4f); lineTo(16f, 8f); moveTo(8f, 16f); lineTo(12f, 20f); lineTo(16f, 16f)
    } }
}
