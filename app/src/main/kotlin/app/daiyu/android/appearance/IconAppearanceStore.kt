package app.daiyu.android.appearance

import android.content.Context
import android.content.SharedPreferences
import app.daiyu.android.domain.appearance.IconAppearance
import app.daiyu.android.domain.appearance.IconThemeMode
import app.daiyu.android.domain.appearance.Task17IconCatalog
import java.util.WeakHashMap

/** Independent icon-only storage, not a migration of the reference app's whole datastore. */
class IconAppearanceStore internal constructor(
    private val appearance: SharedPreferences,
    private val launcher: SharedPreferences,
) {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences("task17_icon_appearance", Context.MODE_PRIVATE),
        context.applicationContext.getSharedPreferences("launcher_icon", Context.MODE_PRIVATE),
    )

    fun load(): IconAppearance = synchronized(lock) {
        check(fenced[appearance] != true) { "图标偏好写入结果未确认，请重新保存" }
        read()
    }

    private fun read(): IconAppearance = IconAppearance(
        themeMode = IconThemeMode.fromPersistedValue(appearance.getString("theme_mode", null)),
        iconLightColor = appearance.getInt("icon_light_color", IconAppearance.DEFAULT_ICON_LIGHT),
        iconDarkColor = appearance.getInt("icon_dark_color", IconAppearance.DEFAULT_ICON_DARK),
        iconCatColor = appearance.getInt("icon_cat_color", IconAppearance.DEFAULT_ICON_CAT),
    ).normalized()

    fun save(value: IconAppearance): Unit = synchronized(lock) {
        val settings = value.normalized()
        // commit(false) may already update the process map. New store instances share this fence.
        fenced[appearance] = true
        check(appearance.edit().putString("theme_mode", settings.themeMode.persistedValue)
            .putInt("icon_light_color", settings.iconLightColor)
            .putInt("icon_dark_color", settings.iconDarkColor)
            .putInt("icon_cat_color", settings.iconCatColor).commit()) { "Icon preferences could not be persisted" }
        check(read() == settings) { "图标偏好回读不一致，请重新保存" }
        fenced.remove(appearance)
    }

    // Preserve the original LauncherIconSync current-resource contract and key.
    fun recordCurrent(entry: Task17IconCatalog.Entry) {
        check(launcher.edit().putString("res", entry.sourceResourceName).commit()) { "Current icon could not be persisted" }
    }

    fun current(): Task17IconCatalog.Entry? = Task17IconCatalog.fromResourceName(launcher.getString("res", null))

    companion object {
        private val lock = Any()
        private val fenced = WeakHashMap<SharedPreferences, Boolean>()
    }
}
