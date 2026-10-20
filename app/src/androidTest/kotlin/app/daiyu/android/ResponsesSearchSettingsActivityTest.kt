package app.daiyu.android

import androidx.compose.ui.test.*

class ResponsesSearchSettingsActivityTest : WiredActivityTestCase() {
    fun testOfficialSearchSettingPersistsAndProtocolSwitchDoesNotRestoreIt() {
        setDraft("search settings unsent"); openModels()
        editModel("Base URL", "https://api.openai.com/v1")
        reveal("settings-switch:Provider 托管网页搜索").assertIsOff().performClick().assertIsOn()
        saveModel(); awaitUniqueTag("chat-draft")
        assertTrue(fixture.settings.load().requestOptions.openAiWebSearchEnabled)
        assertDraft("search settings unsent")
        openModels(); reveal("settings-switch:Provider 托管网页搜索").assertIsOn()
        chooseProviderProtocol("Anthropic Messages")
        reveal("settings-switch:Provider 托管网页搜索").assertIsOff().assertIsNotEnabled()
        chooseProviderProtocol("OpenAI Responses")
        reveal("settings-switch:Provider 托管网页搜索").assertIsOff().assertIsEnabled()
        assertTrue(fixture.settings.load().requestOptions.openAiWebSearchEnabled)
        assertEquals(0, fixture.opens.get())
    }
    fun testEnablingSearchAtGatewayDoesNotSaveOrSendAnything() {
        val before = fixture.settings.load()
        openModels(); reveal("settings-switch:Provider 托管网页搜索").performClick()
        saveModel()
        node("page:主模型服务商").assertExists()
        assertEquals(before, fixture.settings.load()); assertEquals(0, fixture.opens.get())
    }
}
