package app.daiyu.android

import android.content.Context
import app.daiyu.android.data.model.*

@Suppress("DEPRECATION")
class AppearanceSettingsPersistenceTest : android.test.AndroidTestCase() {
    fun testMorphSettingsPersistIndependentlyAndMissingKeysKeepRetainedDefaults() {
        val preferences = context.getSharedPreferences("appearance-projection-test", Context.MODE_PRIVATE)
        check(preferences.edit().clear().commit())
        try {
            val store = AppearanceSettingsStore(preferences)
            assertTrue(store.load().morphLoadingIndicator)
            assertFalse(store.load().morphLoadingBeforeResponseOnly)
            val saved = store.save(store.load().copy(morphLoadingIndicator = false,
                morphLoadingBeforeResponseOnly = true, messageTimestampsEnabled = true,
                themeMode = AppearanceThemeMode.DARK, monetEnabled = true,
                paletteStyle = AppearancePaletteStyle.EXPRESSIVE, accentColor = AppearanceAccentColor.RED))
            assertEquals(saved, AppearanceSettingsStore(preferences).load())
            val updated = store.save(saved.copy(morphLoadingIndicator = true))
            assertTrue(updated.morphLoadingBeforeResponseOnly)
            assertEquals(updated, AppearanceSettingsStore(preferences).load())
        } finally { check(preferences.edit().clear().commit()) }
    }
    fun testColorDefaultsResetOnlyTheirOwnPersistentKeys() {
        val preferences = context.getSharedPreferences("appearance-color-groups-test", Context.MODE_PRIVATE)
        check(preferences.edit().clear().commit())
        try {
            val store = AppearanceSettingsStore(preferences)
            val original = store.save(AppearanceSettings(paletteStyle = AppearancePaletteStyle.EXPRESSIVE,
                paletteSeedColor = 0xFF368A91.toInt(), primaryAccentColor = 0xFF956549.toInt(),
                secondaryAccentColor = 0xFF496695.toInt(), interfaceScale = .9f,
                messageTimestampsEnabled = true, pureBlackEnabled = true))
            assertEquals(original, AppearanceSettingsStore(preferences).load())
            val styleDefault = store.save(original.withColorStyle(AppearanceSettings()))
            assertFalse(preferences.contains("palette_seed"))
            assertEquals(original.primaryAccentColor, styleDefault.primaryAccentColor)
            assertEquals(original.secondaryAccentColor, styleDefault.secondaryAccentColor)
            assertEquals(original, styleDefault.withColorStyle(original))
            assertEquals(styleDefault, AppearanceSettingsStore(preferences).load())
            store.save(original)
            val accentDefault = store.save(original.withAccentColors(AppearanceSettings()))
            assertFalse(preferences.contains("primary_accent"))
            assertFalse(preferences.contains("secondary_accent"))
            assertTrue(preferences.contains("palette_seed"))
            assertEquals(original, accentDefault.withAccentColors(original))
            assertEquals(accentDefault, AppearanceSettingsStore(preferences).load())
        } finally { check(preferences.edit().clear().commit()) }
    }

    fun testAllColorStylesRoundTripWithoutChangingAccents() {
        val preferences = context.getSharedPreferences("appearance-color-styles-test", Context.MODE_PRIVATE)
        check(preferences.edit().clear().commit())
        try {
            val store = AppearanceSettingsStore(preferences)
            val original = AppearanceSettings(primaryAccentColor = 0xFF82529A.toInt(),
                secondaryAccentColor = 0xFF499488.toInt(), paletteSeedColor = 0xFF439965.toInt())
            for (style in AppearancePaletteStyle.entries) {
                val expected = original.copy(paletteStyle = style)
                assertEquals(expected, store.save(expected))
                assertEquals(expected, AppearanceSettingsStore(preferences).load())
            }
        } finally { check(preferences.edit().clear().commit()) }
    }

}
