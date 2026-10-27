package app.daiyu.android.platform

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import app.daiyu.android.ChatApplication
import app.daiyu.android.domain.tools.ToolArguments
import org.json.JSONArray
import org.json.JSONObject

/** The OS grant is checked live; a connected service is a separate requirement. No global payload cache. */
internal class CrossAppNotifications(
    private val granted: () -> Boolean,
    private val store: NotificationHistoryStore,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val gate = Any()
    private var listener: NotificationSnapshotSource? = null
    private var generation = 0L
    internal interface NotificationSnapshotSource { fun active(): List<JSONObject> }

    fun connected(source: NotificationSnapshotSource) = synchronized(gate) {
        generation++
        listener = source
        if (!granted()) revoke()
        else store.resumeAfterSystemGrant(clock())
    }
    fun disconnected(source: NotificationSnapshotSource) = synchronized(gate) {
        if (listener === source) {
            listener = null; generation++
            if (!granted()) revoke()
        }
    }
    fun maintain() = synchronized(gate) {
        if (!granted()) revoke() else store.prune(clock())
    }
    private fun revoke() {
        listener = null; generation++
        store.clearForSystemRevocation()
    }
    fun status(): String = synchronized(gate) {
        if (!granted()) { revoke(); "未获系统通知读取授权；记录已停止并清除" }
        else if (listener == null) "系统已授权，但监听服务未连接；读取拒绝"
        else "系统已授权且服务已连接；仍需run工具选择与模型/历史双同意"
    }
    fun recording(): Boolean = synchronized(gate) {
        if (!granted()) { revoke(); false } else listener != null && store.enabled()
    }
    fun record(enabled: Boolean) = synchronized(gate) {
        if (enabled) check(granted() && listener != null) { "notification_access_unavailable" }
        store.setEnabled(enabled, clock())
        generation++
    }
    /** Called for future posted events only; never backfills active/pre-grant notifications. */
    fun posted(source: NotificationSnapshotSource, row: () -> JSONObject) = synchronized(gate) {
        if (!granted()) { revoke(); return@synchronized }
        if (listener !== source || !store.enabled()) return@synchronized
        store.append(row(), clock())
    }
    fun epoch(): Long = synchronized(gate) { generation }
    fun admission(tool: PlatformReadTool, policy: DeviceResultPolicy): String? = synchronized(gate) {
        if (!policy.allowsShared(tool.toolName)) return@synchronized "private_result_consent_required"
        if (!granted()) { revoke(); return@synchronized "notification_access_required" }
        if (listener == null) return@synchronized "notification_listener_not_connected"
        if (tool == PlatformReadTool.NOTIFICATION_HISTORY && !store.enabled()) return@synchronized "notification_history_not_recording"
        null
    }
    fun read(tool: PlatformReadTool, args: ToolArguments, policy: DeviceResultPolicy, cancelled: () -> Boolean): JSONObject {
        checkReadCancellation(cancelled)
        if (!policy.allowsShared(tool.toolName)) return failure("private_result_consent_required")
        val initial = synchronized(gate) {
            if (!granted()) { revoke(); return failure("notification_access_required") }
            val source = listener ?: return failure("notification_listener_not_connected")
            generation to source
        }
        val history = tool == PlatformReadTool.NOTIFICATION_HISTORY
        val now = clock()
        val limit = if (args.contains("limit")) args.integer("limit").toInt() else 20
        val packageFilter = if (args.contains("package")) args.text("package") else null
        val query = if (args.contains("query")) args.text("query") else ""
        val start = if (history && args.contains("start_ms")) args.integer("start_ms") else maxOf(0, now - NotificationHistoryStore.RETENTION_MS)
        val end = if (history && args.contains("end_ms")) args.integer("end_ms") else now
        if (end < start || end - start > NotificationHistoryStore.RETENTION_MS || end > now) return failure("invalid_time_window")
        val rows: List<JSONObject>
        val since: Long
        synchronized(gate) {
            if (!granted()) { revoke(); return failure("notification_access_required") }
            if (initial.first != generation || listener !== initial.second) return failure("notification_listener_changed")
            if (history && !store.enabled()) return failure("notification_history_not_recording")
            since = if (history) store.since() else 0
            rows = if (history) store.rows(now, start, end) else initial.second.active()
        }
        val items = JSONArray()
        var truncated = rows.size >= NotificationHistoryStore.MAX_ROWS
        var bytes = 0
        for (row in rows) {
            checkReadCancellation(cancelled)
            if (packageFilter != null && row.optString("package") != packageFilter) continue
            if (query.isNotEmpty() && !row.optString("title").contains(query, true) && !row.optString("text").contains(query, true)) continue
            val size = row.toString().toByteArray(Charsets.UTF_8).size
            if (items.length() >= limit || bytes + size > 48 * 1024) { truncated = true; break }
            bytes += size; items.put(row)
        }
        synchronized(gate) {
            if (!granted()) { revoke(); return failure("notification_access_required") }
            if (initial.first != generation || listener !== initial.second) return failure("notification_listener_changed")
        }
        checkReadCancellation(cancelled)
        return success(JSONObject().put("scope", if (history) "opt_in_posted_event_history" else "cross_app_current")
            .put("items", items).put("truncated", truncated).put("observed_at", now)
            .put("complete_inventory", false).put("scan_limit", NotificationHistoryStore.MAX_ROWS).apply {
                if (history) {
                    put("recording_since_ms", since); put("retention_ms", NotificationHistoryStore.RETENTION_MS)
                    put("requested_start_ms", start); put("requested_end_ms", end)
                    put("coverage", "best_effort_since_opt_in_only")
                    put("empty_means_no_notifications", false)
                }
            })
    }
    companion object {
        fun from(context: Context): CrossAppNotifications = (context.applicationContext as ChatApplication).notificationAccess
        fun systemGranted(context: Context): Boolean {
            val component = ComponentName(context, CrossAppNotificationListener::class.java)
            return if (android.os.Build.VERSION.SDK_INT >= 27) {
                (context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)
                    ?.isNotificationListenerAccessGranted(component) == true
            } else {
                // API26 has no NotificationManager check API; compare exact flattened components.
                android.provider.Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
                    ?.split(':')?.any { ComponentName.unflattenFromString(it) == component } == true
            }
        }
        fun row(sbn: StatusBarNotification): JSONObject = JSONObject().put("package", AndroidPlatformReads.bounded(sbn.packageName))
            .put("posted_at", sbn.postTime).put("title", AndroidPlatformReads.bounded(sbn.notification.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()))
            .put("text", AndroidPlatformReads.bounded(sbn.notification.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()))
            .put("fields_may_be_truncated", true)
    }
}

/** Exported only to the system binder protected by BIND_NOTIFICATION_LISTENER_SERVICE. */
class CrossAppNotificationListener : NotificationListenerService() {
    private val host get() = CrossAppNotifications.from(this)
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val maintenance = object : Runnable {
        override fun run() {
            safely { host.maintain() }
            handler.postDelayed(this, 60_000)
        }
    }
    private val source = object : CrossAppNotifications.NotificationSnapshotSource {
        override fun active(): List<JSONObject> = (activeNotifications ?: emptyArray()).take(500).map(CrossAppNotifications::row)
    }
    override fun onListenerConnected() {
        super.onListenerConnected(); safely { host.connected(source) }
        handler.removeCallbacks(maintenance); handler.postDelayed(maintenance, 60_000)
    }
    override fun onListenerDisconnected() {
        handler.removeCallbacks(maintenance); safely { host.disconnected(source) }; super.onListenerDisconnected()
    }
    override fun onDestroy() { handler.removeCallbacks(maintenance); safely { host.disconnected(source) }; super.onDestroy() }
    override fun onNotificationPosted(sbn: StatusBarNotification) { safely { host.posted(source) { CrossAppNotifications.row(sbn) } } }
    private inline fun safely(block: () -> Unit) { try { block() } catch (_: RuntimeException) { /* No content/exception diagnostics. */ } }
}
