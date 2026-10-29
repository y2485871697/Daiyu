package app.daiyu.android.ui.screens.chat.conversation

internal data class ToolOutputPreview(val text: String, val truncated: Boolean)

internal fun toolOutputPreview(text: String, tail: Boolean = false): ToolOutputPreview {
    val window = if (tail) text.takeLast(241) else text.take(241)
    val lines = window.split('\n')
    val truncated = text.length > 240 || lines.size > 3
    val selected = (if (tail) lines.takeLast(3) else lines.take(3)).joinToString("\n")
    val body = if (tail) selected.takeLast(240) else selected.take(240)
    return ToolOutputPreview(if (!truncated) body else if (tail) "…$body" else "$body…", truncated)
}
