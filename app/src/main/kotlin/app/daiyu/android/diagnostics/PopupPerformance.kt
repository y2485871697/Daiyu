package app.daiyu.android.diagnostics

/** Short, owner-bound sampling for the two chat selectors; no animation or business ownership. */
internal class PopupPerformanceCapture(
    private val clock: () -> Long = System::nanoTime,
    private val epoch: () -> Long,
    private val incident: (BackPerformanceRecorder.Incident, Long, BackPerformanceRecorder.Scroll) -> Unit,
    private val boundary: (Boundary) -> Unit,
) {
    enum class Type(val kind: BackPerformanceRecorder.Kind) {
        ASSISTANT_PICKER(BackPerformanceRecorder.Kind.ASSISTANT_PICKER),
        MODEL_PICKER(BackPerformanceRecorder.Kind.MODEL_PICKER),
    }
    data class Boundary(val epoch: Long, val type: Type, val opened: Long, val at: Long,
        val closed: Boolean, val scrollAtOpen: BackPerformanceRecorder.Scroll)
    class Lease internal constructor(val owner: BackPerformanceRecorder.Owner, val window: Any,
        val type: Type, val token: BackPerformanceRecorder.Token, val opened: Long,
        val scrollAtOpen: BackPerformanceRecorder.Scroll, internal val onEnded: () -> Unit) {
        internal var closed = false
    }
    @Volatile private var active: Lease? = null
    val recorder = BackPerformanceRecorder(clock = clock, epoch = epoch, emit = { value ->
        active?.let { incident(value, it.opened, it.scrollAtOpen) }
    })

    @Synchronized fun start(host: Any, window: Any, type: Type, scroll: BackPerformanceRecorder.Scroll,
        schedule: (Long, () -> Unit) -> Unit, onEnded: () -> Unit): Lease {
        active?.let { end(it, BackPerformanceRecorder.End.SUPERSEDED) }
        val owner = BackPerformanceRecorder.Owner(host, schedule)
        val opened = clock()
        val lease = Lease(owner, window, type, recorder.start(owner, type.kind), opened, scroll, onEnded)
        active = lease
        boundary(Boundary(lease.token.epoch, type, opened, opened, false, scroll))
        schedule(4000) { end(lease, BackPerformanceRecorder.End.HARD_LIMIT) }
        return lease
    }
    @Synchronized fun close(lease: Lease) {
        if (lease.closed) return
        lease.closed = true
        end(lease, BackPerformanceRecorder.End.OWNER_REMOVED)
        if (lease.token.epoch == epoch()) boundary(Boundary(lease.token.epoch, lease.type,
            lease.opened, clock(), true, lease.scrollAtOpen))
    }
    @Synchronized private fun end(lease: Lease, reason: BackPerformanceRecorder.End) {
        if (active !== lease) return
        val end = if (clock() - lease.opened >= BackPerformanceRecorder.HARD_NS)
            BackPerformanceRecorder.End.HARD_LIMIT else reason
        recorder.end(lease.owner, end)
        active = null
        lease.onEnded()
    }
    @Synchronized fun endHost(host: Any, reason: BackPerformanceRecorder.End) {
        active?.takeIf { it.owner.host === host }?.let { end(it, reason) }
    }
    fun observing(host: Any, window: Any? = null): Boolean {
        val lease = active ?: return false
        return lease.owner.host === host && (window == null || lease.window === window) &&
            lease.token.epoch == epoch() && recorder.compositionToken() == lease.token
    }
    @Synchronized fun frame(host: Any, window: Any?, vsync: Long, duration: Long, budget: Long,
        hz: Float, stages: LongArray, scroll: BackPerformanceRecorder.Scroll) {
        val lease = active ?: return
        if (!observing(host, window)) return
        recorder.frame(vsync, duration, budget, hz, stages, host, sourceToken = lease.token,
            source = if (window == null) BackPerformanceRecorder.Source.MAIN else BackPerformanceRecorder.Source.POPUP,
            scroll = scroll)
    }
}

internal object PopupPerformance {
    val capture = PopupPerformanceCapture(epoch = RuntimeDiagnostics::backEpoch,
        incident = RuntimeDiagnostics::popupIncident, boundary = RuntimeDiagnostics::popupBoundary)
}

// Compiler-qualified subtree roots; descendants keep their original self-time accounting.
internal fun popupCompositionRoot(name: String): Boolean =
    name.startsWith("app.daiyu.android.ui.screens.assistants.AssistantPickerDialog (") ||
    name.startsWith("app.daiyu.android.ui.screens.chat.conversation.ChatModelPicker (") ||
    name.startsWith("app.daiyu.android.ui.components.PreviewDialog (") ||
    name.startsWith("app.daiyu.android.ui.screens.chat.conversation.ChatMessageList (") ||
    name.startsWith("app.daiyu.android.ui.screens.chat.conversation.DaiyuChatScreen (")
