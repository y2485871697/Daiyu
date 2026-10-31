package app.daiyu.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.daiyu.android.ui.icons.DaiyuIcons
import top.yukonga.miuix.kmp.basic.Text

@Composable
internal fun GroupedPickerSearch(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    description: String,
    modifier: Modifier = Modifier,
) {
    var searchFocused by remember { mutableStateOf(false) }
    val colors = usagePalette()
    BasicTextField(
        value = query, onValueChange = onQueryChange, singleLine = true,
        textStyle = TextStyle(color = colors.text, fontSize = 14.sp, lineHeight = 22.sp),
        cursorBrush = imeCursorBrush(colors.accent),
        modifier = Modifier.managedInput().then(modifier).fillMaxWidth()
            .semantics { contentDescription = description }
            .onFocusChanged { searchFocused = it.isFocused }
            .heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp)).background(colors.background)
            .border(1.dp, if (searchFocused) colors.accent else colors.line, RoundedCornerShape(14.dp)),
        decorationBox = { input ->
            Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(DaiyuIcons.Search, null, Modifier.size(20.dp), tint = colors.muted)
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text(placeholder, color = colors.muted, fontSize = 14.sp, lineHeight = 22.sp)
                    input()
                }
            }
        })
}

// Draw a continuous card across lazy rows without composing an entire large catalog at once.
internal fun Modifier.pickerGroupSurface(top: Boolean, bottom: Boolean, surface: Color, line: Color): Modifier = drawBehind {
    val inset = .5.dp.toPx()
    val left = inset; val right = size.width - inset
    val start = if (top) inset else 0f; val end = if (bottom) size.height - inset else size.height
    val radius = 14.dp.toPx().coerceAtMost((end - start) / 2)
    val path = Path().apply {
        moveTo(left, if (top) start + radius else start)
        if (top) { quadraticBezierTo(left, start, left + radius, start); lineTo(right - radius, start)
            quadraticBezierTo(right, start, right, start + radius) } else lineTo(right, start)
        lineTo(right, if (bottom) end - radius else end)
        if (bottom) { quadraticBezierTo(right, end, right - radius, end); lineTo(left + radius, end)
            quadraticBezierTo(left, end, left, end - radius) } else lineTo(left, end)
        close()
    }
    drawPath(path, surface)
    val edge = Path().apply {
        moveTo(left, if (bottom) end - radius else end)
        lineTo(left, if (top) start + radius else start)
        if (top) { quadraticBezierTo(left, start, left + radius, start); lineTo(right - radius, start)
            quadraticBezierTo(right, start, right, start + radius) } else moveTo(right, start)
        lineTo(right, if (bottom) end - radius else end)
        if (bottom) { quadraticBezierTo(right, end, right - radius, end); lineTo(left + radius, end)
            quadraticBezierTo(left, end, left, end - radius) }
    }
    drawPath(edge, line, style = Stroke(1.dp.toPx()))
}
