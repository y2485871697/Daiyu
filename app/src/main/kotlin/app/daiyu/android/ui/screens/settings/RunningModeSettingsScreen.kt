package app.daiyu.android.ui.screens.settings

import app.daiyu.android.ui.navigation.PageBackHandler as BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import app.daiyu.android.ui.haptics.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.daiyu.android.ChatApplication
import app.daiyu.android.runningmode.*
import app.daiyu.android.ui.icons.DaiyuIcon
import app.daiyu.android.ui.icons.DaiyuIcons
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun RunningModeSettingsScreen(onBack: () -> Unit, modifier: Modifier = Modifier,
    store: RunningModeStore = AndroidRunningModeStore.get(LocalContext.current),
    authorization: RunningModeAuthorization? = null) {
    val context = LocalContext.current
    val permissions = authorization ?: remember(context) {
        AndroidRunningModeAuthorization((context.applicationContext as ChatApplication).shizuku)
    }
    val editor = remember(store, permissions) { RunningModeEditor(store, permissions) }
    val selected by store.mode.collectAsState()
    val state by editor.state.collectAsState()
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(editor, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) scope.launch { editor.refreshShizuku() }
        }
        lifecycle.addObserver(observer)
        onDispose { editor.close(); lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(editor) { editor.refreshShizuku() }
    val back = { editor.close(); onBack() }
    BackHandler(onBack = back)
    SettingsScaffoldPage("运行模式", back, modifier) {
        item("running-modes") {
            SettingsCard(Modifier.padding(top = 12.dp).selectableGroup()) {
                RunningMode.entries.forEach { mode ->
                    val name = when (mode) {
                        RunningMode.NORMAL -> "普通模式"
                        RunningMode.ROOT -> "Root"
                        RunningMode.SHIZUKU -> "Shizuku"
                    }
                    val failure = when (mode) {
                        RunningMode.NORMAL -> state.normalFailure
                        RunningMode.ROOT -> state.rootFailure
                        RunningMode.SHIZUKU -> state.shizukuFailure
                    }
                    Box(Modifier.fillMaxWidth().testTag("running-mode:${mode.name.lowercase()}")
                        .selectable(selected = selected == mode, role = Role.RadioButton,
                            enabled = state.pending == null, onClick = {
                                if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) scope.launch {
                                    editor.select(mode) {
                                        lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.RESUMED) }
                                    }
                                }
                            })) {
                        SettingsRow(title = if (failure == null) name else "$name · $failure", trailing = {
                            if (selected == mode) DaiyuIcon(DaiyuIcons.Check, "已选择",
                                tint = MiuixTheme.colorScheme.primary, size = 24.dp)
                        })
                    }
                }
            }
        }
    }
}
