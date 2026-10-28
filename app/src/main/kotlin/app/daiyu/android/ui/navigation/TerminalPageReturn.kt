package app.daiyu.android.ui.navigation

internal fun linuxSettingsBackTarget(
    source: String, terminalReturn: String, previousReturn: String, filesOnly: Boolean,
): String = when (source) {
    "terminal" -> if (terminalReturn == "linux-settings" || terminalReturn == "chat-workspace") previousReturn else "terminal"
    "linux-settings", "chat-workspace" -> previousReturn
    else -> if (filesOnly) "chat" else "settings"
}

internal fun terminalBackTarget(source: String, previousReturn: String, linuxReturn: String): String = when (source) {
    "linux-settings", "chat-workspace" -> if (linuxReturn == "terminal") previousReturn else source
    "settings" -> source
    else -> "chat"
}
