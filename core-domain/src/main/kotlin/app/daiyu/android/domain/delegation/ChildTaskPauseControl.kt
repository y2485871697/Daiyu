package app.daiyu.android.domain.delegation

/** Live process state, deliberately not a durable ChildTaskStatus or replay permission. */
enum class ChildTaskPauseState { ACTIVE, PENDING, PAUSED, FINISHED }

enum class ChildTaskQueueState { ACTIVE, PENDING, FINISHED }

/** PENDING is only intent; PAUSED acknowledges a resumable boundary, never an in-flight read. */
enum class ChildTaskControlReceipt { PENDING, PAUSED, RESUMED, STALE, CONFIGURATION_REQUIRED, ROLE_MISMATCH }

enum class ChildTaskStopDisposition { CONTINUE, PAUSE, STOP }

/** 管理单个已接收子任务的暂停状态。 */
internal class ChildTaskPauseControl(private val onChanged: () -> Unit) {
    private val gate = Object()
    private var state = ChildTaskPauseState.ACTIVE
    private var ended = false
    private var configurationBlocked = false
    private var pauseObserver: () -> Unit = {}
    fun observePause(observer: () -> Unit) { synchronized(gate) { pauseObserver = observer } }
    private fun notifyPaused() {
        runCatching { synchronized(gate) { pauseObserver }.invoke() }
        runCatching(onChanged)
    }
    private var pausedAt = 0L
    private var pausedTotal = 0L
    private var poolWaiting = false
    private var excludedAt = 0L
    private var excluded = 0L
    private fun updateExcluded() {
        val waiting = poolWaiting || state == ChildTaskPauseState.PAUSED
        if (waiting && excludedAt == 0L) excludedAt = System.nanoTime()
        if (!waiting && excludedAt != 0L) { excluded += System.nanoTime() - excludedAt; excludedAt = 0L }
    }
    fun schedulingBoundary(waiting: Boolean) = synchronized(gate) { poolWaiting = waiting; updateExcluded() }
    fun excludedNanos(): Long = synchronized(gate) {
        excluded + if (excludedAt != 0L) System.nanoTime() - excludedAt else 0L
    }

    fun snapshot(): ChildTaskPauseState = synchronized(gate) { state }

    fun pause(): ChildTaskControlReceipt = synchronized(gate) {
        if (ended) return@synchronized ChildTaskControlReceipt.STALE
        if (state == ChildTaskPauseState.ACTIVE) state = ChildTaskPauseState.PENDING
        if (state == ChildTaskPauseState.PAUSED) ChildTaskControlReceipt.PAUSED else ChildTaskControlReceipt.PENDING
    }

    fun isConfigurationBlocked(): Boolean = synchronized(gate) { configurationBlocked }

    fun pauseForConfiguration(): ChildTaskControlReceipt = synchronized(gate) {
        if (ended) return@synchronized ChildTaskControlReceipt.STALE
        configurationBlocked = true
        if (state == ChildTaskPauseState.ACTIVE) state = ChildTaskPauseState.PENDING
        if (state == ChildTaskPauseState.PAUSED) ChildTaskControlReceipt.PAUSED else ChildTaskControlReceipt.PENDING
    }

    fun acknowledgeBoundary() {
        val changed = synchronized(gate) {
            if (ended || state != ChildTaskPauseState.PENDING) false else {
                state = ChildTaskPauseState.PAUSED
                pausedAt = System.nanoTime()
                updateExcluded()
                true
            }
        }
        if (changed) notifyPaused()
    }

    fun resumeWithAssignment(): ChildTaskControlReceipt = synchronized(gate) {
        if (ended) return@synchronized ChildTaskControlReceipt.STALE
        configurationBlocked = false
        resume()
    }

    /** Also retracts an intent that has not reached a boundary. Never opens a request. */
    fun resume(): ChildTaskControlReceipt = synchronized(gate) {
        if (ended) return@synchronized ChildTaskControlReceipt.STALE
        if (configurationBlocked) return@synchronized ChildTaskControlReceipt.CONFIGURATION_REQUIRED
        endPause()
        state = ChildTaskPauseState.ACTIVE
        updateExcluded()
        gate.notifyAll()
        ChildTaskControlReceipt.RESUMED
    }

    fun terminate() = synchronized(gate) {
        ended = true
        endPause()
        state = ChildTaskPauseState.ACTIVE
        updateExcluded()
        gate.notifyAll()
    }

    /** Only confirmed pause time is excluded from the existing execution timeout. */
    fun pausedNanos(): Long = synchronized(gate) {
        pausedTotal + if (state == ChildTaskPauseState.PAUSED) System.nanoTime() - pausedAt else 0L
    }

    /** Called only before the first open, or after a failed read on a resumable SAME session. */
    fun awaitBoundary(requireRequest: Boolean = false, boundary: () -> Unit = {}): Boolean {
        var claimed = false
        while (true) {
            val changed = synchronized(gate) {
                if (ended || (requireRequest && !claimed && state != ChildTaskPauseState.PENDING)) return false
                claimed = true
                if (state == ChildTaskPauseState.PENDING) {
                    state = ChildTaskPauseState.PAUSED
                    pausedAt = System.nanoTime()
                    updateExcluded()
                    true
                } else false
            }
            if (changed) notifyPaused()
            boundary()
            synchronized(gate) {
                if (!ended && state == ChildTaskPauseState.PAUSED) gate.wait(25)
                if (ended) return false
                if (state == ChildTaskPauseState.ACTIVE) return true
                // A new pause raced the resume before this worker left its boundary.
            }
        }
    }

    private fun endPause() {
        if (state == ChildTaskPauseState.PAUSED) pausedTotal += System.nanoTime() - pausedAt
    }
}
