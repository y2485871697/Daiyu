package app.daiyu.android

import android.content.ContextWrapper
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
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
        awaitUniqueTag("local-model-add")
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
    fun testModelRowsIgnoreTitleAndBlankAreaTouchesWhileSettingsButtonStillEdits() {
        setDraft("model management stays unsent")
        openLocalModels()
        val saved = fixture.settings.load()
        val before = checkNotNull(fixture.settings.localModelCatalogSnapshot())
        val messages = fixture.database.exportBackup()
        fun assertNoModelSelection() {
            ui.waitForIdle()
            node("catalog-model-editor").assertDoesNotExist()
            ui.onNodeWithText("当前模型已切换").assertDoesNotExist()
            ui.onNodeWithText("模型未切换，请重新选择").assertDoesNotExist()
            assertEquals(saved, fixture.settings.load())
            assertEquals(before, fixture.settings.localModelCatalogSnapshot())
        }
        for (modelId in listOf("extra-local", saved.model)) {
            val tag = "local-model:$modelId"
            reveal(tag)
            val row = ui.onNodeWithTag(tag, useUnmergedTree = true).assertHasClickAction()
            val name = before.models.single { it.id == modelId }.displayName
            ui.onAllNodes(hasText(name) and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true)
                .onFirst().performTouchInput { click(center) }
            assertNoModelSelection()
            row.performTouchInput { click(Offset(8f, center.y)) }
            assertNoModelSelection()
        }
        val editTag = "local-model-rename:extra-local"
        reveal(editTag)
        ui.onNodeWithTag(editTag, useUnmergedTree = true).assertHasClickAction().performClick()
        node("catalog-model-editor").assertExists()
        node("settings-field:展示名称").assertEditableText("Extra local model")
        node("dialog-cancel").performClick()
        assertEquals(saved, fixture.settings.load())
        assertEquals(before, fixture.settings.localModelCatalogSnapshot())
        assertEquals(messages, fixture.database.exportBackup())
        assertEquals(0, fixture.opens.get())
        assertNull(fixture.controller.active)
        backToPage(activity, "page:模型提供商")
        backToPage(activity, "page:设置")
        backToChat(activity)
        assertDraft("model management stays unsent")
    }

    private fun editorSwitch(tag: String) = ui.onNode(
        hasTestTag(tag) and hasAnyAncestor(hasTestTag("catalog-model-editor")))

    private fun awaitEditorSwitch(tag: String, on: Boolean) {
        val expected = hasTestTag(tag) and hasAnyAncestor(hasTestTag("catalog-model-editor")) and
            SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState,
                if (on) ToggleableState.On else ToggleableState.Off)
        ui.waitUntil(5_000) {
            ui.mainClock.advanceTimeByFrame()
            ui.onAllNodes(expected).fetchSemanticsNodes(atLeastOneRootRequired = false).size == 1
        }
        if (on) editorSwitch(tag).assertIsOn() else editorSwitch(tag).assertIsOff()
    }

    fun testModelBadgesUseExplicitNamesAndKeepOnlyTheCurrentModelCheck() {
        openLocalModels()
        val current = fixture.settings.load().model
        node("local-model-search").performTextReplacement("extra-local")
        dismissIme()
        reveal("local-model:extra-local")
        ui.onNodeWithText("上下文未知").assertExists()
        ui.onNodeWithText("支持视觉").assertExists()
        ui.onNodeWithText("原生搜索").assertDoesNotExist()
        ui.onNodeWithContentDescription("当前模型").assertDoesNotExist()
        for (old in listOf("当前", "推理", "视觉", "搜索"))
            ui.onNodeWithText(old).assertDoesNotExist()

        reveal("local-model-rename:extra-local").performClick()
        awaitUniqueTag("catalog-model-editor")
        awaitDialogEnter(node("catalog-model-editor"))
        awaitNodeWindowFocus(node("catalog-model-editor"))
        node("settings-field:上下文窗口").performTextReplacement("8")
        dismissIme()
        node("catalog-model-editor").scrollToDescendant(hasTestTag("settings-switch:支持视觉"))
        awaitEditorSwitch("settings-switch:支持视觉", true)
        editorSwitch("settings-switch:支持视觉").performClick()
        awaitEditorSwitch("settings-switch:支持视觉", false)
        node("catalog-model-editor").scrollToDescendant(hasTestTag("settings-switch:原生搜索"))
        awaitEditorSwitch("settings-switch:原生搜索", false)
        editorSwitch("settings-switch:原生搜索").performClick()
        awaitEditorSwitch("settings-switch:原生搜索", true)
        node("dialog-confirm").performClick()
        awaitControl("badge configuration saved") {
            fixture.settings.localModelCatalogSnapshot()!!.models.single { it.id == "extra-local" }
                .settings.let { it.contextWindowOverride == 8000 && it.visionOverride == false &&
                    it.requestOptions.openAiWebSearchEnabled }
        }
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("catalog-model-editor").fetchSemanticsNodes().isEmpty() }
        reveal("local-model:extra-local")
        ui.onNodeWithText("8K 上下文").assertExists()
        ui.onNodeWithText("支持视觉").assertDoesNotExist()
        ui.onNodeWithText("原生搜索").assertExists()
        node("local-model-search").performTextReplacement(current)
        dismissIme()
        reveal("local-model:$current")
        ui.onNodeWithContentDescription("当前模型").assertExists()
        for (old in listOf("当前", "推理", "视觉", "搜索"))
            ui.onNodeWithText(old).assertDoesNotExist()
        assertEquals(current, fixture.settings.load().model)
        assertEquals(0, fixture.opens.get())
    }

    fun testContextUnitsPersistExactMillionTokensAndReopenInM() {
        openLocalModels()
        val settings = fixture.settings.load()
        reveal("local-model-rename:extra-local").performClick()
        awaitUniqueTag("catalog-model-editor")
        node("catalog-model-context-unit").assertTextEquals("K").performClick().assertTextEquals("M")
        node("settings-field:上下文窗口").performTextReplacement("1.5")
        node("dialog-confirm").performClick()
        awaitControl("million-token window saved") {
            fixture.settings.localModelCatalogSnapshot()!!.models.single { it.id == "extra-local" }
                .settings.contextWindowOverride == 1500000
        }
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("catalog-model-editor").fetchSemanticsNodes().isEmpty() }
        reveal("local-model-rename:extra-local").performClick()
        awaitUniqueTag("catalog-model-editor")
        node("settings-field:上下文窗口").assertEditableText("1.5")
        node("catalog-model-context-unit").assertTextEquals("M").performClick().assertTextEquals("K")
        node("settings-field:上下文窗口").assertEditableText("1500")
        node("dialog-confirm").performClick()
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("catalog-model-editor").fetchSemanticsNodes().isEmpty() }
        assertEquals(1500000, fixture.settings.localModelCatalogSnapshot()!!.models.single { it.id == "extra-local" }
            .settings.contextWindowOverride)
        assertEquals(settings, fixture.settings.load())
        assertEquals(0, fixture.opens.get())
    }

    fun testCustomModelDialogMatchesFieldsAndSavesOnlyAnExplicitValidModel() {
        openLocalModels()
        val saved = fixture.settings.load()
        val before = fixture.settings.localModelCatalogSnapshot()!!
        reveal("local-model-add").performClick()
        ui.onNodeWithText("添加模型").assertExists()
        node("settings-field:展示名称").assertEditableText("自定义模型")
        node("dialog-confirm").assertIsNotEnabled()
        node("settings-field:Model ID").performTextReplacement("custom-model")
        node("settings-field:上下文窗口").performTextReplacement("8.192")
        node("settings-switch:支持推理").assertDoesNotExist()
        node("catalog-model-editor").scrollToDescendant(hasTestTag("settings-switch:支持视觉"))
        node("settings-switch:支持视觉").assertIsOn()
        node("catalog-model-editor").scrollToDescendant(hasTestTag("settings-switch:原生搜索"))
        node("settings-switch:原生搜索").assertIsOff().performClick().assertIsOn()
        node("catalog-model-auto").assertDoesNotExist()
        assertEquals(before, fixture.settings.localModelCatalogSnapshot())
        node("dialog-confirm").assertIsEnabled().performClick()
        awaitControl("custom record saved") {
            fixture.settings.localModelCatalogSnapshot()!!.models.any { it.id == "custom-model" }
        }
        val record = fixture.settings.localModelCatalogSnapshot()!!.models.single { it.id == "custom-model" }
        assertEquals("自定义模型", record.displayName)
        assertEquals(8192, record.settings.contextWindowOverride)
        assertEquals(true, record.settings.reasoningOverride)
        assertEquals(true, record.settings.visionOverride)
        assertTrue(record.settings.requestOptions.openAiWebSearchEnabled)
        assertEquals(saved, fixture.settings.load())
        assertEquals(0, fixture.opens.get())
        reveal("local-model-add").performClick()
        node("settings-field:Model ID").performTextReplacement("cancelled-model")
        node("dialog-cancel").performClick()
        assertFalse(fixture.settings.localModelCatalogSnapshot()!!.models.any { it.id == "cancelled-model" })
    }
    fun testProductionEntryRenamesAndDeletesOnlyTheNonCurrentCatalogRecord() {
        setDraft("local model edits remain unsent")
        val saved = fixture.settings.load(); val before = fixture.database.exportBackup()
        openLocalModels()
        val current = checkNotNull(fixture.settings.localModelCatalogSnapshot()).models.single { it.id == saved.model }
        val extra = checkNotNull(fixture.settings.localModelCatalogSnapshot()).models.single { it.id == "extra-local" }
        reveal("local-model-rename:${saved.model}").performClick()
        node("catalog-model-editor").scrollToDescendant(hasTestTag("local-model-delete:${saved.model}"))
        node("local-model-delete:${saved.model}").assertIsEnabled()
        node("dialog-cancel").performClick()
        reveal("local-model-rename:extra-local").performClick()
        node("settings-field:展示名称").performTextReplacement("Renamed local model")
        node("dialog-confirm").performClick()
        awaitControl("local rename completed") { !fixture.controller.dataBusy }
        reveal("local-model-add")
        ui.waitUntil(5_000) {
            ui.onAllNodes(hasTestTag("local-model-add") and isEnabled()).fetchSemanticsNodes().size == 1
        }
        val renamedRow = reveal("local-model:extra-local")
        val expectedRenamed = extra.copy(displayName = "Renamed local model",
            settings = extra.settings.copy(reasoningOverride = true, visionOverride = true))
        ui.waitUntil(5_000) {
            ui.mainClock.advanceTimeByFrame()
            fixture.settings.localModelCatalogSnapshot()?.models
                ?.singleOrNull { it.id == extra.id } == expectedRenamed &&
                ui.onAllNodes(hasTestTag("local-model:extra-local") and
                hasAnyAncestor(hasTestTag("page:主模型服务商")) and
                hasText("Renamed local model")).fetchSemanticsNodes().size == 1
        }
        renamedRow.assert(hasText("Renamed local model"))
        val renamed = checkNotNull(fixture.settings.localModelCatalogSnapshot())
        assertEquals(expectedRenamed, renamed.models.single { it.id == extra.id })
        assertEquals(current, renamed.models.single { it.id == current.id })
        reveal("local-model-rename:extra-local").performClick()
        node("catalog-model-editor").scrollToDescendant(hasTestTag("local-model-delete:extra-local"))
        node("local-model-delete:extra-local").performClick()
        node("dialog-confirm").performClick()
        awaitControl("local delete completed") { !fixture.controller.dataBusy }
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("local-model:extra-local").fetchSemanticsNodes().isEmpty() }
        assertEquals(listOf(current), checkNotNull(fixture.settings.localModelCatalogSnapshot()).models)
        assertEquals(saved, fixture.settings.load()); assertEquals(before, fixture.database.exportBackup())
        assertEquals(0, fixture.opens.get()); assertNull(fixture.controller.active)
        backToPage(activity, "page:模型提供商")
        node("page:主模型服务商").assertDoesNotExist()
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
            node("dialog-confirm").assertIsNotEnabled()
            node("catalog-model-editor").scrollToDescendant(hasTestTag("settings-field:展示名称"))
            node("settings-field:展示名称").assertExists()
            assertEquals(before, fixture.settings.localModelCatalogSnapshot())
        }
        node("dialog-cancel").performClick()
        reveal("local-model-rename:extra-local").performClick()
        node("catalog-model-editor").scrollToDescendant(hasTestTag("local-model-delete:extra-local"))
        node("local-model-delete:extra-local").performClick()
        node("dialog-cancel").performClick()
        assertEquals(before, fixture.settings.localModelCatalogSnapshot()); assertEquals(saved, fixture.settings.load())
        assertEquals(0, fixture.opens.get())
    }
    fun testOldConfirmationAfterPauseCannotModifyOrDismissFreshConfirmation() {
        openLocalModels()
        reveal("local-model-rename:extra-local").performClick()
        node("catalog-model-editor").scrollToDescendant(hasTestTag("local-model-delete:extra-local"))
        node("local-model-delete:extra-local").performClick()
        val stale = checkNotNull(node("dialog-confirm").fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        realPauseResume(activity)
        awaitUniqueTag("page:主模型服务商")
        openLocalModelTab()
        reveal("local-model-rename:extra-local").performClick()
        node("catalog-model-editor").scrollToDescendant(hasTestTag("local-model-delete:extra-local"))
        node("local-model-delete:extra-local").performClick()
        val before = fixture.settings.localModelCatalogSnapshot()
        mainThread { assertTrue(stale()) }
        node("dialog-confirm").assertExists()
        assertEquals(before, fixture.settings.localModelCatalogSnapshot())
        node("dialog-cancel").performClick()
        assertEquals(0, fixture.opens.get()); assertNull(fixture.controller.active)
    }
    fun testUnsavedModelAddSurvivesRepeatedPauseWithoutWritingCatalog() {
        openLocalModels()
        val before = fixture.settings.localModelCatalogSnapshot()
        reveal("local-model-add").performClick()
        node("settings-field:展示名称").performTextReplacement("retained custom model")
        node("settings-field:Model ID").performTextReplacement("retained-model-id")
        node("settings-field:上下文窗口").performTextReplacement("32.768")
        repeat(2) {
            realPauseResume(activity)
            awaitUniqueTag("catalog-model-editor")
            node("dialog-confirm").assertIsEnabled()
            node("settings-field:展示名称").assertEditableText("retained custom model")
            node("settings-field:Model ID").assertEditableText("retained-model-id")
            node("settings-field:上下文窗口").assertEditableText("32.768")
            assertEquals(before, fixture.settings.localModelCatalogSnapshot())
        }
        node("dialog-cancel").performClick()
        assertEquals(before, fixture.settings.localModelCatalogSnapshot())
        assertEquals(0, fixture.opens.get())
    }
    fun testUnsavedModelEditSurvivesPauseAndRejectsOldSaveCallback() {
        openLocalModels()
        val before = fixture.settings.localModelCatalogSnapshot()
        reveal("local-model-rename:extra-local").performClick()
        node("settings-field:展示名称").performTextReplacement("retained local rename")
        val oldSave = checkNotNull(node("dialog-confirm").fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        realPauseResume(activity)
        awaitUniqueTag("catalog-model-editor")
        node("dialog-confirm").assertIsEnabled()
        mainThread { oldSave() }
        node("settings-field:展示名称").assertEditableText("retained local rename")
        assertEquals(before, fixture.settings.localModelCatalogSnapshot())
        node("dialog-confirm").performClick()
        awaitControl("resumed local rename saved") {
            fixture.settings.localModelCatalogSnapshot()?.models?.single { it.id == "extra-local" }?.displayName == "retained local rename"
        }
        assertEquals(0, fixture.opens.get())
    }

}
