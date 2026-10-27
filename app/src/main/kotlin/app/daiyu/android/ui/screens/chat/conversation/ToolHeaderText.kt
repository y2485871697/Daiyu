package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em

internal fun toolHeaderText(text: String, emphasizeSeparators: Boolean): AnnotatedString = buildAnnotatedString {
    append(text)
    if (emphasizeSeparators) {
        val separatorStyle = SpanStyle(fontSize = 1.4.em, fontWeight = FontWeight.Bold)
        text.forEachIndexed { index, character ->
            if (character == '·' || character == '•') addStyle(separatorStyle, index, index + 1)
        }
    }
}
