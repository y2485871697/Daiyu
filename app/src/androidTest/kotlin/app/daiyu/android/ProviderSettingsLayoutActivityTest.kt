package app.daiyu.android

import androidx.compose.ui.test.*

class ProviderSettingsLayoutActivityTest : WiredActivityTestCase() {
    fun testConnectionFieldsStayCompactAndSecretActionSharesTheFieldCenter() {
        openModels()
        val name = reveal("provider-name-container").fetchSemanticsNode().boundsInRoot
        val endpoint = reveal("settings-field:Base URL-container").fetchSemanticsNode().boundsInRoot
        val key = reveal("settings-field:API Key-container").fetchSemanticsNode().boundsInRoot
        val eye = node("settings-secret-visibility:API Key").fetchSemanticsNode().boundsInRoot
        val density = activity.resources.displayMetrics.density
        assertTrue("name must not reserve multiline space", name.height / density < 80f)
        assertTrue("URL must not reserve multiline space", endpoint.height / density < 80f)
        assertTrue("secret must not add a button row", key.height / density < 90f)
        assertEquals(key.center.y, eye.center.y, 2f)
        modelField("API Key").assert(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.Password))
        assertEquals(0, fixture.opens.get())
    }

    fun testInlineNameAndConnectionFieldsHaveNoLegacyModelOrPathControls() {
        val before = fixture.settings.load()
        openModels()
        reveal("provider-name").assertEditableText("主模型服务商").performTextReplacement("本地名称草稿")
        modelField("Base URL").assertEditableText(before.endpoint)
        node("provider-protocol-select").assertDoesNotExist()
        node("responses-path-select").assertDoesNotExist()
        node("settings-field:模型名").assertDoesNotExist()
        assertEquals("主模型服务商", fixture.controller.providers.snapshot().providers.single().name)
        assertEquals(before, fixture.settings.load())
        selectProviderTab("模型")
        reveal("model-catalog-fetch").assertExists()
        reveal("local-model-add").assertExists()
        reveal("local-model-search").assertExists()
        selectProviderTab("配置")
        reveal("provider-name").assertEditableText("本地名称草稿")
        assertEquals(0, fixture.opens.get())
    }
    fun testNameIsSavedInsideConfigurationWithoutChangingModelOrCredentials() {
        val before = fixture.settings.load()
        openModels()
        reveal("provider-name").performTextReplacement("已改名供应商")
        saveModel()
        awaitControl("name saved") { fixture.controller.providers.snapshot().providers.single().name == "已改名供应商" }
        assertEquals(before, fixture.settings.load())
        assertEquals(0, fixture.opens.get())
    }
    fun testEndpointMenuHasExactChoicesAndDoesNotSaveOnSelection() {
        val before = fixture.settings.load()
        openModels()
        reveal("provider-endpoint-select").performClick()
        node("settings-radio:OpenAI Responses").assertIsSelected()
        node("settings-radio:Google Gemini").assertDoesNotExist()
        node("settings-radio:Anthropic Messages").assertDoesNotExist()
        node("settings-radio:OpenAI Chat Completions").performClick()
        reveal("provider-endpoint-select").assertTextContains("Chat Completions API")
        modelField("API Key").assertEditableText("")
        node("settings-switch:重输密钥并绑定").assertDoesNotExist()
        assertEquals(before, fixture.settings.load())
        assertEquals(0, fixture.opens.get())
    }
    fun testMenuClosesOnPauseAndDoesNotChangeSavedSettings() {
        val before = fixture.settings.load()
        openModels()
        reveal("provider-endpoint-select").performClick()
        node("provider-endpoint-select-menu").assertExists()
        realPauseResume(activity, atPause = {})
        awaitModelEditor()
        node("provider-endpoint-select-menu").assertDoesNotExist()
        assertEquals(before, fixture.settings.load())
    }
}
