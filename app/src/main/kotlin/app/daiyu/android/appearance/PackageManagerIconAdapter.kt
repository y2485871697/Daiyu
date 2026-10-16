package app.daiyu.android.appearance

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import app.daiyu.android.domain.appearance.IconComponentSnapshot
import app.daiyu.android.domain.appearance.IconComponentState
import app.daiyu.android.domain.appearance.LauncherComponentPort

@Suppress("DEPRECATION")
class PackageManagerIconAdapter(context: Context) : LauncherComponentPort {
    private val contextForResources = context.applicationContext
    private val packageName = context.packageName
    private val pm = context.packageManager
    private fun component(name: String): ComponentName {
        require(name.startsWith("$packageName.icon.L_")) { "Not an owned icon alias" }
        return ComponentName(packageName, name)
    }

    override fun validate(componentName: String) {
        val component = component(componentName)
        val flags = PackageManager.MATCH_DISABLED_COMPONENTS
        val info = pm.getActivityInfo(component, flags)
        check(info.packageName == packageName && info.targetActivity != null && info.exported) { "Missing exported activity-alias: $componentName" }
        val target = pm.getActivityInfo(ComponentName(packageName, info.targetActivity), flags)
        check(target.enabled && target.applicationInfo.enabled) { "Disabled alias target: ${info.targetActivity}" }
        val launchers = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(packageName), flags)
        check(launchers.any { it.activityInfo.name == componentName }) { "Alias has no MAIN/LAUNCHER filter: $componentName" }
        val entry = app.daiyu.android.domain.appearance.Task17IconCatalog.entries.firstOrNull {
            it.componentName(packageName) == componentName
        } ?: error("Alias is outside the icon catalog")
        val iconId = Task17IconResources.drawableId(contextForResources, entry)
        check(iconId != 0 && info.icon == iconId) { "Alias icon is missing or does not match catalog: $componentName" }
    }

    override fun snapshot(componentName: String): IconComponentSnapshot {
        val component = component(componentName)
        val explicit = fromPm(pm.getComponentEnabledSetting(component))
        val info = pm.getActivityInfo(component, PackageManager.MATCH_DISABLED_COMPONENTS)
        val enabled = info.applicationInfo.enabled && when (explicit) {
            IconComponentState.ENABLED -> true
            IconComponentState.DEFAULT -> info.enabled
            else -> false
        }
        return IconComponentSnapshot(explicit, enabled)
    }

    override fun setState(componentName: String, state: IconComponentState) {
        pm.setComponentEnabledSetting(component(componentName), when (state) {
            IconComponentState.DEFAULT -> PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
            IconComponentState.ENABLED -> PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            IconComponentState.DISABLED -> PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            IconComponentState.DISABLED_USER -> PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
            IconComponentState.DISABLED_UNTIL_USED -> PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED
        }, PackageManager.DONT_KILL_APP)
    }

    private fun fromPm(state: Int): IconComponentState = when (state) {
        PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> IconComponentState.DEFAULT
        PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> IconComponentState.ENABLED
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED -> IconComponentState.DISABLED
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER -> IconComponentState.DISABLED_USER
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED -> IconComponentState.DISABLED_UNTIL_USED
        else -> error("Unknown component state $state")
    }
}
