package app.daiyu.android

import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState

class ResponsesSearchSettingsActivityTest : WiredActivityTestCase() {
    internal override fun createFixture(): RunControlFixture = super.createFixture().also {
        it.settings.modelCatalogSnapshot()
    }

    private fun awaitLocalCatalogReady() {
        reveal("local-model-add", lazyKey = "catalog-state")
        ui.waitUntil(5_000) {
            ui.mainClock.advanceTimeByFrame()
            ui.onAllNodes(hasTestTag("local-model-add") and hasClickAction() and isEnabled())
                .fetchSemanticsNodes(atLeastOneRootRequired = false).size == 1
        }
    }

    private fun openCurrentModelSearchEditor(): String {
        selectProviderTab("模型")
        val id = fixture.settings.load().model
        awaitLocalCatalogReady()
        reveal("local-model:$id", lazyKey = "local-model:$id")
        val edit = hasContentDescription("模型设置") and hasClickAction() and
            hasAnyAncestor(hasTestTag("local-model:$id"))
        ui.onNode(edit).assertIsDisplayed().assertIsEnabled().performTouchInput { click(center) }
        awaitUniqueTag("catalog-model-editor")
        awaitNodeWindowFocus(node("catalog-model-editor"))
        awaitDialogEnter(node("catalog-model-editor"))
        node("catalog-model-editor").scrollToDescendant(hasTestTag("settings-switch:原生搜索"))
        return id
    }

    private fun changeSearchDraft(enabled: Boolean) {
        val switch = hasTestTag("settings-switch:原生搜索") and
            hasAnyAncestor(hasTestTag("catalog-model-editor"))
        val before = if (enabled) ToggleableState.Off else ToggleableState.On
        val after = if (enabled) ToggleableState.On else ToggleableState.Off
        ui.onNode(switch).assertIsDisplayed().assertIsEnabled().assert(
            SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, before))
        ui.onNode(switch).performTouchInput { click(center) }
        val expected = switch and SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, after)
        ui.waitUntil(5_000) {
            ui.mainClock.advanceTimeByFrame()
            ui.onAllNodes(expected).fetchSemanticsNodes(atLeastOneRootRequired = false).size == 1
        }
        ui.onNode(switch).assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, after))
    }

    private fun awaitModelSearchEditorClosed() {
        ui.waitUntil(5_000) {
            ui.mainClock.advanceTimeByFrame()
            ui.onAllNodesWithTag("catalog-model-editor")
                .fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty()
        }
    }

    private fun editCurrentModelSearch(enabled: Boolean) {
        val id = openCurrentModelSearchEditor()
        changeSearchDraft(enabled)
        node("dialog-confirm").assertIsDisplayed().assertIsEnabled().performTouchInput { click(center) }
        awaitControl("model search saved") {
            fixture.settings.localModelCatalogSnapshot()?.models?.singleOrNull { it.id == id }
                ?.settings?.requestOptions?.openAiWebSearchEnabled == enabled
        }
        awaitModelSearchEditorClosed()
        awaitLocalCatalogReady()
    }

    fun testSearchIsModelScopedAndProviderConfigurationHasNoSwitch() {
        setDraft("search settings unsent"); openModels()
        node("settings-switch:Provider 托管网页搜索").assertDoesNotExist()
        editCurrentModelSearch(true)
        assertTrue(fixture.settings.loadForRun().requestOptions.openAiWebSearchEnabled)
        assertFalse(fixture.settings.load().requestOptions.openAiWebSearchEnabled)
        selectProviderTab("配置")
        saveModel()
        assertTrue(fixture.settings.loadForRun().requestOptions.openAiWebSearchEnabled)
        backToChat(activity)
        assertDraft("search settings unsent")
        openModels(); openCurrentModelSearchEditor()
        changeSearchDraft(false)
        node("dialog-cancel").assertIsDisplayed().assertIsEnabled().performTouchInput { click(center) }
        awaitModelSearchEditorClosed()
        assertTrue(fixture.settings.loadForRun().requestOptions.openAiWebSearchEnabled)
        assertEquals(0, fixture.opens.get())
    }

    fun testChatModelSearchPersistsWithoutChangingEndpointOrSending() {
        val before = fixture.settings.load()
        openModels()
        chooseProviderProtocol("OpenAI Chat Completions")
        saveModel()
        editCurrentModelSearch(true)
        assertEquals(app.daiyu.android.model.ProviderProtocol.CHAT_COMPLETIONS, fixture.settings.loadForRun().protocol)
        assertTrue(fixture.settings.loadForRun().requestOptions.openAiWebSearchEnabled)
        assertEquals(before.endpoint, fixture.settings.load().endpoint)
        editCurrentModelSearch(false)
        assertFalse(fixture.settings.loadForRun().requestOptions.openAiWebSearchEnabled)
        assertEquals(0, fixture.opens.get())
    }

    fun testSaveErrorIsCenteredAndDoesNotEscapeConfigurationOrSurviveReopening() {
        val before = fixture.settings.load()
        openModels()
        modelField("API Key").performTextReplacement("")
        saveModel()
        val error = reveal("model-save-error", lazyKey = "model_save").assertIsDisplayed()
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        error.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) {
            assertTrue(it(layouts))
        }
        assertEquals(androidx.compose.ui.text.style.TextAlign.Center, layouts.single().layoutInput.style.textAlign)
        val pageBounds = node("page:主模型服务商").fetchSemanticsNode().boundsInRoot
        val errorBounds = error.fetchSemanticsNode().boundsInRoot
        assertEquals(pageBounds.center.x, errorBounds.center.x, 1f)
        assertEquals(before, fixture.settings.load())
        selectProviderTab("模型")
        node("model-save-error").assertDoesNotExist()
        ui.onNodeWithText("未保存，请检查输入和必要确认。").assertDoesNotExist()
        selectProviderTab("配置")
        reveal("model-save-error", lazyKey = "model_save").assertExists()
        mainThread { activity.onBackPressedDispatcher.onBackPressed() }
        awaitUniqueTag("provider-management")
        reveal("provider-open-primary", "模型提供商").assertIsEnabled()
        ui.onNodeWithText("未保存，请检查输入和必要确认。").assertDoesNotExist()
        node("provider-open-primary").performClick()
        awaitModelEditor()
        reveal("model-save", lazyKey = "model_save").assertIsDisplayed()
        node("model-save-error").assertDoesNotExist()
        modelField("API Key").assert(SemanticsMatcher.keyIsDefined(
            androidx.compose.ui.semantics.SemanticsProperties.Password))
        reveal("settings-secret-visibility:API Key").performClick()
        modelField("API Key").assertEditableText(before.apiKey).performTextReplacement("")
        saveModel()
        reveal("model-save-error", lazyKey = "model_save").assertExists()
        modelField("API Key").performTextReplacement(before.apiKey)
        reveal("model-save", lazyKey = "model_save").assertIsDisplayed()
        node("model-save-error").assertDoesNotExist()
        assertEquals(before, fixture.settings.load())
        assertEquals(0, fixture.opens.get())
    }

    fun testNewProviderValidationErrorDoesNotEscapeIntoAuthenticationOrDirectory() {
        openProviderManagement()
        val before = fixture.controller.providers.snapshot()
        reveal("provider-add", "模型提供商").performClick()
        reveal("provider-auth-api-key", "认证方式").performClick()
        awaitUniqueTag("page:添加提供商")
        reveal("model-save", "添加提供商", "model_save").performClick()
        reveal("model-save-error", "添加提供商", "model_save").assertIsDisplayed()
        mainThread { activity.onBackPressedDispatcher.onBackPressed() }
        awaitUniqueTag("page:认证方式")
        ui.onNodeWithText("未保存，请检查名称、连接配置及密钥。").assertDoesNotExist()
        reveal("provider-auth-api-key", "认证方式").performClick()
        awaitUniqueTag("page:添加提供商")
        reveal("model-save", "添加提供商", "model_save").assertIsDisplayed()
        node("model-save-error").assertDoesNotExist()
        mainThread { activity.onBackPressedDispatcher.onBackPressed() }
        awaitUniqueTag("page:认证方式")
        mainThread { activity.onBackPressedDispatcher.onBackPressed() }
        awaitUniqueTag("provider-management")
        ui.onNodeWithText("未保存，请检查名称、连接配置及密钥。").assertDoesNotExist()
        assertEquals(before, fixture.controller.providers.snapshot())
        assertEquals(0, fixture.opens.get())
    }
}
