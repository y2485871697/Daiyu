package app.daiyu.android.browser

import android.app.Application
import android.content.Context
import app.daiyu.android.browser.session.BrowserAccess
import app.daiyu.android.platform.DeviceResultPolicy
import app.daiyu.android.platform.PlatformReadTool
import app.daiyu.android.storage.PlatformToolSettings
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Product ceilings only. No WebView, Android permission requests or credential reads. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class, manifest = Config.NONE)
class BrowserProductDefaultsTest {
    @Test fun productRemainsFullForMissingAndObsoleteSavedCeilingsIncludingCookieAndHistoryPolicy() {
        val context: Context = RuntimeEnvironment.getApplication()
        val preferences = context.getSharedPreferences("browser-settings", Context.MODE_PRIVATE)
        for (legacy in listOf(null, "v1:DISABLED", "v1:READ_ONLY", "v1:FULL")) {
            preferences.edit().clear().apply { if (legacy != null) putString(BrowserSettingsStore.KEY, legacy) }.commit()
            val before = preferences.all.toMap()
            val settings = BrowserSettingsStore(context).snapshot()
            assertEquals(BrowserAccess.FULL, settings.access)
            assertEquals("ALLOW", settings.historyConsent)
            assertTrue(settings.allCookieNamesAllowed)
            assertTrue(settings.allowsCookieName("synthetic_session"))
            assertFalse(settings.allowsCookieName(""))
            assertEquals(before, preferences.all) // No credential/name lists are persisted.
        }
    }

    @Test fun strictInjectedFullDoesNotAcquireProductOutboundOrCookiePolicy() {
        val context: Context = RuntimeEnvironment.getApplication()
        val preferences = context.getSharedPreferences("strict-browser-policy", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        val strict = BrowserSettingsStore(preferences)
        assertEquals(BrowserAccess.DISABLED, strict.snapshot().access)
        val settings = strict.save(BrowserSettings(BrowserAccess.FULL))
        assertEquals("DENY", settings.historyConsent)
        assertFalse(settings.allCookieNamesAllowed)
        assertFalse(settings.allowsCookieName("synthetic_session"))
        assertTrue(settings.allowedCookieNames.isEmpty())
    }

    @Test fun platformContextStillSuppliesEveryToolAndBothPrivateDestinationsDespiteLegacyEmptySets() {
        val context: Context = RuntimeEnvironment.getApplication()
        val preferences = context.getSharedPreferences("platform-read-settings", Context.MODE_PRIVATE)
        preferences.edit().clear().putStringSet("selected-v1", emptySet())
            .putStringSet("private-model-v1", emptySet()).putStringSet("private-history-v1", emptySet()).commit()
        val before = preferences.all.toMap()
        val settings = PlatformToolSettings(context).freeze()
        assertTrue(settings.allDeviceTools)
        assertEquals(PlatformReadTool.entries.toSet(), settings.platformAllowed)
        for (tool in PlatformReadTool.entries.filter { it.toolName in DeviceResultPolicy.PRIVATE_TOOLS }) {
            assertTrue(settings.modelConsent(tool)); assertTrue(settings.historyConsent(tool))
            assertTrue(settings.resultPolicy.allowsShared(tool.toolName))
        }
        assertEquals(before, preferences.all)
        assertTrue(PlatformToolSettings(preferences).freeze().platformAllowed.isEmpty())
    }
}
