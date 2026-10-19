package app.daiyu.android

import androidx.compose.ui.test.*
import app.daiyu.android.model.reconnect.*

/** Real catalog -> Activity route -> editor, using the Controller's exact store. Not run yet. */
class ReconnectSettingsActivityTest : WiredActivityTestCase() {
    private lateinit var store: ReconnectSettingsStore
    private lateinit var original: ReconnectSettings

    override fun setUp() {
        super.setUp()
        store = fixture.controller.reconnectSettings
        original = store.snapshot()
        store.save(ReconnectSettings(ReconnectDurationPreset.NONE))
    }

    override fun tearDown() {
        try { super.tearDown() } finally { store.save(original) }
    }

    private fun enter() {
        openSettings()
        reveal("settings-row:错误重连", "设置").performClick()
        node("page:错误重连").assertExists()
        ui.waitUntil(timeoutMillis = 5_000) {
            ui.onAllNodes(hasTestTag("reconnect-duration:none") and isEnabled())
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun owner(): ReconnectSettingsSaveOwner {
        lateinit var result: ReconnectSettingsSaveOwner
        mainThread {
            val state = MainActivity::class.java.getDeclaredField("reconnectSettingsPageOwner\$delegate")
                .apply { isAccessible = true }.get(activity) as androidx.compose.runtime.State<*>
            result = state.value as ReconnectSettingsSaveOwner
        }
        return result
    }

    fun testSelectingPresetImmediatelySavesOnlyIndependentPreference() {
        val before = fixture.database.exportBackup()
        enter()
        reveal("reconnect-duration:window_5m", "错误重连").performClick()
        ui.waitUntil(timeoutMillis = 5_000) {
            store.snapshot().duration == ReconnectDurationPreset.WINDOW_5M
        }
        assertEquals(before, fixture.database.exportBackup())
        assertEquals(0, fixture.opens.get())
        assertNull(fixture.controller.active)
        node("reconnect-background").assertDoesNotExist()
        assertFalse(store.snapshot().responsesBackgroundEnabled)
    }

    fun testLeavingAndReenteringRevokesRatherThanReusesSaveOwner() {
        enter()
        val old = owner()
        assertTrue(old.isActive)
        backToPage(activity, "page:设置")
        node("page:设置").assertExists()
        assertFalse(old.isActive)
        reveal("settings-row:错误重连", "设置").performClick()
        val replacement = owner()
        assertNotSame(old, replacement)
        assertTrue(replacement.isActive)
        try {
            store.save(ReconnectSettings(ReconnectDurationPreset.CONTINUOUS), old)
            fail("Revoked visit must not save through the replacement visit")
        } catch (_: IllegalStateException) { }
        assertEquals(ReconnectDurationPreset.NONE, store.snapshot().duration)
        assertTrue(replacement.isActive)
    }

    fun testRecreationSynchronouslyRevokesOriginalVisit() {
        enter()
        val old = owner()
        activity = recreateActivity(activity)
        assertFalse(old.isActive)
        node("chat-draft").assertExists()
        assertEquals(ReconnectDurationPreset.NONE, store.snapshot().duration)
        assertEquals(0, fixture.opens.get())
    }

    fun testUnrelatedNavigationAlsoRevokesBeforeComposeDisposal() {
        enter()
        val old = owner()
        mainThread {
            // Exercise the shared route setter, not the editor's own BackHandler.
            MainActivity::class.java.getDeclaredMethod("returnToChat").apply { isAccessible = true }
                .invoke(activity)
            assertFalse(old.isActive)
        }
        node("chat-draft").assertExists()
        assertEquals(0, fixture.opens.get())
    }
}
