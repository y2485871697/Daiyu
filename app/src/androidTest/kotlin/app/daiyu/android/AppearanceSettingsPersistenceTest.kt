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
                themeMode = AppearanceThemeMode.DARK, paletteStyle = AppearancePaletteStyle.EXPRESSIVE))
            assertEquals(saved, AppearanceSettingsStore(preferences).load())
            val updated = store.save(saved.copy(morphLoadingIndicator = true))
            assertTrue(updated.morphLoadingBeforeResponseOnly)
            assertEquals(updated, AppearanceSettingsStore(preferences).load())
        } finally { check(preferences.edit().clear().commit()) }
    }
}
