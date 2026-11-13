package app.daiyu.android.ui.screens.chat.conversation

import app.daiyu.android.domain.delegation.ChildRunOwner
import org.json.JSONObject

private fun childTaskReceiptPayload(toolName: String, result: String?): JSONObject? {
    if (toolName !in setOf("get_child_task", "inspect_child_tasks", "control_child_task") ||
        result == null || result.length > 131_072) return null
    return runCatching { JSONObject(result) }.getOrNull()
}

private fun childTaskReceiptTargets(conversationId: String, toolName: String, arguments: String,
    output: JSONObject): List<Pair<JSONObject, ChildRunOwner>> {
    val args = if (arguments.length <= 131_072) runCatching { JSONObject(arguments) }.getOrNull() else null
    val rows = output.optJSONArray("tasks") ?: output.optJSONArray("results")
    val targets = if (rows == null) listOf(output) else
        (0 until minOf(rows.length(), 32)).mapNotNull(rows::optJSONObject)
    return targets.mapNotNull { row ->
        if ((row.opt("agent_name") as? String)?.isNotBlank() == true) return@mapNotNull null
        fun value(key: String): Any? = if (row.has(key)) row.opt(key)
            else if (rows == null && toolName != "inspect_child_tasks") args?.opt(key) else null
        fun text(key: String) = (value(key) as? String)?.takeIf { it.isNotBlank() }
        val recordedConversation = text("conversation_id")
        if (recordedConversation != null && recordedConversation != conversationId) return@mapNotNull null
        val generation = value("generation")?.toString()?.toLongOrNull() ?: return@mapNotNull null
        val owner = runCatching { ChildRunOwner(conversationId,
            text("parent_run_id") ?: return@mapNotNull null,
            text("task_group_id") ?: return@mapNotNull null, generation,
            text("child_run_id") ?: return@mapNotNull null,
            text("assistant_message_id") ?: return@mapNotNull null) }.getOrNull() ?: return@mapNotNull null
        row to owner
    }
}

internal fun childTaskReceiptOwners(conversationId: String, toolName: String, arguments: String,
    result: String?): List<ChildRunOwner> {
    val output = childTaskReceiptPayload(toolName, result) ?: return emptyList()
    return childTaskReceiptTargets(conversationId, toolName, arguments, output).map { it.second }.distinct()
}

/** Enrich only the ephemeral projection, outside the pure tool-presentation cache. */
internal fun childTaskNamedPresentation(presentation: ToolCardPresentations.Presentation, conversationId: String,
    toolName: String, arguments: String, result: String?, names: Map<ChildRunOwner, String>): ToolCardPresentations.Presentation {
    if (names.isEmpty() || presentation.details.rich.bodyRestricted) return presentation
    val output = childTaskReceiptPayload(toolName, result) ?: return presentation
    var changed = false
    childTaskReceiptTargets(conversationId, toolName, arguments, output).forEach { (row, owner) ->
        names[owner]?.takeIf { it.isNotBlank() }?.let { name -> row.put("agent_name", name); changed = true }
    }
    return if (changed) ToolCardPresentations.present(toolName, arguments, output.toString()) else presentation
}
