package app.daiyu.android.data.model

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class AppearanceSettingsStoreTest {
    private val custom = AppearanceSettings(paletteStyle = AppearancePaletteStyle.EXPRESSIVE,
        paletteSeedColor = 0xFF439965.toInt(), primaryAccentColor = 0xFF996543.toInt(),
        secondaryAccentColor = 0xFF436599.toInt(), interfaceScale = .9f,
        pureBlackEnabled = true, messageTimestampsEnabled = true)

    @Test fun missingKeysKeepOriginalDefaultsAndAllStylesRoundTrip() {
        val preferences = Preferences()
        assertEquals(AppearanceSettings(), AppearanceSettingsStore(preferences.value).load())
        for (style in AppearancePaletteStyle.entries) {
            val expected = custom.copy(paletteStyle = style)
            assertEquals(expected, AppearanceSettingsStore(preferences.value).save(expected))
            assertEquals(expected, AppearanceSettingsStore(preferences.value).load())
        }
        assertEquals(9, preferences.commits)
    }

    @Test fun styleDefaultRemovesOnlySeedAndPreservesBothAccents() {
        val preferences = Preferences()
        val store = AppearanceSettingsStore(preferences.value)
        store.save(custom)
        val expected = custom.withColorStyle(AppearanceSettings())
        assertEquals(expected, store.save(expected))
        assertFalse(preferences.raw.containsKey("palette_seed"))
        assertEquals(custom.primaryAccentColor, preferences.raw["primary_accent"])
        assertEquals(custom.secondaryAccentColor, preferences.raw["secondary_accent"])
        assertEquals(expected, AppearanceSettingsStore(preferences.value).load())
    }

    @Test fun accentDefaultRemovesBothOverridesAndPreservesStyle() {
        val preferences = Preferences()
        val store = AppearanceSettingsStore(preferences.value)
        store.save(custom)
        val expected = custom.withAccentColors(AppearanceSettings())
        assertEquals(expected, store.save(expected))
        assertFalse(preferences.raw.containsKey("primary_accent"))
        assertFalse(preferences.raw.containsKey("secondary_accent"))
        assertEquals(custom.paletteSeedColor, preferences.raw["palette_seed"])
        assertEquals(expected, AppearanceSettingsStore(preferences.value).load())
    }

    @Test fun normalizationMakesCustomColorsOpaqueWithoutCreatingMissingOverrides() {
        val preferences = Preferences()
        val store = AppearanceSettingsStore(preferences.value)
        val saved = store.save(AppearanceSettings(paletteSeedColor = 0x00112233,
            primaryAccentColor = 0x00445566, secondaryAccentColor = 0x00778899))
        assertEquals(0xFF112233.toInt(), saved.paletteSeedColor)
        assertEquals(0xFF445566.toInt(), saved.primaryAccentColor)
        assertEquals(0xFF778899.toInt(), saved.secondaryAccentColor)
        assertEquals(saved, AppearanceSettingsStore(preferences.value).load())
        store.save(AppearanceSettings())
        assertEquals(AppearanceSettings(), store.load())
        assertFalse(preferences.raw.containsKey("palette_seed"))
        assertFalse(preferences.raw.containsKey("primary_accent"))
        assertFalse(preferences.raw.containsKey("secondary_accent"))
    }

    @Test fun failedCommitRejectsTheColorUpdateInsteadOfReturningItAsSaved() {
        val preferences = Preferences()
        preferences.commitResult = false
        try {
            AppearanceSettingsStore(preferences.value).save(custom)
            fail("failed preference commit must not return a saved appearance")
        } catch (failure: IllegalStateException) {
            assertEquals("外观设置保存失败", failure.message)
        }
        assertEquals(1, preferences.commits)
    }

    private class Preferences {
        val raw = mutableMapOf<String, Any?>()
        var commits = 0
        var commitResult = true
        val value = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "contains" -> raw.containsKey(args!![0])
                "getString", "getInt", "getFloat", "getBoolean" -> raw[args!![0]] ?: args[1]
                "edit" -> editor()
                else -> error("unexpected preference call ${method.name}")
            }
        } as SharedPreferences

        private fun editor(): SharedPreferences.Editor {
            val changes = mutableMapOf<String, Any?>()
            lateinit var editor: SharedPreferences.Editor
            editor = Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
                arrayOf(SharedPreferences.Editor::class.java)) { _, method, args ->
                when (method.name) {
                    "putString", "putInt", "putFloat", "putBoolean" -> {
                        changes[args!![0] as String] = args[1]; editor
                    }
                    "remove" -> { changes[args!![0] as String] = null; editor }
                    "commit" -> {
                        commits++
                        changes.forEach { (key, value) -> if (value == null) raw.remove(key) else raw[key] = value }
                        commitResult
                    }
                    else -> error("unexpected editor call ${method.name}")
                }
            } as SharedPreferences.Editor
            return editor
        }
    }
}
