package app.daiyu.android.platform

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DeviceResultPolicyTest {
    @Test fun defaultPrivateProjectionsWithholdDataAndSharedDeliveryRequiresBothConsents() {
        val tool = PlatformReadTool.CONTACTS.toolName
        val raw = JSONObject().put("ok", true).put("status", "ok").put("data", JSONObject().put("display_name", "private-fixture"))
        val policy = DeviceResultPolicy()
        assertFalse(policy.allowsShared(tool))
        for (safe in listOf(policy.forModel(tool, raw), policy.forHistory(tool, raw), policy.forSharedDelivery(tool, raw))) {
            assertFalse(safe.getBoolean("ok")); assertTrue(safe.getBoolean("privacy_redacted"))
            assertFalse(safe.toString().contains("private-fixture")); assertFalse(safe.has("data"))
        }
        val modelOnly = DeviceResultPolicy(modelPrivateTools = setOf(tool))
        assertTrue(modelOnly.forModel(tool, raw).getBoolean("ok"))
        assertFalse(modelOnly.allowsShared(tool)); assertFalse(modelOnly.forSharedDelivery(tool, raw).has("data"))
        val historyOnly = DeviceResultPolicy(historyPrivateTools = setOf(tool))
        assertFalse(historyOnly.allowsShared(tool)); assertFalse(historyOnly.forSharedDelivery(tool, raw).has("data"))
        val both = DeviceResultPolicy(setOf(tool), setOf(tool))
        assertTrue(both.allowsShared(tool)); assertTrue(both.forSharedDelivery(tool, raw).has("data"))
    }

    @Test fun consentSetsAreSnapshotsAndPermissionDiagnosticsSurviveWithholding() {
        val tool = PlatformReadTool.MEDIA.toolName
        val mutable = mutableSetOf(tool)
        val policy = DeviceResultPolicy(mutable, mutable)
        mutable.clear()
        assertTrue(policy.allowsShared(tool))
        mutable.add(PlatformReadTool.NOTIFICATIONS.toolName)
        assertFalse(policy.allowsShared(PlatformReadTool.NOTIFICATIONS.toolName))
        val denied = failure("permission_denied", permissions = listOf("android.permission.READ_CONTACTS"))
        val safe = DeviceResultPolicy().forSharedDelivery(PlatformReadTool.CONTACTS.toolName, denied)
        assertEquals("permission_denied", safe.getString("error"))
        assertEquals("android.permission.READ_CONTACTS", safe.getString("required_permission"))
        assertFalse(safe.has("data"))
    }

    @Test fun publicStatusIsCopiedAndHistoricalPrivateNamesStayPrivate() {
        val policy = DeviceResultPolicy()
        val source = success(JSONObject().put("api_level", 26))
        val delivered = policy.forSharedDelivery(PlatformReadTool.ENVIRONMENT.toolName, source)
        assertTrue(delivered.getBoolean("ok")); delivered.getJSONObject("data").put("api_level", 37)
        assertEquals(26, source.getJSONObject("data").getInt("api_level"))
        for (name in listOf("device_location", "query_notifications", "query_calendar", "query_contacts", "query_media", "query_files"))
            assertFalse(policy.allowsShared(name))
    }
}
