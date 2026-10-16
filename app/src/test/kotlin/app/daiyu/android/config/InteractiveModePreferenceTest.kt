package app.daiyu.android.config

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class InteractiveModePreferenceTest {
    @Test fun absentOrUnavailablePreferenceIsOffAndReadingDoesNotWrite() {
        val storage = PreferencesFake(mapOf("execution_location" to "remote"))
        val preference = InteractiveModePreference(storage.preferences)
        assertFalse(preference.enabled)
        assertFalse(preference.snapshot().enabled)
        assertFalse(InteractiveModePreference.read(null))
        assertFalse(InteractiveModePreference(null).snapshot().enabled)
        assertEquals(0, storage.edits)
        assertEquals(mapOf("execution_location" to "remote"), storage.values)
        assertEquals(listOf(false, false), storage.readDefaults)
    }

    @Test fun explicitTrueAndFalseAreSavedAndNewInstancesReadThem() {
        val storage = PreferencesFake(mapOf("execution_location" to "local"))
        val preference = InteractiveModePreference(storage.preferences)
        preference.setEnabled(true)
        assertTrue(InteractiveModePreference(storage.preferences).enabled)
        preference.setEnabled(false)
        assertFalse(InteractiveModePreference(storage.preferences).enabled)
        assertEquals(false, storage.values[InteractiveModePreference.PREF_KEY])
        assertEquals("local", storage.values["execution_location"])
        assertEquals(2, storage.edits)
        assertEquals(2, storage.applies)
    }

    @Test fun corruptValueOrReadFailureIsOffWithoutRepairWrite() {
        val storage = PreferencesFake(mapOf(InteractiveModePreference.PREF_KEY to "true"))
        assertFalse(InteractiveModePreference(storage.preferences).enabled)
        storage.readFailure = IllegalStateException("unavailable")
        assertFalse(InteractiveModePreference(storage.preferences).snapshot().enabled)
        assertEquals(0, storage.edits)
        assertEquals("true", storage.values[InteractiveModePreference.PREF_KEY])
    }

    @Test fun eachRunCapturesExactlyOneReadAndKeepsItsValueAfterLaterChanges() {
        val storage = PreferencesFake()
        val preference = InteractiveModePreference(storage.preferences)
        val firstRun = preference.snapshot()
        assertEquals(1, storage.readDefaults.size)
        preference.setEnabled(true)
        assertFalse(firstRun.enabled)
        val secondRun = preference.snapshot()
        assertEquals(2, storage.readDefaults.size)
        preference.setEnabled(false)
        assertTrue(secondRun.enabled)
        assertFalse(preference.snapshot().enabled)
        assertFalse(firstRun.enabled)
        assertEquals(3, storage.readDefaults.size)
    }

    @Test fun nullStorageAcceptsExplicitWritesWithoutInventingInMemoryState() {
        val preference = InteractiveModePreference(null)
        preference.setEnabled(true)
        assertFalse(preference.enabled)
        preference.setEnabled(false)
        assertFalse(preference.snapshot().enabled)
    }

    /** Strict fake: unexpected calls fail so reads cannot silently write/migrate other settings. */
    private class PreferencesFake(initial: Map<String, Any> = emptyMap()) {
        val values = initial.toMutableMap()
        val readDefaults = mutableListOf<Boolean>()
        var readFailure: RuntimeException? = null
        var edits = 0
        var applies = 0
        val preferences: SharedPreferences = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getBoolean" -> {
                    assertEquals(InteractiveModePreference.PREF_KEY, args!![0])
                    val default = args[1] as Boolean
                    readDefaults += default
                    readFailure?.let { throw it }
                    (values[args[0] as String] ?: default) as Boolean
                }
                "edit" -> { edits++; editor() }
                else -> error("Unexpected preferences call: ${method.name}")
            }
        } as SharedPreferences

        private fun editor(): SharedPreferences.Editor {
            val pending = mutableMapOf<String, Boolean>()
            lateinit var editor: SharedPreferences.Editor
            editor = Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
                arrayOf(SharedPreferences.Editor::class.java)) { _, method, args ->
                when (method.name) {
                    "putBoolean" -> {
                        assertEquals(InteractiveModePreference.PREF_KEY, args!![0])
                        pending[args[0] as String] = args[1] as Boolean
                        editor
                    }
                    "apply" -> { applies++; values.putAll(pending); null }
                    else -> error("Unexpected editor call: ${method.name}")
                }
            } as SharedPreferences.Editor
            return editor
        }
    }
}
