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

    @Test fun userBubbleModesRoundTripAndUnknownModesKeepOriginalAppearance() {
        val preferences = Preferences()
        val store = AppearanceSettingsStore(preferences.value)
        assertEquals(UserBubbleMode.THEME, store.load().userBubbleMode)
        preferences.raw["user_bubble_mode"] = "future-mode"
        assertEquals(UserBubbleMode.THEME, store.load().userBubbleMode)
        for (mode in UserBubbleMode.entries) {
            val saved = store.save(custom.copy(userBubbleMode = mode, userBubbleColor = 0x00448866))
            assertEquals(0xFF448866.toInt(), saved.userBubbleColor)
            assertEquals(saved, AppearanceSettingsStore(preferences.value).load())
        }
    }

    @Test fun bubbleDefaultOnlyResetsItsOwnFieldsAndOtherResetsPreserveIt() {
        val value = custom.copy(userBubbleMode = UserBubbleMode.CUSTOM, userBubbleColor = 0xFF448866.toInt())
        assertEquals(custom, value.withUserBubbleColors(AppearanceSettings()))
        assertEquals(value.userBubbleColor, value.withColorStyle(AppearanceSettings()).userBubbleColor)
        assertEquals(value.userBubbleMode, value.withAccentColors(AppearanceSettings()).userBubbleMode)
        val preferences = Preferences()
        val store = AppearanceSettingsStore(preferences.value)
        store.save(value)
        store.save(value.withUserBubbleColors(AppearanceSettings()))
        assertFalse(preferences.raw.containsKey("user_bubble_color"))
        assertEquals(custom.primaryAccentColor, preferences.raw["primary_accent"])
        assertEquals(custom.paletteSeedColor, preferences.raw["palette_seed"])
    }

    @Test fun failedCommitKeepsConfirmedAppearanceAcrossNewStoresUntilSuccessfulRetry() {
        val preferences = Preferences()
        val store = AppearanceSettingsStore(preferences.value)
        val previous = store.save(custom)
        val draft = custom.copy(userBubbleMode = UserBubbleMode.CUSTOM, userBubbleColor = 0xFF448866.toInt())
        preferences.commitResult = false
        try { store.save(draft); fail("commit failure must reject the draft") } catch (_: IllegalStateException) { }
        assertEquals("custom", preferences.raw["user_bubble_mode"])
        repeat(2) { assertEquals(previous, AppearanceSettingsStore(preferences.value).load()) }
        preferences.commitResult = true
        assertEquals(draft, AppearanceSettingsStore(preferences.value).save(draft))
        assertEquals(draft, AppearanceSettingsStore(preferences.value).load())
    }

    @Test fun failedFirstSaveKeepsInitialAppearanceWithoutNeedingAPriorLoad() {
        val preferences = Preferences()
        preferences.commitResult = false
        try { AppearanceSettingsStore(preferences.value).save(custom) } catch (_: IllegalStateException) { }
        assertEquals(AppearanceSettings(), AppearanceSettingsStore(preferences.value).load())
    }

    @Test fun commitExceptionAndReadbackMismatchBothKeepConfirmedAppearance() {
        val preferences = Preferences()
        val store = AppearanceSettingsStore(preferences.value)
        val previous = store.save(custom)
        val draft = custom.copy(userBubbleMode = UserBubbleMode.OFF)
        preferences.commitFailure = IllegalStateException("disk error")
        try { store.save(draft); fail("commit exception must reject the draft") } catch (_: IllegalStateException) { }
        assertEquals(previous, AppearanceSettingsStore(preferences.value).load())
        preferences.commitFailure = null
        preferences.afterCommit = { preferences.raw["user_bubble_mode"] = "custom" }
        try { store.save(draft); fail("readback mismatch must reject the draft") } catch (failure: IllegalStateException) {
            assertEquals("外观设置回读不一致，请重新保存", failure.message)
        }
        assertEquals(previous, AppearanceSettingsStore(preferences.value).load())
        preferences.afterCommit = {}
        assertEquals(draft, store.save(draft))
        assertEquals(draft, AppearanceSettingsStore(preferences.value).load())
    }

    private class Preferences {
        val raw = mutableMapOf<String, Any?>()
        var commits = 0
        var commitResult = true
        var commitFailure: RuntimeException? = null
        var afterCommit: () -> Unit = {}
        val value = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)) { proxy, method, args ->
            when (method.name) {
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args!![0]
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
                        commitFailure?.let { throw it }
                        afterCommit()
                        commitResult
                    }
                    else -> error("unexpected editor call ${method.name}")
                }
            } as SharedPreferences.Editor
            return editor
        }
    }
}
