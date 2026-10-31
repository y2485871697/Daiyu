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
        get() = name != "testCacheChoicePersistsThroughProviderSaveAndReopen"
    private var draft by mutableStateOf(ModelSettingsDraftUi())
    private var saved: ModelSettingsDraftUi? = null
    private var saveCount = 0
    private fun showComponent() {
        mainThread {
            draft = ModelSettingsDraftUi(protocol = app.daiyu.android.model.ProviderProtocol.MESSAGES)
            activity.setContent {
                MiuixTheme {
                    Column(Modifier.fillMaxSize()) {
                        RequestBodyFieldsEditor(draft, true, true, { draft = it })
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
    fun testCacheChoicePersistsThroughProviderSaveAndReopen() {
        val before = fixture.settings.load()
        setDraft("Cache setting unsent draft")
        openProviderManagement()
        reveal("provider-add-anthropic", "模型提供商").performClick()
        reveal("provider-name", "添加提供商").performTextReplacement("Cache fixture")
        reveal("settings-field:Base URL", "添加提供商").performTextReplacement("https://fixture.invalid/v1")
        reveal("settings-field:API Key", "添加提供商").performTextReplacement("synthetic-key")
        reveal("provider-cache-retention", "添加提供商").assertIsDisplayed()
        ui.onNodeWithText("自定义请求体").assertIsDisplayed()
        node("provider-cache-retention").performClick()
        node("provider-cache-retention:1h").assertExists()
        node("provider-cache-retention:5m").performClick()
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        assertEquals(before, fixture.settings.load())
        reveal("model-save", "添加提供商").performClick()
        awaitUniqueTag("page:Cache fixture")
        val slot = fixture.controller.providers.snapshot().providers.single { it.name == "Cache fixture" }.slotId
        fun retention() = fixture.controller.providers.editing(fixture.controller.providers.editor(slot)).settings.requestOptions.promptCacheRetention
        assertEquals(app.daiyu.android.model.DaiyuPromptCacheRetention.FIVE_MINUTES, retention())
        reveal("provider-cache-retention", "Cache fixture").assertIsDisplayed()
        ui.onNodeWithText("自定义请求体").assertIsDisplayed()
        node("provider-cache-retention").performClick()
        node("provider-cache-retention:5m").assertExists()
        node("provider-cache-retention:none").performClick()
        assertEquals(app.daiyu.android.model.DaiyuPromptCacheRetention.FIVE_MINUTES, retention())
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        reveal("model-save", "Cache fixture").performClick()
        awaitControl("cache retention saved") { retention() == app.daiyu.android.model.DaiyuPromptCacheRetention.NONE }
        assertEquals(before, fixture.settings.load())
        backToChat(activity)
        assertDraft("Cache setting unsent draft")
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
