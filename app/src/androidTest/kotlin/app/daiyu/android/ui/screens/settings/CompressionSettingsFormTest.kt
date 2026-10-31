package app.daiyu.android.ui.screens.settings

import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import app.daiyu.android.WiredActivityTestCase
import app.daiyu.android.context.CompressionSettingsEditor
import app.daiyu.android.context.SummaryModelBinding
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.data.model.AppearanceThemeMode
import app.daiyu.android.model.GptSpeedMode
import app.daiyu.android.model.ProviderProtocol
import app.daiyu.android.model.ProviderRequestOptions
import app.daiyu.android.storage.ModelSettings
import app.daiyu.android.ui.app.AgentAppTheme

/** Exercises actual form controls and captured requests, without a provider or persistence. */
class CompressionSettingsFormTest : WiredActivityTestCase() {
    private var submitted: CompressionSettingsEditor.SaveRequest? = null
    private var pickerOpens = 0
    private var observed: CompressionSettingsDraftUi? = null

    private fun draft() = CompressionSettingsDraftUi(
        SummaryModelBinding("form-provider", "form-model"), configureSummary = true,
        provider = ModelSettings("https://summary.invalid", "model", "synthetic-form-key", false),
        summaryWindow = "32000", inputBudget = "27000", outputBudget = "2000",
        reasoningBudget = "1000", overheadBudget = "2000", deadlineMillis = "30000",
    )

    private fun show(initial: CompressionSettingsDraftUi, dark: Boolean = false) {
        submitted = null
        observed = null
        pickerOpens = 0
        mainThread {
            activity.setContent {
                AgentAppTheme(AppearanceSettings(themeMode = if (dark) AppearanceThemeMode.DARK else AppearanceThemeMode.LIGHT),
                    applyInterfaceScale = false) {
                    var value by remember { mutableStateOf(initial) }
                    CompressionSettingsForm(value, onChange = { value = it; observed = it },
                        onSave = {
                            submitted = CompressionSettingsEditor.SaveRequest(it.compression(), it.summary(),
                                it.protocolChangeAuthorized, it.requestOptionsReplacementAuthorized)
                        }, onBack = {}, enabled = true, notice = null, error = false,
                        onChooseModel = { pickerOpens++ })
                }
            }
        }
        ui.waitForIdle()
    }

    fun testFirstScreenHasOriginalControlsWithoutParameterOrConnectionForms() {
        show(draft())
        node("settings-switch:自动压缩").assertExists()
        node("compression-protocol").assertExists()
        node("settings-switch:使用独立摘要配置").assertExists()
        node("compression-summary-config").assertExists()
        node("compression-policy-config").assertDoesNotExist()
        node("compression-model-budget").assertDoesNotExist()
        node("settings-field:摘要 Base URL").assertDoesNotExist()
        node("settings-field:摘要 API Key").assertDoesNotExist()
        node("compression-save").assertDoesNotExist()
        assertNull(submitted)
        assertEquals(0, fixture.opens.get())
    }

    fun testModelRowOpensPickerWithoutSavingOrRequestingProvider() {
        show(draft())
        reveal("compression-summary-config", "上下文压缩").performClick()
        ui.waitForIdle()
        assertEquals(1, pickerOpens)
        assertNull(submitted)
        assertEquals(0, fixture.opens.get())
    }

    fun testSummaryEndpointMenuKeepsOriginalOptionsWithCollapsedHelp() {
        show(draft())
        node("compression-protocol-help").assertExists()
        node("compression-protocol-help-text").assertDoesNotExist()
        ui.onNodeWithText("关闭时沿用对话模型。").assertExists()
        node("compression-protocol").performClick()
        node("compression-protocol-menu").assertExists()
        node("compression-endpoint:chat-completions").assertTextEquals("Chat Completions API")
        node("compression-endpoint:responses").assertTextEquals("Responses API")
        ui.onNodeWithText("Anthropic Messages").assertDoesNotExist()
        ui.onNodeWithText("OpenAI Responses").assertDoesNotExist()
        assertEquals(0, pickerOpens)
        assertNull(submitted)
        assertEquals(0, fixture.opens.get())
    }

    fun testHelpStartsCollapsedBetweenProtocolAndModelAndDoesNotSave() {
        for (dark in listOf(false, true)) {
            val initial = draft()
            show(initial, dark)
            ui.onNodeWithText("实测达到窗口 80% 时压缩，保留约 16% 最近上下文。").assertDoesNotExist()
            node("compression-protocol-help-text").assertDoesNotExist()
            val help = reveal("compression-protocol-help", "上下文压缩")
            ui.waitForIdle()
            val protocol = node("compression-protocol").fetchSemanticsNode().boundsInRoot
            val helpBounds = help.fetchSemanticsNode().boundsInRoot
            val model = node("settings-switch:使用独立摘要配置").fetchSemanticsNode().boundsInRoot
            assertTrue(protocol.bottom <= helpBounds.top)
            assertTrue(helpBounds.bottom <= model.top)
            repeat(2) {
                help.performClick()
                reveal("compression-protocol-help-text", "上下文压缩").assertTextEquals(
                    "仅用于摘要。默认 Chat Completions；模型需要时选 Responses。Anthropic / Codex 不受此项影响。")
                reveal("compression-protocol-help", "上下文压缩").performClick()
                ui.waitForIdle()
                node("compression-protocol-help-text").assertDoesNotExist()
            }
            assertNull(submitted)
            assertNull(observed)
            assertEquals(0, pickerOpens)
            assertEquals(0, fixture.opens.get())
        }
    }

    fun testEndpointPlaceholderDoesNotChangeProviderOrOpenModelPicker() {
        val initial = draft().copy(provider = draft().provider.copy(protocol = ProviderProtocol.MESSAGES))
        show(initial)
        node("compression-protocol").performClick()
        listOf("compression-endpoint:chat-completions", "compression-endpoint:responses").forEach { option ->
            node(option).assertIsNotSelected().performClick()
            node("compression-protocol-menu").assertExists()
            assertEquals(0, pickerOpens)
            assertNull(submitted)
            assertNull(observed)
            assertEquals(0, fixture.opens.get())
        }
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        ui.waitForIdle()
        node("compression-protocol-menu").assertDoesNotExist()
        reveal("compression-summary-config", "上下文压缩").performClick()
        assertEquals(1, pickerOpens)
    }

    fun testDraftProtocolAuthorizationAndRequestPolicyResetRemainExplicit() {
        val original = draft().copy(savedProtocol = ProviderProtocol.RESPONSES,
            provider = draft().provider.copy(requestOptions = ProviderRequestOptions(speedMode = GptSpeedMode.FAST)))
        val changed = original.selectProtocol(ProviderProtocol.MESSAGES)
        assertFalse(changed.protocolChangeAuthorized)
        assertEquals("", changed.provider.apiKey)
        assertEquals(GptSpeedMode.FAST, changed.provider.requestOptions.speedMode)
        val authorized = changed.copy(provider = changed.provider.copy(apiKey = "replacement-synthetic-key"),
            protocolChangeAuthorized = true).resetRequestOptions()
        val request = CompressionSettingsEditor.SaveRequest(authorized.compression(), authorized.summary(),
            authorized.protocolChangeAuthorized, authorized.requestOptionsReplacementAuthorized)
        assertTrue(request.protocolChangeAuthorized)
        assertTrue(request.requestOptionsReplacementAuthorized)
        assertEquals(ProviderRequestOptions(), request.profile!!.provider.requestOptions)
        assertEquals(ProviderProtocol.MESSAGES, request.profile!!.provider.protocol)
        request.profile!!.configuration()
        assertEquals(0, fixture.opens.get())
    }
}
