package app.daiyu.android.gui

import java.util.concurrent.atomic.AtomicBoolean

/** Pure, monotonic fence: central receipt Unknown must reach this BEFORE another tool admission.
 * It deliberately performs no Android I/O, cleanup, retry, or replacement-call-ID allocation. */
internal class GuiIoFence {
    private val closed = AtomicBoolean(false)
    private val unknown = AtomicBoolean(false)
    fun close() { closed.set(true) }
    fun unknown() { unknown.set(true) }
    fun rejection(): GuiResult.Rejected? = when {
        closed.get() -> GuiResult.Rejected("GUI_AUTHORIZATION_REVOKED")
        unknown.get() -> GuiResult.Rejected("GUI_PREVIOUS_OPERATION_UNCONFIRMED")
        else -> null
    }
}

/** Same bounded evidence delivered to the parent/new run, not a subsequent model observe. */
internal data class GuiConfirmedDelivery(
    val session: GuiRunSession,
    val observation: GuiObservation,
    val action: GuiAction,
    val writes: Set<app.daiyu.android.domain.tools.WriteEffect>,
) {
    fun modelContext(): String = "This run is authorized for exactly this planned GUI action. Use this observation_id; " +
        "do not observe again (that invalidates the confirmation). Never retry an Unknown.\n" +
        GuiScreenToolFactory.observationJson(observation) + "\nscreen_action arguments: " + actionArgumentsJson()
    fun actionArgumentsJson(): String = org.json.JSONObject().put("operation", action.operation.name.lowercase())
        .put("observation_id", action.observationId).apply {
            action.nodeId?.let { put("node_id", it) }
            action.text?.let { put("text", it) }
        }.toString()
}

/** Monotonic callback ticket. Re-render/cancel/service loss makes every older callback inert. */
internal class GuiConfirmationTicket {
    private var epoch = 0L
    private var closed = false
    fun next(): Long { check(!closed); return ++epoch }
    fun current(value: Long): Boolean = !closed && value == epoch
    fun close() { closed = true; epoch++ }
}

internal object GuiExactTarget {
    fun candidates(observation: GuiObservation, operation: GuiOperation): List<GuiNode> =
        observation.nodes.filter { node -> !node.password && node.enabled && when (operation) {
            GuiOperation.TAP -> node.clickable
            GuiOperation.LONG_PRESS -> node.longClickable
            GuiOperation.INPUT -> node.editable
            GuiOperation.SCROLL_FORWARD, GuiOperation.SCROLL_BACKWARD -> node.scrollable
            else -> false
        } }
    fun resolve(observation: GuiObservation, operation: GuiOperation, text: String?): GuiResult<GuiAction> {
        if ((operation == GuiOperation.INPUT) != (text != null) || (text?.length ?: 0) > 4096)
            return GuiResult.Rejected("GUI_ACTION_PARAMETERS_REQUIRED")
        if (observation.truncated) return GuiResult.Rejected("TARGET_TREE_TRUNCATED_RECONFIRM_NOT_EXECUTED")
        val global = operation in setOf(GuiOperation.BACK, GuiOperation.HOME, GuiOperation.RECENTS, GuiOperation.NOTIFICATIONS)
        val target: GuiNode? = if (global) null else {
            candidates(observation, operation).singleOrNull()
                ?: return GuiResult.Rejected("GUI_TARGET_CLARIFICATION_REQUIRED")
        }
        return GuiResult.Value(GuiAction(observation.observationId, operation, target?.id, text))
    }
    fun same(a: GuiNode, b: GuiNode): Boolean = a.copy(id = 0, parent = null) == b.copy(id = 0, parent = null)
    fun unique(reference: GuiObservation, nodeId: Int?, current: GuiObservation): GuiResult<GuiNode?> {
        if (reference.packageName != current.packageName || reference.windowId != current.windowId)
            return GuiResult.Rejected("TARGET_WINDOW_CHANGED_RECONFIRM_NOT_EXECUTED")
        if (reference.truncated || current.truncated) return GuiResult.Rejected("TARGET_TREE_TRUNCATED_RECONFIRM_NOT_EXECUTED")
        if (nodeId == null) return GuiResult.Value(null)
        val target = reference.nodes.singleOrNull { it.id == nodeId }
            ?: return GuiResult.Rejected("NODE_NOT_IN_OBSERVATION")
        if (target.password || !target.enabled) return GuiResult.Rejected("TARGET_UNSAFE_NOT_EXECUTED")
        val matches = current.nodes.filter { same(target, it) }
        return if (matches.size == 1) GuiResult.Value(matches.single())
        else GuiResult.Rejected("TARGET_NOT_UNIQUE_RECONFIRM_NOT_EXECUTED")
    }
}
