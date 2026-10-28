package app.daiyu.android.data.model

import android.content.Context
import android.content.SharedPreferences
import java.util.WeakHashMap

/** The retained appearance preference file; does not read or migrate another app's data. */
internal class AppearanceSettingsStore(private val preferences: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE))

    fun load(): AppearanceSettings = synchronized(lock) {
        fenced[preferences] ?: read()
    }

    private fun read(): AppearanceSettings = AppearanceSettings(
        themeMode = AppearanceThemeMode.fromPersistedValue(preferences.getString("theme", null)),
        interfaceScale = preferences.getFloat("scale", DEFAULT_INTERFACE_SCALE),
        messageTimestampsEnabled = preferences.getBoolean("timestamps", false),
        pureBlackEnabled = preferences.getBoolean("pure_black", false),
        blurEnabled = preferences.getBoolean("blur", true),
        topBarBlurStyle = AppearanceTopBarBlurStyle.fromPersistedValue(preferences.getString("blur_style", null)),
        swipeDismissEnabled = preferences.getBoolean("swipe_dismiss", true),
        predictiveBackEnabled = preferences.getBoolean("predictive_back", false),
        iconLightColor = preferences.getInt("icon_light", AppearanceSettings.DEFAULT_ICON_LIGHT),
        iconDarkColor = preferences.getInt("icon_dark", AppearanceSettings.DEFAULT_ICON_DARK),
        iconCatColor = preferences.getInt("icon_cat", AppearanceSettings.DEFAULT_ICON_CAT),
    ).normalized()

    /** Caller only publishes the returned state after the complete synchronous commit succeeds. */
    fun save(value: AppearanceSettings): AppearanceSettings = synchronized(lock) {
        val normalized = value.normalized()
        // A failed synchronous commit may already have changed the process preference map.
        fenced.getOrPut(preferences) { read() }
        check(preferences.edit()
            .remove("monet")
            .remove("palette")
            .remove("accent")
            .remove("palette_seed")
            .remove("primary_accent")
            .remove("secondary_accent")
            .remove("user_bubble_mode")
            .remove("user_bubble_color")
            .remove("morph_loading")
            .remove("morph_before_response_only")
            .putString("theme", normalized.themeMode.persistedValue)
            .putFloat("scale", normalized.interfaceScale)
            .putBoolean("timestamps", normalized.messageTimestampsEnabled)
            .putBoolean("pure_black", normalized.pureBlackEnabled)
            .putBoolean("blur", normalized.blurEnabled)
            .putString("blur_style", normalized.topBarBlurStyle.persistedValue)
            .putBoolean("swipe_dismiss", normalized.swipeDismissEnabled)
            .putBoolean("predictive_back", normalized.predictiveBackEnabled)
            .putInt("icon_light", normalized.iconLightColor)
            .putInt("icon_dark", normalized.iconDarkColor)
            .putInt("icon_cat", normalized.iconCatColor)
            .commit()) { "外观设置保存失败" }
        check(read() == normalized) { "外观设置回读不一致，请重新保存" }
        fenced.remove(preferences)
        normalized
    }

    companion object {
        const val FILE_NAME = "appearance-settings"
        private val lock = Any()
        private val fenced = WeakHashMap<SharedPreferences, AppearanceSettings>()
    }
}
