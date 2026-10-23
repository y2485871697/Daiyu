package app.daiyu.android.ui.screens.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import app.daiyu.android.ui.navigation.PageBackHandler as BackHandler
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.daiyu.android.ChatApplication
import app.daiyu.android.ui.FeatureRoute
import app.daiyu.android.ui.screens.shizuku.ShizukuScreen

internal const val PROJECT_SOURCE = "https://github.com/y2485871697/Daiyu"

internal fun openProjectSource(context: Context) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PROJECT_SOURCE))
            .addCategory(Intent.CATEGORY_BROWSABLE))
    } catch (_: Exception) {
        Toast.makeText(context, "无法打开项目源码仓库", Toast.LENGTH_LONG).show()
    }
}

private class SystemAccessVisit { var active = true }

@Composable
internal fun SettingsSystemAccessPage(
    page: SettingsLocalPage,
    onBack: () -> Unit,
    onOpenShizuku: () -> Unit,
    onRoute: (FeatureRoute) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val application = context.applicationContext as ChatApplication
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val visit = remember(page) { SystemAccessVisit() }
    fun current() = visit.active && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
    var overlayGranted by remember(page) { mutableStateOf(Settings.canDrawOverlays(context)) }
    DisposableEffect(visit, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && visit.active && page == SettingsLocalPage.OVERLAY) {
                overlayGranted = Settings.canDrawOverlays(context)
                application.pets.refreshOverlayPermission()
            }
        }
        lifecycle.addObserver(observer)
        onDispose { visit.active = false; lifecycle.removeObserver(observer) }
    }
    val back: () -> Unit = { if (current()) onBack() }
    BackHandler(previewEnabled = false) { back() }
    if (page == SettingsLocalPage.SHIZUKU_ACCESS) {
        ShizukuScreen(application.shizuku, session = null, onBack = back,
            onReady = { run, _ -> run.close() })
        return
    }
    val title = when (page) {
        SettingsLocalPage.ENHANCEMENTS -> "Root 与系统增强"
        SettingsLocalPage.OVERLAY -> "悬浮窗权限"
        else -> error("unsupported_system_access_page")
    }
    SettingsScaffoldPage(title, onBack = back, modifier = modifier) {
        when (page) {
            SettingsLocalPage.ENHANCEMENTS -> {
                item("notice") { SettingsNote("系统授权与每次任务授权分别控制。进入本页不执行命令、不连接执行后端，也不批准旧任务。") }
                item("access") {
                    SettingsCard {
                        SettingsRow("Shizuku 状态与系统授权", summary = "查看现有服务，需要时明确请求系统授权",
                            onClick = { if (current()) onOpenShizuku() })
                        SettingsRow("Linux 工具环境", summary = "进入已有 PRoot / chroot 配置与安装流程",
                            onClick = { if (current()) onRoute(FeatureRoute.LINUX_SETTINGS) })
                    }
                }
            }
            SettingsLocalPage.OVERLAY -> {
                item("notice") { SettingsNote("系统悬浮权限不等于开启桌宠。本页不更改桌宠显示同意、全局显示配置或任务执行权限。") }
                item("access") {
                    SettingsCard {
                        SettingsRow(if (overlayGranted) "系统悬浮权限已允许" else "系统悬浮权限未允许",
                            summary = "返回应用后重新读取系统状态", tag = "overlay-system-status")
                        SettingsRow("前往系统悬浮窗设置", summary = "由你在系统页面决定是否授权",
                            onClick = {
                                if (current()) {
                                    try {
                                        context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                            Uri.fromParts("package", context.packageName, null)))
                                    } catch (_: Exception) {
                                        Toast.makeText(context, "系统悬浮窗设置不可用；未修改权限", Toast.LENGTH_LONG).show()
                                    }
                                }
                            })
                    }
                }
            }
            else -> error("unsupported_system_access_page")
        }
    }
}
