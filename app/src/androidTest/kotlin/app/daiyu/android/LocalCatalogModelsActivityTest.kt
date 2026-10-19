package app.daiyu.android

import android.content.ContextWrapper
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import app.daiyu.android.model.catalog.CatalogCommitResult
import app.daiyu.android.model.catalog.CatalogModelParameters
import app.daiyu.android.model.catalog.ModelCatalogService
import app.daiyu.android.model.catalog.RemoteModelFetcher
import app.daiyu.android.model.catalog.SavedCatalogModel
import java.io.File
import java.util.UUID

class LocalCatalogModelsActivityTest : WiredActivityTestCase() {
    private var directory: File? = null
    internal override fun createFixture(): RunControlFixture {
        val root = File(instrumentation.targetContext.cacheDir, "local-models-${UUID.randomUUID()}").apply { check(mkdirs()) }
        directory = root
        return RunControlFixture(object : ContextWrapper(instrumentation.targetContext) { override fun getFilesDir() = root })
    }
    override fun cleanupFixture() {
        try { super.cleanupFixture() } finally { directory?.deleteRecursively(); directory = null }
    }
    private fun openLocalModelTab() {
        selectProviderTab("模型")
        awaitUniqueTag("local-model-refresh")
        ui.waitUntil(5_000) { ui.onAllNodesWithText("正在读取本地模型").fetchSemanticsNodes().isEmpty() }
        reveal("local-model:extra-local").assertExists()
    }
    private fun openLocalModels() {
        val snapshot = fixture.settings.modelCatalogSnapshot()
        if (snapshot.models.none { it.id == "extra-local" }) assertEquals(CatalogCommitResult.COMMITTED,
            fixture.settings.modelCatalog.compareAndAppend(snapshot.providerIdentity, snapshot.revision,
                listOf(SavedCatalogModel("extra-local", "Extra local model", CatalogModelParameters()))) { false })
        fixture.controller.modelCatalog = ModelCatalogService({ error("local edits must not load credentials for remote fetching") },
            RemoteModelFetcher { error("local edits must not fetch models") })
        openModels()
        openLocalModelTab()
    }
    fun testProductionEntryRenamesAndDeletesOnlyTheNonCurrentCatalogRecord() {
        setDraft("local model edits remain unsent")
        val saved = fixture.settings.load(); val before = fixture.database.exportBackup()
        openLocalModels()
        val current = checkNotNull(fixture.settings.localModelCatalogSnapshot()).models.single { it.id == saved.model }
        val extra = checkNotNull(fixture.settings.localModelCatalogSnapshot()).models.single { it.id == "extra-local" }
        reveal("local-model-rename:${saved.model}").performClick()
        node("catalog-model-editor").performScrollToNode(hasTestTag("local-model-delete:${saved.model}"))
        node("local-model-delete:${saved.model}").assertIsNotEnabled()
        node("catalog-model-editor").performScrollToNode(hasTestTag("dialog-cancel"))
        node("catalog-model-editor").performScrollToNode(hasTestTag("dialog-cancel"))
        node("dialog-cancel").performClick()
        reveal("local-model-rename:extra-local").performClick()
        node("settings-field:展示名称").performTextReplacement("Renamed local model")
        node("catalog-model-editor").performScrollToNode(hasTestTag("dialog-confirm"))
        node("dialog-confirm").performClick()
        awaitControl("local rename completed") { !fixture.controller.dataBusy }
        ui.waitUntil(5_000) {
            ui.onAllNodes(hasTestTag("local-model:extra-local") and
                hasAnyDescendant(hasText("Renamed local model"))).fetchSemanticsNodes().size == 1
        }
        val renamed = checkNotNull(fixture.settings.localModelCatalogSnapshot())
        assertEquals(extra.copy(displayName = "Renamed local model"), renamed.models.single { it.id == extra.id })
        assertEquals(current, renamed.models.single { it.id == current.id })
        reveal("local-model-rename:extra-local").performClick()
        node("catalog-model-editor").performScrollToNode(hasTestTag("local-model-delete:extra-local"))
        node("local-model-delete:extra-local").performClick()
        node("dialog-confirm").performClick()
        awaitControl("local delete completed") { !fixture.controller.dataBusy }
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("local-model:extra-local").fetchSemanticsNodes().isEmpty() }
        assertEquals(listOf(current), checkNotNull(fixture.settings.localModelCatalogSnapshot()).models)
        assertEquals(saved, fixture.settings.load()); assertEquals(before, fixture.database.exportBackup())
        assertEquals(0, fixture.opens.get()); assertNull(fixture.controller.active)
        backToPage(activity, "page:提供商")
        node("page:模型设置").assertDoesNotExist()
        backToPage(activity, "page:设置")
        backToChat(activity)
        assertDraft("local model edits remain unsent")
    }
    fun testInvalidRenameAndCancelledDeletionNeverMutateTheCatalog() {
        openLocalModels()
        val before = fixture.settings.localModelCatalogSnapshot(); val saved = fixture.settings.load()
        reveal("local-model-rename:extra-local").performClick()
        for (value in listOf(" ", "x".repeat(201))) {
            node("settings-field:展示名称").performTextReplacement(value)
            node("catalog-model-editor").performScrollToNode(hasTestTag("dialog-confirm"))
            node("dialog-confirm").assertIsNotEnabled()
            node("catalog-model-editor").performScrollToNode(hasTestTag("settings-field:展示名称"))
            node("settings-field:展示名称").assertExists()
            assertEquals(before, fixture.settings.localModelCatalogSnapshot())
        }
        node("dialog-cancel").performClick()
        reveal("local-model-rename:extra-local").performClick()
        node("catalog-model-editor").performScrollToNode(hasTestTag("local-model-delete:extra-local"))
        node("local-model-delete:extra-local").performClick()
        node("dialog-cancel").performClick()
        assertEquals(before, fixture.settings.localModelCatalogSnapshot()); assertEquals(saved, fixture.settings.load())
        assertEquals(0, fixture.opens.get())
    }
    fun testOldConfirmationAfterPauseCannotModifyOrDismissFreshConfirmation() {
        openLocalModels()
        reveal("local-model-rename:extra-local").performClick()
        node("catalog-model-editor").performScrollToNode(hasTestTag("local-model-delete:extra-local"))
        node("local-model-delete:extra-local").performClick()
        val stale = checkNotNull(node("dialog-confirm").fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        realPauseResume(activity)
        awaitModelEditor()
        openLocalModelTab()
        reveal("local-model-rename:extra-local").performClick()
        node("catalog-model-editor").performScrollToNode(hasTestTag("local-model-delete:extra-local"))
        node("local-model-delete:extra-local").performClick()
        val before = fixture.settings.localModelCatalogSnapshot()
        mainThread { assertTrue(stale()) }
        node("dialog-confirm").assertExists()
        assertEquals(before, fixture.settings.localModelCatalogSnapshot())
        node("dialog-cancel").performClick()
        assertEquals(0, fixture.opens.get()); assertNull(fixture.controller.active)
    }
}
