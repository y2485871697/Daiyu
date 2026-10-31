package app.daiyu.android.ui.screens.chat.conversation

internal fun childTaskTitle(prompt: String?, index: Int): String {
    val line = prompt?.let { ToolRichDetails.scrub(it) }?.lineSequence()
        ?.firstOrNull { it.isNotBlank() }?.trim()?.replace(Regex("\\s+"), " ")
        ?: return "任务 ${index + 1}"
    return if (line.length > 80) line.take(80) + "…" else line
}

internal fun childTaskBatchCompleted(rows: List<SubAgentStatusRow>): Boolean =
    rows.isNotEmpty() && rows.all { it.completed }

internal fun childTaskProgress(rows: List<SubAgentStatusRow>): String =
    rows.groupingBy { it.status }.eachCount().entries.joinToString(" · ") { (status, count) -> "$status $count 项" }
