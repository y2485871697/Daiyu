package app.daiyu.android

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
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
    override val engineeringUi: Boolean
        get() = name != "testJsonBodyEditsOnlyDraftThenPersistsThroughTheExistingSave"
    private var draft by mutableStateOf(ModelSettingsDraftUi())
    private var saved: ModelSettingsDraftUi? = null
    private var saveCount = 0
    private fun showComponent() {
        mainThread {
            draft = ModelSettingsDraftUi(protocol = app.daiyu.android.model.ProviderProtocol.MESSAGES)
            activity.setContent {
                MiuixTheme {
                    Column(Modifier.fillMaxSize()) {
                        ModelSettingsScreen(draft, onDraftChange = { draft = it },
                            onSave = { saved = it; saveCount++ }, onFetchCatalog = { error("no fetch") },
                            onBack = {}, modifier = Modifier.weight(1f), engineeringUi = true, showCatalog = false)
                    }
                }
            }
        }
        awaitModelEditor()
    }
    private fun fieldControl(tag: String, list: String = "model-body-field-editor-list"): SemanticsNodeInteraction {
        dismissIme()
        node(list).scrollToDescendant(hasTestTag(tag))
        return node(tag).assertIsDisplayed()
    }
    private fun beginField(name: String, type: String, value: String) {
        reveal("model-body-fields").assertExists()
            .performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it()) }
        awaitUniqueTag("model-body-fields-list")
        fieldControl("model-body-field-add", "model-body-fields-list").performClick()
        fieldControl("settings-field:字段名").performTextReplacement(name)
        fieldControl("settings-radio:$type").performClick()
        fieldControl("settings-field:字段值").performTextReplacement(value)
        fieldControl("model-body-field-apply")
    }
    fun testDismissImeUsesTheOpenFieldDialogWindow() {
        showComponent()
        reveal("model-body-fields").assertExists()
            .performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it()) }
        awaitUniqueTag("model-body-fields-list")
        fieldControl("model-body-field-add", "model-body-fields-list").performClick()
        val field = node("settings-field:字段名")
        field.performClick()
        val dialogRoot = focusedWindowRoot()
        mainThread {
            assertNotSame(activity.window.decorView, dialogRoot)
            var windowContext = dialogRoot.context
            while (windowContext is android.content.ContextWrapper && windowContext !is android.app.Activity) {
                windowContext = windowContext.baseContext
            }
            assertSame(activity, windowContext)
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
        val metadata = """{"marker":true,"numeric":"123"}"""
        setDraft("JSON editor unsent draft")
        openProviderManagement()
        reveal("provider-add-anthropic", "模型提供商").performClick()
        reveal("provider-name", "添加提供商").performTextReplacement("Body fixture")
        reveal("settings-field:Base URL", "添加提供商").performTextReplacement("https://fixture.invalid/v1")
        reveal("settings-field:API Key", "添加提供商").performTextReplacement("synthetic-key")
        fun openBody(page: String) {
            reveal("model-body-fields", page)
            if (ui.onAllNodesWithTag("model-body-field-add").fetchSemanticsNodes().isEmpty()) {
                node("model-body-fields").performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it()) }
            }
            reveal("model-body-field-add", page)
        }
        openBody("添加提供商")
        reveal("model-body-field-add", "添加提供商").performClick()
        reveal("model-body-field-name:0", "添加提供商").performTextReplacement("metadata")
        reveal("model-body-field-value:0", "添加提供商").performTextReplacement(metadata)
        assertEquals(before, fixture.settings.load())
        reveal("model-save", "添加提供商").performClick()
        awaitUniqueTag("page:Body fixture")
        val slot = fixture.controller.providers.snapshot().providers.single { it.name == "Body fixture" }.slotId
        fun body() = JSONObject(fixture.controller.providers.editing(fixture.controller.providers.editor(slot)).settings.requestOptions.customBody)
        assertTrue(body().getJSONObject("metadata").getBoolean("marker"))
        assertEquals("123", body().getJSONObject("metadata").get("numeric"))
        openBody("Body fixture")
        reveal("model-body-field-name:0", "Body fixture").assertEditableText("metadata")
        reveal("model-body-field-value:0", "Body fixture").assertEditableText(metadata)
        reveal("model-body-field-delete:0", "Body fixture").assertIsEnabled().performClick()
        assertTrue(body().has("metadata"))
        reveal("model-save", "Body fixture").performClick()
        awaitControl("body removal saved") { !body().has("metadata") }
        assertEquals(before, fixture.settings.load())
        backToChat(activity)
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
        reveal("model-body-fields").performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it()) }
        awaitUniqueTag("model-body-fields-list")
        node("model-body-field:0").assertDoesNotExist()
        ui.onNodeWithText("关闭").performClick()
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
