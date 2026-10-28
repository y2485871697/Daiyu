package app.daiyu.android.haptics

import android.icu.text.BreakIterator
import android.os.Looper
import android.view.View
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import app.daiyu.android.ChatController
import app.daiyu.android.domain.Message
import app.daiyu.android.domain.ModelEvent

/** Activity-owned capabilities; the runtime/store remain Application-owned.
 * A page change is not run completion. Conversation replacement and destruction revoke observers
 * before their binding. Only the renderer may report actual visible reveal growth.
 */
internal class HapticsActivityHost(
    val runtime: HapticsRuntime,
    private val view: View,
    private val lifecycleOwner: LifecycleOwner,
    private val observe: (HapticsHostBinding) -> AutoCloseable,
) : DefaultLifecycleObserver, AutoCloseable {
    constructor(runtime: HapticsRuntime, view: View, lifecycleOwner: LifecycleOwner,
                controller: ChatController) : this(runtime, view, lifecycleOwner, { binding ->
        val observer = ControllerHapticsObserver(controller, binding)
        val reasoning = HapticsReasoningForwarder { observer.run }
        val subscription = controller.observeReasoning { message, event -> reasoning.accept(message, event) }
        AutoCloseable { subscription.close(); reasoning.close(); observer.close() }
    })

    private class Owner(val binding: HapticsHostBinding, val observation: AutoCloseable) {
        fun close() { observation.close(); binding.close() }
    }
    private var chat: Owner? = null
    var settingsOwner: HapticsHostBinding? = null
        private set
    private var closed = false

    init {
        checkMain()
        check(lifecycleOwner.lifecycle.currentState != Lifecycle.State.DESTROYED)
        lifecycleOwner.lifecycle.addObserver(this)
    }

    /** Suppress duplicate network ticks on the chat surface. Visible reveal growth arrives through
     * revealAdvance; hidden/paused hosts retain the existing background text/tool event path.
     */
    fun sync(conversationId: String?, chatVisible: Boolean, settingsVisible: Boolean) {
        checkMain()
        if (closed) return
        if (chat?.binding?.conversationId != conversationId) {
            closeSettings()
            chat?.close(); chat = null
            if (conversationId != null) {
                val binding = HapticsHostBinding(runtime, view, lifecycleOwner, conversationId)
                binding.setVisibleOutput(chatVisible)
                chat = Owner(binding, observe(binding))
            }
        }
        chat?.binding?.setVisibleOutput(chatVisible)
        if (!settingsVisible) closeSettings()
        else if (settingsOwner == null) {
            // Sharing the conversation avoids stealing the active run's engine arbitration.
            settingsOwner = HapticsHostBinding(runtime, view, lifecycleOwner,
                conversationId ?: "haptics-settings").also { it.setVisibleOutput(false) }
        }
    }

    /** Renderer-only capability. The host rechecks the exact message; this binding fences re-entry.
     * It does not revive a runner: normal completion may still have visible reveal left to drain.
     */
    fun revealAdvance(conversationId: String?): () -> Unit {
        checkMain()
        val owner = chat?.takeIf { it.binding.conversationId == conversationId }
        return {
            checkMain()
            if (!closed && owner != null && chat === owner) owner.binding.feedback.visibleAdvance()
        }
    }

    fun isCurrentSettings(owner: HapticsHostBinding): Boolean = !closed && settingsOwner === owner

    private fun closeSettings() { settingsOwner?.close(); settingsOwner = null }
    private fun checkMain() = check(Looper.myLooper() == Looper.getMainLooper())
    override fun onDestroy(owner: LifecycleOwner) = close()
    override fun close() {
        checkMain()
        if (closed) return
        closed = true
        lifecycleOwner.lifecycle.removeObserver(this)
        closeSettings()
        chat?.close(); chat = null
    }
}

/** Only consumes the runner's accepted reasoning events. No persisted history or synthetic final.
 * The run capability and identity are rechecked for every event; replacement drops the accumulator.
 */
internal class HapticsReasoningForwarder(
    private val currentRun: () -> HapticsHostBinding.Run?,
) : AutoCloseable {
    private var run: HapticsHostBinding.Run? = null
    private val text = mutableMapOf<String, String>()
    private val completed = mutableSetOf<String>()
    private var closed = false

    fun accept(message: Message, event: ModelEvent) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (closed) return
        val current = currentRun()?.takeIf {
            !it.ended && it.runId == message.runId.value && it.messageId == message.id.value
        } ?: return
        if (run !== current) { run = current; text.clear(); completed.clear() }
        when (event) {
            is ModelEvent.ReasoningDelta -> {
                if (event.itemId in completed) return
                val old = text[event.itemId].orEmpty()
                val next = if (event.cumulative) event.text else old + event.text
                text[event.itemId] = next
                current.output((graphemes(next) - graphemes(old)).coerceAtLeast(0), reasoning = true)
            }
            is ModelEvent.ReasoningFinal -> {
                if (!completed.add(event.itemId)) return
                val old = text.put(event.itemId, event.text).orEmpty()
                if (event.text.startsWith(old)) {
                    current.output((graphemes(event.text) - graphemes(old)).coerceAtLeast(0), reasoning = true)
                }
                current.reasoningCompleted(event.itemId)
            }
            else -> Unit
        }
    }

    override fun close() { closed = true; run = null; text.clear(); completed.clear() }
    private fun graphemes(value: String): Int {
        val iterator = BreakIterator.getCharacterInstance()
        iterator.setText(value); iterator.first()
        var count = 0
        while (iterator.next() != BreakIterator.DONE) count++
        return count
    }
}
