package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.em

internal const val ToolHeaderSeparatorId = "tool-header-separator"

// Keep the text spacing while drawing a smaller centered dot.
internal val ToolHeaderSeparatorPlaceholder = Placeholder(.5.em, .5.em, PlaceholderVerticalAlign.Center)

internal fun toolHeaderText(text: String, emphasizeSeparators: Boolean): AnnotatedString = buildAnnotatedString {
    text.forEach { character ->
        if (emphasizeSeparators && (character == '·' || character == '•')) {
            appendInlineContent(ToolHeaderSeparatorId, character.toString())
        } else append(character)
    }
}

internal fun toolHeaderInlineContent(color: Color): Map<String, InlineTextContent> = mapOf(
    ToolHeaderSeparatorId to InlineTextContent(ToolHeaderSeparatorPlaceholder) {
        Canvas(Modifier.fillMaxSize().testTag(ToolHeaderSeparatorId)) {
            drawCircle(color, radius = size.minDimension * .224f, center = center)
        }
    },
)
