package app.daiyu.android

import androidx.compose.ui.test.*

class ResponsesSearchSettingsActivityTest : WiredActivityTestCase() {
    fun testOfficialSearchSettingPersistsAndProtocolSwitchDoesNotRestoreIt() {
        setDraft("search settings unsent"); openModels()
        editModel("Base URL", "https://api.openai.com/v1")
        reveal("settings-switch:原生网页搜索").assertIsOff().performClick().assertIsOn()
        saveModel(); awaitUniqueTag("chat-draft")
        assertTrue(fixture.settings.load().requestOptions.openAiWebSearchEnabled)
        assertDraft("search settings unsent")
        openModels(); reveal("settings-switch:原生网页搜索").assertIsOn()
        reveal("settings-radio:Anthropic Messages").performClick()
        reveal("settings-switch:原生网页搜索").assertIsOff().assertIsNotEnabled()
        reveal("settings-radio:OpenAI Responses").performClick()
        reveal("settings-switch:原生网页搜索").assertIsOff().assertIsEnabled()
        assertTrue(fixture.settings.load().requestOptions.openAiWebSearchEnabled)
        assertEquals(0, fixture.opens.get())
    }
    fun testEnablingSearchAtGatewayDoesNotSaveOrSendAnything() {
        val before = fixture.settings.load()
        openModels(); reveal("settings-switch:原生网页搜索").performClick()
        saveModel()
        node("page:模型设置").assertExists()
        assertEquals(before, fixture.settings.load()); assertEquals(0, fixture.opens.get())
    }
}
