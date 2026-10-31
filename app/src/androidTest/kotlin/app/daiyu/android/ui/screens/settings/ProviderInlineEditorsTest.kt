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
    fun testBodyUsesRawJsonInputAndBlocksInvalidSaveWithoutLosingRowsOnRemount() {
        mainThread { draft = draft.copy(protocol = app.daiyu.android.model.ProviderProtocol.MESSAGES) }
        render()
        addInlineBodyField("temperature", "not-json")
        assertFalse(draft.bodyFieldsValid)
        assertEquals("not-json", draft.bodyFieldRows!!.single().value)
        attemptBlockedInlineBodySave(); assertNull(saved)
        render()
        openInlineBodyFields()
        inlineBodyControl("model-body-field-value:0").assertEditableText("not-json")
        assertFalse(draft.bodyFieldsValid)
        attemptBlockedInlineBodySave(); assertNull(saved)
        inlineBodyControl("model-body-field-value:0").performTextReplacement("0.1234567890123456789")
        assertTrue(draft.bodyFieldsValid)
        assertTrue(draft.customBody.contains("0.1234567890123456789"))
        control("model-save").performClick()
        assertEquals(draft, saved); assertEquals(0, fixture.opens.get())
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
        control("model-custom-headers").assertExists()
        control("model-save").performClick()
        assertNotNull(saved)
        assertEquals("not-json-hidden", draft.customBody)
        mainThread { draft = draft.copy(protocol = app.daiyu.android.model.ProviderProtocol.MESSAGES,
            customBody = "", bodyFieldsValid = true, bodyFieldsError = null) }
        render()
        control("model-body-fields").assertExists()
        mainThread { activity.setContent { MiuixTheme {
            ModelSettingsScreen(draft, { draft = it }, { saved = it }, {}, {}, showCatalog = false, speechOnly = true)
        } } }
        awaitModelEditor()
        node("model-custom-headers").assertDoesNotExist()
        node("model-body-fields").assertDoesNotExist()
        node("provider-session-gateway").assertDoesNotExist()
        assertEquals(before, fixture.settings.load())
        assertEquals(0, fixture.opens.get())
    }
}
