package app.daiyu.android

import android.content.ContextWrapper
import androidx.compose.ui.test.*
import app.daiyu.android.model.catalog.CatalogCommitResult
import app.daiyu.android.model.catalog.CatalogModelParameters
import app.daiyu.android.model.catalog.SavedCatalogModel
import java.io.File
import java.util.UUID

class ModelBulkSelectionActivityTest : WiredActivityTestCase() {
    private var directory: File? = null
    internal override fun createFixture(): RunControlFixture {
        val root = File(instrumentation.targetContext.cacheDir, "model-bulk-${UUID.randomUUID()}").apply { check(mkdirs()) }
        directory = root
        return RunControlFixture(object : ContextWrapper(instrumentation.targetContext) { override fun getFilesDir() = root })
    }
    override fun cleanupFixture() {
        try { super.cleanupFixture() } finally { directory?.deleteRecursively(); directory = null }
    }
    private fun openList() {
        val base = fixture.settings.modelCatalogSnapshot()
        assertEquals(CatalogCommitResult.COMMITTED, fixture.settings.modelCatalog.compareAndAppend(base.providerIdentity, base.revision,
            listOf(SavedCatalogModel("bulk-first", "A model with a deliberately long display name that wraps across lines",
                CatalogModelParameters(contextWindowOverride = 272000, visionOverride = true)),
                SavedCatalogModel("bulk-second", "Second bulk model", CatalogModelParameters()))) { false })
        openModels(); selectProviderTab("模型")
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("local-model:bulk-first").fetchSemanticsNodes().size == 1 }
    }
    private fun row(id: String) = reveal("local-model:$id")
    private fun select(id: String) { row(id).performTouchInput { longClick() } }
    private fun confirmDeletion() {
        node("local-model-delete-selected").performClick()
        awaitUniqueTag("dialog-confirm"); awaitNodeWindowFocus(node("dialog-confirm")); awaitVisualIdle()
        node("dialog-confirm").performClick()
        awaitControl("batch mutation finished") { !fixture.controller.dataBusy }
    }
    fun testLongPressCancelBackAndPauseKeepCatalogAndSettings() {
        openList()
        val before = fixture.settings.localModelCatalogSnapshot(); val saved = fixture.settings.load()
        val oldSettings = reveal("local-model-rename:bulk-first").fetchSemanticsNode().config[
            androidx.compose.ui.semantics.SemanticsActions.OnClick].action!!
        select("bulk-first")
        mainThread { oldSettings() }
        node("catalog-model-editor").assertDoesNotExist()
        row("bulk-first").assertIsOn(); row("bulk-second").performClick(); row("bulk-second").assertIsOn()
        node("local-model-rename:bulk-first").assertDoesNotExist()
        node("catalog-model-editor").assertDoesNotExist()
        val page = node("page:主模型服务商").fetchSemanticsNode().boundsInRoot
        val footer = node("local-model-selection-actions").fetchSemanticsNode().boundsInRoot
        assertTrue(footer.top >= page.bottom - 1f)
        node("local-model-cancel-selection").performClick()
        node("local-model-selection-actions").assertDoesNotExist()
        select("bulk-first"); mainThread { activity.onBackPressedDispatcher.onBackPressed() }
        node("local-model-selection-actions").assertDoesNotExist(); node("page:主模型服务商").assertExists()
        select("bulk-first"); realPauseResume(activity)
        node("local-model-selection-actions").assertDoesNotExist()
        assertEquals(before, fixture.settings.localModelCatalogSnapshot()); assertEquals(saved, fixture.settings.load())
        assertEquals(0, fixture.opens.get())
    }
    fun testFilteredAllAndInvertPreserveHiddenSelectionAndKeepEmptyMode() {
        openList(); val before = fixture.settings.localModelCatalogSnapshot(); val saved = fixture.settings.load()
        select(saved.model)
        reveal("local-model-search").performTextReplacement("bulk-"); dismissIme()
        reveal("local-model-select-all", lazyKey = "catalog-state").performClick()
        row("bulk-first").assertIsOn(); row("bulk-second").assertIsOn()
        reveal("local-model-invert-selection", lazyKey = "catalog-state").performClick()
        row("bulk-first").assertIsOff(); row("bulk-second").assertIsOff()
        reveal("local-model-search").performTextClearance(); dismissIme()
        row(saved.model).assertIsOn()
        row(saved.model).performClick(); node("local-model-delete-selected").assertIsNotEnabled()
        reveal("local-model-invert-selection", lazyKey = "catalog-state").performClick()
        reveal("local-model-invert-selection", lazyKey = "catalog-state").performClick()
        node("local-model-delete-selected").assertIsNotEnabled(); node("local-model-selection-actions").assertIsDisplayed()
        reveal("local-model-search").performTextReplacement("no-matching-model"); dismissIme()
        reveal("local-model-select-all", lazyKey = "catalog-state").assertIsNotEnabled()
        reveal("local-model-invert-selection", lazyKey = "catalog-state").assertIsNotEnabled()
        assertEquals(before, fixture.settings.localModelCatalogSnapshot()); assertEquals(saved, fixture.settings.load())
    }
    fun testBatchConfirmationDeletesBothWithOneRevisionAndNoChatWrites() {
        setDraft("keep bulk draft"); openList()
        val before = checkNotNull(fixture.settings.localModelCatalogSnapshot()); val saved = fixture.settings.load()
        val history = fixture.database.exportBackup()
        select("bulk-first"); row("bulk-second").performClick()
        node("local-model-delete-selected").performClick(); awaitUniqueTag("dialog-confirm")
        awaitNodeWindowFocus(node("dialog-cancel")); awaitVisualIdle(); node("dialog-cancel").performClick()
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("dialog-confirm").fetchSemanticsNodes().isEmpty() }
        assertEquals(before, fixture.settings.localModelCatalogSnapshot())
        confirmDeletion()
        awaitControl("both selected models removed") { fixture.settings.localModelCatalogSnapshot()?.models?.none { it.id.startsWith("bulk-") } == true }
        val after = checkNotNull(fixture.settings.localModelCatalogSnapshot())
        assertEquals(before.revision + 1, after.revision)
        assertEquals(before.models.filterNot { it.id.startsWith("bulk-") }, after.models)
        assertEquals(saved, fixture.settings.load()); assertEquals(history, fixture.database.exportBackup())
        assertEquals(0, fixture.opens.get())
        node("local-model-selection-actions").assertDoesNotExist()
        backToPage(activity, "page:模型提供商")
        backToPage(activity, "page:设置")
        backToChat(activity); assertDraft("keep bulk draft")
    }
    fun testCurrentModelBatchDeletesWithOneRevisionAndClearsSelectionWithoutSwitching() {
        openList(); val before = checkNotNull(fixture.settings.localModelCatalogSnapshot()); val saved = fixture.settings.load()
        select("bulk-first"); row(saved.model).performClick(); confirmDeletion()
        awaitControl("catalog deletion committed") { fixture.settings.localModelCatalogSnapshot()?.revision == before.revision + 1 }
        val after = checkNotNull(fixture.settings.localModelCatalogSnapshot())
        assertEquals(before.models.filterNot { it.id in setOf("bulk-first", saved.model) }, after.models)
        assertEquals(saved.copy(model = ""), fixture.settings.load())
        assertEquals(0, fixture.opens.get())
    }
    fun testPrePauseBatchConfirmationCannotDeleteOrDismissFreshSelection() {
        openList()
        val before = fixture.settings.localModelCatalogSnapshot()
        select("bulk-first"); node("local-model-delete-selected").performClick()
        awaitUniqueTag("dialog-confirm"); awaitNodeWindowFocus(node("dialog-confirm")); awaitVisualIdle()
        val oldConfirm = checkNotNull(node("dialog-confirm").fetchSemanticsNode().config[
            androidx.compose.ui.semantics.SemanticsActions.OnClick].action)
        realPauseResume(activity)
        ui.waitUntil(5_000) { ui.onAllNodesWithText("正在读取本地模型").fetchSemanticsNodes().isEmpty() }
        node("local-model-selection-actions").assertDoesNotExist()
        select("bulk-second"); node("local-model-delete-selected").performClick()
        awaitUniqueTag("dialog-confirm"); awaitNodeWindowFocus(node("dialog-confirm")); awaitVisualIdle()
        mainThread { oldConfirm() }
        node("dialog-confirm").assertExists()
        assertEquals(before, fixture.settings.localModelCatalogSnapshot())
        node("dialog-cancel").performClick()
        assertEquals(0, fixture.opens.get())
    }

    fun testSettingsButtonIsCenteredInWholeModelCardIncludingWrappedNameAndBadges() {
        openList()
        for (id in listOf("bulk-first", "bulk-second")) {
            reveal("local-model-rename:$id"); awaitVisualIdle()
            val card = ui.onNodeWithTag("local-model:$id", useUnmergedTree = true).fetchSemanticsNode()
            val button = ui.onNodeWithTag("local-model-rename:$id", useUnmergedTree = true).fetchSemanticsNode()
            ui.runOnIdle {
                assertSame(card.root, button.root)
                assertEquals(card.positionInRoot.y + card.size.height / 2f, button.positionInRoot.y + button.size.height / 2f, 1f)
            }
        }
    }
}
