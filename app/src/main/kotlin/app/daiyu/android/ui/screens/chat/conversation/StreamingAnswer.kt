package app.daiyu.android.ui.screens.chat.conversation

import app.daiyu.android.diagnostics.HapticTrace
import app.daiyu.android.diagnostics.RuntimeDiagnostics.Code as HC


import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.text.BreakIterator
import java.util.Locale
import me.rerere.bettertext.markdown.MarkdownInline
import me.rerere.bettertext.markdown.MarkdownBlock
import me.rerere.bettertext.markdown.plainText
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

internal class AnswerPaint(private val initialSource: String = "") {
    var last: MarkdownDocument? = null
        private set

    private val boundaries by lazy { BreakIterator.getCharacterInstance(Locale.ROOT) }

    fun record(document: MarkdownDocument): Int {
        val previous = last
        if (previous === document) return 0
        last = document
        val before = previous?.source ?: initialSource
        val next = document.source
        if (next.length <= before.length || !next.startsWith(before) || document.blocks.isEmpty() ||
            previous?.blocks == document.blocks) return 0
        val oldTail = previous?.blocks?.lastOrNull()
        val tail = document.blocks.last()
        fun List<MarkdownInline>.characters() = (singleOrNull() as? MarkdownInline.Text)?.text ?: plainText()
        fun textOf(block: MarkdownBlock?) = when (block) {
            is MarkdownBlock.Paragraph -> block.content.characters()
            is MarkdownBlock.Heading -> block.content.characters()
            is MarkdownBlock.CodeBlock -> block.code
            else -> null
        }
        val sameBlock = previous != null && previous.blocks.size == document.blocks.size
        val rendered = textOf(tail)
        val oldText = if (sameBlock) textOf(oldTail) else null
        val text: String
        val start: Int
        if (rendered != null && oldText != null) {
            if (!rendered.startsWith(oldText)) return 0
            text = rendered; start = oldText.length
        } else if (rendered != null && previous == null && before.isEmpty()) {
            text = rendered; start = 0
        } else {
            text = next; start = before.length
        }
        if (start >= text.length) return 0
        boundaries.setText(text)
        var i = if (boundaries.isBoundary(start)) start else boundaries.following(start)
        var count = 0
        while (i in 0 until text.length && count < ReplyCharacterTicks.BURST) {
            if (!text[i].isWhitespace()) count++
            i = boundaries.following(i)
        }
        return count
    }
}

/** Same parser, document publisher and block renderer as the reference app. No local character queue. */
@Composable
internal fun StreamingAnswer(source: String, streaming: Boolean, id: String, reveal: MarkdownReveal, modifier: Modifier) {
    val playback = LocalReplyPlayback.current
    val frozen = !LocalChatPresentationActive.current || playback?.let { it.paused || it.control.stopped } == true
    val receipt = remember(id, reveal.publication) { reveal.publication.answerPaint.getOrPut(id) {
        AnswerPaint(reveal.publication.initialSources.firstOrNull { it.id == id }?.text.orEmpty())
    } }
    val document = rememberMarkdownDocument(if (frozen) receipt.last?.source.orEmpty() else source, streaming = streaming)
    val shown = if (frozen) receipt.last else document
    val limit = reveal.limit(id)
    val following = LocalStreamingTextClearance.current != null && LocalChatBottomFollowIntent.current
    val bodySize = if (following) Modifier else Modifier.animateContentSize()
    val uri = LocalUriHandler.current
    SideEffect {
        reveal.publication.active = true
        if (shown?.source == source) { reveal.applied(id, limit); reveal.presented(id) }
    }
    val latestAdvance = rememberUpdatedState(reveal.onDocumentAdvance)
    val ticks = remember(receipt, streaming, frozen) {
        val handler = Handler(Looper.getMainLooper())
        ReplyCharacterTicks(SystemClock::uptimeMillis, { task, time -> handler.postAtTime(task, time) },
            { handler.removeCallbacks(it) }) {
            if (!frozen && playback?.let { it.paused || it.control.stopped } != true) latestAdvance.value()
        }
    }
    DisposableEffect(ticks) { onDispose { ticks.close() } }
    if (shown != null) BetterSelectionContainer(modifier) {
        Markdown(document = shown, modifier = bodySize.padding(horizontal = 4.dp).drawWithContent {
                drawContent()
                val added = receipt.record(shown)
                if (!frozen && added > 0) { HapticTrace.mark(HC.DOCUMENT_DRAW, added); ticks.offer(if (streaming) added else 1) }
            },
            style = MiuixTheme.textStyles.body1.copy(color = conversationColors().text, fontSize = 16.sp, lineHeight = 26.sp),
            onLinkClick = { if (MarkdownText.isSafeLink(it)) runCatching { uri.openUri(it) }; Unit })
    }
}
