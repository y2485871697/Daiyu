package app.daiyu.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun CompactDialogTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    val colors = MiuixTheme.colorScheme
    val style = MiuixTheme.textStyles.body1.copy(fontSize = 14.sp, lineHeight = 21.sp, color = colors.onSurface)
    Box(Modifier.fillMaxWidth().padding(start = 9.dp, end = 9.dp, bottom = 13.dp)) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = style,
            cursorBrush = imeCursorBrush(colors.primary),
            modifier = modifier.managedInput().fillMaxWidth().heightIn(min = 48.dp)
                .semantics { contentDescription = placeholder }
                .clip(RoundedCornerShape(16.dp)).background(colors.surfaceVariant),
            decorationBox = { editor ->
                Box(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) Text(placeholder, style = style, color = colors.onSurfaceVariantSummary)
                    editor()
                }
            },
        )
    }
}
