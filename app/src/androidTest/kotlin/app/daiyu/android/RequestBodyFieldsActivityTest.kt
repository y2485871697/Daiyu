package app.daiyu.android

import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.daiyu.android.ui.screens.settings.ModelSettingsDraftUi
import app.daiyu.android.ui.screens.settings.ModelSettingsScreen
import app.daiyu.android.ui.screens.settings.RequestBodyFieldsEditor
import top.yukonga.miuix.kmp.theme.MiuixTheme
import app.daiyu.android.ui.screens.settings.RetainedRequestBodyFieldApply
import androidx.compose.ui.test.*
import org.json.JSONObject

class RequestBodyFieldsActivityTest : WiredActivityTestCase() {
    override val engineeringUi = true
    private var draft by mutableStateOf(ModelSettingsDraftUi())
    private var saved: ModelSettingsDraftUi? = null
    private var saveCount = 0
    private fun showComponent() {
        mainThread {
            draft = ModelSettingsDraftUi(protocol = fixture.settings.load().protocol)
            activity.setContent {
                MiuixTheme {
                    ModelSettingsScreen(draft, onDraftChange = { draft = it },
                        onSave = { saved = it; saveCount++ }, onFetchCatalog = { error("no fetch") },
                        onBack = {}, engineeringUi = true, showCatalog = false,
                        headerContent = { item { RequestBodyFieldsEditor(draft, true, true) { draft = it } } })
                }
            }
        }
        awaitModelEditor()
    }
    private fun fieldControl(tag: String, list: String = "model-body-field-editor-list"): SemanticsNodeInteraction {
        dismissIme()
        node(list).performScrollToNode(hasTestTag(tag))
        return node(tag).assertIsDisplayed()
    }
    private fun beginField(name: String, type: String, value: String) {
        reveal("model-body-fields").performClick()
        fieldControl("model-body-field-add", "model-body-fields-list").performClick()
        fieldControl("settings-field:字段名").performTextReplacement(name)
        fieldControl("settings-radio:$type").performClick()
        fieldControl("settings-field:字段值").performTextReplacement(value)
        fieldControl("model-body-field-apply")
    }
    fun testDismissImeUsesTheOpenFieldDialogWindow() {
        showComponent()
        reveal("model-body-fields").performClick()
        fieldControl("model-body-field-add", "model-body-fields-list").performClick()
        val field = node("settings-field:字段名")
        field.performClick()
        val dialogRoot = focusedWindowRoot()
        mainThread {
            assertNotSame(activity.window.decorView, dialogRoot)
            assertFalse(activity.window.decorView.hasWindowFocus())
            assertTrue(dialogRoot.hasWindowFocus())
            val focus = checkNotNull(dialogRoot.findFocus())
            val imm = dialogRoot.context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                as android.view.inputmethod.InputMethodManager
            imm.showSoftInput(focus, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
        ui.waitUntil(5_000) {
            var visible = false
            instrumentation.runOnMainSync {
                visible = androidx.core.view.ViewCompat.getRootWindowInsets(dialogRoot)
                    ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true
            }
            visible
        }
        dismissIme()
        mainThread {
            assertTrue(dialogRoot.isAttachedToWindow)
            val insets = checkNotNull(androidx.core.view.ViewCompat.getRootWindowInsets(dialogRoot))
            assertFalse(insets.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()))
        }
        dismissIme() // Already hidden in the same Dialog remains a valid no-op.
        mainThread {
            assertTrue(dialogRoot.isAttachedToWindow)
            assertFalse(checkNotNull(androidx.core.view.ViewCompat.getRootWindowInsets(dialogRoot))
                .isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()))
        }
        field.assertExists()
        assertSame(dialogRoot, focusedWindowRoot())
        fieldControl("model-body-field-apply").assertExists()
        assertEquals(0, fixture.opens.get())
    }
    fun testJsonBodyEditsOnlyDraftThenPersistsThroughTheExistingSave() {
        val before = fixture.settings.load()
        setDraft("JSON editor unsent draft"); openModels()
        editModel("自定义请求体", """{"metadata":{"marker":true,"numeric":"123"}}""")
        assertEquals(before, fixture.settings.load())
        saveModel()
        val body = JSONObject(fixture.settings.load().requestOptions.customBody)
        assertTrue(body.getJSONObject("metadata").getBoolean("marker"))
        assertEquals("123", body.getJSONObject("metadata").get("numeric"))
        assertEquals(before.copy(requestOptions = before.requestOptions.copy(customBody = body.toString())), fixture.settings.load())
        openModels()
        modelField("自定义请求体").assertEditableText(body.toString())
        editModel("自定义请求体", "{}")
        assertTrue(JSONObject(fixture.settings.load().requestOptions.customBody).has("metadata"))
        saveModel()
        assertFalse(JSONObject(fixture.settings.load().requestOptions.customBody).has("metadata"))
        assertDraft("JSON editor unsent draft")
        assertEquals(0, fixture.opens.get())
    }
    fun testPausedFieldApplyCapabilityCannotChangeTheComponentDraftOrSave() {
        val before = fixture.settings.load()
        showComponent()
        val initial = draft
        beginField("metadata", "对象", """{"marker":true}""")
        val apply = node("model-body-field-apply").fetchSemanticsNode().config[RetainedRequestBodyFieldApply]
        realPauseResume(activity, atPause = { apply(); assertEquals(initial, draft) })
        mainThread { apply() }
        assertEquals(initial, draft)
        assertNull(saved); assertEquals(0, saveCount)
        modelField("自定义请求体").assertEditableText(initial.customBody)
        node("model-body-field-apply").assertDoesNotExist()
        beginField("fresh", "对象", """{"marker":"fresh"}""")
        mainThread { apply() }
        assertEquals(initial, draft)
        node("model-body-field-apply").assertExists().performClick()
        assertEquals("fresh", JSONObject(draft.customBody).getJSONObject("fresh").getString("marker"))
        assertFalse(JSONObject(draft.customBody).has("metadata"))
        assertEquals(before, fixture.settings.load())
        saveModel()
        assertEquals(draft, saved); assertEquals(1, saveCount)
        assertEquals(before, fixture.settings.load())
        assertEquals(0, fixture.opens.get())
    }
}
