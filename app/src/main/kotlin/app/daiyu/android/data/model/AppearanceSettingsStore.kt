package app.daiyu.android.data.model

import android.content.Context
import android.content.SharedPreferences

/** The retained appearance preference file; does not read or migrate another app's data. */
internal class AppearanceSettingsStore(private val preferences: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE))

    fun load(): AppearanceSettings = AppearanceSettings(
        themeMode = AppearanceThemeMode.fromPersistedValue(preferences.getString("theme", null)),
        interfaceScale = preferences.getFloat("scale", DEFAULT_INTERFACE_SCALE),
        messageTimestampsEnabled = preferences.getBoolean("timestamps", false),
        monetEnabled = preferences.getBoolean("monet", false),
        pureBlackEnabled = preferences.getBoolean("pure_black", false),
        paletteStyle = AppearancePaletteStyle.fromPersistedValue(preferences.getString("palette", null)),
        accentColor = AppearanceAccentColor.fromPersistedValue(preferences.getString("accent", null)),
        blurEnabled = preferences.getBoolean("blur", true),
        topBarBlurStyle = AppearanceTopBarBlurStyle.fromPersistedValue(preferences.getString("blur_style", null)),
        morphLoadingIndicator = preferences.getBoolean("morph_loading", true),
        morphLoadingBeforeResponseOnly = preferences.getBoolean("morph_before_response_only", false),
        swipeDismissEnabled = preferences.getBoolean("swipe_dismiss", true),
        predictiveBackEnabled = preferences.getBoolean("predictive_back", false),
        iconLightColor = preferences.getInt("icon_light", AppearanceSettings.DEFAULT_ICON_LIGHT),
        iconDarkColor = preferences.getInt("icon_dark", AppearanceSettings.DEFAULT_ICON_DARK),
        iconCatColor = preferences.getInt("icon_cat", AppearanceSettings.DEFAULT_ICON_CAT),
    ).normalized()

    /** Caller only publishes the returned state after the complete synchronous commit succeeds. */
    fun save(value: AppearanceSettings): AppearanceSettings {
        val normalized = value.normalized()
        check(preferences.edit()
            .putString("theme", normalized.themeMode.persistedValue)
            .putFloat("scale", normalized.interfaceScale)
            .putBoolean("timestamps", normalized.messageTimestampsEnabled)
            .putBoolean("monet", normalized.monetEnabled)
            .putBoolean("pure_black", normalized.pureBlackEnabled)
            .putString("palette", normalized.paletteStyle.persistedValue)
            .putString("accent", normalized.accentColor.persistedValue)
            .putBoolean("blur", normalized.blurEnabled)
            .putString("blur_style", normalized.topBarBlurStyle.persistedValue)
            .putBoolean("morph_loading", normalized.morphLoadingIndicator)
            .putBoolean("morph_before_response_only", normalized.morphLoadingBeforeResponseOnly)
            .putBoolean("swipe_dismiss", normalized.swipeDismissEnabled)
            .putBoolean("predictive_back", normalized.predictiveBackEnabled)
            .putInt("icon_light", normalized.iconLightColor)
            .putInt("icon_dark", normalized.iconDarkColor)
            .putInt("icon_cat", normalized.iconCatColor)
            .commit()) { "外观设置保存失败" }
        return normalized
    }

    companion object { const val FILE_NAME = "appearance-settings" }
}
