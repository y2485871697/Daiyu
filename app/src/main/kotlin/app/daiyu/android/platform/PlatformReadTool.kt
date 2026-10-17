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
    FILE_TEXT("read_text_file", "platform.files.text.read", "Read at most 8192 bytes of UTF-8 text from an explicitly granted content URI. No permission acquisition.", ToolSchema(listOf(uriField()))),
    CALENDAR("calendar_events", "platform.calendar.read", "Read at most 50 calendar instances in an explicit interval no longer than 31 days.", ToolSchema(listOf(
        ArgumentField("start_ms", ArgumentType.Integer(0, 4102444800000L)),
        ArgumentField("end_ms", ArgumentType.Integer(1, 4102444800000L)), limitField()))),
    CONTACTS("search_contacts", "platform.contacts.read", "Search contact display names only (no numbers, photos or notes).", ToolSchema(listOf(
        ArgumentField("query", ArgumentType.Text(128, minimumLength = 1)), limitField()))),
    NOTIFICATIONS("app_notifications", "platform.notifications.read", "Read bounded active notifications posted by this app only, not other apps' notifications.", ToolSchema(listOf(limitField())));

    fun definition() = ToolDefinition(toolName, 1, description, capability, ToolEffect.READ_ONLY, schema)
}

private fun limitField() = ArgumentField("limit", ArgumentType.Integer(1, 50), false)
private fun uriField() = ArgumentField("uri", ArgumentType.Text(2048, minimumLength = 1))

/** Injectable API boundary for host-only ownership tests. Production uses AndroidPlatformReads. */
internal fun interface PlatformReadBackend {
    fun read(tool: PlatformReadTool, arguments: ToolArguments, cancelled: () -> Boolean): String
}
