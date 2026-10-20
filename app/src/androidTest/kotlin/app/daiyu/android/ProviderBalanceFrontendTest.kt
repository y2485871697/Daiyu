package app.daiyu.android

import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.text.AnnotatedString
import app.daiyu.android.balance.BalanceOption
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.data.model.AppearanceThemeMode
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.screens.settings.ModelSettingsDraftUi
import app.daiyu.android.ui.screens.settings.ModelSettingsScreen

class ProviderBalanceFrontendTest : WiredActivityTestCase() {
    private val title = "测试提供商"
    private var draft = ModelSettingsDraftUi()

    private fun show(dark: Boolean) {
        mainThread { activity.setContent {
            AgentAppTheme(AppearanceSettings(themeMode = if (dark) AppearanceThemeMode.DARK else AppearanceThemeMode.LIGHT),
                applyInterfaceScale = false) {
                var value by remember(dark) { mutableStateOf(ModelSettingsDraftUi(providerName = "测试提供商", providerEnabled = true)) }
                SideEffect { draft = value }
                ModelSettingsScreen(value, { value = it }, {}, {}, {}, pageTitle = title,
                    providerName = "测试提供商", showCatalog = false)
            }
        } }
    }

    private fun preset(id: String) {
        reveal("provider-balance-preset", title).performClick()
        node("provider-balance-preset:$id").performClick()
    }

    fun testProviderSwitchAndFourBalanceTemplatesOnlyChangeFrontendDraftInBothThemes() {
        val before = fixture.settings.load()
        val registry = fixture.controller.providers.snapshot()
        for (dark in listOf(false, true)) {
            show(dark)
            reveal("settings-switch:启用此 Provider", title).assertIsOn().performClick()
            assertFalse(draft.providerEnabled)
            reveal("settings-switch:余额", title).assertIsOff().performClick()
            reveal("provider-balance-path", title)
            assertEquals("", draft.balanceOption.apiPath)
            preset("sub2api")
            assertEquals("/usage", draft.balanceOption.apiPath)
            assertEquals("remaining", draft.balanceOption.resultPath)
            preset("deepseek")
            assertEquals("/user/balance", draft.balanceOption.apiPath)
            assertEquals("balance_infos[0].total_balance", draft.balanceOption.resultPath)
            preset("custom")
            reveal("provider-balance-path", title).performSemanticsAction(SemanticsActions.SetText) {
                assertTrue(it(AnnotatedString("/my/balance")))
            }
            assertEquals("/my/balance", draft.balanceOption.apiPath)
            preset("new_api")
            reveal("provider-balance-token", title).performSemanticsAction(SemanticsActions.SetText) {
                assertTrue(it(AnnotatedString("synthetic-preview-token")))
            }
            assertEquals(BalanceOption.PRESET_NEW_API, draft.balanceOption.preset)
            assertEquals("synthetic-preview-token", draft.balanceOption.accessToken)
            val beforeClick = draft
            reveal("provider-balance-test", title).assertIsEnabled().performClick()
            assertEquals(beforeClick, draft)
            ui.onNodeWithText("未接入", substring = true).assertDoesNotExist()
            ui.onNodeWithText("界面预览", substring = true).assertDoesNotExist()
            node("provider-balance-path").assertDoesNotExist()
            reveal("settings-switch:余额", title).performClick()
            node("provider-balance-test").assertDoesNotExist()
            assertEquals(before, fixture.settings.load())
            assertEquals(registry, fixture.controller.providers.snapshot())
            assertEquals(0, fixture.opens.get())
        }
    }
}
