package app.daiyu.android.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.daiyu.android.appearance.IconAppearanceCoordinator
import app.daiyu.android.domain.appearance.IconNotificationPort
import app.daiyu.android.domain.appearance.IconSyncResult

/**
 * Call once in the activity's root composition, OUTSIDE the settings route, then pass the returned
 * coordinator to [IconAppearanceEditor]. Wires lifecycle updates to the coordinator while preserving
 * its automatic-vs-explicit distinction.
 *
 * The host supplies its existing-notification refresh port (an explicit empty implementation is
 * appropriate only when there are no active app notifications). Every automatic receipt/read
 * failure is returned to the host on main, never treated as a successful icon change.
 */
@Composable
fun rememberIconAppearanceCoordinator(
    notifications: IconNotificationPort,
    onAutomaticSync: (Result<IconSyncResult>) -> Unit,
): IconAppearanceCoordinator {
    val context = LocalContext.current.applicationContext
    val coordinator = remember(context, notifications) { IconAppearanceCoordinator(context, notifications) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val uiMode = LocalConfiguration.current.uiMode
    val report by rememberUpdatedState(onAutomaticSync)
    DisposableEffect(coordinator, lifecycle, uiMode) {
        // addObserver also dispatches ON_RESUME when already resumed. Re-register on uiMode changes
        // to handle an activity that consumes configChanges without requiring a settings visit.
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) report(runCatching { coordinator.applyCurrent() })
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    return coordinator
}
