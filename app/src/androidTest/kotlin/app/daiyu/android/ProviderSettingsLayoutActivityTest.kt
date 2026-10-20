package app.daiyu.android

import androidx.compose.ui.test.*
import app.daiyu.android.model.ProviderProtocol

class ProviderSettingsLayoutActivityTest : WiredActivityTestCase() {
    fun testRegisteredNameTabsAndCompactConnectionFieldsUseTheProductionEntry() {
        val before = fixture.settings.load()
        openModels()
        node("page:主模型服务商").assertExists()
        node("page:模型设置").assertDoesNotExist()
        selectProviderTab("配置")
        reveal("provider-name").assert(hasAnyDescendant(hasText("主模型服务商")))
        node("page:主模型服务商").performScrollToNode(hasTestTag("settings-field:Base URL"))
        val endpoint = node("settings-field:Base URL").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val key = node("settings-field:API Key").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val density = activity.resources.displayMetrics.density
        assertTrue("Single-line URL must not reserve a tall empty area", endpoint.height <= 64 * density)
        assertTrue("Key must directly follow URL", key.top >= endpoint.bottom && key.top - endpoint.bottom <= 72 * density)
        node("settings-secret-visibility:API Key").assertIsDisplayed().assertHasClickAction()
        reveal("provider-endpoint-select").assertTextContains("Responses API")
        selectProviderTab("模型")
        reveal("model-catalog-fetch").assertIsNotEnabled()
        selectProviderTab("配置")
        modelField("Base URL").assertEditableText(before.endpoint)
        assertEquals(before, fixture.settings.load())
        assertEquals(0, fixture.opens.get())
    }

    fun testNewProviderUsesItsRegisteredNameAndKeepsBothTabsBeforeFirstSave() {
        openProviderManagement()
        val before = fixture.controller.providers.snapshot()
        reveal("provider-add", "模型提供商").performClick()
        reveal("provider-auth-api-key", "认证方式").performClick()
        awaitUniqueTag("prompt-input")
        node("prompt-input").performTextReplacement("Custom provider layout")
        ui.onNodeWithText("保存").performClick()
        awaitControl("layout provider registered") {
            fixture.controller.providers.snapshot().providers.any { it.name == "Custom provider layout" }
        }
        val registered = fixture.controller.providers.snapshot()
        awaitUniqueTag("page:Custom provider layout")
        reveal("provider-name", "Custom provider layout").assert(hasAnyDescendant(hasText("Custom provider layout")))
        reveal("settings-radio:配置", "Custom provider layout").assertIsSelected()
        reveal("settings-radio:模型", "Custom provider layout").performClick()
        reveal("model-catalog-fetch", "Custom provider layout").assertIsNotEnabled()
        reveal("settings-radio:配置", "Custom provider layout").performClick()
        assertEquals(before.activeSlotId, fixture.controller.providers.snapshot().activeSlotId)
        assertEquals(registered, fixture.controller.providers.snapshot())
        assertEquals(0, fixture.opens.get())
    }

    fun testEndpointMenuAndResponsesPathRemainIndependentAndDoNotSaveOnSelection() {
        val before = fixture.settings.load()
        openModels()
        reveal("responses-path-select").performClick()
        node("responses-path-select-menu").assertExists()
        node("prompt-choice:1").performClick()
        reveal("provider-endpoint-select").assertTextContains("Responses API").performClick()
        node("provider-endpoint-select-menu").assertExists()
        node("settings-radio:OpenAI Responses").assertIsSelected().performClick()
        reveal("responses-path-select").assertTextContains("/responses")
        reveal("provider-endpoint-select").performClick()
        node("settings-radio:OpenAI Chat Completions").performClick()
        reveal("provider-endpoint-select").assertTextContains("Chat Completions API")
        node("responses-path-select").assertDoesNotExist()
        modelField("API Key").assertEditableText("")
        node("settings-switch:重输密钥并绑定").assertDoesNotExist()
        assertEquals(ProviderProtocol.RESPONSES, before.protocol)
        assertEquals(before, fixture.settings.load())
        assertEquals(0, fixture.opens.get())
    }

    fun testProtocolMenuHasNoGeminiAndKeyRebindSwitchIsAbsent() {
        val before = fixture.settings.load()
        openModels()
        reveal("provider-protocol-select").performClick()
        node("provider-family:openai").assertExists()
        node("settings-radio:Google Gemini").assertDoesNotExist()
        node("settings-radio:Anthropic Messages").assertExists().performClick()
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
        assertEquals(0, fixture.opens.get())
    }
}
