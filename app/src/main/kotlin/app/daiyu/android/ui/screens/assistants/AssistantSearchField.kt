package app.daiyu.android.ui.screens.assistants

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.daiyu.android.ui.components.imeCursorBrush
import app.daiyu.android.ui.components.managedInput
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text

@Composable
internal fun AssistantSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    focused: Boolean,
    onFocusChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    fieldModifier: Modifier = Modifier,
) {
    val colors = assistantPreviewPalette()
    val s = assistantPreviewScale()
    Row(modifier.fillMaxWidth().heightIn(min = 44.dp * s)
        .clip(RoundedCornerShape(13.dp * s)).background(colors.soft)
        .border(1.dp, if (focused) colors.accent else colors.line, RoundedCornerShape(13.dp * s))
        .padding(1.dp).padding(horizontal = 11.dp * s),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp * s)) {
        Icon(AssistantPreviewIcons.Search, null, Modifier.size(18.dp * s), tint = colors.muted)
        BasicTextField(value, onValueChange, singleLine = true,
            modifier = fieldModifier.managedInput().weight(1f).heightIn(min = 42.dp * s)
                .onFocusChanged { onFocusChanged(it.isFocused) },
            cursorBrush = imeCursorBrush(colors.accent),
            textStyle = TextStyle(color = colors.text, fontSize = (14f * s).sp, lineHeight = (20f * s).sp),
            decorationBox = { innerTextField ->
                Box(Modifier.padding(horizontal = 2.dp * s, vertical = 1.dp * s), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) Text(placeholder, color = colors.muted,
                        fontSize = (14f * s).sp, lineHeight = (20f * s).sp)
                    innerTextField()
                }
            })
    }
}
