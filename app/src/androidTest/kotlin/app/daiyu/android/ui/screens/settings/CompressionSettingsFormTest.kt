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
import app.daiyu.android.model.GptSpeedMode
import app.daiyu.android.model.ProviderProtocol
import app.daiyu.android.model.ProviderRequestOptions
import app.daiyu.android.storage.ModelSettings
import app.daiyu.android.ui.app.AgentAppTheme

/** Exercises actual form controls and captured requests, without a provider or persistence. */
class CompressionSettingsFormTest : WiredActivityTestCase() {
    private var submitted: CompressionSettingsEditor.SaveRequest? = null
    private var observed: CompressionSettingsDraftUi? = null

    private fun draft() = CompressionSettingsDraftUi(
        SummaryModelBinding("form-provider", "form-model"), configureSummary = true,
        provider = ModelSettings("https://summary.invalid", "model", "synthetic-form-key", false),
        summaryWindow = "32000", inputBudget = "27000", outputBudget = "2000",
        reasoningBudget = "1000", overheadBudget = "2000", deadlineMillis = "30000",
    )

    private fun show(initial: CompressionSettingsDraftUi) {
        submitted = null
        mainThread {
            activity.setContent {
                AgentAppTheme(AppearanceSettings()) {
                    var value by remember { mutableStateOf(initial) }
                    CompressionSettingsForm(value, onChange = { value = it; observed = it },
                        onSave = {
                            submitted = CompressionSettingsEditor.SaveRequest(it.compression(), it.summary(),
                                it.protocolChangeAuthorized, it.requestOptionsReplacementAuthorized)
                        }, onBack = {}, enabled = true, notice = null, error = false)
                }
            }
        }
        ui.waitForIdle()
    }

    fun testUnavailableStoredProtocolRequiresAndCarriesExplicitRebind() {
        show(draft()) // An unavailable profile does not disclose the old stored protocol.
        assertNull(submitted)
        reveal("settings-switch:重输摘要密钥并绑定新协议", "上下文压缩").performClick()
        assertNull(submitted)
        reveal("compression-save", "上下文压缩").performClick()
        ui.waitForIdle()
        val request = checkNotNull(submitted)
        assertTrue(request.protocolChangeAuthorized)
        assertEquals(ProviderProtocol.RESPONSES, request.profile!!.provider.protocol)
        request.profile!!.configuration()
        assertEquals(0, fixture.opens.get())
    }

    fun testCrossProtocolOptionsOnlyResetAfterExplicitAction() {
        val initial = draft().copy(savedProtocol = ProviderProtocol.RESPONSES,
            provider = draft().provider.copy(requestOptions = ProviderRequestOptions(speedMode = GptSpeedMode.FAST)))
        show(initial)
        reveal("settings-radio:Anthropic Messages", "上下文压缩").performClick()
        mainThread { assertEquals(GptSpeedMode.FAST, observed!!.provider.requestOptions.speedMode) }
        reveal("settings-field:摘要 API Key", "上下文压缩").performTextReplacement("replacement-synthetic-key")
        reveal("settings-switch:重输摘要密钥并绑定新协议", "上下文压缩").performClick()
        reveal("settings-row:重置摘要请求策略并授权替换", "上下文压缩").performClick()
        assertNull(submitted)
        reveal("compression-save", "上下文压缩").performClick()
        ui.waitForIdle()
        val request = checkNotNull(submitted)
        assertTrue(request.protocolChangeAuthorized)
        assertTrue(request.requestOptionsReplacementAuthorized)
        assertEquals(ProviderRequestOptions(), request.profile!!.provider.requestOptions)
        assertEquals(ProviderProtocol.MESSAGES, request.profile!!.provider.protocol)
        request.profile!!.configuration()
        assertEquals(0, fixture.opens.get())
    }
}
