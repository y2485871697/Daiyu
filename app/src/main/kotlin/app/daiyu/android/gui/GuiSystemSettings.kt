package app.daiyu.android.gui

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityManager

internal object GuiSystemSettings {
    private fun component(context: Context) = ComponentName(context, GuiAccessibilityService::class.java)
    fun enabledBySystem(context: Context): Boolean {
        val target = component(context)
        val manager = context.getSystemService(AccessibilityManager::class.java) ?: return false
        return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any {
            val info = it.resolveInfo.serviceInfo
            ComponentName(info.packageName, info.name) == target
        }
    }
    /** Visible user click only; opens public settings without enabling the service. */
    fun openFromUser(context: Context): GuiResult<Unit> = try {
        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        GuiResult.Value(Unit)
    } catch (_: Exception) { GuiResult.Rejected("SYSTEM_SETTINGS_UNAVAILABLE") }

    /** Opens settings for manual off/on; does not request a programmatic rebind. */
    fun requestRebindFromUser(context: Context): GuiResult<Unit> = try {
        if (!enabledBySystem(context)) GuiResult.Rejected("USER_MUST_ENABLE_SERVICE_IN_SYSTEM_SETTINGS")
        else openFromUser(context)
    } catch (_: Exception) { GuiResult.Rejected("SYSTEM_SETTINGS_UNAVAILABLE") }
}
