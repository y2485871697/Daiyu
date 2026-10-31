package app.daiyu.android

import androidx.compose.ui.test.*
import androidx.activity.compose.setContent
import app.daiyu.android.title.TitleModelSettingsEditor
import app.daiyu.android.ui.FeatureCatalog
import app.daiyu.android.ui.FeatureRoute
import app.daiyu.android.ui.screens.settings.SettingsCatalog

class TitleModelSettingsActivityTest : WiredActivityTestCase() {
    private fun enter() {
        openSettings()
        reveal("settings-row:标题模型", "设置").performClick()
        node("page:标题模型").assertExists()
    }
    private fun owner(): TitleModelSettingsEditor {
        lateinit var result: TitleModelSettingsEditor
        mainThread {
            result = MainActivity::class.java.getDeclaredField("titleSettingsPageOwner")
                .apply { isAccessible = true }.get(activity) as TitleModelSettingsEditor
        }
        return result
    }
    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    fun testRepeatedModelSelectionKeepsSheetSearchAndDisabledSwitch() {
        val catalog = fixture.settings.modelCatalogSnapshot()
        assertEquals(app.daiyu.android.model.catalog.CatalogCommitResult.COMMITTED,
            fixture.settings.modelCatalog.compareAndAppend(catalog.providerIdentity, catalog.revision,
                listOf(app.daiyu.android.model.catalog.SavedCatalogModel("picker-audit-b", "picker-audit-b",
                    app.daiyu.android.model.catalog.CatalogModelParameters()))) { false })
        val source = app.daiyu.android.ui.screens.settings.SettingsModelSource(fixture.application, fixture.controller.providers)
        val choices = source.choices(app.daiyu.android.ui.screens.settings.SettingsModelProtocols)
        val namespace = "title-picker-${java.util.UUID.randomUUID()}"
        val preferences = fixture.application.getSharedPreferences(namespace, 0)
        var stored: app.daiyu.android.storage.ModelSettings? = null
        val store = app.daiyu.android.title.TitleModelSettingsStore(preferences,
            { checkNotNull(stored) }, { value, _, _ -> stored = value }, namespace)
        val editor = TitleModelSettingsEditor { store }
        try {
            mainThread { activity.setContent {
                app.daiyu.android.ui.app.AgentAppTheme(app.daiyu.android.data.model.AppearanceSettings(), applyInterfaceScale = false) {
                    app.daiyu.android.title.TitleModelSettingsScreen(editor, { true }, {}, source)
                }
            } }
            ui.waitUntil(timeoutMillis = 5000) {
                ui.onAllNodes(hasTestTag("title-model-configure") and hasClickAction()).fetchSemanticsNodes().size == 1
            }
            node("title-model-configure").performClick()
            ui.waitUntil(timeoutMillis = 5000) {
                ui.onAllNodes(hasTestTag("chat-model-picker") and SemanticsMatcher.expectValue(
                    androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "模型目录读取完成")).fetchSemanticsNodes().size == 1
            }
            node("chat-model-search").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetText) {
                assertTrue(it(androidx.compose.ui.text.AnnotatedString(choices.first().providerName)))
            }
            val sheet = node("chat-selection-sheet").fetchSemanticsNode().root
            val provider = choices.first().provider.slotId
            for (choice in choices.take(2)) {
                val id = choice.selection.record.id
                ui.onNode(hasTestTag("chat-model-option:$id") or hasTestTag("chat-model-option:$provider:$id")).performClick()
                ui.waitUntil(timeoutMillis = 5000) { source.key("title") == choice.key && stored?.model == choice.selection.record.id }
                assertSame(sheet, node("chat-selection-sheet").fetchSemanticsNode().root)
                node("chat-model-search").assertTextContains(choices.first().providerName)
                ui.onNodeWithText("正在读取模型目录…").assertDoesNotExist()
                ui.onNodeWithText("当前任务结束后可切换模型").assertDoesNotExist()
                node("chat-model-option:$id").assertIsSelected()
                assertFalse(store.snapshot().enabled)
            }
            assertEquals(0, fixture.opens.get())
        } finally { editor.revoke(); preferences.edit().clear().commit() }
    }

    fun testCustomOffDisplaysCurrentModelWithoutMakingARequest() {
        enter()
        node("title-model-configure").assertExists()
        ui.onNodeWithText("当前使用的模型").assertExists()
        assertEquals(0, fixture.opens.get())
    }

    fun testP08AndSettingsRouteAreRealLocalOnlyEntry() {
        assertEquals(FeatureRoute.TITLE_MODEL_SETTINGS, FeatureCatalog.groups.flatMap { it.entries }.single { it.id == "P08" }.route)
        assertEquals(FeatureRoute.TITLE_MODEL_SETTINGS, SettingsCatalog.entry("title_model").route)
        val before = fixture.database.exportBackup()
        enter()
        assertEquals(before, fixture.database.exportBackup())
        assertEquals(0, fixture.opens.get()); assertNull(fixture.controller.active)
    }
    fun testLeavingAndRecreatingPermanentlyRevokesVisitWithoutRestoringKeyDraft() {
        enter(); val first = owner()
        backToPage(activity, "page:设置")
        assertNull(first.prepareLoad())
        reveal("settings-row:标题模型", "设置").performClick()
        val replacement = owner(); assertNotSame(first, replacement)
        activity = recreateActivity(activity)
        assertNull(replacement.prepareLoad())
        node("chat-draft").assertExists()
        assertEquals(0, fixture.opens.get())
    }
}
