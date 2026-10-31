package app.daiyu.android.gui

import app.daiyu.android.domain.tools.WriteEffect
import java.util.Collections
import java.util.UUID

internal const val GUI_OBSERVATION_TTL_MILLIS = 120_000L

/** Independent host implementation; no imported external host implementation or persistent grants. */
internal data class GuiOwner(val sessionId: String, val runId: String) {
    init { require(sessionId.isNotBlank() && runId.isNotBlank()) }
}
internal data class GuiBounds(val left: Int, val top: Int, val right: Int, val bottom: Int)
internal data class GuiNode(
    val id: Int, val parent: Int?, val bounds: GuiBounds, val text: String,
    val description: String, val className: String, val viewId: String,
    val clickable: Boolean, val longClickable: Boolean, val scrollable: Boolean,
    val editable: Boolean, val enabled: Boolean, val password: Boolean,
)
internal class GuiObservation(
    val observationId: String, val packageName: String, val windowId: Int,
    val generation: Long, val capturedAt: Long, nodes: List<GuiNode>, val truncated: Boolean,
) {
    val nodes: List<GuiNode> = Collections.unmodifiableList(nodes.toList())
}
internal enum class GuiOperation { TAP, LONG_PRESS, SCROLL_FORWARD, SCROLL_BACKWARD, INPUT, BACK, HOME, RECENTS, NOTIFICATIONS }
internal data class GuiAction(val observationId: String, val operation: GuiOperation, val nodeId: Int? = null, val text: String? = null) {
    init {
        require(observationId.isNotBlank())
        val nodeAction = operation in setOf(GuiOperation.TAP, GuiOperation.LONG_PRESS,
            GuiOperation.SCROLL_FORWARD, GuiOperation.SCROLL_BACKWARD, GuiOperation.INPUT)
        require(nodeAction == (nodeId != null))
        require(nodeId == null || nodeId >= 0)
        require((operation == GuiOperation.INPUT) == (text != null))
        require(text == null || text.length <= 4096)
    }
    fun effect(owner: GuiOwner): WriteEffect = WriteEffect("gui", operation.name.lowercase(),
        "${owner.sessionId.length}:${owner.sessionId}:${owner.runId.length}:${owner.runId}:$observationId:${nodeId ?: "global"}",
        text?.let { "${it.length}:$it" } ?: "invoke")
}
internal sealed interface GuiResult<out T> {
    data class Value<T>(val value: T) : GuiResult<T>
    /** Guaranteed no requested action was attempted. */
    data class Rejected(val code: String) : GuiResult<Nothing>
    /** Action may have been delivered. Never automatically retry, including via another backend. */
    data class Unknown(val code: String) : GuiResult<Nothing>
}
internal data class GuiReadApproval(val observations: Int, val screenshots: Int) {
    init { require(observations in 0..8 && screenshots in 0..1) }
}

/** Pure admission state. All methods are serialized by the Android host's main Looper.
 * There is one screen lease for the entire process, not one per tool or service instance.
 * Closing/rebinding does not resurrect a lease or any of its approvals.
 */
internal class GuiLeaseGate(private val now: () -> Long) {
    internal class Lease internal constructor(val owner: GuiOwner) {
        val nonce: String = UUID.randomUUID().toString()
        @Volatile internal var valid = true
        internal var reads = 0
        internal var screenshots = 0
        internal var observation: GuiObservation? = null
        internal val actions = linkedSetOf<GuiAction>()
    }
    private var current: Lease? = null
    var connected: Boolean = false
        private set
    fun connect() { disconnect(); connected = true }
    fun disconnect() { current?.valid = false; current = null; connected = false }
    fun acquire(owner: GuiOwner): GuiResult<Lease> {
        if (!connected) return GuiResult.Rejected("SERVICE_DISCONNECTED_NOT_EXECUTED")
        if (current != null) return GuiResult.Rejected("FOREGROUND_BUSY")
        return GuiResult.Value(Lease(owner).also { current = it })
    }
    fun rejection(lease: Lease): GuiResult.Rejected? = when {
        !connected -> GuiResult.Rejected("SERVICE_DISCONNECTED_NOT_EXECUTED")
        current !== lease -> GuiResult.Rejected(if (current != null) "FOREGROUND_BUSY" else "GUI_AUTHORIZATION_REVOKED")
        !lease.valid -> GuiResult.Rejected("GUI_AUTHORIZATION_REVOKED")
        else -> null
    }
    fun approveReads(lease: Lease, approval: GuiReadApproval): GuiResult<Unit> {
        rejection(lease)?.let { return it }
        lease.reads = approval.observations
        lease.screenshots = approval.screenshots
        return GuiResult.Value(Unit)
    }
    fun consumeRead(lease: Lease, screenshot: Boolean): GuiResult<Unit> {
        rejection(lease)?.let { return it }
        if (screenshot) {
            if (lease.screenshots <= 0) return GuiResult.Rejected("SCREENSHOT_NOT_EXPLICITLY_AUTHORIZED")
            lease.screenshots--
        } else {
            if (lease.reads <= 0) return GuiResult.Rejected("OBSERVATION_NOT_AUTHORIZED")
            lease.reads--
        }
        return GuiResult.Value(Unit)
    }
    /** A replacement attempt retires the old snapshot even when the new read fails. */
    fun retireObservation(lease: Lease) {
        if (rejection(lease) != null) return
        lease.observation = null
        lease.actions.clear()
    }
    fun publish(lease: Lease, observation: GuiObservation): GuiResult<GuiObservation> {
        rejection(lease)?.let { return it }
        lease.actions.clear()
        lease.observation = observation
        return GuiResult.Value(observation)
    }
    fun validateObservation(lease: Lease, id: String, generation: Long): GuiResult.Rejected? {
        rejection(lease)?.let { return it }
        val observation = lease.observation ?: return GuiResult.Rejected("OBSERVATION_REQUIRED")
        return when {
            observation.observationId != id -> GuiResult.Rejected("OBSERVATION_ID_MISMATCH")
            observation.generation != generation -> GuiResult.Rejected("OBSERVATION_STALE")
            now() - observation.capturedAt !in 0..GUI_OBSERVATION_TTL_MILLIS -> GuiResult.Rejected("OBSERVATION_EXPIRED")
            else -> null
        }
    }
    fun approveAction(lease: Lease, action: GuiAction, generation: Long): GuiResult<Unit> {
        validateObservation(lease, action.observationId, generation)?.let { return it }
        if (action.nodeId != null && lease.observation!!.nodes.none { it.id == action.nodeId })
            return GuiResult.Rejected("NODE_NOT_IN_OBSERVATION")
        lease.actions.add(action)
        return GuiResult.Value(Unit)
    }
    fun consumeAction(lease: Lease, action: GuiAction, generation: Long): GuiResult<Unit> {
        validateObservation(lease, action.observationId, generation)?.let { return it }
        if (!lease.actions.remove(action)) return GuiResult.Rejected("EXACT_ACTION_NOT_AUTHORIZED")
        // One action invalidates the entire snapshot, even if Android subsequently rejects it.
        lease.observation = null
        lease.actions.clear()
        return GuiResult.Value(Unit)
    }
    fun approvedActions(lease: Lease): Set<GuiAction> = if (rejection(lease) == null)
        Collections.unmodifiableSet(lease.actions.toSet()) else emptySet()
    /** Cleanup identity only; never an admission check or a way to resurrect a revoked lease. */
    fun ownsSlot(lease: Lease): Boolean = current === lease
    fun close(lease: Lease) { lease.valid = false; if (current === lease) current = null }
}
