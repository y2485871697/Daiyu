package app.daiyu.android

import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import app.daiyu.android.model.sessiongateway.SessionGatewayRule
import app.daiyu.android.ui.screens.settings.SessionGatewaySettingsScreen
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Controlled editor only; route/store/controller integration is owned by the parent change. */
@Suppress("DEPRECATION")
class SessionGatewaySettingsScreenTest : WiredActivityTestCase() {
    private var draft by mutableStateOf(SessionGatewayRule())
    private var saved: SessionGatewayRule? = null
    private val title = "保留会话网关"

    override fun setUp() {
        super.setUp()
        mainThread {
            draft = SessionGatewayRule()
            saved = null
            activity.setContent {
                MiuixTheme {
                    SessionGatewaySettingsScreen(draft, { draft = it }, { saved = it }, {})
                }
            }
        }
    }

    private fun field(label: String) = reveal("settings-field:$label", title)

    fun testAllFiveFieldsRemainEditableAndUnverifiedValuesAreNotSilentlyReset() {
        field("模型正则（全匹配）").performTextReplacement("relay/.*")
        field("完整 URL 正则（包含匹配）").performTextReplacement("gateway.invalid")
        field("retention").performTextReplacement("unverified-retention")
        field("keySource").performTextReplacement("unverified-source")
        field("顶层身份键").performTextReplacement("literal.key")
        reveal("session-gateway-save", title).performClick()
        mainThread {
            assertEquals(SessionGatewayRule("relay/.*", "gateway.invalid", "unverified-retention", "unverified-source", "literal.key"), saved)
        }
        assertEquals(0, fixture.opens.get())
        assertNull(fixture.controller.active)
    }

    fun testRuntimeFieldCannotBeSavedAndBadRegexIsAnEditableNonMatch() {
        field("顶层身份键").performTextReplacement("instructions")
        reveal("session-gateway-save", title).assertIsNotEnabled()
        mainThread { assertNull(saved) }
        field("顶层身份键").performTextReplacement("prompt_cache_key")
        field("模型正则（全匹配）").performTextReplacement("[")
        reveal("session-gateway-save", title).assertIsEnabled().performClick()
        mainThread {
            assertEquals("[", saved?.modelPattern)
            assertFalse(checkNotNull(saved).matches("gpt-5", "https://gateway.invalid/v1/responses"))
        }
        assertEquals(0, fixture.opens.get())
    }
}
