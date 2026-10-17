package app.daiyu.android.platform

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract

/** Explicit settings UI actions only. Never called from a Catalog leaf or a model request. */
internal object PlatformSettingsAccess {
    fun runtimePermissions(tool: PlatformReadTool, api: Int = Build.VERSION.SDK_INT): List<String> = when (tool) {
        PlatformReadTool.NETWORK -> if (api >= 31) listOf(Manifest.permission.BLUETOOTH_CONNECT) else emptyList()
        PlatformReadTool.LAST_KNOWN_LOCATION -> listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
        PlatformReadTool.CONTACTS -> listOf(Manifest.permission.READ_CONTACTS)
        PlatformReadTool.CALENDAR -> listOf(Manifest.permission.READ_CALENDAR)
        PlatformReadTool.MEDIA -> if (api >= 33) buildList {
            add(Manifest.permission.READ_MEDIA_IMAGES)
            add(Manifest.permission.READ_MEDIA_VIDEO)
            add(Manifest.permission.READ_MEDIA_AUDIO)
            if (api >= 34) add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        } else listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        else -> emptyList()
    }

    fun permissionRequest(tool: PlatformReadTool, permission: String, api: Int = Build.VERSION.SDK_INT): Array<String> {
        require(permission in runtimePermissions(tool, api))
        // Android 12+ fine requests must include coarse; the user's approximate choice remains valid.
        if (tool == PlatformReadTool.LAST_KNOWN_LOCATION && permission == Manifest.permission.ACCESS_FINE_LOCATION)
            return arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
        // Android 14 lets the system present selected-only visual access, never treats it as full access.
        return if (api >= 34 && permission in listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO))
            arrayOf(permission, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) else arrayOf(permission)
    }

    fun status(context: Context, tool: PlatformReadTool): String = when (tool) {
        PlatformReadTool.LAST_KNOWN_LOCATION -> {
            val precision = when {
                hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) -> "精确"
                hasPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) -> "大致"
                else -> "未授权，调用将拒绝"
            }
            "前台位置权限：$precision；只读最多3个固定来源的已有缓存，可能无记录或已陈旧；不请求更新、不唤醒GPS、不后台定位、不读取SSID"
        }
        PlatformReadTool.NETWORK -> {
            val bluetooth = if (Build.VERSION.SDK_INT >= 31) Manifest.permission.BLUETOOTH_CONNECT else Manifest.permission.BLUETOOTH
            val missing = listOf(Manifest.permission.ACCESS_NETWORK_STATE, Manifest.permission.ACCESS_WIFI_STATE, bluetooth)
                .filterNot { hasPermission(context, it) }
            if (missing.isEmpty()) "网络/WiFi/蓝牙开关状态权限已授予；不读取SSID、地址或设备清单"
            else "缺少 ${missing.joinToString()}；对应网络/子状态读取将拒绝"
        }
        PlatformReadTool.CONTACTS, PlatformReadTool.CALENDAR -> {
            val missing = runtimePermissions(tool).filterNot { hasPermission(context, it) }
            if (missing.isEmpty()) "系统权限已授予（可随时在系统中撤回）" else "缺少 ${missing.joinToString()}；调用将拒绝"
        }
        PlatformReadTool.MEDIA -> {
            val granted = runtimePermissions(tool).filter { hasPermission(context, it) }
            "媒体按种类检查权限；未授权种类将拒绝。当前：${granted.joinToString().ifEmpty { "无" }}" +
                if (Build.VERSION.SDK_INT >= 34) "；仅选中视觉媒体不等于完整媒体库" else ""
        }
        PlatformReadTool.FILES -> "须精确持久化的只读SAF树授权；缺授权将拒绝；仅直接子项"
        PlatformReadTool.FILE_METADATA, PlatformReadTool.FILE_TEXT -> "须明确的content URI只读授权；缺授权将拒绝；不访问原始路径"
        PlatformReadTool.APPS -> "仅Android允许本应用可见的包；不是完整应用清单，不申请全包可见性"
        PlatformReadTool.NOTIFICATIONS -> "仅本应用当前通知；不读其他应用，不需要通知监听或发通知权限"
        PlatformReadTool.CROSS_APP_NOTIFICATIONS, PlatformReadTool.NOTIFICATION_HISTORY -> CrossAppNotifications.from(context).status()
        else -> "无需危险系统权限；SDK不可用或限制时安全拒绝"
    }

    fun pickerIntent(tree: Boolean): Intent = Intent(if (tree) Intent.ACTION_OPEN_DOCUMENT_TREE else Intent.ACTION_OPEN_DOCUMENT).apply {
        if (!tree) { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*" }
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
    }

    /** A result never becomes an ambient filesystem grant, write grant or ancestor-tree grant. */
    fun acceptedUri(data: Intent, tree: Boolean): Uri? {
        val uri = data.data ?: return null
        if (uri.toString().length > 2048) return null // Same bound as the existing Catalog URI schema.
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        if (data.flags and flags != flags || uri.scheme != "content" || uri.authority.isNullOrBlank() ||
            uri.userInfo != null || uri.fragment != null || DocumentsContract.isTreeUri(uri) != tree) return null
        // A descendant document URI with a tree prefix must not masquerade as its ancestor grant.
        if (tree && (uri.pathSegments.size != 2 || uri.pathSegments[0] != "tree" || uri.pathSegments[1].isEmpty() || uri.query != null)) return null
        return uri
    }
}

/** Tracks only grants explicitly picked on the platform settings page, never scans provider data. */
internal class PlatformSafGrants internal constructor(private val preferences: android.content.SharedPreferences,
    private val persistedReads: () -> List<Uri>, private val takeRead: (Uri) -> Unit,
    private val releaseRead: (Uri) -> Unit) {
    constructor(context: Context) : this(context.getSharedPreferences("platform-read-saf", Context.MODE_PRIVATE),
        { context.contentResolver.persistedUriPermissions.filter { it.isReadPermission }.map { it.uri } },
        { context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) },
        { context.contentResolver.releasePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) })

    private fun selected(): Set<String> = try {
        preferences.getStringSet("exact-read-uris", emptySet()).orEmpty().toSet()
    } catch (_: ClassCastException) { emptySet() }

    fun grants(): List<Uri> = try {
        val selected = selected()
        persistedReads().filter { it.toString() in selected }.distinct()
    } catch (_: RuntimeException) { emptyList() }

    fun accept(data: Intent, tree: Boolean): Boolean {
        val uri = PlatformSettingsAccess.acceptedUri(data, tree) ?: return false
        return try {
            val alreadyGranted = uri in persistedReads()
            takeRead(uri)
            if (uri !in persistedReads()) return false
            if (preferences.edit().putStringSet("exact-read-uris", selected() + uri.toString()).commit()) true
            else {
                if (!alreadyGranted) releaseRead(uri)
                false
            }
        } catch (_: RuntimeException) { false }
    }

    fun revoke(uri: Uri) {
        require(uri in grants())
        releaseRead(uri)
        check(preferences.edit().putStringSet("exact-read-uris", selected() - uri.toString()).commit())
    }
}
