package app.daiyu.android.ui.components.subagent

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*

import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
            Column(Modifier.fillMaxWidth().padding(start = 15.dp, end = 15.dp, top = 10.dp, bottom = 16.dp)) {
                ConversationAgentHandle()
                Row(Modifier.fillMaxWidth().padding(bottom = 12.dp).heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.titleLarge.copy(fontSize = 18.sp, lineHeight = 26.sp), modifier = Modifier.weight(1f))
                }
                content()
            }
        }
    }
}

@Composable internal fun ConversationAgentField(label: String, value: String, onValueChange: (String) -> Unit,
    enabled: Boolean, keyboardOptions: KeyboardOptions = KeyboardOptions.Default) {
    val colors = conversationAgentColors()
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Text(label, fontSize = 13.sp, lineHeight = 20.sp, color = colors.text, modifier = Modifier.padding(bottom = 5.dp))
        BasicTextField(value, onValueChange, enabled = enabled, singleLine = true, keyboardOptions = keyboardOptions,
            textStyle = TextStyle(fontSize = 14.sp, lineHeight = 21.sp, color = colors.text),
            modifier = Modifier.fillMaxWidth().background(androidx.compose.ui.graphics.Color(if (colors.dark) 0x06ffffff else 0x60ffffff), RoundedCornerShape(10.dp))
                .border(1.dp, colors.line, RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 9.dp))
    }
}
