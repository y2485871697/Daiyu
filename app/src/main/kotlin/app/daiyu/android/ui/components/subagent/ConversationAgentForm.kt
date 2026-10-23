package app.daiyu.android.ui.components.subagent

import app.daiyu.android.ui.components.imeCursorBrush
import app.daiyu.android.ui.components.managedInput

import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*

import androidx.compose.runtime.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import app.daiyu.android.ui.layout.appWindowSize
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable internal fun SubAgentConfigurationWindow(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    if (!LocalConversationAgentStyle.current) {
        WindowDialog(show = true, title = title, onDismissRequest = onDismiss) { Column { content() } }
        return
    }
    val maximumHeight = (appWindowSize().height - 32.dp).coerceAtLeast(0.dp)
    ConversationAgentTypography {
        ConversationAgentSheet(onDismiss, modifier = Modifier.heightIn(max = maximumHeight)) {
            Column(Modifier.fillMaxWidth().testTag("subagent-config-window").padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 16.dp)) {
                ConversationAgentHandle()
                Text(title, style = MaterialTheme.typography.titleLarge.copy(fontSize = 18.sp, lineHeight = 26.sp),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp).heightIn(min = 40.dp))
                content()
            }
        }
    }
}

@Composable internal fun ConversationAgentField(label: String, value: String, onValueChange: (String) -> Unit,
    enabled: Boolean, keyboardOptions: KeyboardOptions = KeyboardOptions.Default) {
    val colors = conversationAgentColors()
    val density = LocalDensity.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    var labelSize by remember(label) { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    val halfLabelHeight = with(density) { if (labelSize.height == 0) 10.sp.toDp() else (labelSize.height / 2f).toDp() }
    Box(Modifier.fillMaxWidth().padding(top = halfLabelHeight, bottom = 8.dp)) {
        BasicTextField(value, onValueChange, enabled = enabled, singleLine = true, keyboardOptions = keyboardOptions,
            textStyle = TextStyle(fontSize = 14.sp, lineHeight = 21.sp, color = colors.text, textAlign = TextAlign.Start),
            modifier = Modifier.managedInput().fillMaxWidth().testTag("subagent-field:$label")
                .drawBehind {
                    val stroke = 1.dp.toPx()
                    val notchWidth = labelSize.width + 8.dp.toPx()
                    val notchStart = if (rtl) size.width - 8.dp.toPx() - notchWidth else 8.dp.toPx()
                    clipRect(left = notchStart, top = -halfLabelHeight.toPx() - stroke,
                        right = notchStart + notchWidth, bottom = halfLabelHeight.toPx(), clipOp = ClipOp.Difference) {
                        drawRoundRect(colors.line, topLeft = Offset(stroke / 2, stroke / 2),
                            size = Size(size.width - stroke, size.height - stroke),
                            cornerRadius = CornerRadius(10.dp.toPx()), style = Stroke(stroke))
                    }
                },
            cursorBrush = imeCursorBrush(colors.accent),
            decorationBox = { editor ->
                Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 10.dp),
                    contentAlignment = Alignment.CenterStart) {
                    Box(Modifier.testTag("subagent-field-editor:$label")) { editor() }
                }
            },
        )
        Text(label, fontSize = 13.sp, lineHeight = 20.sp, color = colors.text,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 12.dp).offset(y = -halfLabelHeight)
                .onSizeChanged { labelSize = it })
    }
}
