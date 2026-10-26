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
            PlatformReadTool.FILE_TEXT -> success(fileText(checkNotNull(uri), args, cancelled))
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

    private fun fileText(uri: Uri, arguments: JSONObject, cancelled: () -> Boolean): JSONObject {
        val mime = app.contentResolver.getType(uri) ?: throw Rejected("text_mime_required")
        val supported = mime.startsWith("text/") || mime in setOf("application/json", "application/xml",
            "application/javascript", "application/x-yaml", "application/yaml", "application/sql", "application/x-ndjson")
        if (!supported) throw Rejected("unsupported_document_format")
        val offset = arguments.optLong("offset_bytes", 0)
        val line = if (arguments.has("start_line")) arguments.getLong("start_line") else null
        val query = if (arguments.has("query")) arguments.getString("query") else null
        val result = app.contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
            val duplicate = android.os.ParcelFileDescriptor.dup(descriptor.parcelFileDescriptor.fileDescriptor)
            android.os.ParcelFileDescriptor.AutoCloseInputStream(duplicate).use { original ->
                val start = descriptor.startOffset
                val length = descriptor.declaredLength.takeIf { it >= 0 }
                var remaining = length
                // AFD.createInputStream keeps an internal remaining-byte counter that cannot be
                // reset by channel.position. Own a duplicate and bound this document slice ourselves.
                fun position(target: Long): Boolean {
                    if (length != null && target > length) throw Rejected("offset_beyond_eof")
                    return try {
                        original.channel.position(Math.addExact(start, target))
                        remaining = length?.minus(target)
                        true
                    } catch (_: java.io.IOException) { false }
                }
                if (start > 0 && !position(0)) throw Rejected("unavailable")
                val input = object : java.io.InputStream() {
                    override fun read(): Int {
                        if (remaining == 0L) return -1
                        val next = original.read()
                        if (next >= 0) remaining = remaining?.minus(1)
                        return next
                    }
                    override fun read(buffer: ByteArray, at: Int, count: Int): Int {
                        if (count == 0) return 0
                        if (remaining == 0L) return -1
                        val size = remaining?.let { minOf(it, count.toLong()).toInt() } ?: count
                        val read = original.read(buffer, at, size)
                        if (read > 0) remaining = remaining?.minus(read)
                        return read
                    }
                }
                try { DocumentTextWindow.read(input, offset, line, arguments.optInt("line_count", 200), query, cancelled,
                    seek = ::position, skipLines = arguments.optLong("skip_lines", 0)) }
                catch (_: java.nio.charset.CharacterCodingException) { throw Rejected("invalid_utf8") }
                catch (error: IllegalArgumentException) {
                    throw Rejected(if (error.message in setOf("binary_document", "non_seekable_offset_limit", "offset_beyond_eof"))
                        checkNotNull(error.message) else "invalid_arguments")
                }
            }
        } ?: throw Rejected("unavailable")
        return JSONObject().put("scope", "explicit_content_uri_grant").put("mime_type", bounded(mime))
            .put("text", result.text).put("offset_bytes", result.offset).put("next_offset_bytes", result.nextOffset)
            .put("first_line_in_scan", result.firstLine).put("lines_returned", result.lines)
            .put("remaining_lines_to_skip", result.remainingLines).put("scanned_bytes", result.scannedBytes)
            .put("partial_line", result.partialLine).put("search_complete", query == null || !result.truncated)
            .put("truncated", result.truncated).put("size_bytes", result.text.toByteArray(Charsets.UTF_8).size)
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
