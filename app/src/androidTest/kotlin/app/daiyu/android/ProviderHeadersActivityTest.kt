package app.daiyu.android

import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import app.daiyu.android.ui.screens.settings.ModelSettingsDraftUi
import app.daiyu.android.ui.screens.settings.ModelSettingsScreen
import app.daiyu.android.ui.screens.settings.selectProtocol
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
                    ModelSettingsScreen(draft, onDraftChange = { draft = it },
                        onSave = { saved = it }, onFetchCatalog = { error("no fetch") },
                        onBack = {}, showCatalog = false)
                }
            }
        }
        awaitModelEditor()
    }
    private fun control(tag: String): SemanticsNodeInteraction {
        dismissIme()
        node("model-headers-list").performScrollToNode(hasTestTag(tag))
        return node(tag).assertIsDisplayed()
    }
    private fun openEditor() {
        reveal("model-custom-headers").performClick()
        awaitUniqueTag("model-headers-list")
    }
    private fun add(name: String, value: String, index: Int = 1) {
        control("model-header-add").performClick()
        control("settings-field:请求头名称 $index").performTextReplacement(name)
        control("settings-field:请求头值 $index").performTextReplacement(value)
    }
    fun testFieldsOnlyChangeDraftUntilExistingSaveAndCanBeDeleted() {
        val before = fixture.settings.load()
        showComponent()
        openEditor(); add("X-Project", "header-canary")
        control("model-custom-headers-apply").performClick()
        assertEquals("header-canary", JSONObject(draft.customHeaders).getString("x-project"))
        assertNull(saved)
        assertEquals(before, fixture.settings.load())
        openEditor()
        control("model-header-delete:0").performClick()
        control("model-custom-headers-apply").performClick()
        assertEquals(0, JSONObject(draft.customHeaders).length())
        reveal("model-save").performClick()
        assertEquals(draft, saved)
        assertEquals(before, fixture.settings.load())
        assertEquals(0, fixture.opens.get())
    }
    fun testCaseInsensitiveDuplicatesAndRuntimeOwnedHeadersDoNotApply() {
        showComponent("""{"X-Project":"original"}""")
        val before = draft
        openEditor(); add("x-project", "duplicate", 2)
        control("model-custom-headers-apply").performClick()
        assertEquals(before, draft)
        control("settings-field:请求头名称 2").performTextReplacement("Authorization")
        control("model-custom-headers-apply").performClick()
        assertEquals(before, draft)
        assertNull(saved)
        assertEquals(0, fixture.opens.get())
    }
    fun testCancelledEditLeavesExistingHeaderAndPersistentSettingsUnchanged() {
        showComponent("""{"X-Project":"original"}""")
        val before = draft
        val settings = fixture.settings.load()
        openEditor()
        control("settings-field:请求头值 1").performTextReplacement("unsaved")
        dismissIme()
        ui.onNodeWithText("取消").performClick()
        assertEquals(before, draft)
        assertEquals(settings, fixture.settings.load())
        assertNull(saved)
    }
    fun testPausedApplyCannotChangeDraftAfterResume() {
        showComponent()
        val before = draft
        openEditor(); add("X-Project", "late")
        val apply = checkNotNull(control("model-custom-headers-apply")
            .fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        realPauseResume(activity, atPause = { apply(); assertEquals(before, draft) })
        mainThread { apply() }
        assertEquals(before, draft)
        node("model-headers-list").assertDoesNotExist()
        assertNull(saved)
        assertEquals(0, fixture.opens.get())
    }
    fun testReplacedProtocolRevokesOldHeaderApply() {
        showComponent()
        openEditor(); add("X-Project", "late")
        val apply = checkNotNull(control("model-custom-headers-apply")
            .fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        mainThread { draft = draft.selectProtocol(ProviderProtocol.MESSAGES) }
        ui.waitForIdle()
        val expected = draft
        mainThread { apply() }
        assertEquals(expected, draft)
        assertEquals("", draft.customHeaders)
        node("model-headers-list").assertDoesNotExist()
        assertNull(saved)
        assertEquals(0, fixture.opens.get())
    }
}
