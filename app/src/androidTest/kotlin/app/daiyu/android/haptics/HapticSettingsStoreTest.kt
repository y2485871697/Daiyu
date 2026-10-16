package app.daiyu.android.haptics

import android.content.Context
import android.content.SharedPreferences
import android.test.InstrumentationTestCase
import app.daiyu.android.domain.haptics.*
import java.lang.reflect.Proxy

/** Platform test SOURCE. Must not be reported as executed without an instrumentation run. */
@Suppress("DEPRECATION")
class HapticSettingsStoreTest : InstrumentationTestCase() {
    fun testLocalDefaultsRoundTripAndReopen() {
        val context = instrumentation.targetContext
        val name = "task17-haptic-test-${System.nanoTime()}"
        try {
            val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            val store = LocalHapticSettingsStore(prefs)
            assertEquals(HapticSettings(), store.read())
            val next = HapticSettings(touch = false, generation = true, background = false,
                backgroundReasoning = false, intensity = HapticIntensity.HIGH)
            assertTrue(store.write(next))
            assertEquals(next, LocalHapticSettingsStore(prefs).read())
            assertTrue(store.write(next.copy(touch = true)))
            assertEquals(HapticIntensity.HIGH, store.read().intensity)
        } finally { context.deleteSharedPreferences(name) }
    }

    fun testCommitFalseAndThrowNeverPublishSuccessEvenWhenMemoryAlreadyChanged() {
        for (throwOnCommit in listOf(false, true)) {
            val memory = mutableMapOf<String, Any?>()
            val prefs = failingPreferences(memory, throwOnCommit)
            val store = LocalHapticSettingsStore(prefs)
            var notifications = 0
            val observer = store.observe { notifications++ }
            assertFalse(store.write(HapticSettings(touch = false, intensity = HapticIntensity.LOW)))
            assertEquals(HapticSettings(), store.read())
            assertEquals(1, notifications)
            assertEquals(true, memory[LocalHapticSettingsStore.TOUCH])
            observer.close()
        }
    }

    fun testIntensityInvalidWireAndWrongBooleanTypesUseOriginalDefaults() {
        val context = instrumentation.targetContext
        val name = "task17-haptic-corrupt-${System.nanoTime()}"
        try {
            val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            assertTrue(prefs.edit().putString(LocalHapticSettingsStore.TOUCH, "wrong type")
                .putString(LocalHapticSettingsStore.INTENSITY, "unknown").commit())
            assertEquals(HapticSettings(), LocalHapticSettingsStore(prefs).read())
        } finally { context.deleteSharedPreferences(name) }
    }

    /** Android commit(false) can still change its process-local map; model precisely that case. */
    private fun failingPreferences(memory: MutableMap<String, Any?>, throws: Boolean): SharedPreferences {
        val pending = mutableMapOf<String, Any?>()
        lateinit var editor: SharedPreferences.Editor
        editor = Proxy.newProxyInstance(javaClass.classLoader,
            arrayOf(SharedPreferences.Editor::class.java)) { _, method, args ->
            when (method.name) {
                "putBoolean", "putString" -> { pending[args!![0] as String] = args[1]; editor }
                "commit" -> {
                    memory.putAll(pending)
                    pending.clear()
                    if (throws) throw IllegalStateException("disk unavailable")
                    false
                }
                else -> throw UnsupportedOperationException(method.name)
            }
        } as SharedPreferences.Editor
        return Proxy.newProxyInstance(javaClass.classLoader,
            arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "getBoolean", "getString" -> memory[args!![0] as String] ?: args[1]
                "edit" -> editor
                else -> throw UnsupportedOperationException(method.name)
            }
        } as SharedPreferences
    }
}
