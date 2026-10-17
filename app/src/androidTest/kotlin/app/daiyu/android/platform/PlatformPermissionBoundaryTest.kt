package app.daiyu.android.platform

import android.content.ContentResolver
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.net.Uri
import android.test.InstrumentationTestCase
import org.json.JSONObject

/** Denial-only fixtures: no real contacts, calendar, media, apps or notifications are read. */
@Suppress("DEPRECATION")
class PlatformPermissionBoundaryTest : InstrumentationTestCase() {
    private class DenyingContext(base: Context, private val runtimeGranted: Boolean = false,
        private val uriGranted: Boolean = false) : ContextWrapper(base) {
        var permissionChecks = 0
        override fun getApplicationContext(): Context = this
        override fun checkSelfPermission(permission: String): Int {
            permissionChecks++
            return if (runtimeGranted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
        }
        override fun checkUriPermission(uri: Uri, pid: Int, uid: Int, modeFlags: Int) =
            if (uriGranted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
        override fun getPackageManager(): PackageManager = throw AssertionError("package inventory must not be touched")
        override fun getSystemService(name: String): Any? = throw AssertionError("system service must not be touched")
        override fun getContentResolver(): ContentResolver = throw AssertionError("content provider must not be touched")
    }

    fun testMissingPermissionsRejectBeforeAnyServiceOrProviderRead() {
        val context = DenyingContext(instrumentation.targetContext)
        val backend = AndroidPlatformReads(context)
        val cases = listOf(PlatformReadTool.NETWORK to "{}", PlatformReadTool.MEDIA to """{"kind":"image"}""",
            PlatformReadTool.CALENDAR to """{"start_ms":1,"end_ms":2}""", PlatformReadTool.CONTACTS to """{"query":"fixture"}""")
        for ((tool, args) in cases) {
            val result = JSONObject(backend.read(tool, tool.schema.parse(args)) { false })
            assertFalse(result.getBoolean("ok")); assertEquals("permission_denied", result.getString("error"))
            assertTrue(result.getString("required_permission").startsWith("android.permission."))
        }
        assertTrue(context.permissionChecks >= cases.size)
    }

    fun testUriToolsNeverUseAmbientProviderPermissionOrRequestGrants() {
        val backend = AndroidPlatformReads(DenyingContext(instrumentation.targetContext))
        for (tool in listOf(PlatformReadTool.FILE_METADATA, PlatformReadTool.FILE_TEXT)) {
            val denied = JSONObject(backend.read(tool, tool.schema.parse("""{"uri":"content://fixture/document/1"}""")) { false })
            assertEquals("uri_read_grant_required", denied.getString("error"))
            for (uri in listOf("file:///fixture.txt", "https://fixture.invalid/a", "content://user@fixture/a", "content://fixture/a#x")) {
                val args = tool.schema.parse(JSONObject().put("uri", uri).toString())
                assertEquals("invalid_uri", JSONObject(backend.read(tool, args) { false }).getString("error"))
            }
        }
    }

    fun testCancellationAndInvalidWindowsDoNotReadOrCheckPermissions() {
        val context = DenyingContext(instrumentation.targetContext)
        val backend = AndroidPlatformReads(context)
        assertEquals("cancelled", JSONObject(backend.read(PlatformReadTool.NETWORK,
            PlatformReadTool.NETWORK.schema.parse("{}")) { true }).getString("error"))
        assertEquals("invalid_time_window", JSONObject(backend.read(PlatformReadTool.CALENDAR,
            PlatformReadTool.CALENDAR.schema.parse("""{"start_ms":2,"end_ms":1}""")) { false }).getString("error"))
        assertEquals(0, context.permissionChecks)
    }

    fun testPermissionInspectionIsAnObservationNotAPermissionRequest() {
        val backend = AndroidPlatformReads(DenyingContext(instrumentation.targetContext))
        val result = JSONObject(backend.read(PlatformReadTool.PERMISSIONS, PlatformReadTool.PERMISSIONS.schema.parse("{}")) { false })
        assertTrue(result.getBoolean("ok")); assertEquals("own_app_only", result.getJSONObject("data").getString("notification_scope"))
        val states = result.getJSONObject("data").getJSONArray("permissions")
        assertTrue(states.length() >= 4)
        for (index in 0 until states.length()) assertFalse(states.getJSONObject(index).getBoolean("granted"))
    }

    fun testDefaultPolicyDoesNotReadPrivateDataEvenWhenSystemGrantsExist() {
        val backend = AndroidPlatformReads(DenyingContext(instrumentation.targetContext, runtimeGranted = true, uriGranted = true))
        val cases = listOf(PlatformReadTool.APPS to "{}", PlatformReadTool.MEDIA to """{"kind":"image"}""",
            PlatformReadTool.CALENDAR to """{"start_ms":1,"end_ms":2}""", PlatformReadTool.CONTACTS to """{"query":"fixture"}""",
            PlatformReadTool.NOTIFICATIONS to "{}", PlatformReadTool.FILES to """{"tree_uri":"content://fixture/tree/1"}""",
            PlatformReadTool.FILE_METADATA to """{"uri":"content://fixture/document/1"}""",
            PlatformReadTool.FILE_TEXT to """{"uri":"content://fixture/document/1"}""")
        for ((tool, args) in cases) {
            val result = JSONObject(backend.read(tool, tool.schema.parse(args)) { false })
            assertFalse(result.getBoolean("ok")); assertEquals("private_result_consent_required", result.getString("error"))
            assertTrue(result.getBoolean("privacy_redacted")); assertFalse(result.has("data"))
        }
    }

    fun testFieldBoundDoesNotSplitSurrogatePairs() {
        val value = "a".repeat(255) + "\uD83D\uDE00"
        val bounded = AndroidPlatformReads.bounded(value)
        assertEquals(255, bounded.length)
        assertFalse(bounded.last().isHighSurrogate())
    }
}
