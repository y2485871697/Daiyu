package app.daiyu.android.ui.screens.settings

import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.text.AnnotatedString
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
        control("model-body-fields").performClick()
        control("model-body-field-add").performClick()
        control("model-body-field-name:0").performTextReplacement("temperature")
        control("model-body-field-value:0").performTextReplacement("not-json")
        assertFalse(draft.bodyFieldsValid)
        assertEquals("not-json", draft.bodyFieldRows!!.single().value)
        render()
        control("model-body-fields").performClick()
        control("model-body-field-value:0").assertTextContains("not-json")
        control("model-body-field-value:0").performTextReplacement("0.1234567890123456789")
        assertTrue(draft.bodyFieldsValid)
        assertTrue(draft.customBody.contains("0.1234567890123456789"))
        control("model-save").performClick()
        assertEquals(draft, saved); assertEquals(0, fixture.opens.get())
    }
    fun testGatewayEditsAreInProviderDraftAndOldCallbackCannotRevertNewDraft() {
        render()
        control("provider-session-gateway").performClick()
        val oldSet = checkNotNull(control("settings-field:模型正则（每一行一个）").fetchSemanticsNode()
            .config[SemanticsActions.SetText].action)
        control("settings-field:字段").performTextReplacement("request_cache_key")
        val current = draft
        mainThread { oldSet(AnnotatedString("stale-pattern")) }
        assertEquals(current, draft)
        assertEquals("request_cache_key", draft.sessionGateway.keyField)
        assertNull(saved); assertEquals(0, fixture.opens.get())
    }
}
