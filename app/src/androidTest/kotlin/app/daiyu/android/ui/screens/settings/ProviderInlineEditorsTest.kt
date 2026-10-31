package app.daiyu.android.ui.screens.settings

import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import app.daiyu.android.WiredActivityTestCase
import top.yukonga.miuix.kmp.theme.MiuixTheme

class ProviderInlineEditorsTest : WiredActivityTestCase() {
    private var draft by mutableStateOf(ModelSettingsDraftUi())
    private var saved: ModelSettingsDraftUi? = null
    private fun render() {
        mainThread { activity.setContent { MiuixTheme {
            ModelSettingsScreen(draft, { draft = it }, { saved = it }, { error("no network") }, {}, showCatalog = false)
        } } }
        awaitModelEditor()
    }
    private fun control(tag: String) = reveal(tag).scrollToVisible()
    fun testCacheChoicesOnlyEditDraftAndSurviveRemountBeforeSave() {
        mainThread { draft = draft.copy(protocol = app.daiyu.android.model.ProviderProtocol.MESSAGES) }
        render()
        control("provider-cache-retention").assertExists()
        ui.onNodeWithText("提示缓存").assertIsDisplayed()
        ui.onNodeWithText("1小时").assertExists()
        node("model-body-fields").assertDoesNotExist()
        control("provider-cache-retention").performClick()
        for (value in app.daiyu.android.model.DaiyuPromptCacheRetention.entries) {
            node("provider-cache-retention:${value.wireValue}").assertExists().performClick()
            assertEquals(value, draft.promptCacheRetention)
            assertNull(saved)
            node("provider-cache-retention-menu").assertExists()
        }
        node("provider-cache-retention:5m").performClick()
        render()
        control("provider-cache-retention").assertExists()
        ui.onNodeWithText("提示缓存").assertIsDisplayed()
        ui.onNodeWithText("5分钟").assertExists()
        control("model-save").performClick()
        assertEquals(app.daiyu.android.model.DaiyuPromptCacheRetention.FIVE_MINUTES, saved?.promptCacheRetention)
        assertEquals(0, fixture.opens.get())
    }
    fun testGatewayIsHiddenAndUnrelatedEditsPreserveItsEffectivePolicy() {
        mainThread { draft = draft.copy(sessionGateway = draft.sessionGateway.copy(keyField = "request_cache_key")) }
        render()
        node("provider-session-gateway").assertDoesNotExist()
        ui.onNodeWithText("会话 ID").assertDoesNotExist()
        val rule = draft.sessionGateway
        control("settings-field:Base URL").performTextReplacement("https://fixture.invalid/v1")
        assertEquals(rule, draft.sessionGateway)
        control("model-save").performClick()
        assertEquals(rule, saved?.sessionGateway)
        assertEquals(0, fixture.opens.get())
    }
    fun testRequestFieldsFollowProtocolAndSpeechEntryWithoutChangingHiddenDraft() {
        val before = fixture.settings.load()
        mainThread { draft = draft.copy(customBody = "not-json-hidden", bodyFieldsValid = false,
            bodyFieldsError = "hidden body error") }
        render()
        node("model-body-fields").assertDoesNotExist()
        node("provider-cache-retention").assertDoesNotExist()
        ui.onNodeWithText("提示缓存").assertDoesNotExist()
        control("model-custom-headers").assertExists()
        control("model-save").performClick()
        assertNotNull(saved)
        assertEquals("not-json-hidden", draft.customBody)
        mainThread { draft = draft.copy(protocol = app.daiyu.android.model.ProviderProtocol.MESSAGES,
            customBody = "", bodyFieldsValid = true, bodyFieldsError = null) }
        render()
        control("provider-cache-retention").assertExists()
        ui.onNodeWithText("提示缓存").assertIsDisplayed()
        mainThread { activity.setContent { MiuixTheme {
            ModelSettingsScreen(draft, { draft = it }, { saved = it }, {}, {}, showCatalog = false, speechOnly = true)
        } } }
        awaitModelEditor()
        node("model-custom-headers").assertDoesNotExist()
        node("provider-cache-retention").assertDoesNotExist()
        ui.onNodeWithText("提示缓存").assertDoesNotExist()
        node("model-body-fields").assertDoesNotExist()
        node("provider-session-gateway").assertDoesNotExist()
        assertEquals(before, fixture.settings.load())
        assertEquals(0, fixture.opens.get())
    }
}
