package app.daiyu.android.appearance

import android.content.Context
import app.daiyu.android.domain.appearance.IconAppearance
import app.daiyu.android.domain.appearance.IconThemeMode
import app.daiyu.android.domain.appearance.Task17IconCatalog

/** Independent icon-only storage, not a migration of the reference app's whole datastore. */
class IconAppearanceStore(context: Context) {
    private val appearance = context.applicationContext.getSharedPreferences("task17_icon_appearance", Context.MODE_PRIVATE)
    // Preserve the original LauncherIconSync current-resource contract and key.
    private val launcher = context.applicationContext.getSharedPreferences("launcher_icon", Context.MODE_PRIVATE)

    fun load(): IconAppearance = IconAppearance(
        themeMode = IconThemeMode.fromPersistedValue(appearance.getString("theme_mode", null)),
        iconLightColor = appearance.getInt("icon_light_color", IconAppearance.DEFAULT_ICON_LIGHT),
        iconDarkColor = appearance.getInt("icon_dark_color", IconAppearance.DEFAULT_ICON_DARK),
        iconCatColor = appearance.getInt("icon_cat_color", IconAppearance.DEFAULT_ICON_CAT),
    ).normalized()

    fun save(value: IconAppearance) {
        val settings = value.normalized()
        check(appearance.edit().putString("theme_mode", settings.themeMode.persistedValue)
            .putInt("icon_light_color", settings.iconLightColor)
            .putInt("icon_dark_color", settings.iconDarkColor)
            .putInt("icon_cat_color", settings.iconCatColor).commit()) { "Icon preferences could not be persisted" }
    }

    fun recordCurrent(entry: Task17IconCatalog.Entry) {
        check(launcher.edit().putString("res", entry.sourceResourceName).commit()) { "Current icon could not be persisted" }
    }

    fun current(): Task17IconCatalog.Entry? = Task17IconCatalog.fromResourceName(launcher.getString("res", null))
}
