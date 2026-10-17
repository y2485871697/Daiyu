package app.daiyu.android.platform

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Synthetic cache/permission fixtures only; no real providers, permission prompts or device data. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class, manifest = Config.NONE)
class LastKnownLocationReadsTest {
    private val tool = PlatformReadTool.LAST_KNOWN_LOCATION
    private val now = 20_000_000_000L
    private class AccessContext : ContextWrapper(RuntimeEnvironment.getApplication()) {
        var coarse = true
        var fine = true
        var serviceReads = 0
        override fun getApplicationContext(): Context = this
        override fun checkSelfPermission(permission: String): Int = if (when (permission) {
            Manifest.permission.ACCESS_COARSE_LOCATION -> coarse
            Manifest.permission.ACCESS_FINE_LOCATION -> fine
            else -> false
        }) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
        override fun getSystemService(name: String): Any? {
            serviceReads++
            throw AssertionError("No Android service may be read by synthetic fixtures")
        }
    }
    private fun sample(seconds: Long, wallTime: Long = seconds * 1000) = Location("synthetic").apply {
        latitude = 12.5; longitude = 23.75; accuracy = 15f
        time = wallTime; elapsedRealtimeNanos = seconds * 1_000_000_000L
    }
    private fun both() = DeviceResultPolicy(setOf(tool.toolName), setOf(tool.toolName))
    private fun read(context: AccessContext, source: LastKnownLocationSource, cancelled: () -> Boolean = { false }) =
        LastKnownLocationReads.read(context, source, cancelled) { now }

    @Test fun permissionAndEachMissingDestinationRejectBeforeCacheOrAndroidService() {
        val context = AccessContext()
        var reads = 0
        val source = LastKnownLocationSource { reads++; error("must not read") }
        val policies = listOf(DeviceResultPolicy(), DeviceResultPolicy(setOf(tool.toolName)),
            DeviceResultPolicy(historyPrivateTools = setOf(tool.toolName)))
        for (policy in policies) {
            val backend = AndroidPlatformReads(context, policy, source)
            val result = JSONObject(backend.read(tool, tool.schema.parse("{}")) { false })
            assertEquals("private_result_consent_required", result.getString("error"))
            assertTrue(result.getBoolean("privacy_redacted")); assertFalse(result.has("data"))
        }
        context.coarse = false; context.fine = false
        val denied = JSONObject(AndroidPlatformReads(context, both(), source).read(tool, tool.schema.parse("{}")) { false })
        assertEquals("permission_denied", denied.getString("error"))
        assertEquals(2, denied.getJSONArray("required_permissions").length())
        assertFalse(denied.has("data")); assertEquals(0, reads); assertEquals(0, context.serviceReads)
    }

    @Test fun newestAccessibleRecordUsesMonotonicTimeAndReturnsSampleAgeAndSource() {
        val context = AccessContext()
        val calls = mutableListOf<String>()
        val result = read(context, LastKnownLocationSource { provider ->
            calls += provider
            when (provider) {
                LocationManager.NETWORK_PROVIDER -> sample(10, 999_999) // Newer wall time must not win.
                LocationManager.GPS_PROVIDER -> throw SecurityException("synthetic inaccessible provider")
                LocationManager.PASSIVE_PROVIDER -> sample(18, 123_456)
                else -> error("unbounded provider")
            }
        })
        assertTrue(result.getBoolean("ok"))
        val data = result.getJSONObject("data")
        assertEquals(listOf("network", "gps", "passive"), calls)
        assertEquals("passive", data.getString("provider")); assertEquals(123_456L, data.getLong("sampled_at_ms"))
        assertEquals(2000L, data.getLong("age_ms")); assertEquals("elapsed_realtime", data.getString("age_clock"))
        assertEquals("LocationManager.getLastKnownLocation", data.getString("source"))
        assertEquals("last_known_only", data.getString("scope")); assertEquals(12.5, data.getDouble("latitude"), 0.0)
        assertEquals(15.0, data.getDouble("accuracy_m"), 0.0); assertFalse(data.has("extras"))
    }

    @Test fun authorizedAndroidPlatformAdapterDeliversOnlyCacheRecordOrExplicitNoData() {
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(30))
        val context = AccessContext()
        var reads = 0
        val backend = AndroidPlatformReads(context, both(), LastKnownLocationSource { reads++; sample(10) })
        val delivered = JSONObject(backend.read(tool, tool.schema.parse("{}")) { false })
        assertTrue(delivered.getBoolean("ok")); assertTrue(delivered.has("observed_at"))
        assertEquals("network", delivered.getJSONObject("data").getString("provider"))
        assertEquals(10_000L, delivered.getJSONObject("data").getLong("sampled_at_ms"))
        assertTrue(delivered.getJSONObject("data").getLong("age_ms") >= 20_000L)
        assertEquals(3, reads)
        val empty = AndroidPlatformReads(context, both(), LastKnownLocationSource { null })
        val noData = JSONObject(empty.read(tool, tool.schema.parse("{}")) { false })
        assertEquals("no_cached_location", noData.getString("error")); assertFalse(noData.has("data"))
        context.coarse = false; context.fine = false
        val revoked = JSONObject(backend.read(tool, tool.schema.parse("{}")) { false })
        assertEquals("permission_denied", revoked.getString("error")); assertFalse(revoked.has("data"))
        assertEquals(3, reads); assertEquals(0, context.serviceReads)
    }

    @Test fun stableTieBreakAndCoarseOnlyNeverProbeGps() {
        val context = AccessContext().apply { fine = false }
        val calls = mutableListOf<String>()
        val result = read(context, LastKnownLocationSource { provider -> calls += provider; sample(10) })
        assertEquals(listOf("network", "passive"), calls)
        assertEquals("network", result.getJSONObject("data").getString("provider"))
        assertEquals("coarse", result.getJSONObject("data").getString("permission_precision"))
    }

    @Test fun noDataIsExplicitAndNoAttemptsToAcquireNewFixArePossible() {
        val context = AccessContext()
        var reads = 0
        val result = read(context, LastKnownLocationSource { reads++; null })
        assertFalse(result.getBoolean("ok")); assertEquals("no_cached_location", result.getString("error"))
        assertEquals(3, reads); assertFalse(result.has("data"))
        val invalid = read(context, LastKnownLocationSource { sample(21) }) // Future monotonic sample is not usable.
        assertEquals("no_cached_location", invalid.getString("error"))
        val denied = read(context, LastKnownLocationSource { throw SecurityException("synthetic") })
        assertEquals("permission_denied", denied.getString("error")); assertFalse(denied.has("data"))
    }

    @Test fun revocationAndFineDowngradeDiscardAlreadyReadCoordinates() {
        for (retainCoarse in listOf(false, true)) {
            val context = AccessContext()
            var reads = 0
            val result = read(context, LastKnownLocationSource {
                reads++; context.fine = false; context.coarse = retainCoarse; sample(10)
            })
            assertEquals("permission_denied", result.getString("error"))
            assertFalse(result.has("data")); assertEquals(1, reads)
        }
    }

    @Test fun cancellationBeforeReadAndAfterCacheAccessNeverReturnsCoordinates() {
        val context = AccessContext()
        var reads = 0
        var cancelled = true
        val source = LastKnownLocationSource { reads++; cancelled = true; sample(10) }
        val backend = AndroidPlatformReads(context, both(), source)
        val before = JSONObject(backend.read(tool, tool.schema.parse("{}")) { cancelled })
        assertEquals("cancelled", before.getString("error")); assertEquals(0, reads)
        cancelled = false
        val after = JSONObject(backend.read(tool, tool.schema.parse("{}")) { cancelled })
        assertEquals("cancelled", after.getString("error")); assertEquals(1, reads)
        assertFalse(after.has("data")); assertEquals(0, context.serviceReads)
    }

    @Test fun bothDestinationProjectionsIndependentlyWithholdLocation() {
        val raw = success(JSONObject().put("latitude", 12.5).put("longitude", 23.75))
        assertTrue(tool.toolName in DeviceResultPolicy.PRIVATE_TOOLS)
        val modelOnly = DeviceResultPolicy(setOf(tool.toolName))
        val historyOnly = DeviceResultPolicy(historyPrivateTools = setOf(tool.toolName))
        assertFalse(modelOnly.allowsShared(tool.toolName)); assertFalse(historyOnly.allowsShared(tool.toolName))
        assertFalse(modelOnly.forHistory(tool.toolName, raw).has("data"))
        assertFalse(historyOnly.forModel(tool.toolName, raw).has("data"))
        assertTrue(both().forSharedDelivery(tool.toolName, raw).has("data"))
    }
}
