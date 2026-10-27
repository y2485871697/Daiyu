package app.daiyu.android

import androidx.compose.ui.test.*
import app.daiyu.android.storage.ProviderRegistry
import app.daiyu.android.storage.ProviderRegistrySnapshot

class ProviderDirectorySelectionActivityTest : WiredActivityTestCase() {
    private fun addRows(): ProviderRegistrySnapshot {
        val repo = fixture.controller.providers
        val first = repo.register(repo.snapshot().revision, "Selection First")
        return repo.register(first.revision, "Selection Second")
    }
    private fun row(slot: String) = reveal("provider-open-$slot", "模型提供商")
    private fun select(slot: String) { row(slot).performTouchInput { longClick() } }

    fun testLongPressSelectsInlineAndCancelBackAndPauseNeverWrite() {
        val before = addRows()
        val settings = fixture.settings.load()
        openProviderManagement()
        select(ProviderRegistry.PRIMARY)
        node("provider-open-primary").assertIsOn()
        node("provider-selection-actions").assertIsDisplayed()
        val page = node("page:模型提供商").fetchSemanticsNode().boundsInRoot
        val actions = node("provider-selection-actions").fetchSemanticsNode().boundsInRoot
        assertTrue("selection retains a visible list", page.height > actions.height)
        assertTrue("footer is below the list, not over it", actions.top >= page.bottom - 1f)
        val delete = node("provider-delete-selected").fetchSemanticsNode().boundsInRoot
        val cancel = node("provider-cancel-selection").fetchSemanticsNode().boundsInRoot
        assertTrue(delete.left < cancel.left)
        assertEquals(delete.width, cancel.width, 1f)
        ui.onNodeWithText("配置与模型").assertDoesNotExist()
        node("dialog-confirm").assertDoesNotExist()
        row(before.providers[1].slotId).performClick()
        node("provider-open-${before.providers[1].slotId}").assertIsOn()
        node("page:模型提供商").assertExists()
        node("provider-cancel-selection").performClick()
        node("provider-selection-actions").assertDoesNotExist()
        select(ProviderRegistry.PRIMARY)
        mainThread { activity.onBackPressedDispatcher.onBackPressed() }
        node("provider-selection-actions").assertDoesNotExist()
        node("page:模型提供商").assertExists()
        select(ProviderRegistry.PRIMARY)
        realPauseResume(activity)
        node("provider-selection-actions").assertDoesNotExist()
        assertEquals(before, fixture.controller.providers.snapshot())
        assertEquals(settings, fixture.settings.load())
        assertEquals(0, fixture.opens.get())
        row(ProviderRegistry.PRIMARY).performClick()
        awaitUniqueTag("page:主模型服务商")
    }

    fun testFilteredSelectAllAndEmptySelectionDoNotSelectHiddenProviders() {
        val before = addRows()
        openProviderManagement()
        select(ProviderRegistry.PRIMARY)
        reveal("provider-search", "模型提供商").performTextReplacement("Selection First")
        reveal("provider-select-all", "模型提供商", "provider-count").performClick()
        row(before.providers[1].slotId).assertIsOn()
        reveal("provider-search", "模型提供商").performTextReplacement("")
        row(ProviderRegistry.PRIMARY).assertIsOn()
        row(before.providers[2].slotId).assertIsOff()
        row(ProviderRegistry.PRIMARY).performClick()
        row(before.providers[1].slotId).performClick()
        node("provider-selection-actions").assertIsDisplayed()
        node("provider-delete-selected").assertIsNotEnabled()
        node("provider-cancel-selection").performClick()
        assertEquals(before, fixture.controller.providers.snapshot())
        assertEquals(0, fixture.opens.get())
    }

    fun testConfirmedBatchDeletionUsesOneRevisionAndDoesNotSwitchCurrentProvider() {
        val before = addRows()
        val settings = fixture.settings.load()
        openProviderManagement()
        select(ProviderRegistry.PRIMARY)
        row(before.providers[1].slotId).performClick()
        node("provider-delete-selected").performClick()
        awaitUniqueTag("dialog-confirm")
        node("dialog-cancel").performClick()
        assertEquals(before, fixture.controller.providers.snapshot())
        node("provider-delete-selected").performClick()
        awaitUniqueTag("dialog-confirm")
        awaitNodeWindowFocus(node("dialog-confirm"))
        awaitVisualIdle()
        node("dialog-confirm").performClick()
        awaitControl("selected providers removed") { fixture.controller.providers.snapshot().providers.size == 1 }
        val after = fixture.controller.providers.snapshot()
        assertEquals(before.revision + 1, after.revision)
        assertEquals(before.providers.drop(2), after.providers)
        assertNull(after.activeSlotId)
        node("provider-selection-actions").assertDoesNotExist()
        node("page:模型提供商").assertExists()
        assertEquals(settings, fixture.settings.load())
        assertEquals(0, fixture.opens.get())
    }
}
