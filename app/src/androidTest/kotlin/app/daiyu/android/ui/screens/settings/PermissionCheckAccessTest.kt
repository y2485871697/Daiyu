package app.daiyu.android.ui.screens.settings

import android.content.ActivityNotFoundException
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import app.daiyu.android.WiredActivityTestCase

class PermissionCheckAccessTest : WiredActivityTestCase() {
    private inner class SettingsContext : ContextWrapper(activity) {
        val opened = mutableListOf<Intent>()
        var rejectPackageUsage = false
        var appListGranted = false
        var rejectAll = false
        override fun checkSelfPermission(permission: String): Int =
            if (permission == "android.permission.QUERY_ALL_PACKAGES") {
                if (appListGranted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
            } else super.checkSelfPermission(permission)
        override fun startActivity(intent: Intent, options: Bundle?) = startActivity(intent)
        override fun startActivity(intent: Intent) {
            opened += Intent(intent)
            if (rejectAll) throw ActivityNotFoundException("No settings activity")
            if (rejectPackageUsage && intent.action == Settings.ACTION_USAGE_ACCESS_SETTINGS && intent.data != null)
                throw ActivityNotFoundException("No package-scoped usage settings")
        }
    }

    @Suppress("DEPRECATION")
    fun testInstalledManifestDeclaresTheThreeCheckedCapabilities() {
        val names = activity.packageManager.getPackageInfo(activity.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty().toSet()
        for (name in listOf("QUERY_ALL_PACKAGES", "PACKAGE_USAGE_STATS", "POST_NOTIFICATIONS"))
            assertTrue(name, "android.permission.$name" in names)
    }

    fun testAppListStatusUsesTheNormalPermissionAndOpensThisApp() {
        val context = SettingsContext()
        val access = AndroidPermissionCheckAccess(context)
        assertEquals(Build.VERSION.SDK_INT < 30, access.granted(PermissionCheckItem.APPS))
        context.appListGranted = true
        assertTrue(access.granted(PermissionCheckItem.APPS))
        access.open(PermissionCheckItem.APPS)
        val intent = context.opened.single()
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intent.action)
        assertEquals(Uri.fromParts("package", activity.packageName, null), intent.data)
    }

    fun testUsageSettingsTargetsThisPackageAndFallsBackOnlyWhenUnsupported() {
        val context = SettingsContext()
        val access = AndroidPermissionCheckAccess(context)
        access.open(PermissionCheckItem.USAGE)
        assertEquals(Settings.ACTION_USAGE_ACCESS_SETTINGS, context.opened.single().action)
        assertEquals(if (Build.VERSION.SDK_INT >= 29) Uri.fromParts("package", activity.packageName, null) else null,
            context.opened.single().data)
        context.opened.clear()
        context.rejectPackageUsage = true
        access.open(PermissionCheckItem.USAGE)
        assertEquals(if (Build.VERSION.SDK_INT >= 29) 2 else 1, context.opened.size)
        assertEquals(Settings.ACTION_USAGE_ACCESS_SETTINGS, context.opened.last().action)
        assertNull(context.opened.last().data)
    }

    fun testMissingSettingsPropagatesAndDoesNotRetryOtherItems() {
        val context = SettingsContext().apply { rejectAll = true }
        val access = AndroidPermissionCheckAccess(context)
        for (item in listOf(PermissionCheckItem.USAGE, PermissionCheckItem.TASK_NOTIFICATIONS)) {
            context.opened.clear()
            try {
                access.open(item)
                fail("Missing system settings must reach the page's existing error feedback")
            } catch (_: ActivityNotFoundException) {
                assertEquals(if (item == PermissionCheckItem.USAGE && Build.VERSION.SDK_INT >= 29) 2 else 1,
                    context.opened.size)
            }
        }
    }

    fun testTaskNotificationRowReadsSystemStateAndOpensOnlyThisAppsPostingSettings() {
        val context = SettingsContext()
        val access = AndroidPermissionCheckAccess(context)
        val before = NotificationManagerCompat.from(context).areNotificationsEnabled()
        assertEquals(before, access.granted(PermissionCheckItem.TASK_NOTIFICATIONS))
        access.open(PermissionCheckItem.TASK_NOTIFICATIONS)
        val intent = context.opened.single()
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, intent.action)
        assertEquals(activity.packageName, intent.getStringExtra(Settings.EXTRA_APP_PACKAGE))
        assertNull(intent.data)
        assertEquals(before, access.granted(PermissionCheckItem.TASK_NOTIFICATIONS))
    }
}
