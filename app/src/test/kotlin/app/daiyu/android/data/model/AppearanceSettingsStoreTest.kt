package app.daiyu.android.data.model

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class AppearanceSettingsStoreTest {
    private val retained = AppearanceSettings(themeMode = AppearanceThemeMode.DARK,
        interfaceScale = .9f, pureBlackEnabled = true, messageTimestampsEnabled = true,
        blurEnabled = false, topBarBlurStyle = AppearanceTopBarBlurStyle.PROGRESSIVE,
        swipeDismissEnabled = false, predictiveBackEnabled = true,
        iconLightColor = 0xFF123456.toInt(), iconDarkColor = 0xFF234567.toInt(), iconCatColor = 0xFF345678.toInt())
    private val retired = listOf("monet", "palette", "accent", "palette_seed", "primary_accent", "secondary_accent", "user_bubble_mode", "user_bubble_color", "morph_loading", "morph_before_response_only")

    @Test fun retainedDefaultsAndSettingsRoundTrip() {
        val preferences = Preferences()
        val store = AppearanceSettingsStore(preferences.value)
        assertEquals(AppearanceSettings(), store.load())
        assertEquals(retained, store.save(retained))
        assertEquals(retained, AppearanceSettingsStore(preferences.value).load())
        assertEquals(1, preferences.commits)
    }

    @Test fun oldColorAndLoadingKeysAreIgnoredWithoutWritingDuringLoad() {
        for (loading in listOf(false, true)) for (beforeOnly in listOf(false, true)) {
            val preferences = Preferences()
            retired.forEach { preferences.raw[it] = "retired-wrong-type" }
            preferences.raw["morph_loading"] = loading
            preferences.raw["morph_before_response_only"] = beforeOnly
            preferences.raw["pure_black"] = true
            preferences.raw["theme"] = "dark"
            val before = preferences.raw.toMap()
            assertEquals(AppearanceSettings(themeMode = AppearanceThemeMode.DARK, pureBlackEnabled = true),
                AppearanceSettingsStore(preferences.value).load())
            assertEquals(before, preferences.raw)
            assertEquals(0, preferences.commits)
        }
    }

    @Test fun confirmedSaveRemovesOnlyRetiredKeysAndKeepsUnrelatedValues() {
        val preferences = Preferences()
        retired.forEach { preferences.raw[it] = false }
        preferences.raw["unrelated"] = "keep"
        val store = AppearanceSettingsStore(preferences.value)
        assertEquals(retained, store.save(retained))
        retired.forEach { assertFalse("retired key $it", preferences.raw.containsKey(it)) }
        assertEquals("keep", preferences.raw["unrelated"])
        assertEquals(retained, AppearanceSettingsStore(preferences.value).load())
        assertEquals(1, preferences.commits)
    }

    @Test fun normalizationKeepsScaleLimitsAndOpaqueIconColors() {
        val store = AppearanceSettingsStore(Preferences().value)
        val saved = store.save(retained.copy(interfaceScale = 20f, iconCatColor = 0x00112233))
        assertEquals(MAX_INTERFACE_SCALE, saved.interfaceScale, 0f)
        assertEquals(0xFF112233.toInt(), saved.iconCatColor)
        assertEquals(DEFAULT_INTERFACE_SCALE, retained.copy(interfaceScale = Float.NaN).normalized().interfaceScale, 0f)
    }

    @Test fun failedCommitRejectsUpdateAndKeepsConfirmedStateAcrossStoresUntilRetry() {
        val preferences = Preferences()
        val store = AppearanceSettingsStore(preferences.value)
        val previous = store.save(retained)
        val draft = retained.copy(pureBlackEnabled = false)
        preferences.commitResult = false
        try { store.save(draft); fail("commit failure must reject the draft") } catch (failure: IllegalStateException) {
            assertEquals("外观设置保存失败", failure.message)
        }
        assertEquals(false, preferences.raw["pure_black"])
        repeat(2) { assertEquals(previous, AppearanceSettingsStore(preferences.value).load()) }
        preferences.commitResult = true
        assertEquals(draft, AppearanceSettingsStore(preferences.value).save(draft))
        assertEquals(draft, AppearanceSettingsStore(preferences.value).load())
    }

    @Test fun failedFirstSaveKeepsInitialAppearanceWithoutPriorLoad() {
        val preferences = Preferences()
        preferences.commitResult = false
        try { AppearanceSettingsStore(preferences.value).save(retained); fail("commit failure expected") }
        catch (failure: IllegalStateException) { assertEquals("外观设置保存失败", failure.message) }
        assertEquals(AppearanceSettings(), AppearanceSettingsStore(preferences.value).load())
    }

    @Test fun commitExceptionAndReadbackMismatchKeepConfirmedState() {
        val preferences = Preferences()
        val store = AppearanceSettingsStore(preferences.value)
        val previous = store.save(retained)
        val draft = retained.copy(pureBlackEnabled = false)
        preferences.commitFailure = IllegalStateException("disk error")
        try { store.save(draft); fail("commit exception expected") } catch (_: IllegalStateException) { }
        assertEquals(previous, AppearanceSettingsStore(preferences.value).load())
        preferences.commitFailure = null
        preferences.afterCommit = { preferences.raw["scale"] = 1f }
        try { store.save(draft); fail("readback mismatch expected") } catch (failure: IllegalStateException) {
            assertEquals("外观设置回读不一致，请重新保存", failure.message)
        }
        assertEquals(false, preferences.raw["pure_black"])
        assertEquals(1f, preferences.raw["scale"])
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
