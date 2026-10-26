package app.daiyu.android.ui.components.subagent

import app.daiyu.android.ui.components.imeCursorBrush
import app.daiyu.android.ui.components.managedInput

import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*

import androidx.compose.runtime.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
    val textStyle = TextStyle(fontSize = 14.sp, lineHeight = 21.sp, color = colors.text, textAlign = TextAlign.Start)
    Box(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        BasicTextField(value, onValueChange, enabled = enabled, singleLine = true, keyboardOptions = keyboardOptions,
            textStyle = textStyle,
            modifier = Modifier.managedInput().fillMaxWidth().testTag("subagent-field:$label")
                .semantics { contentDescription = label }
                .drawBehind {
                    val stroke = 1.dp.toPx()
                    drawRoundRect(colors.line, topLeft = Offset(stroke / 2, stroke / 2),
                        size = Size(size.width - stroke, size.height - stroke),
                        cornerRadius = CornerRadius(10.dp.toPx()), style = Stroke(stroke))
                },
            cursorBrush = imeCursorBrush(colors.accent),
            decorationBox = { editor ->
                Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 10.dp),
                    contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) Text(label, style = textStyle, color = colors.text.copy(alpha = .6f))
                    Box(Modifier.testTag("subagent-field-editor:$label")) { editor() }
                }
            },
        )
    }
}
