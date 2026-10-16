package app.daiyu.android.haptics

import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import app.daiyu.android.domain.haptics.HapticAgentAction

/**
 * Real host boundary, not an event producer. Own one binding per screen/lifecycle instance.
 * The controller owns acceptance of a run; only beginRun after that acceptance. Every asynchronous
 * callback captures the returned Run, never looks up a mutable "current run" at delivery time.
 * Conversation/run/message identity is frozen, haptic settings are deliberately LIVE kill switches.
 * onStop retains background output; onDestroy/close revokes every capability and cancels its motor.
 */
class HapticsHostBinding(
    private val runtime: HapticsRuntime,
    private val view: View,
    private val lifecycleOwner: LifecycleOwner,
    val conversationId: String,
) : DefaultLifecycleObserver, AutoCloseable {
    private val handler = Handler(Looper.getMainLooper())
    val feedback = runtime.attach(view, conversationId)
    private var visibleOutput = true
    private var enabled = true
    private var closed = false
    private var current: Run? = null
    private val closeListeners = linkedSetOf<() -> Unit>()
    internal val acceptsRuns: Boolean get() = !closed && enabled

    internal fun onClosed(listener: () -> Unit): AutoCloseable {
        checkMain()
        check(!closed)
        closeListeners += listener
        return AutoCloseable { closeListeners -= listener }
    }

    init {
        checkMain()
        check(lifecycleOwner.lifecycle.currentState != Lifecycle.State.DESTROYED)
        lifecycleOwner.lifecycle.addObserver(this)
        publishLifecycle()
    }

    private fun checkMain() = check(Looper.myLooper() == Looper.getMainLooper())
    private fun resumed() = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
    private fun publishLifecycle() {
        if (closed) return
        feedback.lifecycle(resumed(), visibleOutput, enabled)
        current?.session?.lifecycle(resumed(), visibleOutput, enabled)
    }

    /** False while this host has no actually visible typewriter; Activity resume alone is not enough. */
    fun setVisibleOutput(value: Boolean) { checkMain(); visibleOutput = value; publishLifecycle() }

    /** Revocation, e.g. owner/config replacement. Re-enabling never resurrects the previous run. */
    fun setEnabled(value: Boolean) {
        checkMain()
        if (!value) current?.stop()
        enabled = value
        publishLifecycle()
    }

    fun beginRun(runId: String, messageId: String): Run {
        checkMain()
        check(!closed && enabled)
        require(runId.isNotBlank() && messageId.isNotBlank())
        current?.takeIf { !it.ended && it.runId == runId && it.messageId == messageId }?.let { return it }
        current?.stop()
        return Run(runId, messageId, runtime.attach(view, conversationId)).also {
            current = it
            publishLifecycle()
        }
    }

    inner class Run internal constructor(
        val runId: String,
        val messageId: String,
        internal val session: HapticsRuntime.Session,
    ) {
        @Volatile internal var ended = false
        private fun deliver(block: () -> Unit) {
            if (ended) return
            if (Looper.myLooper() == Looper.getMainLooper()) {
                if (!closed && enabled && !ended && current === this) block()
            } else handler.post { if (!closed && enabled && !ended && current === this) block() }
        }
        private fun key(kind: String, id: String) =
            "${runId.length}:$runId:${messageId.length}:$messageId:$kind:${id.length}:$id"

        /** Exact newly arrived grapheme count from the real stream; never UTF-16 String.length. */
        fun output(graphemes: Int, reasoning: Boolean = false) = deliver {
            session.backgroundOutput(graphemes, reasoning)
        }
        /** Call only when the actual visible text/reasoning reveal advances, not on recomposition. */
        fun visibleAdvance() = deliver { session.visibleAdvance() }
        fun toolAppeared(stableCallId: String) = deliver {
            if (stableCallId.isNotBlank()) session.toolAppeared(key("tool", stableCallId))
        }
        fun reasoningCompleted(stableItemId: String) = deliver {
            if (stableItemId.isNotBlank()) session.reasoningCompleted(key("reasoning", stableItemId))
        }
        fun agentAction(action: HapticAgentAction) = deliver { session.agentAction(action) }

        /** On accepted stop/failure/completion, NOT just when the Activity becomes background. */
        fun stop() {
            ended = true // Revoke worker callbacks before waiting for the main looper.
            session.close() // Also fences already queued engine ticks, even off the main thread.
            val dispose = {
                if (current === this) current = null
            }
            if (Looper.myLooper() == Looper.getMainLooper()) dispose() else handler.post { dispose() }
        }
    }

    override fun onResume(owner: LifecycleOwner) = publishLifecycle()
    override fun onPause(owner: LifecycleOwner) = publishLifecycle()
    override fun onStop(owner: LifecycleOwner) = publishLifecycle()
    override fun onDestroy(owner: LifecycleOwner) = close()
    override fun close() {
        checkMain()
        if (closed) return
        closed = true
        lifecycleOwner.lifecycle.removeObserver(this)
        val listeners = closeListeners.toList()
        closeListeners.clear()
        listeners.forEach { it() }
        current?.stop()
        feedback.close()
        // Keep already posted stop disposals: removing them could leak a revoked Run's Session.
    }
}
