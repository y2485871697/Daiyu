package app.daiyu.android

import androidx.activity.compose.setContent
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.screens.settings.ProviderManagementRoute
import app.daiyu.android.ui.screens.settings.RetainedProviderConfigurationForm

/** Real route/controller/store; no model or balance network request is made. */
class ProviderSavePositionActivityTest : WiredActivityTestCase() {
    internal override fun createFixture(): RunControlFixture = super.createFixture().also {
        it.settings.modelCatalogSnapshot()
    }

    private fun showProviders() {
        mainThread { activity.setContent {
            AgentAppTheme(AppearanceSettings(), false) {
                ProviderManagementRoute(fixture.controller, { true }, {}, engineeringUi = true)
            }
        } }
        awaitUniqueTag("provider-management")
    }

    private fun form(): Pair<Any, LazyListState> = ui.onNode(
        SemanticsMatcher.keyIsDefined(RetainedProviderConfigurationForm), useUnmergedTree = true,
    ).fetchSemanticsNode().config[RetainedProviderConfigurationForm]

    private fun awaitSavedForm(title: String) {
        awaitUniqueTag("page:$title")
        ui.waitUntil(5_000) {
            ui.onAllNodesWithTag("model-save").fetchSemanticsNodes().singleOrNull()?.config
                ?.contains(SemanticsProperties.Disabled) == false && !fixture.controller.dataBusy
        }
        awaitVisualIdle()
    }

    private fun saveAtBottom(title: String, savedTitle: String = title) {
        reveal("model-save", title, lazyKey = "model_save").assertIsEnabled()
        val before = form()
        val position = ui.runOnIdle {
            before.second.firstVisibleItemIndex to before.second.firstVisibleItemScrollOffset
        }
        assertTrue("test must save away from the top", position.first > 0)
        node("model-save").performClick()
        awaitSavedForm(savedTitle)
        val after = form()
        assertSame("save must not reconstruct the form", before.first, after.first)
        assertSame("save must keep the scroll owner", before.second, after.second)
        ui.runOnIdle {
            assertEquals(position.first, after.second.firstVisibleItemIndex)
            assertEquals(position.second, after.second.firstVisibleItemScrollOffset)
        }
        node("model-save").assertIsDisplayed()
        ui.onNodeWithText("正在读取提供商详情").assertDoesNotExist()
    }

    fun testFirstRegistrationAndFollowingEditSaveKeepOneFormAndBottomPosition() {
        val previous = fixture.controller.providers.snapshot()
        showProviders()
        reveal("provider-add", "模型提供商").performClick()
        reveal("provider-auth-api-key", "认证方式").performClick()
        awaitUniqueTag("page:添加提供商")
        reveal("provider-name", "添加提供商").performTextReplacement("Position provider")
        reveal("settings-field:Base URL", "添加提供商").performTextReplacement("https://position.invalid")
        reveal("settings-field:API Key", "添加提供商").performTextReplacement("position-test-key")
        saveAtBottom("添加提供商", "Position provider")
        val registered = fixture.controller.providers.snapshot()
        val slot = registered.providers.single { added -> previous.providers.none { it.slotId == added.slotId } }.slotId
        assertEquals(previous.activeSlotId, registered.activeSlotId)
        val editor = fixture.controller.providers.editor(slot)
        val saved = fixture.controller.providers.editing(editor).settings
        assertEquals("https://position.invalid", saved.endpoint)
        assertEquals("position-test-key", saved.apiKey)
        val catalog = fixture.controller.providers.withEditor(editor) { it.localModelCatalogSnapshot() }

        reveal("provider-name", "Position provider").performTextReplacement("Renamed position provider")
        saveAtBottom("Position provider", "Renamed position provider")
        val currentEditor = fixture.controller.providers.editor(slot)
        assertEquals(saved, fixture.controller.providers.editing(currentEditor).settings)
        val currentCatalog = fixture.controller.providers.withEditor(currentEditor) { it.localModelCatalogSnapshot() }
        assertEquals(catalog?.providerIdentity, currentCatalog?.providerIdentity)
        assertEquals(catalog?.models, currentCatalog?.models)
        assertEquals("Renamed position provider",
            fixture.controller.providers.snapshot().providers.single { it.slotId == slot }.name)

        // A real switch must NOT reuse the first provider's form or its scroll state.
        val first = form()
        mainThread { activity.onBackPressedDispatcher.onBackPressed() }
        awaitUniqueTag("provider-management")
        val other = previous.providers.first().slotId
        reveal("provider-open-$other", "模型提供商").performClick()
        awaitModelEditor()
        assertNotSame(first.first, form().first)
        assertNotSame(first.second, form().second)
        assertEquals(0, fixture.opens.get())
    }

    fun testExistingProviderSaveAndValidationFailureKeepPositionAndCatalog() {
        val snapshot = fixture.controller.providers.snapshot()
        val slot = snapshot.providers.first().slotId
        val before = fixture.settings.load()
        val catalog = fixture.settings.localModelCatalogSnapshot()
        showProviders()
        reveal("provider-open-$slot", "模型提供商").performClick()
        awaitModelEditor()
        val title = snapshot.providers.first().name
        saveAtBottom(title)
        assertEquals(before, fixture.settings.load())
        assertEquals(catalog?.providerIdentity, fixture.settings.localModelCatalogSnapshot()?.providerIdentity)
        assertEquals(catalog?.models, fixture.settings.localModelCatalogSnapshot()?.models)

        reveal("settings-field:API Key", title).performTextReplacement("")
        reveal("model-save", title, lazyKey = "model_save")
        val failedForm = form()
        node("model-save").performClick()
        reveal("model-save-error", title, lazyKey = "model_save").assertIsDisplayed()
        assertSame(failedForm.first, form().first)
        assertSame(failedForm.second, form().second)
        assertEquals(before, fixture.settings.load())
        assertEquals(catalog?.providerIdentity, fixture.settings.localModelCatalogSnapshot()?.providerIdentity)
        assertEquals(catalog?.models, fixture.settings.localModelCatalogSnapshot()?.models)
        assertEquals(0, fixture.opens.get())
    }
}
