package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.getTextLayoutResult
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import app.daiyu.android.MarkdownBlock
import kotlin.math.ceil

/** Code is already non-selectable. Keep interactive Markdown on its visible-prefix text path. */
@Composable
internal fun MarkdownCodeText(block: MarkdownBlock.Code, visibleCharacters: Int, modifier: Modifier, style: TextStyle) {
    val boundaries = remember(block.text) { MarkdownTextBoundaries(block.text) }
    val end = boundaries.end(visibleCharacters)
    val visible = remember(block.text, end) { block.text.substring(0, end) }
    val simpleShaping = remember(block.text) { block.text.all { it.code < 128 } }
    if (end >= block.text.length || !simpleShaping) {
        BasicText(markdownCodeDisplayText(block, visible), modifier, style = style, softWrap = false)
        return
    }
    val measurer = rememberTextMeasurer(cacheSize = 1)
    val complete = remember(block.text) { AnnotatedString(block.text) }
    // The measurer's one-entry cache also invalidates when resolved fonts or environment change.
    val layout = measurer.measure(complete, style, softWrap = false)
    val reveal = remember(layout.multiParagraph) { MarkdownCodeClip(layout) }
    reveal.advance(end)
    val semanticText = remember(visible) { AnnotatedString(visible) }
    Canvas(modifier.semantics {
        text = semanticText
        getTextLayoutResult { results ->
            // Accessibility/test queries get the visible text's layout, never the hidden suffix.
            results += measurer.measure(semanticText, style, softWrap = false, skipCache = true)
            true
        }
    }.layout { measurable, constraints ->
        val width = constraints.constrainWidth(reveal.width)
        val height = constraints.constrainHeight(reveal.height)
        val placeable = measurable.measure(Constraints.fixed(width, height))
        layout(width, height) { placeable.place(0, 0) }
    }) {
        clipPath(reveal.path) { drawText(layout) }
    }
}

internal class MarkdownCodeClip(private val layout: TextLayoutResult) {
    val path = Path()
    private var end = 0
    private val precedingWidths = FloatArray(layout.lineCount + 1).also { widths ->
        for (line in 0 until layout.lineCount) widths[line + 1] = maxOf(widths[line], layout.getLineRight(line))
    }
    var width: Int = 0
        private set
    var height: Int = 0
        private set

    fun advance(visibleEnd: Int) {
        if (visibleEnd < end) { path.reset(); end = 0 }
        if (visibleEnd > end) path.addPath(layout.getPathForRange(end, visibleEnd))
        end = visibleEnd
        val line = layout.getLineForOffset(end)
        width = ceil(maxOf(precedingWidths[line], layout.getHorizontalPosition(end, usePrimaryDirection = true))).toInt()
        height = ceil(layout.getLineBottom(line)).toInt()
    }
}
