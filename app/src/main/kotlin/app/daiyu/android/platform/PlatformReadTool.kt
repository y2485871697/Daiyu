package app.daiyu.android.platform

import app.daiyu.android.domain.tools.*

/** Catalog leaves, not additions to the legacy ReadOnlyTool public contract. No grant is implied. */
enum class PlatformReadTool(val toolName: String, val capability: String, val description: String, val schema: ToolSchema) {
    NETWORK("network_status", "platform.network.read", "Read current network transports and validation, without addresses or SSID.", ToolSchema()),
    TIME("device_time", "platform.time.read", "Read current device time, timezone and UTC offset without changing system time.", ToolSchema()),
    AUDIO("device_audio", "platform.audio.read", "Read current audio stream volumes, ringer mode and music-active state; never changes audio settings.", ToolSchema()),
    ENVIRONMENT("device_environment", "platform.environment.read", "Read non-identifying Android build, locale, timezone and power environment.", ToolSchema()),
    PERMISSIONS("platform_permissions", "platform.permissions.read", "Inspect current app permission state; never requests permissions.", ToolSchema()),
    APPS("search_apps", "platform.apps.read", "Search only packages visible to this app; not a complete installed-app inventory.", ToolSchema(listOf(
        ArgumentField("query", ArgumentType.Text(128), false), limitField()))),
    MEDIA("search_media", "platform.media.read", "List bounded metadata for permitted shared media; never reads media bytes or file paths.", ToolSchema(listOf(
        ArgumentField("kind", ArgumentType.Text(8, setOf("image", "video", "audio"))), limitField()))),
    FILES("list_files", "platform.files.list", "List direct children of an exactly persisted read-granted SAF tree, never arbitrary paths or recursion.", ToolSchema(listOf(
        ArgumentField("tree_uri", ArgumentType.Text(2048, minimumLength = 1)),
        ArgumentField("query", ArgumentType.Text(128), false), limitField()))),
    FILE_METADATA("file_metadata", "platform.files.metadata.read", "Read metadata only for an existing explicitly granted content URI; no filesystem paths.", ToolSchema(listOf(uriField()))),
    FILE_TEXT("read_text_file", "platform.files.text.read", "Read/search a UTF-8 document on demand from an explicitly read-granted content URI. Returns at most 8192 text bytes and scans at most 1 MiB plus 4 UTF-8 lookahead bytes per call. Seekable files jump to offset; non-seekable prefix discard is limited to 1 MiB. Continue with next_offset_bytes. If remaining_lines_to_skip is positive, pass it as skip_lines along with next_offset_bytes to continue locating the requested line; first_line_in_scan is relative to this scan. Search windows overlap to retain cross-boundary matches. Never loads the whole file or acquires permission.", ToolSchema(listOf(uriField(),
        ArgumentField("offset_bytes", ArgumentType.Integer(0, Long.MAX_VALUE), false),
        ArgumentField("start_line", ArgumentType.Integer(1, 1000000000), false),
        ArgumentField("line_count", ArgumentType.Integer(1, 200), false),
        ArgumentField("skip_lines", ArgumentType.Integer(0, 1000000000), false),
        ArgumentField("query", ArgumentType.Text(128, minimumLength = 1), false)))),
    CALENDAR("calendar_events", "platform.calendar.read", "Read at most 50 calendar instances in an explicit interval no longer than 31 days.", ToolSchema(listOf(
        ArgumentField("start_ms", ArgumentType.Integer(0, 4102444800000L)),
        ArgumentField("end_ms", ArgumentType.Integer(1, 4102444800000L)), limitField()))),
    CONTACTS("search_contacts", "platform.contacts.read", "Search contact display names only (no numbers, photos or notes).", ToolSchema(listOf(
        ArgumentField("query", ArgumentType.Text(128, minimumLength = 1)), limitField()))),
    NOTIFICATIONS("app_notifications", "platform.notifications.read", "Read bounded active notifications posted by this app only, not other apps' notifications.", ToolSchema(listOf(limitField()))),
    LAST_KNOWN_LOCATION("last_known_location", "platform.location.last_known.read", "Read the newest accessible existing LocationManager cache from a fixed set of at most three providers. Returns sample time, age and provider; never requests updates, wakes GPS or obtains a fresh fix. Requires foreground coarse OR fine permission and separate model AND history consent.", ToolSchema()),
    CROSS_APP_NOTIFICATIONS("current_notifications", "platform.notifications.current.read", "Read bounded currently active cross-app notifications with live listener access, run grant and both private destination consents; not notification history.", ToolSchema(notificationFields())),
    NOTIFICATION_HISTORY("notification_history", "platform.notifications.history.read", "Read only explicitly opt-in captured posted events, retained at most 7 days; optional start_ms/end_ms filter capture time. Missing history never means no recent notifications.", ToolSchema(notificationFields() + listOf(
        ArgumentField("start_ms", ArgumentType.Integer(0, 4102444800000L), false),
        ArgumentField("end_ms", ArgumentType.Integer(0, 4102444800000L), false))));

    fun definition() = ToolDefinition(toolName, 1, description, capability, ToolEffect.READ_ONLY, schema)
}

private fun limitField() = ArgumentField("limit", ArgumentType.Integer(1, 50), false)
private fun notificationFields() = listOf(limitField(),
    ArgumentField("package", ArgumentType.Text(256, minimumLength = 1), false),
    ArgumentField("query", ArgumentType.Text(128), false))
private fun uriField() = ArgumentField("uri", ArgumentType.Text(2048, minimumLength = 1))

/** Injectable API boundary for host-only ownership tests. Production uses AndroidPlatformReads. */
internal fun interface PlatformReadBackend {
    fun read(tool: PlatformReadTool, arguments: ToolArguments, cancelled: () -> Boolean): String
    /** Live access fence for replay of sensitive cached receipts. No content reads. */
    fun admission(tool: PlatformReadTool): String? = null
}
