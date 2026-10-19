package app.daiyu.android

import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.screens.browser.BrowserVisualScreen

class BrowserVisualLayoutTest : WiredActivityTestCase() {
    fun testEmptySheetShowsOriginalStructureWithoutCreatingBrowser() {
        var closed = 0
        mainThread {
            activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    BrowserVisualScreen(null, { false }, { closed++ })
                }
            }
        }
        node("browser-visual-page").assertIsDisplayed()
        node("browser-visual-empty").assertTextEquals("没有打开的标签页")
        node("browser-visual-tabs").assertTextEquals("0/3")
        node("browser-visual-address").assertIsDisplayed()
        node("browser-visual-container").assertDoesNotExist()
        node("browser-visual-go-back").assertIsNotEnabled()
        node("browser-visual-go-forward").assertIsNotEnabled()
        node("browser-visual-reload").assertIsNotEnabled()
        node("browser-visual-takeover").assertDoesNotExist()
        node("browser-visual-back").performClick()
        ui.runOnIdle { assertEquals(1, closed); assertEquals(0, fixture.opens.get()) }
    }
}
