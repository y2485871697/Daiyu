package app.daiyu.android

import app.daiyu.android.ui.screens.settings.RetainedProviderFieldAdd

import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import app.daiyu.android.ui.screens.settings.*
import app.daiyu.android.model.ProviderProtocol
import org.json.JSONObject
import top.yukonga.miuix.kmp.theme.MiuixTheme

class ProviderHeadersActivityTest : WiredActivityTestCase() {
    private var draft by mutableStateOf(ModelSettingsDraftUi())
    private var saved: ModelSettingsDraftUi? = null
    private fun showComponent(headers: String = "") {
        mainThread {
            draft = ModelSettingsDraftUi(customHeaders = headers)
            activity.setContent {
                MiuixTheme {
                    ModelSettingsScreen(draft, { draft = it }, { saved = it }, { error("no fetch") }, {}, showCatalog = false)
                }
            }
        }
        awaitModelEditor()
    }
    private fun control(tag: String) = reveal(tag).scrollToVisible()
    private fun openEditor() { control("model-custom-headers").performClick() }
    private fun add(name: String, value: String, index: Int = 0) {
        control("model-header-add").performClick()
        control("model-header-name:$index").performTextReplacement(name)
        control("model-header-value:$index").performTextReplacement(value)
    }
    fun testInlineFieldsOnlyChangeDraftUntilSaveAndCanBeDeleted() {
        val before = fixture.settings.load()
        showComponent(); openEditor(); add("X-Project", "header-canary")
        assertEquals("header-canary", JSONObject(draft.customHeaders).getString("x-project"))
        assertNull(saved); assertEquals(before, fixture.settings.load())
        control("model-header-delete:0").performClick()
        assertEquals(0, JSONObject(draft.customHeaders).length())
        reveal("model-save").performClick()
        assertEquals(draft, saved); assertEquals(before, fixture.settings.load()); assertEquals(0, fixture.opens.get())
    }
    fun testDuplicatesAndOwnedHeadersBlockSaveWithoutReplacingLastValidJson() {
        showComponent("""{"X-Project":"original"}""")
        openEditor(); add("x-project", "duplicate", 1)
        assertFalse(draft.headerFieldsValid)
        assertEquals("original", JSONObject(draft.customHeaders).getString("X-Project"))
        reveal("model-save").assertIsNotEnabled()
        openEditorIfCollapsed()
        control("model-header-name:1").performTextReplacement("Authorization")
        assertFalse(draft.headerFieldsValid)
        reveal("model-save").assertIsNotEnabled()
        assertNull(saved); assertEquals(0, fixture.opens.get())
    }
    private fun openEditorIfCollapsed() {
        reveal("model-custom-headers")
        if (ui.onAllNodesWithTag("model-header-add").fetchSemanticsNodes().isEmpty()) openEditor()
    }
    fun testCollapseRetainsDraftAndPersistentSettingsRemainUnchanged() {
        showComponent("""{"X-Project":"original"}""")
        val settings = fixture.settings.load()
        openEditor(); control("model-header-value:0").performTextReplacement("unsaved")
        control("model-custom-headers").performClick()
        control("model-custom-headers").performClick()
        assertEquals("unsaved", JSONObject(draft.customHeaders).getString("x-project"))
        assertEquals(settings, fixture.settings.load()); assertNull(saved)
    }
    fun testPausedAddCallbackCannotChangeDraftAfterResume() {
        showComponent(); openEditor()
        val action = control("model-header-add").fetchSemanticsNode().config[RetainedProviderFieldAdd]
        val before = draft
        realPauseResume(activity, atPause = { action(); assertEquals(before, draft) })
        mainThread { action() }
        assertEquals(before, draft); assertNull(saved); assertEquals(0, fixture.opens.get())
    }
    fun testChangedProtocolRevokesOldAddCallback() {
        showComponent(); openEditor()
        val action = control("model-header-add").fetchSemanticsNode().config[RetainedProviderFieldAdd]
        mainThread { draft = draft.selectProtocol(ProviderProtocol.MESSAGES) }
        ui.waitForIdle(); val expected = draft
        mainThread { action() }
        assertEquals(expected, draft); assertEquals("", draft.customHeaders); assertNull(saved)
    }
}
