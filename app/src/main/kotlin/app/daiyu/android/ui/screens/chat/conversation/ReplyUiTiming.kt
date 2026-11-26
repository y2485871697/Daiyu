package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import app.daiyu.android.diagnostics.RuntimeDiagnostics
import app.daiyu.android.diagnostics.RuntimeDiagnostics.Stage
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay

internal fun MarkdownReveal.waitStage(text: MessageContentUi.Text): Stage = when {
    !hasSource(text.itemId, text.text, text.markdownWindow) -> Stage.REPLY_WAIT_PROJECTION
    !ready(text.itemId, text.text, text.markdownWindow) -> Stage.REPLY_WAIT_REVEAL
    else -> Stage.REPLY_WAIT_DRAW
}

/** Log a stalled phase once while pending, and its elapsed duration when it clears. No text or ids. */
@Composable
internal fun RecordReplyUiWait(owner: Any?, partKey: String, stage: Stage) {
    if (!RuntimeDiagnostics.enabled) return
    LaunchedEffect(owner, partKey, stage) {
        val started = System.nanoTime()
        try {
            delay(750)
            RuntimeDiagnostics.event(stage, RuntimeDiagnostics.Code.BEGIN)
            awaitCancellation()
        } finally {
            RuntimeDiagnostics.slow(stage, started, 150)
        }
    }
}
