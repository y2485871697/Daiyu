package app.daiyu.android.platform

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.OpenableColumns
import app.daiyu.android.domain.tools.ToolArguments
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Locale
import java.util.TimeZone

/** Catalog adapter around preserved device reads/queries plus app search and granted-document reads.
 * App SDK identity only: no shell, root, Shizuku, permission acquisition, network probes or logging.
 */
internal class AndroidPlatformReads(context: Context, private val resultPolicy: DeviceResultPolicy = DeviceResultPolicy(),
    lastKnownLocationSource: LastKnownLocationSource? = null) : PlatformReadBackend {
    private val app = context.applicationContext ?: context
    private val locationCache = lastKnownLocationSource ?: AndroidLastKnownLocationSource(app)
    private class Rejected(val code: String, val permission: String? = null) : RuntimeException()

    private val notificationAdmissionGate = Any()
    private var notificationEpoch: Long? = null
    override fun admission(tool: PlatformReadTool): String? = when (tool) {
        PlatformReadTool.CROSS_APP_NOTIFICATIONS, PlatformReadTool.NOTIFICATION_HISTORY -> try {
            synchronized(notificationAdmissionGate) {
                val host = CrossAppNotifications.from(app)
                val denial = host.admission(tool, resultPolicy)
                if (denial != null) denial else {
                    val epoch = host.epoch()
                    val frozen = notificationEpoch
                    if (frozen == null) { notificationEpoch = epoch; null }
                    else if (frozen != epoch) "notification_access_changed" else null
                }
            }
        } catch (_: RuntimeException) { "notification_access_unavailable" }
        else -> null
    }

    override fun read(tool: PlatformReadTool, arguments: ToolArguments, cancelled: () -> Boolean): String = try {
        checkReadCancellation(cancelled)
        val args = JSONObject(arguments.canonicalJson()) // Already strictly parsed/normalized by ToolSchema.
        if (tool == PlatformReadTool.CALENDAR) {
            val start = arguments.integer("start_ms"); val end = arguments.integer("end_ms")
            if (end <= start || end - start > MAX_CALENDAR_WINDOW_MS) throw Rejected("invalid_time_window")
        }
        // Report actual missing permissions even when outbound private-result consent is absent.
        val denial = permissionDenial(tool, args)
        val uri = if (tool == PlatformReadTool.FILE_METADATA || tool == PlatformReadTool.FILE_TEXT) explicitUri(arguments) else null
        val result = denial ?: if (!resultPolicy.allowsShared(tool.toolName)) {
            // There is no local-only delivery sink here; do not read private user data just to discard it.
            failure("private_result_consent_required")
        } else when (tool) {
            PlatformReadTool.NETWORK -> DeviceReads.network(app)
            PlatformReadTool.TIME -> DeviceReads.time()
            PlatformReadTool.AUDIO -> DeviceReads.audio(app)
            PlatformReadTool.ENVIRONMENT -> success(environment())
            PlatformReadTool.PERMISSIONS -> success(permissions())
            PlatformReadTool.APPS -> success(apps(arguments, cancelled))
            PlatformReadTool.MEDIA -> DeviceQueries.media(app, args, cancelled)
            PlatformReadTool.FILE_METADATA -> fileMetadata(checkNotNull(uri), cancelled)
            PlatformReadTool.FILE_TEXT -> success(fileText(checkNotNull(uri), cancelled))
            PlatformReadTool.FILES -> DeviceQueries.files(app, args, cancelled)
            PlatformReadTool.CALENDAR -> DeviceQueries.calendar(app, args, cancelled)
            PlatformReadTool.CONTACTS -> DeviceQueries.contacts(app, args, cancelled)
            PlatformReadTool.NOTIFICATIONS -> DeviceQueries.notifications(app, args, cancelled)
            PlatformReadTool.LAST_KNOWN_LOCATION -> LastKnownLocationReads.read(app, locationCache, cancelled)
            PlatformReadTool.CROSS_APP_NOTIFICATIONS, PlatformReadTool.NOTIFICATION_HISTORY ->
                CrossAppNotifications.from(app).read(tool, arguments, resultPolicy, cancelled)
        }
        checkReadCancellation(cancelled)
        val delivered = resultPolicy.forSharedDelivery(tool.toolName, result.put("observed_at", System.currentTimeMillis()))
        val payload = delivered.toString()
        if (payload.length > MAX_RESULT_CHARS) failure("result_too_large").toString() else payload
    } catch (e: Rejected) {
        failure(e.code, permissions = listOfNotNull(e.permission)).toString()
    } catch (_: SecurityException) {
        failure("permission_denied").toString()
    } catch (_: android.os.OperationCanceledException) {
        failure("cancelled").toString()
    } catch (_: java.io.IOException) {
        failure("unavailable").toString()
    } catch (_: RuntimeException) {
        // Exception messages may contain contact names, URIs, provider SQL or filesystem paths.
        failure("unavailable").toString()
    }

    private fun permissionDenial(tool: PlatformReadTool, args: JSONObject): JSONObject? = when (tool) {
        PlatformReadTool.NETWORK -> requirePermission(app, Manifest.permission.ACCESS_NETWORK_STATE)
        PlatformReadTool.LAST_KNOWN_LOCATION -> LastKnownLocationReads.permissionDenial(app)
        PlatformReadTool.CALENDAR -> requirePermission(app, Manifest.permission.READ_CALENDAR)
        PlatformReadTool.CONTACTS -> requirePermission(app, Manifest.permission.READ_CONTACTS)
        PlatformReadTool.MEDIA -> if (DeviceQueries.selectedMedia(app, args.getString("kind"))) null
            else requirePermission(app, DeviceQueries.mediaPermission(args.getString("kind")))
        else -> null
    }

    private fun environment(): JSONObject {
        val power = app.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: throw Rejected("unavailable")
        val configuration = app.resources.configuration
        return JSONObject().put("api_level", Build.VERSION.SDK_INT).put("android_release", bounded(Build.VERSION.RELEASE))
            .put("manufacturer", bounded(Build.MANUFACTURER)).put("model", bounded(Build.MODEL))
            .put("locale", bounded(configuration.locales.get(0)?.toLanguageTag() ?: Locale.getDefault().toLanguageTag()))
            .put("timezone", bounded(TimeZone.getDefault().id)).put("power_save", power.isPowerSaveMode)
            .put("interactive", power.isInteractive).put("density_dpi", configuration.densityDpi)
            .put("orientation", configuration.orientation)
    }

    private fun permissions(): JSONObject {
        val states = JSONArray()
        val permissions = mutableListOf(Manifest.permission.ACCESS_NETWORK_STATE, Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.READ_CALENDAR, Manifest.permission.READ_CONTACTS, Manifest.permission.READ_EXTERNAL_STORAGE)
        if (Build.VERSION.SDK_INT >= 31) permissions += Manifest.permission.BLUETOOTH_CONNECT else permissions += Manifest.permission.BLUETOOTH
        if (Build.VERSION.SDK_INT >= 33) permissions += listOf(Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT >= 34) permissions += Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
        for (permission in permissions) states.put(JSONObject().put("permission", permission).put("granted", hasPermission(app, permission)))
        return JSONObject().put("permissions", states).put("notification_scope", "own_app_only")
            .put("package_visibility", "android_filtered").put("file_scope", "explicit_content_uri_grants_only")
            .put("privileged_access", "not_used")
    }

    @Suppress("DEPRECATION")
    private fun apps(arguments: ToolArguments, cancelled: () -> Boolean): JSONObject {
        val query = if (arguments.contains("query")) arguments.text("query").trim() else ""
        val limit = if (arguments.contains("limit")) arguments.integer("limit").toInt() else 20
        val rows = JSONArray()
        val packages = app.packageManager.getInstalledApplications(0)
        var scanned = 0
        var truncated = false
        var fieldsTruncated = false
        for (info in packages) {
            checkReadCancellation(cancelled)
            if (scanned++ >= MAX_APP_SCAN) { truncated = true; break }
            val label = app.packageManager.getApplicationLabel(info).toString()
            if (!info.packageName.contains(query, true) && !label.contains(query, true)) continue
            if (rows.length() == limit) { truncated = true; break }
            fieldsTruncated = fieldsTruncated || clippedText(info.packageName) || clippedText(label)
            rows.put(JSONObject().put("package", bounded(info.packageName)).put("label", bounded(label)).put("enabled", info.enabled))
        }
        return JSONObject().put("scope", "android_visible_packages_only").put("complete_inventory", false)
            .put("items", rows).put("truncated", truncated || fieldsTruncated).put("fields_truncated", fieldsTruncated)
            .put("scan_limit", MAX_APP_SCAN)
    }

    private fun explicitUri(arguments: ToolArguments): Uri {
        val uri = Uri.parse(arguments.text("uri"))
        if (uri.scheme != "content" || uri.authority.isNullOrBlank() || uri.userInfo != null || uri.fragment != null)
            throw Rejected("invalid_uri")
        if (app.checkUriPermission(uri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_READ_URI_PERMISSION) != PackageManager.PERMISSION_GRANTED)
            throw Rejected("uri_read_grant_required")
        return uri
    }

    private fun fileMetadata(uri: Uri, cancelled: () -> Boolean): JSONObject = DeviceQueries.rows(app.contentResolver, uri,
        arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null, 1, cancelled) { cursor ->
        JSONObject().put("name", boundedText(cursor.getString(0)))
            .put("size_bytes", if (cursor.isNull(1)) JSONObject.NULL else cursor.getLong(1))
    }.apply { if (optBoolean("ok")) getJSONObject("data").put("scope", "explicit_content_uri_grant") }

    private fun fileText(uri: Uri, cancelled: () -> Boolean): JSONObject {
        val mime = app.contentResolver.getType(uri) ?: throw Rejected("text_mime_required")
        if (!mime.startsWith("text/")) throw Rejected("text_mime_required")
        val bytes = app.contentResolver.openInputStream(uri)?.use { stream ->
            val buffer = ByteArray(MAX_TEXT_BYTES + 1)
            var count = 0
            while (count < buffer.size) {
                checkReadCancellation(cancelled)
                val read = stream.read(buffer, count, buffer.size - count)
                if (read < 0) break
                if (read == 0) throw Rejected("unavailable")
                count += read
            }
            if (count > MAX_TEXT_BYTES) throw Rejected("file_too_large")
            buffer.copyOf(count)
        } ?: throw Rejected("unavailable")
        val text = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: java.nio.charset.CharacterCodingException) { throw Rejected("invalid_utf8") }
        return JSONObject().put("scope", "explicit_content_uri_grant").put("mime_type", bounded(mime))
            .put("text", text).put("size_bytes", bytes.size).put("truncated", false)
    }

    companion object {
        const val MAX_TEXT_BYTES = 8192
        const val MAX_RESULT_CHARS = 65536
        const val MAX_FIELD_CHARS = 256
        const val MAX_APP_SCAN = 512
        const val MAX_CALENDAR_WINDOW_MS = 31L * 24 * 60 * 60 * 1000
        internal fun bounded(value: String): String {
            val end = minOf(value.length, MAX_FIELD_CHARS)
            return value.substring(0, if (end > 0 && value[end - 1].isHighSurrogate()) end - 1 else end)
        }
    }
}
