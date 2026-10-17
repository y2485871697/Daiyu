package app.daiyu.android.platform.actions

import app.daiyu.android.domain.tools.*

/** Dedicated, finite platform operations. No command strings, intents, packages or permission grants. */
internal enum class PlatformActionKind(val toolName: String, val title: String, val schema: ToolSchema) {
    ALARM("platform_set_alarm", "闹钟", ToolSchema(listOf(
        ArgumentField("hour", ArgumentType.Integer(0, 23)),
        ArgumentField("minute", ArgumentType.Integer(0, 59)),
        ArgumentField("label", ArgumentType.Text(120))))),
    TIMER("platform_set_timer", "计时器", ToolSchema(listOf(
        ArgumentField("seconds", ArgumentType.Integer(1, 86400)),
        ArgumentField("label", ArgumentType.Text(120))))),
    VOLUME("platform_set_volume", "音量", ToolSchema(listOf(
        ArgumentField("stream", ArgumentType.Text(choices = setOf("music", "alarm"))),
        ArgumentField("index", ArgumentType.Integer(0, 100))))),
    MEDIA("platform_media_key", "媒体", ToolSchema(listOf(
        ArgumentField("key", ArgumentType.Text(choices = setOf("play", "pause", "stop", "next", "previous")))))),
    WIFI("platform_set_wifi", "联网 / Wi-Fi", ToolSchema(listOf(
        ArgumentField("enabled", ArgumentType.BooleanValue))));

    val capability: String get() = "platform.action.${name.lowercase()}"
}

internal class PlatformAction private constructor(val kind: PlatformActionKind, val arguments: ToolArguments) {
    val json: String = arguments.canonicalJson()
    // Both invocation aliases and new call IDs share this complete semantic identity.
    val effect = WriteEffect("android.platform", kind.toolName, when (kind) {
        PlatformActionKind.VOLUME -> arguments.text("stream")
        PlatformActionKind.MEDIA -> "active_media_session"
        PlatformActionKind.WIFI -> "wifi_radio"
        else -> "default_clock"
    }, json)
    val preview: String get() = "${kind.title} · 普通应用身份\n${kind.toolName}\n$json"
    companion object {
        fun parse(kind: PlatformActionKind, json: String) = PlatformAction(kind, kind.schema.parse(json))
    }
}

internal interface PlatformActionBackend {
    /** Read-only availability, never a permission request. Actual execution rechecks dependencies. */
    fun unavailable(kind: PlatformActionKind): String?
    fun execute(action: PlatformAction, cancelled: () -> Boolean): ExecutionReceipt
}
