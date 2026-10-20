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
    private fun control(tag: String) = reveal(tag).performScrollTo()
    fun testBodyUsesRawJsonInputAndBlocksInvalidSaveWithoutLosingRowsOnRemount() {
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
    fun testGatewayEditsAreInProviderDraftAndOldCallbackCannotRevertNewDraft() {
        render()
        val gateway = control("provider-session-gateway")
        val (oldRule, oldChange) = gateway.fetchSemanticsNode().config[RetainedProviderGatewayChange]
        gateway.performClick()
        control("settings-field:字段").performTextReplacement("request_cache_key")
        val current = draft
        assertEquals("request_cache_key", current.sessionGateway.keyField)
        mainThread { oldChange(oldRule.copy(modelPattern = "stale-pattern")) }
        assertEquals(current, draft); assertSame(current, draft)
        assertEquals("request_cache_key", draft.sessionGateway.keyField)
        control("settings-field:模型正则（每一行一个）").performTextReplacement("current-pattern")
        ui.waitForIdle()
        node("settings-field:模型正则（每一行一个）").assertEditableText("current-pattern")
        assertEquals("current modelPattern edit", "current-pattern", draft.sessionGateway.modelPattern)
        assertEquals("newer keyField must remain", "request_cache_key", draft.sessionGateway.keyField)
        assertEquals(current.sessionGateway.pathPattern, draft.sessionGateway.pathPattern)
        assertEquals(current.sessionGateway.retention, draft.sessionGateway.retention)
        assertEquals(current.sessionGateway.keySource, draft.sessionGateway.keySource)
        assertEquals(current.copy(sessionGateway = current.sessionGateway.copy(modelPattern = "current-pattern")), draft)
        assertNull(saved); assertEquals(0, fixture.opens.get())
    }
}
