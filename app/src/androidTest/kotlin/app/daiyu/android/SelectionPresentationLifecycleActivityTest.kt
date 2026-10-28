package app.daiyu.android

import android.content.ContextWrapper
import androidx.compose.ui.test.*
import app.daiyu.android.model.catalog.CatalogCommitResult
import app.daiyu.android.model.catalog.CatalogLocalEditResult
import app.daiyu.android.model.catalog.CatalogModelParameters
import app.daiyu.android.model.catalog.selectionFor
import app.daiyu.android.model.catalog.SavedCatalogModel
import app.daiyu.android.storage.ProviderRegistry
import app.daiyu.android.ui.screens.settings.RetainedListSelectionAction
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Selection IDs are presentation; a retained button is not a renewed foreground capability. */
class SelectionPresentationLifecycleActivityTest : WiredActivityTestCase() {
    private var directory: File? = null
    internal override fun createFixture(): RunControlFixture {
        val root = File(instrumentation.targetContext.cacheDir, "selection-resume-${UUID.randomUUID()}").apply { check(mkdirs()) }
        directory = root
        return RunControlFixture(object : ContextWrapper(instrumentation.targetContext) { override fun getFilesDir() = root })
    }
    override fun cleanupFixture() {
        try { super.cleanupFixture() } finally { directory?.deleteRecursively(); directory = null }
    }
    private fun homeAndReturn(atStop: () -> Unit = {}) {
        val original = activity
        val stopped = CountDownLatch(1)
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) stopped.countDown()
        }
        mainThread { original.lifecycle.addObserver(observer) }
        try {
            assertTrue(instrumentation.uiAutomation.performGlobalAction(
                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME))
            assertTrue("same Activity reached STOP", stopped.await(5, TimeUnit.SECONDS))
            atStop()
        } finally {
            mainThread { original.lifecycle.removeObserver(observer) }
            restoreOriginalHost(original)
        }
        awaitHostWindowFocus(original)
    }
    private fun retained(tag: String) = node(tag).fetchSemanticsNode().config[RetainedListSelectionAction]
    private fun ready(tag: String) {
        ui.waitUntil(5_000) {
            ui.mainClock.advanceTimeByFrame()
            ui.onAllNodes(hasTestTag(tag) and isEnabled()).fetchSemanticsNodes().size == 1
        }
    }
    private fun providerRow(slot: String) = reveal("provider-open-$slot", "模型提供商")
    private fun modelRow(id: String) = reveal("local-model:$id")
    private fun openModelList() {
        val base = fixture.settings.modelCatalogSnapshot()
        assertEquals(CatalogCommitResult.COMMITTED, fixture.settings.modelCatalog.compareAndAppend(base.providerIdentity, base.revision,
            listOf(SavedCatalogModel("retained-first", "First retained model", CatalogModelParameters()),
                SavedCatalogModel("retained-second", "Second retained model", CatalogModelParameters()))) { false })
        openModels(); selectProviderTab("模型")
        modelRow("retained-first").assertExists()
    }
    fun testProviderHiddenSelectionSurvivesRepeatedPauseAndHomeWithOldButtonsRevoked() {
        val repo = fixture.controller.providers
        val before = repo.register(repo.snapshot().revision, "Retained provider")
        val other = before.providers.last().slotId
        val settings = fixture.settings.load()
        openProviderManagement()
        providerRow(ProviderRegistry.PRIMARY).performTouchInput { longClick() }
        providerRow(other).performClick()
        val oldCancel = retained("provider-cancel-selection")
        val oldDelete = retained("provider-delete-selected")
        reveal("provider-search", "模型提供商").performTextReplacement("Retained provider"); dismissIme()
        repeat(2) {
            realPauseResume(activity)
            ready("provider-delete-selected")
            homeAndReturn()
            ready("provider-delete-selected")
            mainThread { oldCancel(); oldDelete() }
            node("provider-selection-actions").assertIsDisplayed()
            node("dialog-confirm").assertDoesNotExist()
            reveal("provider-search", "模型提供商").assertEditableText("Retained provider")
            providerRow(other).assertIsOn()
            assertEquals(before, repo.snapshot())
        }
        reveal("provider-search", "模型提供商").performTextClearance(); dismissIme()
        providerRow(ProviderRegistry.PRIMARY).assertIsOn(); providerRow(other).assertIsOn()
        providerRow(ProviderRegistry.PRIMARY).performClick(); providerRow(other).performClick()
        homeAndReturn()
        node("provider-selection-actions").assertIsDisplayed(); node("provider-delete-selected").assertIsNotEnabled()
        ready("provider-cancel-selection")
        node("provider-cancel-selection").performClick()
        homeAndReturn()
        node("provider-selection-actions").assertDoesNotExist()
        assertEquals(before, repo.snapshot()); assertEquals(settings, fixture.settings.load())
        assertEquals(0, fixture.opens.get())
    }
    fun testModelHiddenSelectionSurvivesRepeatedPauseAndHomeWithOldButtonsRevoked() {
        openModelList()
        val before = fixture.settings.localModelCatalogSnapshot(); val settings = fixture.settings.load()
        modelRow("retained-first").performTouchInput { longClick() }; modelRow("retained-second").performClick()
        val oldCancel = retained("local-model-cancel-selection")
        val oldDelete = retained("local-model-delete-selected")
        reveal("local-model-search").performTextReplacement("retained-second"); dismissIme()
        repeat(2) {
            realPauseResume(activity)
            ready("local-model-delete-selected")
            homeAndReturn()
            ready("local-model-delete-selected")
            mainThread { oldCancel(); oldDelete() }
            node("local-model-selection-actions").assertIsDisplayed()
            node("dialog-confirm").assertDoesNotExist()
            reveal("local-model-search").assertEditableText("retained-second")
            modelRow("retained-second").assertIsOn()
            assertEquals(before, fixture.settings.localModelCatalogSnapshot())
        }
        reveal("local-model-search").performTextClearance(); dismissIme()
        modelRow("retained-first").assertIsOn(); modelRow("retained-second").assertIsOn()
        modelRow("retained-first").performClick(); modelRow("retained-second").performClick()
        homeAndReturn()
        node("local-model-selection-actions").assertIsDisplayed(); node("local-model-delete-selected").assertIsNotEnabled()
        ready("local-model-cancel-selection")
        node("local-model-cancel-selection").performClick()
        homeAndReturn()
        node("local-model-selection-actions").assertDoesNotExist()
        assertEquals(before, fixture.settings.localModelCatalogSnapshot()); assertEquals(settings, fixture.settings.load())
        assertEquals(0, fixture.opens.get())
    }
    fun testProviderRevisionChangePrunesOnlyRemovedIdsWithoutSelectingNewRows() {
        val repo = fixture.controller.providers
        val before = repo.register(repo.snapshot().revision, "Removed while stopped")
        val removed = before.providers.last().slotId
        var added = ""
        openProviderManagement()
        providerRow(ProviderRegistry.PRIMARY).performTouchInput { longClick() }; providerRow(removed).performClick()
        homeAndReturn {
            val deleted = repo.deleteSelected(repo.snapshot().revision, setOf(removed))
            added = repo.register(deleted.revision, "New while stopped").providers.last().slotId
        }
        ready("provider-delete-selected")
        providerRow(ProviderRegistry.PRIMARY).assertIsOn(); providerRow(added).assertIsOff()
        node("provider-open-$removed").assertDoesNotExist()
        providerRow(ProviderRegistry.PRIMARY).performClick()
        node("provider-delete-selected").assertIsNotEnabled()
        assertEquals(0, fixture.opens.get())
    }
    fun testModelRevisionChangePrunesOnlyRemovedIdsAndKeepsSurvivingSelection() {
        openModelList()
        val saved = fixture.settings.load()
        modelRow("retained-first").performTouchInput { longClick() }; modelRow("retained-second").performClick()
        val oldCancel = retained("local-model-cancel-selection")
        val oldDelete = retained("local-model-delete-selected")
        homeAndReturn {
            val before = checkNotNull(fixture.settings.localModelCatalogSnapshot())
            assertEquals(CatalogLocalEditResult.APPLIED, fixture.settings.deleteCatalogModels(
                listOf(checkNotNull(before.selectionFor("retained-first"))), cancelled = { false }))
            val after = fixture.settings.modelCatalogSnapshot()
            assertEquals(CatalogCommitResult.COMMITTED, fixture.settings.modelCatalog.compareAndAppend(after.providerIdentity, after.revision,
                listOf(SavedCatalogModel("new-while-stopped", "New model", CatalogModelParameters()))) { false })
        }
        ready("local-model-delete-selected")
        mainThread { oldCancel(); oldDelete() }
        node("dialog-confirm").assertDoesNotExist()
        node("local-model-selection-actions").assertIsDisplayed()
        modelRow("retained-second").assertIsOn(); modelRow("new-while-stopped").assertIsOff()
        node("local-model:retained-first").assertDoesNotExist()
        modelRow("retained-second").performClick()
        node("local-model-delete-selected").assertIsNotEnabled()
        assertEquals(saved, fixture.settings.load())
        assertEquals(0, fixture.opens.get())
    }
    fun testDeletingCurrentModelRetainsModelTabAndSurvivingSelection() {
        openModelList()
        val current = fixture.settings.load().model
        assertTrue(current.isNotBlank())
        modelRow(current).performTouchInput { longClick() }; modelRow("retained-second").performClick()
        val oldCancel = retained("local-model-cancel-selection")
        val oldDelete = retained("local-model-delete-selected")
        homeAndReturn {
            val before = checkNotNull(fixture.settings.localModelCatalogSnapshot())
            assertEquals(CatalogLocalEditResult.APPLIED, fixture.settings.deleteCatalogModels(
                listOf(checkNotNull(before.selectionFor(current))), cancelled = { false }))
            assertEquals("", fixture.settings.load().model)
        }
        ready("local-model-delete-selected")
        modelRow("retained-second").assertIsOn()
        node("local-model:$current").assertDoesNotExist()
        mainThread { oldCancel(); oldDelete() }
        node("dialog-confirm").assertDoesNotExist()
        node("local-model-selection-actions").assertIsDisplayed()
        modelRow("retained-second").performClick()
        node("local-model-delete-selected").assertIsNotEnabled()
        assertEquals(0, fixture.opens.get())
    }

}
