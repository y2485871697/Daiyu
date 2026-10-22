package app.daiyu.android.browser

import android.test.InstrumentationTestCase
import app.daiyu.android.browser.ui.BrowserUiPool

@Suppress("DEPRECATION")
class BrowserUiInputTest : InstrumentationTestCase() {
    fun testInvalidAddressIsRejectedWithoutNavigationOrThrowing() {
        instrumentation.runOnMainSync {
            val pool = BrowserUiPool(instrumentation.targetContext, null)
            val page = pool.Tab(0).manager
            for (input in listOf("x".repeat(4097), "https://example.com/\u0000tail", "   ")) {
                pool.notice.value = ""
                page.loadURL(input)
                assertEquals("此地址不能在网页中打开", pool.notice.value)
                assertEquals("", page.currentURL.value)
                assertNull(pool.selectedTabId.value)
                assertTrue(pool.tabs.value.isEmpty())
            }
        }
    }

    fun testAddressNormalizationKeepsValidInputAndRejectsControlCharacters() {
        assertEquals("https://example.com/path", BrowserUiPool.normalize(" example.com/path "))
        assertEquals("https://example.com/path", BrowserUiPool.normalize("https://example.com/path"))
        for (input in listOf("x".repeat(4097), "https://example.com/\u007ftail")) {
            try {
                BrowserUiPool.normalize(input)
                fail("Invalid address was accepted")
            } catch (_: IllegalArgumentException) {
                // The UI boundary turns this validation error into a notice.
            }
        }
    }
}
