package app.daiyu.android.assistants

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class AssistantStoreTest {
    @Test fun fullProfileCasAcrossPreferenceOwnersRejectsStaleAndDeletedRows() {
        val preferences = PreferencesFake().preferences
        val first = SharedPreferencesAssistantStore(preferences)
        val second = SharedPreferencesAssistantStore(preferences)
        val original = first.read(AssistantPrompt.DEFAULT_ID)!!
        val changed = second.compareAndSet(original, original.copy(prompt = "external"))
        assertThrows(StaleAssistantProfileException::class.java) {
            first.compareAndSet(original, original.copy(memoryEnabled = false))
        }
        assertEquals(changed, first.read(original.id))
        first.create()
        second.delete(original.id)
        assertThrows(StaleAssistantProfileException::class.java) { first.compareAndSet(changed, changed.copy(name = "resurrection")) }
        assertNull(first.read(original.id))
    }

    @Test fun seedsOriginalDefaultAssistant() {
        val preferences = PreferencesFake().preferences
        val store = SharedPreferencesAssistantStore(preferences)
        assertEquals(AssistantPrompt.DEFAULT_ID, store.selectedId.value)
        val profile = store.list().single()
        assertEquals(profile, SharedPreferencesAssistantStore(preferences).list().single())
        assertEquals("代鱼", profile.name)
        assertEquals("", profile.prompt)
        assertEquals(AssistantDefaults.ENABLED_SKILL_IDS, profile.enabledSkillIds)
    }

    @Test fun saveReadUpdateAndSelectionSurviveReload() {
        val prefs = PreferencesFake()
        val store = SharedPreferencesAssistantStore(prefs.preferences)
        val profile = AssistantProfile("second", " 名称 ", "提示词\n\"完整\"", "avatar.png", 123L, false, listOf("skill"))
        assertEquals("名称", store.save(profile).name)
        store.select(profile.id)
        val restored = SharedPreferencesAssistantStore(prefs.preferences)
        assertEquals(profile.copy(name = "名称"), restored.read(profile.id))
        assertEquals(profile.id, restored.selectedId.value)
        restored.save(profile.copy(name = "更名", prompt = ""))
        assertEquals(2, restored.list().size)
        assertEquals("", restored.read(profile.id)?.prompt)
        assertNull(restored.read("unknown"))
    }

    @Test fun deleteActiveFallsBackToFirstAndCannotDeleteLast() {
        val store = SharedPreferencesAssistantStore(PreferencesFake().preferences)
        val second = store.create()
        store.select(second.id)
        store.delete(second.id)
        assertEquals(AssistantPrompt.DEFAULT_ID, store.selectedId.value)
        expectFailure { store.delete(AssistantPrompt.DEFAULT_ID) }
        assertEquals(1, store.list().size)
    }

    @Test fun duplicateCopiesProfileWithNewIdAndOriginalSuffix() {
        val store = SharedPreferencesAssistantStore(PreferencesFake().preferences)
        val source = store.read(AssistantPrompt.DEFAULT_ID)!!
        val copied = store.duplicate(source.id)
        assertNotEquals(source.id, copied.id)
        assertEquals(source.name + " 副本", copied.name)
        assertEquals(source.prompt, copied.prompt)
        assertEquals(source.enabledSkillIds, copied.enabledSkillIds)
        assertEquals(source.memoryEnabled, copied.memoryEnabled)
        assertEquals(source.id, store.selectedId.value)
    }

    @Test fun invalidIdsAndMissingSelectionDoNotMutateStore() {
        val store = SharedPreferencesAssistantStore(PreferencesFake().preferences)
        listOf("", "../outside", "a/b", "a\\b", "a".repeat(81)).forEach { id ->
            expectFailure { store.save(AssistantProfile(id, "", "")) }
        }
        expectFailure { store.select("missing") }
        assertEquals(1, store.list().size)
        assertEquals(AssistantPrompt.DEFAULT_ID, store.selectedId.value)
    }

    @Test fun failedPersistenceDoesNotPublishUncommittedState() {
        val prefs = PreferencesFake()
        val store = SharedPreferencesAssistantStore(prefs.preferences)
        prefs.failCommit = true
        expectFailure { store.create() }
        assertEquals(1, store.list().size)
        assertEquals(AssistantPrompt.DEFAULT_ID, store.selectedId.value)
    }

    @Test fun normalizesBlankNameAndPreservesSkillIds() {
        val store = SharedPreferencesAssistantStore(PreferencesFake().preferences)
        val saved = store.save(AssistantProfile("test", "  ", "", enabledSkillIds = listOf("a", "b")))
        assertEquals(AssistantPrompt.DEFAULT_NAME, saved.name)
        assertEquals(listOf("a", "b"), saved.enabledSkillIds)
    }

    @Test fun promptConstructionRetainsOriginalIdentityAndPersonality() {
        assertEquals(AssistantPrompt.EMPTY_PROMPT, AssistantPrompt.build(" ", " "))
        assertEquals(AssistantPrompt.identity("测试") + "\n\n人格设定：\n提示词", AssistantPrompt.build("测试", " 提示词 "))
    }

    private fun expectFailure(action: () -> Unit) {
        try { action(); fail("Expected failure") } catch (expected: IllegalArgumentException) { }
        catch (expected: IllegalStateException) { }
    }

    /** A JVM-only implementation of the exact SharedPreferences methods used by the store. */
    private class PreferencesFake {
        private val values = mutableMapOf<String, String?>()
        var failCommit = false
        val preferences: SharedPreferences = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getString" -> values[args!![0] as String] ?: args[1]
                "edit" -> editor()
                else -> error("Unexpected preferences call: ${method.name}")
            }
        } as SharedPreferences
        private fun editor(): SharedPreferences.Editor {
            val pending = mutableMapOf<String, String?>()
            lateinit var proxy: SharedPreferences.Editor
            proxy = Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
                arrayOf(SharedPreferences.Editor::class.java)) { _, method, args ->
                when (method.name) {
                    "putString" -> { pending[args!![0] as String] = args[1] as String?; proxy }
                    "commit" -> if (failCommit) false else { values.putAll(pending); true }
                    else -> error("Unexpected editor call: ${method.name}")
                }
            } as SharedPreferences.Editor
            return proxy
        }
    }
}
