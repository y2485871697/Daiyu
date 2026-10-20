package app.daiyu.android.ui.screens.settings

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import app.daiyu.android.gui.GuiSystemSettings
import app.daiyu.android.platform.CrossAppNotifications

internal enum class PermissionCheckItem(val title: String) {
    BACKGROUND("后台运行权限"), OVERLAY("悬浮窗权限"), APPS("应用列表读取"), LOCATION("位置权限"),
    NOTIFICATION_ACCESS("通知使用权"), USAGE("使用情况访问"), ACCESSIBILITY("无障碍辅助权限"),
    TASK_NOTIFICATIONS("后台任务通知"),
}

internal interface PermissionCheckAccess {
    fun granted(item: PermissionCheckItem): Boolean
    fun open(item: PermissionCheckItem)
}

internal class AndroidPermissionCheckAccess(private val context: Context) : PermissionCheckAccess {
    private fun permission(name: String) = context.checkSelfPermission(name) == PackageManager.PERMISSION_GRANTED
    override fun granted(item: PermissionCheckItem): Boolean = when (item) {
        PermissionCheckItem.BACKGROUND -> context.getSystemService(PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(context.packageName) == true
        PermissionCheckItem.OVERLAY -> Settings.canDrawOverlays(context)
        PermissionCheckItem.APPS -> Build.VERSION.SDK_INT < 30 || permission("android.permission.QUERY_ALL_PACKAGES")
        PermissionCheckItem.LOCATION -> permission(Manifest.permission.ACCESS_COARSE_LOCATION) || permission(Manifest.permission.ACCESS_FINE_LOCATION)
        PermissionCheckItem.NOTIFICATION_ACCESS -> CrossAppNotifications.systemGranted(context)
        PermissionCheckItem.USAGE -> context.getSystemService(AppOpsManager::class.java)
            ?.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
        PermissionCheckItem.ACCESSIBILITY -> GuiSystemSettings.enabledBySystem(context)
        PermissionCheckItem.TASK_NOTIFICATIONS -> NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    override fun open(item: PermissionCheckItem) {
        val packageUri = Uri.fromParts("package", context.packageName, null)
        val intent = when (item) {
            PermissionCheckItem.BACKGROUND -> Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            PermissionCheckItem.OVERLAY -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri)
            PermissionCheckItem.APPS, PermissionCheckItem.LOCATION -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri)
            PermissionCheckItem.NOTIFICATION_ACCESS -> Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            PermissionCheckItem.USAGE -> Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            PermissionCheckItem.ACCESSIBILITY -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            PermissionCheckItem.TASK_NOTIFICATIONS -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        }
        context.startActivity(intent)
    }
}
