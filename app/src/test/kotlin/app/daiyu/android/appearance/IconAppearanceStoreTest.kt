package app.daiyu.android.appearance

import android.content.SharedPreferences
import app.daiyu.android.domain.appearance.IconAppearance
import app.daiyu.android.domain.appearance.IconThemeMode
import app.daiyu.android.domain.appearance.Task17IconCatalog
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

class IconAppearanceStoreTest {
    private val desired = IconAppearance(IconThemeMode.DARK, 0x00F27A1A, 0x001C1C1E, 0x007B61FF)

    @Test fun defaultsAndSuccessfulSaveAreSharedWithoutTouchingCurrentLauncher() {
        val appearance = Preferences(); val launcher = Preferences()
        val first = IconAppearanceStore(appearance.value, launcher.value)
        val second = IconAppearanceStore(appearance.value, launcher.value)
        assertEquals(IconAppearance(), first.load())
        first.save(desired)
        assertEquals(desired.normalized(), second.load())
        assertEquals(0, launcher.commits)
        first.recordCurrent(Task17IconCatalog.defaultEntry)
        assertEquals(Task17IconCatalog.defaultEntry, second.current())
        assertEquals(desired.normalized(), first.load())
    }

    @Test fun failedCommitCannotBeConsumedByExistingOrRecreatedStore() {
        val appearance = Preferences(); val launcher = Preferences()
        val first = IconAppearanceStore(appearance.value, launcher.value)
        val second = IconAppearanceStore(appearance.value, launcher.value)
        appearance.result = false
        rejected { first.save(desired) }
        assertEquals("dark", appearance.raw["theme_mode"]) // Failure already changed the process map.
        rejected { first.load() }; rejected { second.load() }
        rejected { IconAppearanceStore(appearance.value, launcher.value).load() }
        assertEquals(1, appearance.commits)
        assertEquals(0, launcher.commits)
        appearance.result = true
        second.save(IconAppearance())
        assertEquals(IconAppearance(), first.load())
    }

    @Test fun editAndCommitExceptionsKeepTheFenceUntilExplicitConfirmedSave() {
        for (duringEdit in listOf(false, true)) {
            val appearance = Preferences(); val launcher = Preferences()
            val store = IconAppearanceStore(appearance.value, launcher.value)
            appearance.failEdit = duringEdit; appearance.failCommit = !duringEdit
            rejected { store.save(desired) }
            rejected { IconAppearanceStore(appearance.value, launcher.value).load() }
            appearance.failEdit = false; appearance.failCommit = false
            store.save(desired)
            assertEquals(desired.normalized(), store.load())
        }
    }

    @Test fun successfulCommitWithWrongReadbackDoesNotClearFence() {
        val appearance = Preferences(); val launcher = Preferences()
        val store = IconAppearanceStore(appearance.value, launcher.value)
        appearance.afterCommit = { appearance.raw["theme_mode"] = "light" }
        rejected { store.save(desired) }
        rejected { store.load() }
        appearance.afterCommit = {}
        store.save(desired)
        assertEquals(desired.normalized(), store.load())
    }

    @Test fun malformedPreferencesCanBeRepairedWithoutLoadingThemFirst() {
        val appearance = Preferences(); val launcher = Preferences()
        appearance.raw["icon_cat_color"] = "wrong type"
        val store = IconAppearanceStore(appearance.value, launcher.value)
        try { store.load(); fail("type mismatch") } catch (_: ClassCastException) { }
        store.save(IconAppearance())
        assertEquals(IconAppearance(), store.load())
    }

    @Test fun failedNamespaceDoesNotFenceAnotherPreferencesFile() {
        val bad = Preferences(); val good = Preferences(); val launcher = Preferences()
        bad.result = false
        rejected { IconAppearanceStore(bad.value, launcher.value).save(desired) }
        val independent = IconAppearanceStore(good.value, launcher.value)
        assertEquals(IconAppearance(), independent.load())
        independent.save(desired)
        assertEquals(desired.normalized(), independent.load())
        rejected { IconAppearanceStore(bad.value, launcher.value).load() }
    }

    private fun rejected(block: () -> Unit) {
        try { block(); fail("unconfirmed preferences must stay fenced") } catch (_: IllegalStateException) { }
    }

    private class Preferences {
        val raw = mutableMapOf<String, Any?>()
        var result = true
        var commits = 0
        var failEdit = false
        var failCommit = false
        var afterCommit: () -> Unit = {}
        val value: SharedPreferences = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)) { proxy, method, args ->
            when (method.name) {
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args!![0]
                "getString" -> raw[args!![0]]?.let { it as String } ?: args[1]
                "getInt" -> raw[args!![0]]?.let { it as Int } ?: args[1]
                "edit" -> { check(!failEdit); editor() }
                else -> error("unexpected preferences call ${method.name}")
            }
        } as SharedPreferences
        private fun editor(): SharedPreferences.Editor {
            val pending = mutableMapOf<String, Any?>()
            lateinit var editor: SharedPreferences.Editor
            editor = Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
                arrayOf(SharedPreferences.Editor::class.java)) { _, method, args ->
                when (method.name) {
                    "putString", "putInt" -> { pending[args!![0] as String] = args[1]; editor }
                    "commit" -> { commits++; raw.putAll(pending); check(!failCommit); afterCommit(); result }
                    else -> error("unexpected editor call ${method.name}")
                }
            } as SharedPreferences.Editor
            return editor
        }
    }
}
