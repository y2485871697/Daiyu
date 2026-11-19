package app.daiyu.android.ui.screens.chat.conversation

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

internal fun ComponentActivity.setMarkdownClockContent(nowNanos: () -> Long, content: @Composable () -> Unit) {
    setContent { CompositionLocalProvider(LocalMarkdownReceiptClock provides nowNanos, content = content) }
}
