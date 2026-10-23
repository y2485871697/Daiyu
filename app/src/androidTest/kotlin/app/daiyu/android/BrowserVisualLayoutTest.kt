package app.daiyu.android

import android.content.res.Configuration
import android.os.LocaleList
import java.util.Locale
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
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
            val configuration = Configuration(activity.resources.configuration).apply {
                setLocales(LocaleList(Locale.forLanguageTag("zh-Hans-CN")))
            }
            val context = activity.createConfigurationContext(configuration)
            assertEquals("没有打开的标签页", context.getString(R.string.om_browser_no_tab_open))
            activity.setContent {
                CompositionLocalProvider(LocalContext provides context,
                    LocalConfiguration provides context.resources.configuration,
                    LocalResources provides context.resources) {
                    AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                        BrowserVisualScreen(null, { false }, { closed++ })
                    }
                }
            }
        }
        awaitBrowserWindowStable(node("browser-visual-page"))
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
        awaitBrowserWindowStable(node("browser-visual-page"))
        ui.onNodeWithText(diagnostic).assertDoesNotExist()
        prepareBrowserGeometry(node("browser-visual-page"))
        node("browser-visual-controls").performClick()
        val userAgent = ui.onNodeWithText(activity.getString(R.string.om_browser_settings_user_agent))
        awaitNodeWindowFocus(userAgent)
        dismissIme()
        awaitImeFrame(userAgent, visible = false)
        awaitBrowserWindowStable(userAgent, requireImeHidden = true)
        logBrowserWindow(userAgent, "settings-open")
        userAgent.assertIsDisplayed()
        ui.onNodeWithText("查看网页会暂停当前任务；关闭面板不会自动继续。").assertDoesNotExist()
        ui.onNodeWithText("归还浏览器并继续任务").assertDoesNotExist()
        ui.onNodeWithText("停止任务").assertDoesNotExist()
        ui.onNodeWithTag("browser-visual-restore").assertDoesNotExist()
        ui.onNodeWithTag("browser-visual-stop-owner").assertDoesNotExist()
        val idleTimeout = ui.onNode(hasSetTextAction() and hasText(
            activity.getString(R.string.om_browser_settings_minutes_label)))
        idleTimeout.assertIsNotFocused()
        ui.onNodeWithText(activity.getString(R.string.om_browser_settings_done)).performClick()
        node("browser-visual-controls").performClick()
        awaitNodeWindowFocus(userAgent)
        awaitBrowserWindowStable(userAgent, requireImeHidden = true)
        userAgent.assertIsDisplayed()
        idleTimeout.assertIsNotFocused()
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
            awaitBrowserWindowStable(chip)
            prepareBrowserGeometry(chip)
            val chipBounds = chip.fetchSemanticsNode().boundsInRoot
            val titleBounds = title.fetchSemanticsNode().boundsInRoot
            logBrowserWindow(chip, "baseline dark=$dark fontScale=$fontScale")
            val close = ui.onNodeWithContentDescription(activity.getString(R.string.om_browser_close_tab))
            close.assertExists()
            mainThread { busy = true; pool.refresh() }
            awaitBrowserWindowStable(chip)
            close.assertDoesNotExist()
            logBrowserWindow(chip, "busy dark=$dark fontScale=$fontScale")
            assertEquals(chipBounds, chip.fetchSemanticsNode().boundsInRoot)
            assertEquals(titleBounds, title.fetchSemanticsNode().boundsInRoot)
            chip.performTouchInput { click() }
            mainThread { pool.closeTabFromUI(lease.tabId) }
            assertEquals(0, closes)
            mainThread { busy = false; pool.refresh() }
            awaitBrowserWindowStable(chip)
            assertEquals(chipBounds, chip.fetchSemanticsNode().boundsInRoot)
            assertEquals(titleBounds, title.fetchSemanticsNode().boundsInRoot)
            close.performClick()
            ui.waitForIdle()
            assertEquals(1, closes)
            mainThread { busy = true; pool.refresh() }
            awaitBrowserWindowStable(chip)
            close.assertDoesNotExist()
            assertEquals(chipBounds, chip.fetchSemanticsNode().boundsInRoot)
        }
        assertEquals(0, fixture.opens.get())
    }

    private fun prepareBrowserGeometry(target: SemanticsNodeInteraction) {
        // The fake visit starts with an empty address; Go ends editing without navigating.
        node("browser-visual-address").performImeAction()
        dismissIme()
        awaitImeFrame(target, visible = false)
    }

    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    private fun logBrowserWindow(target: SemanticsNodeInteraction, label: String) {
        val current = target.fetchSemanticsNode()
        val view = (current.root as androidx.compose.ui.platform.ViewRootForTest).view
        instrumentation.runOnMainSync {
            val position = IntArray(2)
            view.getLocationOnScreen(position)
            val insets = androidx.core.view.ViewCompat.getRootWindowInsets(view)
            val ime = androidx.core.view.WindowInsetsCompat.Type.ime()
            android.util.Log.i("DaiyuBrowserGeometry", "$label bounds=${current.boundsInRoot} " +
                "view=${view.width}x${view.height} screen=${position.toList()} " +
                "density=${view.resources.displayMetrics.density} " +
                "imeVisible=${insets?.isVisible(ime)} imeBottom=${insets?.getInsets(ime)?.bottom} " +
                "focus=${view.findFocus()?.javaClass?.name}")
        }
    }

    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    private fun awaitBrowserWindowStable(target: SemanticsNodeInteraction, requireImeHidden: Boolean = false) {
        awaitVisualIdle()
        awaitNodeWindowFocus(target)
        ui.waitForIdle()
        val current = target.fetchSemanticsNode()
        val view = (current.root as androidx.compose.ui.platform.ViewRootForTest).view
        val token = view.windowToken
        val diagnostic = java.util.concurrent.atomic.AtomicReference("sampler-not-called")
        try {
            awaitStableWindowFrame(view) {
                val bounds = current.boundsInRoot
                val insets = androidx.core.view.ViewCompat.getRootWindowInsets(view)
                val ime = androidx.core.view.WindowInsetsCompat.Type.ime()
                val imeReady = !requireImeHidden || (insets != null &&
                    !insets.isVisible(ime) && insets.getInsets(ime).bottom == 0)
                runCatching {
                    val chain = generateSequence(current) { it.parent }.take(12).joinToString("; ") { node ->
                        val coordinates = node.layoutInfo.coordinates
                        val attached = coordinates.isAttached
                        val raw = if (attached) {
                            var root = coordinates
                            while (true) root = root.parentLayoutCoordinates ?: break
                            root.localBoundingBoxOf(coordinates, clipBounds = false)
                        } else null
                        "id=${node.id} placed=${node.layoutInfo.isPlaced} attached=$attached " +
                            "clipped=${node.boundsInRoot} raw=$raw"
                    }
                    diagnostic.set("tokenMatches=${view.windowToken == token} placed=${current.layoutInfo.isPlaced} " +
                        "coordinatesAttached=${current.layoutInfo.coordinates.isAttached} bounds=$bounds " +
                        "imeReady=$imeReady insetsPresent=${insets != null}; $chain")
                }
                if (view.windowToken != token || !current.layoutInfo.isPlaced ||
                    !current.layoutInfo.coordinates.isAttached || bounds.width <= 0f || bounds.height <= 0f ||
                    !listOf(bounds.left, bounds.top, bounds.right, bounds.bottom).all { it.isFinite() } || !imeReady) null
                else {
                    val position = IntArray(2)
                    view.getLocationOnScreen(position)
                    Triple(bounds, view.width to view.height, position.toList())
                }
            }
        } catch (failure: AssertionError) {
            throw AssertionError("${failure.message}\nBrowser sampler diagnostic: ${diagnostic.get()}", failure)
        }
    }

    private class StateVisit(override val taskOwned: Boolean) : BrowserVisualVisit {
        private val lease = BrowserTabLease(BrowserOwner("busy-state", BrowserOwnerKind.MAIN), 0, 1L, 1L)
        var state = ControllerBrowserVisual.State(live = true, lease = lease, takenOver = true,
            tabs = listOf(lease))
        var closed = 0
        val navigations = mutableListOf<String>()
        var newTabs = 0
        var controls = 0
        override val conversationId = null
        override val memory = BrowserUiMemory("busy-state")
        override fun snapshot() = state
        override fun takeover(lease: BrowserTabLease) { state = state.copy(takenOver = true, waiting = false) }
        override fun mount(parent: ViewGroup) = Unit
        override fun unmount(parent: ViewGroup) = Unit
        override fun navigate(url: String) { navigations += url }
        override fun newTab() { newTabs++ }
        override fun selectTab(lease: BrowserTabLease) = Unit
        override fun closeTab() = Unit
        override fun control(action: BrowserVisibleAction) { controls++ }
        override fun configure(configuration: BrowserVisibleConfiguration) = Unit
        override fun downloads(listener: BrowserVisibleDownload?) = Unit
        override fun restore() = Unit
        override fun stop() = Unit
        override fun close() { closed++ }
    }

    fun testHumanFailureNeverShowsAgentBrowsingAndTaskTakeoverStillWorks() {
        for (taskOwned in listOf(false, true)) {
            val visit = StateVisit(taskOwned)
            lateinit var pool: BrowserUiPool
            mainThread {
                pool = BrowserUiPool(activity.applicationContext, visit)
                activity.setContent { AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    BrowserSheet(pool, {})
                } }
            }
            val brand = activity.getString(R.string.om_browser_minis_browsing)
            ui.onNodeWithText(brand).assertDoesNotExist()
            node("browser-visual-new-tab").assertIsEnabled()
            node("browser-visual-history").assertIsEnabled()
            mainThread {
                visit.state = visit.state.copy(takenOver = false, waiting = false)
                pool.refresh()
            }
            node("browser-visual-new-tab").assertIsNotEnabled()
            if (taskOwned) {
                ui.onNodeWithText(brand).assertIsDisplayed()
                ui.onNodeWithText(activity.getString(R.string.om_browser_takeover)).performClick()
                ui.onNodeWithText(brand).assertDoesNotExist()
                node("browser-visual-new-tab").assertIsEnabled()
            } else ui.onNodeWithText(brand).assertDoesNotExist()
            mainThread { visit.state = visit.state.copy(unknown = true, takenOver = false, waiting = true); pool.refresh() }
            assertTrue(pool.unavailable.value)
            ui.onNodeWithText(brand).assertDoesNotExist()
            ui.onNodeWithText(activity.getString(R.string.om_browser_takeover)).assertDoesNotExist()
            node("browser-visual-new-tab").assertIsNotEnabled()
            node("browser-visual-history").assertIsNotEnabled()
        }
        assertEquals(0, fixture.opens.get())
    }

    fun testFailedVisualReturnsOnceWithoutReplayingUnknownNavigation() {
        val visit = StateVisit(false)
        visit.state = visit.state.copy(unknown = true)
        var dismisses = 0
        mainThread {
            activity.setContent { AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                BrowserVisualScreen(visit, { true }, { dismisses++ })
            } }
        }
        ui.waitUntil(5_000) { dismisses == 1 }
        ui.runOnIdle {
            assertEquals(1, dismisses)
            assertTrue(visit.closed > 0)
            assertTrue(visit.navigations.isEmpty())
            assertEquals(0, visit.newTabs)
            assertEquals(0, visit.controls)
        }
        ui.onNodeWithText(activity.getString(R.string.om_browser_minis_browsing)).assertDoesNotExist()
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
