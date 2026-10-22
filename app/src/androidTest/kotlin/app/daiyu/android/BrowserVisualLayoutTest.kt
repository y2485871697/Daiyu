package app.daiyu.android

import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import app.daiyu.android.browser.ported.ui.browser.BrowserSheet
import app.daiyu.android.browser.session.BrowserOwner
import app.daiyu.android.browser.session.BrowserOwnerKind
import app.daiyu.android.data.model.AppearanceThemeMode
import app.daiyu.android.browser.openminis.BrowserVisibleAction
import app.daiyu.android.browser.openminis.BrowserVisibleConfiguration
import app.daiyu.android.browser.openminis.BrowserVisibleDownload
import app.daiyu.android.browser.session.BrowserTabLease
import app.daiyu.android.browser.ui.BrowserUiMemory
import app.daiyu.android.browser.ui.BrowserUiPool
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
        ui.onNodeWithTag("browser-visual-empty", useUnmergedTree = true).assertTextEquals("没有打开的标签页")
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

    fun testTaskBrowserKeepsOriginalPopupWithoutExtraDescriptionsOrTaskButtons() {
        val diagnostic = "未确认：后端诊断，不是原版弹窗内容"
        val visit = object : BrowserVisualVisit {
            override val conversationId = null
            override val memory = BrowserUiMemory("original-browser-ui-test")
            override val taskOwned = true
            override fun snapshot() = ControllerBrowserVisual.State(live = true, takenOver = true, notice = diagnostic)
            override fun takeover(lease: BrowserTabLease) = Unit
            override fun mount(parent: ViewGroup) = Unit
            override fun unmount(parent: ViewGroup) = Unit
            override fun navigate(url: String) = Unit
            override fun newTab() = Unit
            override fun selectTab(lease: BrowserTabLease) = Unit
            override fun closeTab() = Unit
            override fun control(action: BrowserVisibleAction) = Unit
            override fun configure(configuration: BrowserVisibleConfiguration) = Unit
            override fun downloads(listener: BrowserVisibleDownload?) = Unit
            override fun restore() = error("original settings must not resume a task")
            override fun stop() = error("original settings must not stop a task")
            override fun close() = Unit
        }
        mainThread {
            activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    BrowserVisualScreen(visit, { true }, {})
                }
            }
        }
        ui.onNodeWithText(diagnostic).assertDoesNotExist()
        node("browser-visual-controls").performClick()
        ui.onNodeWithText(activity.getString(R.string.om_browser_settings_user_agent)).assertIsDisplayed()
        ui.onNodeWithText("查看网页会暂停当前任务；关闭面板不会自动继续。").assertDoesNotExist()
        ui.onNodeWithText("归还浏览器并继续任务").assertDoesNotExist()
        ui.onNodeWithText("停止任务").assertDoesNotExist()
        ui.onNodeWithTag("browser-visual-restore").assertDoesNotExist()
        ui.onNodeWithTag("browser-visual-stop-owner").assertDoesNotExist()
        ui.onNodeWithText(activity.getString(R.string.om_browser_settings_done)).performClick()
        node("browser-visual-page").assertExists()
        assertEquals(0, fixture.opens.get())
    }

    fun testBusyTabKeepsItsTitleAndCloseSlotGeometryWithoutCloseAuthority() {
        for (dark in listOf(false, true)) for (fontScale in listOf(.9f, 1.3f)) {
            val lease = BrowserTabLease(BrowserOwner("geometry", BrowserOwnerKind.MAIN), 0, 1L, 1L)
            var busy = false
            var closes = 0
            val visit = object : BrowserVisualVisit {
                override val conversationId = null
                override val memory = BrowserUiMemory("tab-slot-geometry")
                override val taskOwned = true
                override fun snapshot() = ControllerBrowserVisual.State(live = true, lease = lease,
                    takenOver = !busy, waiting = busy, tabs = listOf(lease))
                override fun takeover(lease: BrowserTabLease) = Unit
                override fun mount(parent: ViewGroup) = Unit
                override fun unmount(parent: ViewGroup) = Unit
                override fun navigate(url: String) = Unit
                override fun newTab() = Unit
                override fun selectTab(lease: BrowserTabLease) = Unit
                override fun closeTab() { closes++ }
                override fun control(action: BrowserVisibleAction) = Unit
                override fun configure(configuration: BrowserVisibleConfiguration) = Unit
                override fun downloads(listener: BrowserVisibleDownload?) = Unit
                override fun restore() = Unit
                override fun stop() = Unit
                override fun close() = Unit
            }
            lateinit var pool: BrowserUiPool
            mainThread {
                pool = BrowserUiPool(activity.applicationContext, visit)
                activity.setContent {
                    val density = LocalDensity.current
                    CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                        AgentAppTheme(AppearanceSettings(themeMode = if (dark) AppearanceThemeMode.DARK
                            else AppearanceThemeMode.LIGHT), applyInterfaceScale = false) {
                            BrowserSheet(pool, {})
                        }
                    }
                }
            }
            val chip = ui.onNode(hasText("Tab 0") and hasClickAction())
            val title = ui.onNodeWithText("Tab 0", useUnmergedTree = true)
            val chipBounds = chip.fetchSemanticsNode().boundsInRoot
            val titleBounds = title.fetchSemanticsNode().boundsInRoot
            val close = ui.onNodeWithContentDescription(activity.getString(R.string.om_browser_close_tab))
            close.assertExists()
            mainThread { busy = true; pool.refresh() }
            close.assertDoesNotExist()
            assertEquals(chipBounds, chip.fetchSemanticsNode().boundsInRoot)
            assertEquals(titleBounds, title.fetchSemanticsNode().boundsInRoot)
            chip.performTouchInput { click() }
            mainThread { pool.closeTabFromUI(lease.tabId) }
            assertEquals(0, closes)
            mainThread { busy = false; pool.refresh() }
            assertEquals(chipBounds, chip.fetchSemanticsNode().boundsInRoot)
            assertEquals(titleBounds, title.fetchSemanticsNode().boundsInRoot)
            close.performClick()
            ui.waitForIdle()
            assertEquals(1, closes)
            mainThread { busy = true; pool.refresh() }
            close.assertDoesNotExist()
            assertEquals(chipBounds, chip.fetchSemanticsNode().boundsInRoot)
        }
        assertEquals(0, fixture.opens.get())
    }

    fun testAddressNormalizationKeepsOriginalSearchAndHostRecognition() {
        val cases = listOf(
            " example.com/path " to "https://example.com/path",
            "LOCALHOST" to "https://LOCALHOST",
            "localhost:8080/page" to "https://localhost:8080/page",
            "127.0.0.1:8080/page" to "https://127.0.0.1:8080/page",
            "localhostfoo" to "https://www.bing.com/search?q=localhostfoo",
            "example.com." to "https://www.bing.com/search?q=example.com.",
            "a..b" to "https://www.bing.com/search?q=a..b",
            "原版 搜索" to "https://www.bing.com/search?q=%E5%8E%9F%E7%89%88+%E6%90%9C%E7%B4%A2",
            "about:blank" to "about:blank",
            "https://example.com/?q=1" to "https://example.com/?q=1",
        )
        cases.forEach { (input, expected) -> assertEquals(input, expected, BrowserUiPool.normalize(input)) }
    }

}
