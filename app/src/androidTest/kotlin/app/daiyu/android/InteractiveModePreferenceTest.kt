package app.daiyu.android

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Looper
import android.test.InstrumentationTestCase
import app.daiyu.android.config.InteractiveModePreference
import java.io.File
import java.util.UUID

/** Real SharedPreferences disk writes and main-thread observer lifecycle; no model/run is started. */
@Suppress("DEPRECATION")
class InteractiveModePreferenceTest : InstrumentationTestCase() {
    private lateinit var name: String
    private lateinit var preferences: SharedPreferences

    override fun setUp() {
        super.setUp()
        name = "p23-interactive-${UUID.randomUUID()}"
        preferences = instrumentation.targetContext.getSharedPreferences(name, Context.MODE_PRIVATE)
    }

    override fun tearDown() {
        try { instrumentation.targetContext.deleteSharedPreferences(name) }
        finally { super.tearDown() }
    }

    fun testExplicitChoicesPersistAndCapturedRunsDoNotChange() {
        val preference = InteractiveModePreference(preferences)
        val firstRun = preference.snapshot()
        assertFalse(firstRun.enabled)
        assertFalse(preferences.contains(InteractiveModePreference.PREF_KEY))
        preferences.edit().putString("unrelated", "untouched").commit()

        preference.setEnabled(true)
        val secondRun = preference.snapshot()
        assertTrue(secondRun.enabled)
        assertTrue(InteractiveModePreference(preferences).enabled)
        assertOnDisk(true)
        preference.setEnabled(false)
        assertFalse(InteractiveModePreference(preferences).enabled)
        assertOnDisk(false)
        assertFalse(firstRun.enabled)
        assertTrue(secondRun.enabled)
        assertFalse(preference.snapshot().enabled)
        assertEquals("untouched", preferences.getString("unrelated", null))
    }

    private fun assertOnDisk(value: Boolean) {
        // Test-only fence: await preceding apply() writes without changing the saved choice.
        assertTrue(preferences.edit().commit())
        val xml = File(instrumentation.targetContext.applicationInfo.dataDir,
            "shared_prefs/$name.xml").readText()
        assertTrue(xml.contains("<boolean name=\"${InteractiveModePreference.PREF_KEY}\" value=\"$value\""))
    }

    fun testMainObserverGetsInitialRelevantAndClearChangesUntilClosed() {
        val preference = InteractiveModePreference(preferences)
        val events = mutableListOf<Pair<Boolean, Boolean>>()
        var subscription: AutoCloseable? = null
        // Android introduced clear()'s null-key notification in R (the app targets R or newer).
        val clearedEvents = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            listOf(false to true, true to true, false to true)
        else listOf(false to true, true to true)
        try {
            onMain {
                subscription = preference.observe { events += it to (Looper.myLooper() == Looper.getMainLooper()) }
                assertEquals(listOf(false to true), events)
                preferences.edit().putString("unrelated", "x").apply()
                assertEquals(listOf(false to true), events)
            }
            // The instrumentation thread is not the main thread. Delivery must hop to main.
            preference.setEnabled(true)
            instrumentation.waitForIdleSync()
            onMain {
                assertEquals(listOf(false to true, true to true), events)
                preferences.edit().clear().apply()
                assertEquals(clearedEvents, events)
                subscription!!.close()
                subscription!!.close()
                preference.setEnabled(true)
            }
            instrumentation.waitForIdleSync()
            onMain { assertEquals(clearedEvents, events) }
        } finally { subscription?.close() }
    }

    fun testClosingBeforeQueuedDeliverySuppressesInitialAndChangeCallbacks() {
        val preference = InteractiveModePreference(preferences)
        val events = mutableListOf<Boolean>()
        onMain {
            // Keep main busy until worker has queued the initial delivery, a change and close.
            var workerFailure: Throwable? = null
            val worker = Thread {
                try {
                    val subscription = preference.observe { events += it }
                    try { preference.setEnabled(true) }
                    finally { subscription.close(); subscription.close() }
                } catch (failure: Throwable) { workerFailure = failure }
            }
            worker.start()
            worker.join(5_000)
            assertFalse("worker must not need a synchronous main-thread delivery", worker.isAlive)
            workerFailure?.let { throw it }
        }
        instrumentation.waitForIdleSync()
        onMain { assertTrue(events.isEmpty()) }
    }

    fun testBackgroundSubscriptionInitialValueIsDeliveredOnMain() {
        val events = mutableListOf<Pair<Boolean, Boolean>>()
        val subscription = InteractiveModePreference(null).observe {
            events += it to (Looper.myLooper() == Looper.getMainLooper())
        }
        try {
            instrumentation.waitForIdleSync()
            onMain { assertEquals(listOf(false to true), events) }
        } finally { subscription.close() }
    }

    private fun onMain(action: () -> Unit) {
        var failure: Throwable? = null
        instrumentation.runOnMainSync { try { action() } catch (t: Throwable) { failure = t } }
        failure?.let { throw it }
    }
}
