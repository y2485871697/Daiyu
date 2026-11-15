package app.daiyu.android

import androidx.activity.compose.setContent
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import app.daiyu.android.ui.screens.settings.ModelSettingsDraftUi
import app.daiyu.android.ui.screens.settings.ModelSettingsScreen
import app.daiyu.android.ui.screens.settings.RetainedModelSaveAction
import app.daiyu.android.ui.screens.settings.rememberUpdatedSettingsDraft
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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

        reveal("provider-name", "Renamed position provider").performTextReplacement("Third position name")
        node("provider-name").assertTextContains("Third position name")
        saveAtBottom("Renamed position provider", "Third position name")
        assertEquals("Third position name", fixture.controller.providers.snapshot().providers.single { it.slotId == slot }.name)

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

        reveal("provider-name", title).performTextReplacement("After unchanged save")
        node("provider-name").assertTextContains("After unchanged save")
        saveAtBottom(title, "After unchanged save")
        reveal("provider-name", "After unchanged save").performTextReplacement("After renamed save")
        node("provider-name").assertTextContains("After renamed save")
        saveAtBottom("After unchanged save", "After renamed save")
        assertEquals("After renamed save", fixture.controller.providers.snapshot().providers.single { it.slotId == slot }.name)
        val currentTitle = "After renamed save"
        reveal("settings-field:API Key", currentTitle).performTextReplacement("")
        reveal("model-save", currentTitle, lazyKey = "model_save")
        val failedForm = form()
        node("model-save").performClick()
        reveal("model-save-error", currentTitle, lazyKey = "model_save").assertIsDisplayed()
        assertSame(failedForm.first, form().first)
        assertSame(failedForm.second, form().second)
        assertEquals(before, fixture.settings.load())
        assertEquals(catalog?.providerIdentity, fixture.settings.localModelCatalogSnapshot()?.providerIdentity)
        assertEquals(catalog?.models, fixture.settings.localModelCatalogSnapshot()?.models)
        assertEquals(0, fixture.opens.get())
    }

    fun testEqualDraftReplacementUpdatesIdentityAndKeepsCurrentNameEditable() {
        val original = ModelSettingsDraftUi(providerName = "Equal provider", endpoint = "https://equal.invalid", apiKey = "test-key")
        val draftState = mutableStateOf(original, referentialEqualityPolicy())
        var currentIdentity: () -> Boolean = { false }
        var saves = 0
        mainThread { activity.setContent {
            AgentAppTheme(AppearanceSettings(), false) {
                val draft = draftState.value
                val latest by rememberUpdatedSettingsDraft(draft)
                currentIdentity = { latest === draft }
                ModelSettingsScreen(draft, { draftState.value = it }, { saves++ }, {},
                    providerName = "Equal provider", pageTitle = "Equal provider", showCatalog = false)
            }
        } }
        awaitUniqueTag("provider-name")
        val staleIdentity = currentIdentity
        val staleSubmit = reveal("model-save", "Equal provider", lazyKey = "model_save")
            .fetchSemanticsNode().config[RetainedModelSaveAction]
        val replacement = original.copy()
        assertEquals(original, replacement)
        assertNotSame(original, replacement)
        mainThread { draftState.value = replacement }
        ui.waitForIdle()
        assertFalse("an equal old draft must not regain editing authority", staleIdentity())
        assertTrue("the latest equal draft must own new callbacks", currentIdentity())
        mainThread { staleSubmit() }
        assertEquals("an old equal draft cannot submit", 0, saves)
        reveal("provider-name", "Equal provider").performTextReplacement("Equal draft is editable")
        node("provider-name").assertTextContains("Equal draft is editable")
        reveal("model-save", "Equal provider", lazyKey = "model_save").performClick()
        assertEquals(1, saves)
    }

    fun testBlockedSaveKeepsButtonColorsAndRejectsTouchAndStaleActions() {
        val snapshot = fixture.controller.providers.snapshot()
        val slot = snapshot.providers.first().slotId
        val title = snapshot.providers.first().name
        showProviders()
        reveal("provider-open-$slot", "模型提供商").performClick()
        awaitModelEditor()
        reveal("model-save", title, lazyKey = "model_save")
        val host = ui.onNode(SemanticsMatcher.keyIsDefined(RetainedProviderConfigurationForm), useUnmergedTree = true)
        val bounds = host.fetchSemanticsNode().boundsInRoot
        val buttonBounds = node("model-save").fetchSemanticsNode().boundsInRoot
        val sample = Offset(buttonBounds.left - bounds.left + buttonBounds.width * .2f,
            buttonBounds.top - bounds.top + buttonBounds.height * .5f)
        val before = captureScreenNode(host).toPixelMap()
        val expected = before[sample.x.toInt(), sample.y.toInt()]
        val staleSave = node("model-save").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val held = executor.submit<Unit> {
            fixture.settings.modelCatalog.readConfiguration {
                entered.countDown()
                check(release.await(30, TimeUnit.SECONDS))
            }
        }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            node("model-save").performClick()
            ui.waitUntil(5_000) { fixture.controller.dataBusy }
            ui.waitForIdle()
            host.assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Disabled))
            ui.onNodeWithTag("model-save").assertDoesNotExist()
            val pending = captureScreenNode(host).toPixelMap()
            val actual = pending[sample.x.toInt(), sample.y.toInt()]
            assertEquals("save keeps the normal red channel", expected.red, actual.red, .02f)
            assertEquals("save keeps the normal green channel", expected.green, actual.green, .02f)
            assertEquals("save keeps the normal blue channel", expected.blue, actual.blue, .02f)
            host.performTouchInput { click(sample) }
            mainThread { staleSave(); staleSave() }
            assertTrue(fixture.controller.dataBusy)
            release.countDown()
            held.get(5, TimeUnit.SECONDS)
            awaitSavedForm(title)
            ui.onNodeWithTag("model-save-error").assertDoesNotExist()
            reveal("provider-name", title).performTextReplacement("Editable after blocked save")
            node("provider-name").assertTextContains("Editable after blocked save")
            assertEquals(0, fixture.opens.get())
        } finally {
            release.countDown()
            held.get(5, TimeUnit.SECONDS)
            executor.shutdownNow()
        }
    }

}
