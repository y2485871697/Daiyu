package app.daiyu.android.ui.screens.chat.conversation

import android.view.accessibility.AccessibilityManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * Any enabled accessibility service makes Compose rebuild and geometry-sort the whole semantics
 * tree every 100ms while rows move. Chat rows expose semantics only to screen readers and tests.
 */
@Composable
internal fun rememberExposeChatSemantics(): Boolean {
    val context = LocalContext.current
    val manager = remember(context) { context.getSystemService(AccessibilityManager::class.java) }
    var touchExploration by remember(manager) { mutableStateOf(manager?.isTouchExplorationEnabled == true) }
    DisposableEffect(manager) {
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { touchExploration = it }
        manager?.addTouchExplorationStateChangeListener(listener)
        touchExploration = manager?.isTouchExplorationEnabled == true
        onDispose { manager?.removeTouchExplorationStateChangeListener(listener) }
    }
    return touchExploration || instrumented
}

private object ChatSemanticsProbe

// Instrumented processes load the test APK into the app class loader; production never does.
private val instrumented: Boolean by lazy {
    runCatching {
        Class.forName("androidx.test.platform.app.InstrumentationRegistry", false, ChatSemanticsProbe::class.java.classLoader)
    }.isSuccess
}
