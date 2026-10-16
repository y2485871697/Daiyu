package app.daiyu.android.ui.screens.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.daiyu.android.appearance.IconAppearanceCoordinator
import app.daiyu.android.appearance.IconAppearanceSettingsView
import app.daiyu.android.domain.appearance.IconAppearance
import app.daiyu.android.domain.appearance.IconNotificationPort
import app.daiyu.android.domain.appearance.IconSyncResult
import app.daiyu.android.ui.components.MiuixScaffoldPage

/**
 * Call once in the activity's root composition, OUTSIDE the settings route, then pass the returned
 * coordinator to [IconAppearanceScreen]. Reuses the retained PM/store algorithm, including its
 * automatic-vs-explicit distinction; this is lifecycle wiring, not a second icon implementation.
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

/** Thin Compose host for the ORIGINAL selector/preview. No duplicate palette, drawing or switcher. */
@Composable
fun IconAppearanceScreen(
    coordinator: IconAppearanceCoordinator,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)
    val initial = remember(coordinator) { runCatching { coordinator.store.load() } }
    val configuration = LocalConfiguration.current
    // Change the update callback when uiMode changes without recreating the editor or losing drafts.
    val refreshPreview: (IconAppearanceSettingsView) -> Unit = remember(configuration.uiMode) {
        { view -> view.updatePreview() }
    }
    MiuixScaffoldPage(title = "应用图标", onBack = onBack, modifier = modifier) {
        item(key = "icon_appearance_scope") {
            SettingsNote("仅调整应用图标；不更改聊天主题。保存后桌面可能延迟刷新，同色组合沿用原始素材。")
        }
        if (initial.isFailure) {
            item(key = "icon_appearance_read_failure") {
                SettingsNote("无法读取图标偏好，当前预览使用默认值；尚未改动桌面入口。可重新选择并保存以修复：" +
                    (initial.exceptionOrNull()?.message ?: "未知读取错误"))
            }
        }
        item(key = "icon_appearance_editor") {
            key(coordinator) {
                AndroidView(
                    modifier = Modifier.fillMaxWidth().testTag("icon-appearance-editor"),
                    factory = { context ->
                        IconAppearanceSettingsView(context, initial.getOrDefault(IconAppearance()), coordinator::saveAndApply)
                    },
                    update = refreshPreview,
                )
            }
        }
    }
}
