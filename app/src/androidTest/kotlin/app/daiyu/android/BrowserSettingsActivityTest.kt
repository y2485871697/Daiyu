package app.daiyu.android

import androidx.compose.ui.test.*
import app.daiyu.android.browser.BrowserSettings
import app.daiyu.android.browser.BrowserSettingsSaveOwner
import app.daiyu.android.browser.BrowserSettingsStore
import app.daiyu.android.browser.session.BrowserAccess

class BrowserSettingsActivityTest : WiredActivityTestCase() {
    override val engineeringUi = true
    private lateinit var store: BrowserSettingsStore
    override fun createFixture(): RunControlFixture = super.createFixture().also {
        store = it.controller.browserSettings
        store.save(BrowserSettings(BrowserAccess.DISABLED))
    }

    private fun enterFromSettings() {
        openSettings()
        reveal("settings-row:工具", "设置").performClick()
        reveal("settings-row:浏览器", "工具").performClick()
        node("page:浏览器").assertExists()
        reveal("browser-saved", "浏览器").assertExists()
        ui.waitUntil(timeoutMillis = 5_000) {
            ui.onAllNodes(hasTestTag("browser-saved") and
                hasAnyDescendant(hasText("关闭（DISABLED）", substring = true)), useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun currentOwner(): BrowserSettingsSaveOwner {
        lateinit var result: BrowserSettingsSaveOwner
        mainThread {
            result = MainActivity::class.java.getDeclaredField("browserSettingsPageOwner")
                .apply { isAccessible = true }.get(activity) as BrowserSettingsSaveOwner
        }
        return result
    }

    fun testSettingsEntryDoesNotStartModelOrWriteConversation() {
        val before = fixture.database.exportBackup()
        enterFromSettings()
        assertEquals(BrowserAccess.DISABLED, store.snapshot().access)
        assertEquals(before, fixture.database.exportBackup())
        assertNull(fixture.controller.active)
        assertEquals(0, fixture.opens.get())
    }

    fun testPageSaveIsTheSameCeilingReadByController() {
        enterFromSettings()
        reveal("browser-access:READ_ONLY", "浏览器").performClick()
        reveal("browser-save", "浏览器").performClick()
        ui.waitUntil(5_000) { fixture.controller.browserSettings.snapshot().access == BrowserAccess.READ_ONLY }
        assertSame(store, fixture.controller.browserSettings)
        assertEquals(BrowserAccess.READ_ONLY, store.snapshot().access)
        assertEquals(0, fixture.opens.get())
    }

    fun testLeavingAndReenteringRevokesOldSaveOwnerWithoutBorrowingNewOwner() {
        enterFromSettings()
        val old = currentOwner()
        assertTrue(old.isActive)
        backToPage(activity, "page:设置")
        node("page:设置").assertExists()
        assertFalse(old.isActive)
        reveal("settings-row:工具", "设置").performClick()
        reveal("settings-row:浏览器", "工具").performClick()
        val replacement = currentOwner()
        assertNotSame(old, replacement)
        assertTrue(replacement.isActive)
        try {
            store.save(BrowserSettings(BrowserAccess.FULL), old)
            fail("A revoked page must not save through a later page")
        } catch (_: IllegalStateException) { }
        assertEquals(BrowserAccess.DISABLED, store.snapshot().access)
        assertTrue(replacement.isActive)
    }

    fun testRecreationRevokesOldPageAndKeepsSavedCeiling() {
        enterFromSettings()
        val old = currentOwner()
        activity = recreateActivity(activity)
        assertFalse(old.isActive)
        node("chat-draft").assertExists()
        assertEquals(BrowserAccess.DISABLED, store.snapshot().access)
        assertEquals(0, fixture.opens.get())
    }
}
