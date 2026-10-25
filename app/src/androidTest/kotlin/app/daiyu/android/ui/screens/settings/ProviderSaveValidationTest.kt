package app.daiyu.android.ui.screens.settings

import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import app.daiyu.android.WiredActivityTestCase
import top.yukonga.miuix.kmp.theme.MiuixTheme

class ProviderSaveValidationTest : WiredActivityTestCase() {
    private var draft by mutableStateOf(ModelSettingsDraftUi())
    private var enabled by mutableStateOf(true)
    private var hostError by mutableStateOf<String?>(null)
    private var saves = 0

    private fun render(onSave: (ModelSettingsDraftUi) -> Unit = { saves++ }) {
        mainThread { activity.setContent { MiuixTheme {
            ModelSettingsScreen(draft, { draft = it; hostError = null }, onSave,
                { error("no catalog") }, {}, errorText = hostError, enabled = enabled,
                providerName = "提供商", showCatalog = false)
        } } }
        awaitModelEditor()
    }

    fun testInvalidRowsOnlyShowOneSummaryAboveSaveAfterClickAndNeverSubmit() {
        val headerError = "第 1 项请求头名称不能为空"
        val bodyError = "字段值须为有效 JSON，且不能覆盖系统字段。"
        mainThread {
            draft = draft.copy(headerFieldsValid = false, headerFieldsError = headerError,
                headerFieldRows = listOf(ProviderFieldRow(0, "", "header-canary")),
                bodyFieldsValid = false, bodyFieldsError = bodyError,
                bodyFieldRows = listOf(ProviderFieldRow(0, "temperature", "not-json-canary")))
        }
        render()
        reveal("model-custom-headers").performClick()
        reveal("model-header-value:0").assertExists()
        ui.onNodeWithText(headerError).assertDoesNotExist()
        ui.onNodeWithText(bodyError).assertDoesNotExist()
        val save = reveal("model-save").assertIsEnabled()
        ui.onNodeWithText("$headerError\n$bodyError").assertDoesNotExist()
        save.performClick()
        reveal("model-save").assertIsDisplayed()
        val summary = ui.onNodeWithText("$headerError\n$bodyError")
        summary.assertIsDisplayed()
        assertTrue(summary.fetchSemanticsNode().boundsInRoot.bottom <=
            node("model-save").fetchSemanticsNode().boundsInRoot.top)
        assertEquals(0, saves)
        assertEquals("header-canary", draft.headerFieldRows!!.single().value)
        assertEquals("not-json-canary", draft.bodyFieldRows!!.single().value)
        assertEquals(0, fixture.opens.get())
    }

    fun testEditingClearsFailedAttemptAndCorrectedHeaderCanSave() {
        render()
        reveal("model-custom-headers").performClick()
        reveal("model-header-add").performClick()
        val message = checkNotNull(draft.headerFieldsError)
        ui.onNodeWithText(message).assertDoesNotExist()
        val save = reveal("model-save").assertIsEnabled()
        ui.onNodeWithText(message).assertDoesNotExist()
        save.performClick()
        ui.onNodeWithText(message).assertExists()
        assertEquals(0, saves)
        reveal("model-header-name:0").performTextReplacement("X-Project")
        reveal("model-save")
        ui.onNodeWithText(message).assertDoesNotExist()
        reveal("model-header-value:0").performTextReplacement("corrected")
        reveal("model-save").performClick()
        assertEquals(1, saves)
        assertTrue(draft.headerFieldsValid)
        assertEquals("corrected", org.json.JSONObject(draft.customHeaders).getString("x-project"))
        assertEquals(0, fixture.opens.get())
    }

    fun testMalformedOriginalConfigurationIsHiddenUntilSaveAndClearStillWorks() {
        val message = "自定义请求头配置无效，请修正或清除后保存。"
        mainThread { draft = draft.copy(customHeaders = "not-json-secret-canary") }
        render()
        reveal("model-custom-headers").performClick()
        ui.onNodeWithText(message).assertDoesNotExist()
        val save = reveal("model-save").assertIsEnabled()
        ui.onNodeWithText(message).assertDoesNotExist()
        save.performClick()
        reveal("model-save").assertIsDisplayed()
        ui.onNodeWithText(message).assertIsDisplayed()
        assertEquals(0, saves)
        reveal("model-custom-headers")
        ui.onNodeWithText("清除无效配置").performClick()
        reveal("model-save")
        ui.onNodeWithText(message).assertDoesNotExist()
        reveal("model-save").performClick()
        assertEquals(1, saves)
        assertEquals(0, org.json.JSONObject(draft.customHeaders).length())
        assertEquals(0, fixture.opens.get())
    }

    fun testInvalidGatewayUsesTheSameSaveSummary() {
        mainThread { draft = draft.copy(sessionGateway = draft.sessionGateway.copy(keyField = "model")) }
        render()
        reveal("provider-session-gateway").performClick()
        reveal("settings-field:字段").assertEditableText("model")
        ui.onNodeWithText("请检查网关字段。").assertDoesNotExist()
        val save = reveal("model-save").assertIsEnabled()
        ui.onNodeWithText("请检查网关字段。").assertDoesNotExist()
        save.performClick()
        reveal("model-save").assertIsDisplayed()
        ui.onNodeWithText("请检查网关字段。").assertIsDisplayed()
        assertEquals(0, saves)
        assertEquals(0, fixture.opens.get())
    }

    fun testDisabledAndStaleSaveCallbacksCannotCreateErrorsOrSubmit() {
        render()
        val stale = reveal("model-save").fetchSemanticsNode().config[RetainedModelSaveAction]
        mainThread { draft = draft.copy(headerFieldsValid = false, headerFieldsError = "请修正请求头") }
        ui.waitForIdle()
        mainThread { stale() }
        ui.onNodeWithText("请修正请求头").assertDoesNotExist()
        val current = reveal("model-save").fetchSemanticsNode().config[RetainedModelSaveAction]
        mainThread { enabled = false }
        ui.waitForIdle()
        node("model-save").assertIsNotEnabled()
        mainThread { current() }
        ui.onNodeWithText("请修正请求头").assertDoesNotExist()
        assertEquals(0, saves)
        assertEquals(0, fixture.opens.get())
    }

    fun testHostSaveFailureAppearsOnlyAfterSaveAndClearsOnEdit() {
        val message = "未保存，请检查输入和必要确认。"
        render { saves++; hostError = message }
        reveal("model-save").assertIsEnabled()
        ui.onNodeWithText(message).assertDoesNotExist()
        node("model-save").performClick()
        reveal("model-save").assertIsDisplayed()
        ui.onNodeWithText(message).assertIsDisplayed()
        assertTrue(ui.onNodeWithText(message).fetchSemanticsNode().boundsInRoot.bottom <=
            node("model-save").fetchSemanticsNode().boundsInRoot.top)
        assertEquals(1, saves)
        reveal("provider-name").performTextReplacement("新提供商")
        reveal("model-save")
        ui.onNodeWithText(message).assertDoesNotExist()
        assertEquals(0, fixture.opens.get())
    }
}
