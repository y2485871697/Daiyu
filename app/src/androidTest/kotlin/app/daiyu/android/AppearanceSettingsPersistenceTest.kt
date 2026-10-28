package app.daiyu.android

import android.content.Context
import app.daiyu.android.data.model.*

@Suppress("DEPRECATION")
class AppearanceSettingsPersistenceTest : android.test.AndroidTestCase() {
    fun testRetiredPreferencesAreIgnoredAndPrunedWithoutLosingRetainedSettings() {
        val preferences = context.getSharedPreferences("appearance-retired-options-test", Context.MODE_PRIVATE)
        check(preferences.edit().clear().putBoolean("morph_loading", false)
            .putBoolean("morph_before_response_only", true).putInt("palette_seed", 0xFF239D78.toInt())
            .putInt("primary_accent", 0xFF229944.toInt()).putInt("secondary_accent", 0xFFAA3344.toInt())
            .putString("palette", "vibrant").putString("accent", "red").putBoolean("monet", true)
            .putString("user_bubble_mode", "off").putInt("user_bubble_color", 0xFF112233.toInt())
            .putString("unrelated", "keep").putBoolean("pure_black", true).putString("theme", "dark").commit())
        try {
            val store = AppearanceSettingsStore(preferences)
            assertEquals(AppearanceSettings(themeMode = AppearanceThemeMode.DARK, pureBlackEnabled = true), store.load())
            assertTrue(preferences.contains("morph_loading")) // Loading performs no write.
            val expected = store.load().copy(messageTimestampsEnabled = true, interfaceScale = .9f,
                blurEnabled = false, predictiveBackEnabled = true, iconCatColor = 0xFF498273.toInt())
            assertEquals(expected, store.save(expected))
            assertEquals(expected, AppearanceSettingsStore(preferences).load())
            for (key in listOf("monet", "palette", "accent", "palette_seed", "primary_accent", "secondary_accent", "user_bubble_mode", "user_bubble_color", "morph_loading", "morph_before_response_only")) {
                assertFalse(preferences.contains(key))
            }
            assertEquals("keep", preferences.getString("unrelated", null))
        } finally { check(preferences.edit().clear().commit()) }
    }
}
