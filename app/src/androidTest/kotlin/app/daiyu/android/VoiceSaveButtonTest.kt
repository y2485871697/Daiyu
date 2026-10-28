package app.daiyu.android

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.data.model.AppearanceThemeMode
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.app.resolveAppearanceActions

class VoiceSaveButtonTest : WiredActivityTestCase() {
    fun testBothSaveButtonsFollowCurrentThemeAndKeepTheSameGeometry() {
        for (dark in listOf(false, true)) for (accent in listOf(0xFF236D58.toInt(), 0xFFAD4168.toInt())) {
            val appearance = AppearanceSettings(primaryAccentColor = accent,
                themeMode = if (dark) AppearanceThemeMode.DARK else AppearanceThemeMode.LIGHT)
            mainThread {
                activity.setContent {
                    AgentAppTheme(appearance, applyInterfaceScale = false) {
                        Column(Modifier.safeDrawingPadding().width(320.dp)) {
                            VoiceAccountSaveButton("保存账户", "account-save", enabled = true) {}
                            VoiceAccountSaveButton("保存账户信息", "quota-save", enabled = true) {}
                        }
                    }
                }
            }
            awaitUniqueTag("quota-save")
            val expected = resolveAppearanceActions(appearance, dark).primary
            val account = node("account-save").fetchSemanticsNode().boundsInRoot
            val quota = node("quota-save").fetchSemanticsNode().boundsInRoot
            assertEquals(account.width, quota.width, 1f)
            assertEquals(account.height, quota.height, 1f)
            for (tag in listOf("account-save", "quota-save")) {
                val pixels = node(tag).captureToImage().toPixelMap()
                val fill = pixels[pixels.width / 5, pixels.height / 2]
                assertEquals(expected.red, fill.red, .03f)
                assertEquals(expected.green, fill.green, .03f)
                assertEquals(expected.blue, fill.blue, .03f)
            }
        }
    }

    fun testDisabledButtonsRejectTouchesAndEnabledButtonsInvokeOnce() {
        val enabled = mutableStateOf(false)
        var clicks = 0
        mainThread {
            activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    Column(Modifier.safeDrawingPadding().width(320.dp)) {
                        VoiceAccountSaveButton("保存账户", "account-save", enabled.value) { clicks++ }
                        VoiceAccountSaveButton("保存账户信息", "quota-save", enabled.value) { clicks++ }
                    }
                }
            }
        }
        for (tag in listOf("account-save", "quota-save")) {
            node(tag).assertIsNotEnabled().performTouchInput { click() }
        }
        assertEquals(0, clicks)
        mainThread { enabled.value = true }
        for (tag in listOf("account-save", "quota-save")) node(tag).assertIsEnabled().performClick()
        assertEquals(2, clicks)
    }
}
