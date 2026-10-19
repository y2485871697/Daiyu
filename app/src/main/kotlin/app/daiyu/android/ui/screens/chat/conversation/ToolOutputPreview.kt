package app.daiyu.android.ui.screens.chat.conversation

internal data class ToolOutputPreview(val text: String, val truncated: Boolean)

internal fun toolOutputPreview(text: String, tail: Boolean = false): ToolOutputPreview {
    val lines = text.split('\n')
    val truncated = lines.size > 3 || text.length > 240
    val selected = (if (tail) lines.takeLast(3) else lines.take(3)).joinToString("\n")
    val body = if (tail) selected.takeLast(240) else selected.take(240)
    return ToolOutputPreview(if (!truncated) body else if (tail) "…$body" else "$body…", truncated)
}
