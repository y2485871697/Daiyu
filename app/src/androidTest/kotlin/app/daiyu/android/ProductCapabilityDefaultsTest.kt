package app.daiyu.android

import android.content.Context
import android.content.ContextWrapper
import android.test.InstrumentationTestCase
import app.daiyu.android.browser.BrowserSettingsStore
import app.daiyu.android.browser.session.BrowserAccess
import app.daiyu.android.platform.DeviceResultPolicy
import app.daiyu.android.platform.PlatformReadTool
import app.daiyu.android.storage.PlatformToolSettings
import java.util.UUID

@Suppress("DEPRECATION")
class ProductCapabilityDefaultsTest : InstrumentationTestCase() {
    fun testProductCeilingsAreOpenWhileInjectedStoresRetainStrictSavedPolicy() {
        val context = object : ContextWrapper(testContext(instrumentation.targetContext, "product-capabilities-${UUID.randomUUID()}-")) {
            override fun getApplicationContext(): Context = this
        }
        val platform = context.getSharedPreferences("platform-read-settings", 0)
        val browser = context.getSharedPreferences("browser-settings", 0)
        try {
            check(platform.edit().putStringSet("selected-v1", emptySet()).putStringSet("private-model-v1", emptySet())
                .putStringSet("private-history-v1", emptySet()).commit())
            check(browser.edit().putString("browser-settings-v1", "v1:DISABLED").commit())
            val beforePlatform = platform.all
            val beforeBrowser = browser.all
            val product = PlatformToolSettings(context).freeze()
            assertEquals(PlatformReadTool.entries.toSet(), product.platformAllowed)
            assertTrue(product.allDeviceTools)
            assertFalse(PlatformToolSettings(platform).freeze().allDeviceTools)
            for (tool in PlatformReadTool.entries) {
                val privateTool = tool.toolName in DeviceResultPolicy.PRIVATE_TOOLS
                assertEquals(privateTool, product.modelConsent(tool))
                assertEquals(privateTool, product.historyConsent(tool))
            }
            assertTrue(PlatformToolSettings(platform).freeze().platformAllowed.isEmpty())
            val productBrowser = BrowserSettingsStore(context).snapshot()
            assertEquals(BrowserAccess.FULL, productBrowser.access)
            assertEquals("DENY", productBrowser.historyConsent)
            assertTrue(productBrowser.allowedCookieNames.isEmpty())
            assertEquals(BrowserAccess.DISABLED, BrowserSettingsStore(browser).snapshot().access)
            assertEquals(beforePlatform, platform.all)
            assertEquals(beforeBrowser, browser.all)
        } finally { platform.edit().clear().commit(); browser.edit().clear().commit() }
    }
}
