package app.daiyu.android.domain.appearance

/** Icon-only subset of the user's AppearanceSettings (source 3196270). */
data class IconAppearance(
    val themeMode: IconThemeMode = IconThemeMode.LIGHT,
    val iconLightColor: Int = DEFAULT_ICON_LIGHT,
    val iconDarkColor: Int = DEFAULT_ICON_DARK,
    val iconCatColor: Int = DEFAULT_ICON_CAT,
) {
    fun normalized(): IconAppearance = copy(
        iconLightColor = iconLightColor or 0xFF000000.toInt(),
        iconDarkColor = iconDarkColor or 0xFF000000.toInt(),
        iconCatColor = iconCatColor or 0xFF000000.toInt(),
    )

    fun selectedIcon(systemNight: Boolean): Task17IconCatalog.Entry {
        val dark = when (themeMode) {
            IconThemeMode.LIGHT -> false
            IconThemeMode.DARK -> true
            IconThemeMode.SYSTEM -> systemNight
        }
        return Task17IconCatalog.entry(if (dark) iconDarkColor else iconLightColor, iconCatColor)
    }

    companion object {
        const val DEFAULT_ICON_LIGHT: Int = 0xFFFFFFFF.toInt()
        const val DEFAULT_ICON_DARK: Int = 0xFF1C1C1E.toInt()
        const val DEFAULT_ICON_CAT: Int = 0xFF2491FF.toInt()
    }
}

enum class IconThemeMode(val persistedValue: String) {
    SYSTEM("system"), LIGHT("light"), DARK("dark");
    companion object {
        fun fromPersistedValue(value: String?): IconThemeMode =
            entries.firstOrNull { it.persistedValue == value } ?: LIGHT
    }
}
