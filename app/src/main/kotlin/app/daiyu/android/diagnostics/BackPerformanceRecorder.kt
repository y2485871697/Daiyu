package app.daiyu.android.diagnostics

/** Bounded observation, not an animation/business completion state machine. All times are monotonic.
 * Phase durations are INCLUSIVE (nested phases overlap and MUST NOT be added together).
 */
internal class BackPerformanceRecorder(
    private val clock: () -> Long = System::nanoTime,
    private val epoch: () -> Long,
    private val emit: (Incident) -> Unit,
) {
    enum class Kind { CUSTOM_SWIPE, PREDICTIVE, BACK_PRESS }
    enum class End { TAIL, HARD_LIMIT, RESET, SUPERSEDED, PAUSED, DESTROYED, OWNER_REMOVED }
    enum class Phase(val trace: String) {
        CUSTOM_UP("daiyu.back.custom-up-dispatch"), CALLBACK("daiyu.back.callback-commit"),
        PREPARE("daiyu.back.prepare"), BUSINESS("daiyu.back.business-handler"), RESET("daiyu.back.reset"),
        ROUTE("daiyu.back.route-change"), LIFECYCLE_SET("daiyu.retained.lifecycle-set"),
        LIFECYCLE_CLOSE("daiyu.retained.lifecycle-close"), MODEL_COMMIT("daiyu.chat.model-refresh-commit"),
        MODEL_PUBLISH("daiyu.chat.model-refresh-publish"), POSITION("daiyu.chat.position-seed"),
        RESTORE("daiyu.chat.restore-lifecycle"), CLEARANCE("daiyu.chat.restore-clearance"),
        COMPOSE_ENTRY("daiyu.chat.retained-compose-entry"),
        CHAT_REFRESH("daiyu.chat.refresh"), CHAT_PUBLISH("daiyu.chat.publish"),
        MESSAGE_ACTION_REVISION("daiyu.chat.action-revision")
    }
    class Owner(val host: Any, val schedule: (Long, () -> Unit) -> Unit)
    data class Token(val sequence: Long, val epoch: Long)
    data class Sample(val token: Token, val phase: Phase, val started: Long, val slot: Int)
    data class PhaseStats(val phase: Phase, val count: Int, val total: Long, val max: Long,
        val inFlight: Int, val inFlightMaxObserved: Long)
    private class Aggregate {
        var count = 0; var total = 0L; var max = 0L
        val pending = arrayOfNulls<Sample>(8)
    }
    data class Frame(val duration: Long, val budget: Long, val refreshHz: Float, val stages: List<Long>,
        val overlapsStart: Boolean)
    data class Incident(val epoch: Long, val kind: Kind, val reason: End, val commitAttempted: Boolean,
        val routeChanged: Boolean, val elapsed: Long, val frames: Int, val slow: Int, val max: Long,
        val minBudget: Long, val maxBudget: Long, val minHz: Float, val maxHz: Float,
        val phases: List<PhaseStats>, val worst: List<Frame>, val overlapFrames: Int, val overlapSlow: Int)
    private class Window(val owner: Any, val token: Token, val kind: Kind, val start: Long) {
        var deadline = start + HARD_NS
        var commit = false
        var route = false
        var frames = 0; var slow = 0; var max = 0L
        var overlapFrames = 0; var overlapSlow = 0
        var minBudget = Long.MAX_VALUE; var maxBudget = 0L
        var minHz = Float.MAX_VALUE; var maxHz = 0f
        val phases = Array(Phase.entries.size) { Aggregate() }
        val worst = arrayListOf<Frame>()
    }
    private var sequence = 0L
    private var active: Window? = null
    @Synchronized fun start(owner: Any, kind: Kind): Token {
        finish(End.SUPERSEDED)
        return Token(++sequence, epoch()).also { active = Window(owner, it, kind, clock()) }
    }
    private fun valid(): Window? {
        val window = active ?: return null
        if (window.token.epoch != epoch()) { active = null; return null }
        return window
    }
    @Synchronized fun token(): Token? = valid()?.token
    @Synchronized fun observing(host: Any): Boolean = (valid()?.owner as? Owner)?.host === host
    @Synchronized fun endHost(host: Any, reason: End) {
        if ((valid()?.owner as? Owner)?.host === host) finish(reason)
    }
    @Synchronized fun commit(owner: Any) {
        val window = valid()?.takeIf { it.owner === owner } ?: return
        window.commit = true
        window.deadline = minOf(window.deadline, clock() + TAIL_NS)
    }
    @Synchronized fun route(owner: Any) { valid()?.takeIf { it.owner === owner }?.route = true }
    @Synchronized fun reset(owner: Any) {
        val window = valid()?.takeIf { it.owner === owner } ?: return
        // Successful callback finally also resets. Reset is not synonymous with cancellation.
        if (!window.commit) { window.deadline = minOf(window.deadline, clock() + TAIL_NS) }
    }
    @Synchronized fun end(owner: Any, reason: End) {
        if (valid()?.owner === owner) finish(reason)
    }
    @Synchronized fun end(reason: End) { finish(reason) }
    @Synchronized fun checkpoint() {
        val window = valid() ?: return
        if (clock() >= window.deadline) finish(if (window.deadline == window.start + HARD_NS) End.HARD_LIMIT
            else if (window.commit) End.TAIL else End.RESET)
    }
    @Synchronized fun begin(name: String, source: Token? = token()): Sample? {
        checkpoint()
        val window = valid()?.takeIf { it.token == source } ?: return null
        val phase = Phase.entries.firstOrNull { it.trace == name } ?: return null
        val stats = window.phases[phase.ordinal]
        val slot = stats.pending.indexOfFirst { it == null }
        if (slot < 0) return null
        return Sample(window.token, phase, clock(), slot).also { stats.pending[slot] = it }
    }
    @Synchronized fun complete(sample: Sample?) {
        sample ?: return
        checkpoint()
        val window = valid()?.takeIf { it.token == sample.token } ?: return
        val stats = window.phases[sample.phase.ordinal]
        if (stats.pending[sample.slot] !== sample) return
        stats.pending[sample.slot] = null
        val elapsed = (clock() - sample.started).coerceAtLeast(0)
        stats.count = (stats.count + 1).coerceAtMost(MAX_COUNT)
        // Compose entry is a marker only, never a measured composition duration.
        if (sample.phase != Phase.COMPOSE_ENTRY) {
            stats.total = (stats.total + elapsed).coerceAtMost(MAX_TOTAL_NS)
            stats.max = maxOf(stats.max, elapsed)
        }
    }
    @Synchronized fun frame(vsync: Long, duration: Long, budget: Long, hz: Float, stages: LongArray, host: Any? = null) {
        val window = valid() ?: return
        if (host != null && (window.owner as? Owner)?.host !== host) return
        if (vsync > window.deadline || duration < 0 || budget <= 0) return
        // An input-triggering frame can start before claim. Retain overlapping evidence separately:
        // it is ambiguous across consecutive gestures and never counts as an attributed window frame.
        val overlapsStart = vsync < window.start
        if (overlapsStart && duration <= window.start - vsync) return
        if (overlapsStart) {
            window.overlapFrames = (window.overlapFrames + 1).coerceAtMost(MAX_COUNT)
            if (duration <= budget) return
            window.overlapSlow = (window.overlapSlow + 1).coerceAtMost(MAX_COUNT)
            addWorst(window, duration, budget, hz, stages, overlapsStart = true)
            return
        }
        window.frames = (window.frames + 1).coerceAtMost(MAX_COUNT)
        window.max = maxOf(window.max, duration)
        window.minBudget = minOf(window.minBudget, budget); window.maxBudget = maxOf(window.maxBudget, budget)
        if (hz.isFinite() && hz > 0) { window.minHz = minOf(window.minHz, hz); window.maxHz = maxOf(window.maxHz, hz) }
        if (duration <= budget) return
        window.slow = (window.slow + 1).coerceAtMost(MAX_COUNT)
        addWorst(window, duration, budget, hz, stages, overlapsStart = false)
    }
    private fun addWorst(window: Window, duration: Long, budget: Long, hz: Float, stages: LongArray, overlapsStart: Boolean) {
        window.worst.add(Frame(duration, budget, hz.takeIf { it.isFinite() } ?: 0f, stages.take(9), overlapsStart))
        window.worst.sortByDescending { it.duration }
        if (window.worst.size > MAX_WORST) window.worst.removeAt(MAX_WORST)
    }
    private fun finish(reason: End) {
        val window = valid() ?: return
        active = null
        val observedAt = minOf(clock(), window.deadline)
        val phases = Phase.entries.mapNotNull { phase ->
            val stats = window.phases[phase.ordinal]
            val pending = stats.pending.filterNotNull()
            val inFlightMax = if (phase == Phase.COMPOSE_ENTRY) 0L else
                pending.maxOfOrNull { (observedAt - it.started).coerceAtLeast(0) } ?: 0L
            if (stats.count == 0 && pending.isEmpty()) null else
                PhaseStats(phase, stats.count, stats.total, stats.max, pending.size, inFlightMax)
        }
        if (window.slow == 0 && window.overlapSlow == 0 &&
            phases.none { it.max >= SLOW_PHASE_NS || it.inFlightMaxObserved >= SLOW_PHASE_NS }) return
        emit(Incident(window.token.epoch, window.kind, reason, window.commit, window.route,
            (clock() - window.start).coerceAtLeast(0), window.frames, window.slow, window.max,
            window.minBudget.takeUnless { it == Long.MAX_VALUE } ?: 0, window.maxBudget,
            window.minHz.takeUnless { it == Float.MAX_VALUE } ?: 0f, window.maxHz,
            phases, window.worst.toList(), window.overlapFrames, window.overlapSlow))
    }
    companion object {
        const val TAIL_NS = 750_000_000L
        const val HARD_NS = 4_000_000_000L
        const val SLOW_PHASE_NS = 16_000_000L
        const val MAX_WORST = 4
        const val MAX_COUNT = 1_000_000
        const val MAX_TOTAL_NS = 60_000_000_000L
    }
}

/** The narrow automatic channel is independent of the opt-in broad diagnostics switch. */
internal object BackPerformance {
    val recorder = BackPerformanceRecorder(epoch = RuntimeDiagnostics::backEpoch, emit = RuntimeDiagnostics::backIncident)
    fun start(owner: Any, kind: BackPerformanceRecorder.Kind): BackPerformanceRecorder.Token? = try {
        val token = recorder.start(owner, kind)
        (owner as? BackPerformanceRecorder.Owner)?.schedule?.invoke(4000) { safely { recorder.checkpoint() } }
        token
    } catch (_: Throwable) { null }
    fun commit(owner: Any) = safely { recorder.commit(owner); tail(owner) }
    fun reset(owner: Any) = safely { recorder.reset(owner); tail(owner) }
    private fun tail(owner: Any) {
        (owner as? BackPerformanceRecorder.Owner)?.schedule?.invoke(750) { safely { recorder.checkpoint() } }
    }
    inline fun safely(work: () -> Unit) { try { work() } catch (_: Throwable) { } }
}
