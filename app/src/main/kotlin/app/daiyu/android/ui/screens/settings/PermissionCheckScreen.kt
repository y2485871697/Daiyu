package app.daiyu.android.ui.screens.settings

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.daiyu.android.runningmode.AndroidRunningModeStore
import app.daiyu.android.runningmode.RunningMode
import app.daiyu.android.ui.icons.DaiyuIcon
import app.daiyu.android.ui.icons.DaiyuIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun PermissionCheckScreen(onBack: () -> Unit, modifier: Modifier = Modifier,
    access: PermissionCheckAccess? = null) {
    val context = LocalContext.current
    val system = access ?: remember(context) { AndroidPermissionCheckAccess(context) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var runningModeOpen by rememberSaveable { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }
    var active by remember(system, lifecycle) { mutableStateOf(true) }
    var statuses by remember(system) { mutableStateOf<Map<PermissionCheckItem, Boolean?>>(emptyMap()) }
    val runningMode by AndroidRunningModeStore.get(context).mode.collectAsState()
    DisposableEffect(system, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) revision++ }
        lifecycle.addObserver(observer)
        onDispose { active = false; lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(system, revision, runningModeOpen) {
        if (!runningModeOpen) statuses = withContext(Dispatchers.IO) {
            PermissionCheckItem.entries.associateWith { runCatching { system.granted(it) }.getOrNull() }
        }
    }
    if (runningModeOpen) {
        RunningModeSettingsScreen(onBack = { if (active) runningModeOpen = false }, modifier = modifier)
        return
    }
    val back = { if (active) { active = false; onBack() } }
    BackHandler(onBack = back)
    fun current() = active && !runningModeOpen && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
    SettingsScaffoldPage("权限检查", back, modifier) {
        item("permission-heading") { SettingsSectionTitle("权限状态") }
        item("permission-status") {
            SettingsCard {
                PermissionCheckItem.entries.forEach { item ->
                    SettingsRow(item.title, tag = "permission-check:${item.name.lowercase()}", leading = {
                        SettingsIcon(when (item) {
                            PermissionCheckItem.BACKGROUND -> DaiyuIcons.Timestamp
                            PermissionCheckItem.OVERLAY -> DaiyuIcons.VirtualScreen
                            PermissionCheckItem.APPS -> DaiyuIcons.AppIcon
                            PermissionCheckItem.LOCATION -> DaiyuIcons.Locate
                            PermissionCheckItem.NOTIFICATION_ACCESS -> DaiyuIcons.Shield
                            PermissionCheckItem.USAGE -> DaiyuIcons.Chart
                            PermissionCheckItem.ACCESSIBILITY -> DaiyuIcons.Accessibility
                            PermissionCheckItem.TASK_NOTIFICATIONS -> DaiyuIcons.Bell
                        })
                    }, onClick = {
                        if (current()) try { system.open(item) }
                        catch (_: Exception) { Toast.makeText(context, "无法打开系统设置", Toast.LENGTH_SHORT).show() }
                    }, trailing = {
                        PermissionCheckTrailing(when (statuses[item]) { true -> "已授权"; false -> "未授权"; null -> "未确认" })
                    })
                }
                SettingsRow("运行模式", tag = "permission-check:running-mode", leading = { SettingsIcon(DaiyuIcons.Key) },
                    onClick = { if (current()) runningModeOpen = true }, trailing = {
                        PermissionCheckTrailing(when (runningMode) {
                            RunningMode.NORMAL -> "普通模式"
                            RunningMode.ROOT -> "Root"
                            RunningMode.SHIZUKU -> "Shizuku"
                        })
                    })
            }
        }
    }
}

@Composable
private fun PermissionCheckTrailing(label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        DaiyuIcon(DaiyuIcons.ChevronRight, null, tint = MiuixTheme.colorScheme.onSurfaceVariantSummary, size = 18.dp)
    }
}
