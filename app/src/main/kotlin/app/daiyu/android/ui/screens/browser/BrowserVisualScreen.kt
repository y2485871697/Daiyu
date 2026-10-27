package app.daiyu.android.ui.screens.browser

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import app.daiyu.android.BrowserVisualVisit
import app.daiyu.android.browser.BrowserSheetUiState
import app.daiyu.android.browser.ported.ui.browser.BrowserSheet
import app.daiyu.android.browser.ported.ui.theme.OpenMinisBrowserTheme
import app.daiyu.android.browser.ui.BrowserUiPool
import app.daiyu.android.ui.components.WithoutPressRipple
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Original browser surfaces, backed by the original task lease or an explicit human session. */
@Composable
internal fun BrowserVisualScreen(
    visit: BrowserVisualVisit?, ownerCurrent: () -> Boolean,
    onBack: () -> Unit, modifier: Modifier = Modifier,
    uiState: BrowserSheetUiState? = null,
) {
    val context = LocalContext.current
    val pool = remember(visit) { BrowserUiPool(context.applicationContext, visit) }
    val current by rememberUpdatedState(ownerCurrent)
    val dismiss by rememberUpdatedState(onBack)
    DisposableEffect(visit) { onDispose { visit?.close() } }
    LaunchedEffect(visit) {
        while (true) {
            if (visit != null && !current()) { visit.close(); break }
            if (visit != null && pool.unavailable.value) {
                visit.close()
                dismiss()
                break
            }
            kotlinx.coroutines.delay(250)
        }
    }
    OpenMinisBrowserTheme(darkTheme = MiuixTheme.colorScheme.surface.luminance() < 0.5f) {
        val presentation = uiState ?: remember { BrowserSheetUiState() }
        WithoutPressRipple { BrowserSheet(pool, onBack, presentation) }
    }
}
