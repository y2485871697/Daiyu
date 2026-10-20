package app.daiyu.android.ui.components

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import app.daiyu.android.voice.ComposerDictationState
import top.yukonga.miuix.kmp.basic.Text

@Composable
internal fun DictationStatusNotice(state: ComposerDictationState, engineeringUi: Boolean) {
    val context = LocalContext.current
    if (engineeringUi && state.status.isNotEmpty()) {
        Text(state.status, modifier = Modifier.testTag("dictation-status")
            .semantics { liveRegion = LiveRegionMode.Polite })
    }
    DisposableEffect(context, engineeringUi, state.status, state.target) {
        val notice = if (!engineeringUi && state.target == null && state.status.isNotBlank())
            Toast.makeText(context, state.status, Toast.LENGTH_SHORT).also { it.show() }
        else null
        onDispose { notice?.cancel() }
    }
}
