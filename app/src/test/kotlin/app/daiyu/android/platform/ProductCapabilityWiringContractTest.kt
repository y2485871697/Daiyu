package app.daiyu.android.platform

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Source contracts complement (not replace) runtime tests and do not read device/user state. */
class ProductCapabilityWiringContractTest {
    private fun source(path: String): String = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .map { File(it, "app/src/main/kotlin/app/daiyu/android/$path") }.first { it.isFile }.readText()

    @Test fun publicApplicationConstructorAlreadyUsesProductEntriesNotInternalFixtureDefaults() {
        val constructor = source("ChatController.kt").substringAfter("constructor(application:Application):this(")
            .substringBefore("val assistantStore:")
        assertTrue(constructor.contains("platformSettings=PlatformToolSettings(application)"))
        assertTrue(constructor.contains("browserSettings=app.daiyu.android.browser.BrowserSettingsStore(application)"))
        assertFalse(constructor.contains("getSharedPreferences(\"platform-read-settings\""))
        assertFalse(constructor.contains("getSharedPreferences(\"browser-settings\""))
    }

    @Test fun notificationDefaultDoesNotBypassSystemGrantConnectionOrReenableOnEveryReconnect() {
        val host = source("platform/CrossAppNotifications.kt")
        val connected = host.substringAfter("fun connected(").substringBefore("fun disconnected(")
        assertTrue(connected.contains("if (!granted()) revoke()"))
        assertTrue(connected.contains("store.resumeAfterSystemGrant(clock())"))
        assertFalse(connected.contains("setEnabled("))
        val posted = host.substringAfter("fun posted(").substringBefore("fun epoch(")
        assertTrue(posted.contains("if (!granted())"))
        assertTrue(posted.contains("listener !== source || !store.enabled()"))
        val revoke = host.substringAfter("private fun revoke()").substringBefore("fun status()")
        assertTrue(revoke.contains("store.clearForSystemRevocation()"))
        assertFalse(revoke.contains("setEnabled(false"))
    }
}
