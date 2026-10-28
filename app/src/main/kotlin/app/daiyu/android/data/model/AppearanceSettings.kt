package app.daiyu.android.data.model

const val MIN_INTERFACE_SCALE = 0.8f
const val MAX_INTERFACE_SCALE = 1.1f
const val DEFAULT_INTERFACE_SCALE = 1f

data class AppearanceSettings(
    val themeMode: AppearanceThemeMode = AppearanceThemeMode.LIGHT,
    val pureBlackEnabled: Boolean = false,
    val blurEnabled: Boolean = true,
    val topBarBlurStyle: AppearanceTopBarBlurStyle = AppearanceTopBarBlurStyle.GAUSSIAN,
    val swipeDismissEnabled: Boolean = true,
    val predictiveBackEnabled: Boolean = false,
    val interfaceScale: Float = DEFAULT_INTERFACE_SCALE,
    val messageTimestampsEnabled: Boolean = false,
    val iconLightColor: Int = DEFAULT_ICON_LIGHT,
    val iconDarkColor: Int = DEFAULT_ICON_DARK,
    val iconCatColor: Int = DEFAULT_ICON_CAT,
) {
    fun normalized(): AppearanceSettings = copy(
        interfaceScale = normalizeInterfaceScale(interfaceScale),
        iconLightColor = iconLightColor or 0xFF000000.toInt(),
        iconDarkColor = iconDarkColor or 0xFF000000.toInt(),
        iconCatColor = iconCatColor or 0xFF000000.toInt(),
    )

    companion object {
        const val DEFAULT_ICON_LIGHT = 0xFFFFFFFF.toInt()
        const val DEFAULT_ICON_DARK = 0xFF1C1C1E.toInt()
        const val DEFAULT_ICON_CAT = 0xFF2491FF.toInt()
    }
}

enum class AppearanceThemeMode(val persistedValue: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark");

    companion object {
        fun fromPersistedValue(value: String?): AppearanceThemeMode =
            entries.firstOrNull { it.persistedValue == value } ?: LIGHT
    }
}

enum class AppearanceTopBarBlurStyle(val persistedValue: String) {
    GAUSSIAN("gaussian"),
    PROGRESSIVE("progressive");

    companion object {
        fun fromPersistedValue(value: String?): AppearanceTopBarBlurStyle =
            entries.firstOrNull { it.persistedValue == value } ?: GAUSSIAN
    }
}

fun normalizeInterfaceScale(value: Float): Float =
    if (value.isFinite()) {
        value.coerceIn(MIN_INTERFACE_SCALE, MAX_INTERFACE_SCALE)
    } else {
        DEFAULT_INTERFACE_SCALE
    }
