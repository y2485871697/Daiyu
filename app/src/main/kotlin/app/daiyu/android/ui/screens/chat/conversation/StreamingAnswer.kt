package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.sp
import app.daiyu.android.MarkdownText
import me.rerere.bettertext.markdown.Markdown
import me.rerere.bettertext.markdown.MarkdownDocument
import me.rerere.bettertext.markdown.rememberMarkdownDocument
import me.rerere.bettertext.text.BetterSelectionContainer
import top.yukonga.miuix.kmp.theme.MiuixTheme

private class AnswerPaint { var last: MarkdownDocument? = null }

/** Same parser, document publisher and block renderer as the reference app. No local character queue. */
@Composable
internal fun StreamingAnswer(source: String, streaming: Boolean, id: String, reveal: MarkdownReveal, modifier: Modifier) {
    val playback = LocalReplyPlayback.current
    val frozen = playback?.let { it.paused || it.control.stopped } == true
    val receipt = remember(id) { AnswerPaint() }
    val document = rememberMarkdownDocument(if (frozen) receipt.last?.source.orEmpty() else source, streaming = streaming)
    val shown = if (frozen) receipt.last else document
    val limit = reveal.limit(id)
    val uri = LocalUriHandler.current
    SideEffect {
        reveal.publication.active = true
        if (shown?.source == source) { reveal.applied(id, limit); reveal.presented(id) }
    }
    if (shown != null) BetterSelectionContainer(modifier) {
        Markdown(document = shown, modifier = Modifier.animateContentSize().padding(horizontal = 4.dp).drawWithContent { drawContent(); receipt.last = shown },
            style = MiuixTheme.textStyles.body1.copy(color = conversationColors().text, fontSize = 16.sp, lineHeight = 26.sp),
            onLinkClick = { if (MarkdownText.isSafeLink(it)) runCatching { uri.openUri(it) }; Unit })
    }
}
