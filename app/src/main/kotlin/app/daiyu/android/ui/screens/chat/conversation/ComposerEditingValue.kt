package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

internal fun TextFieldValue.forComposer(text: String, selectionStart: Int, selectionEnd: Int): TextFieldValue {
    val selection = TextRange(selectionStart.coerceIn(0, text.length), selectionEnd.coerceIn(0, text.length))
    return if (this.text == text) copy(selection = selection) else TextFieldValue(text, selection)
}
