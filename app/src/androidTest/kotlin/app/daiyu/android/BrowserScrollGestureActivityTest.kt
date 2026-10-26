package app.daiyu.android

import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import app.daiyu.android.browser.openminis.BrowserVisibleAction
import app.daiyu.android.browser.openminis.BrowserVisibleConfiguration
import app.daiyu.android.browser.openminis.BrowserVisibleDownload
import app.daiyu.android.browser.openminis.BrowserVisiblePage
import app.daiyu.android.browser.session.BrowserOwner
import app.daiyu.android.browser.session.BrowserOwnerKind
import app.daiyu.android.browser.session.BrowserTabLease
import app.daiyu.android.browser.ui.BrowserUiMemory
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.screens.browser.BrowserVisualScreen

/** Real WebView input in the production browser sheet, with a network-free document. */
class BrowserScrollGestureActivityTest : WiredActivityTestCase() {
    private lateinit var view: WebView
    private var loaded = false
    private var dismissals = 0

    private fun show(height: Int) {
        mainThread {
            loaded = false
            view = WebView(activity).apply {
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) { loaded = true }
                }
            }
            val lease = BrowserTabLease(BrowserOwner("scroll-fixture", BrowserOwnerKind.MAIN), 0, 1L, 1L)
            val visit = object : BrowserVisualVisit {
                override val conversationId = null
                override val memory = BrowserUiMemory("scroll-fixture")
                override val taskOwned = false
                private var live = true
                override fun snapshot() = ControllerBrowserVisual.State(live = live, lease = lease,
                    takenOver = true, mounted = view.parent != null, tabs = listOf(lease),
                    page = BrowserVisiblePage("https://browser-scroll.test/", "Scroll fixture", emptyList()))
                override fun mount(parent: ViewGroup) {
                    if (view.parent !== parent) {
                        (view.parent as? ViewGroup)?.removeView(view)
                        parent.addView(view, ViewGroup.LayoutParams(-1, -1))
                    }
                }
                override fun unmount(parent: ViewGroup) { if (view.parent === parent) parent.removeView(view) }
                override fun takeover(lease: BrowserTabLease) = Unit
                override fun navigate(url: String) = Unit
                override fun newTab() = Unit
                override fun selectTab(lease: BrowserTabLease) = Unit
                override fun closeTab() = Unit
                override fun control(action: BrowserVisibleAction) = Unit
                override fun configure(configuration: BrowserVisibleConfiguration) = Unit
                override fun downloads(listener: BrowserVisibleDownload?) = Unit
                override fun restore() = Unit
                override fun stop() = Unit
                override fun close() { live = false }
            }
            activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    BrowserVisualScreen(visit, { true }, { dismissals++ })
                }
            }
            view.loadDataWithBaseURL("https://browser-scroll.test/",
                "<html><head><meta name='viewport' content='width=device-width,initial-scale=1'></head>" +
                    "<body style='margin:0;height:${height}px;background:linear-gradient(white,blue)'>scroll</body></html>",
                "text/html", "UTF-8", null)
        }
        awaitUniqueTag("browser-visual-container")
        awaitNodeWindowFocus(node("browser-visual-container"))
        awaitControl("local page loaded and laid out") { loaded && view.height > 0 && view.contentHeight >= height }
    }

    fun testLongPageScrollsBothWaysWithoutDraggingOrClosingBrowser() {
        show(20_000)
        val container = node("browser-visual-container")
        val before = container.fetchSemanticsNode().boundsInRoot
        container.performTouchInput { swipeUp() }
        awaitControl("real WebView scrolled down") { view.scrollY > 0 }
        var down = 0
        mainThread { down = view.scrollY }
        container.performTouchInput { swipeDown() }
        awaitControl("real WebView scrolled back up") { view.scrollY < down }
        mainThread { view.scrollTo(0, 0) }
        container.performTouchInput { swipeDown() }
        awaitVisualIdle()
        mainThread {
            assertEquals(0, view.scrollY)
            assertEquals(0, dismissals)
        }
        assertEquals(before, container.fetchSemanticsNode().boundsInRoot)
        node("browser-visual-page").assertIsDisplayed()
        node("browser-visual-back").performClick()
        mainThread { assertEquals(1, dismissals) }
        assertEquals(0, fixture.opens.get())
    }

    fun testShortPageDownwardFlingDoesNotDismissBrowser() {
        show(100)
        node("browser-visual-container").performTouchInput { swipeDown(durationMillis = 100) }
        awaitVisualIdle()
        node("browser-visual-page").assertIsDisplayed()
        mainThread { assertEquals(0, dismissals); assertEquals(0, view.scrollY) }
        assertEquals(0, fixture.opens.get())
    }

    override fun tearDown() {
        try {
            if (::view.isInitialized) mainThread {
                (view.parent as? ViewGroup)?.removeView(view)
                view.destroy()
            }
        } finally { super.tearDown() }
    }
}
