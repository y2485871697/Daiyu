package app.daiyu.android.haptics

import android.icu.text.BreakIterator
import android.os.Looper
import app.daiyu.android.ChatController
import app.daiyu.android.domain.Message
import app.daiyu.android.domain.MessageBlock
import app.daiyu.android.domain.RunStatus

/**
 * Adapts ChatController.observe/unobserve notifications to haptic run events.
 * Mount seeds the current snapshot without replaying history.
 * Controller currently publishes Text/Tool/Image only: live reasoning must be forwarded separately
 * from the real model event path through the captured Run capability (see INTEGRATION.md).
 * Own alongside HapticsHostBinding; close BEFORE closing that host binding.
 */
class ControllerHapticsObserver(
    private val controller: ChatController,
    private val host: HapticsHostBinding,
) : AutoCloseable {
    private var closed = false
    @Volatile var run: HapticsHostBinding.Run? = null
        private set
    private val text = linkedMapOf<String, String>()
    private val tools = mutableSetOf<String>()
    private val listener: () -> Unit = { accept(controller.active, seed = false) }
    private val hostClosed = host.onClosed(::close)
    private val revocation = controller.observeRunRevocation { owner ->
        // May run on a control thread. Do not wait for a terminal snapshot or the main looper.
        run?.takeIf { it.runId == owner.runId.value && it.messageId == owner.id.value &&
            host.conversationId == owner.conversationId.value }?.stop()
    }

    init {
        checkMain()
        controller.observe(listener)
        accept(controller.active, seed = true)
    }

    private fun checkMain() = check(Looper.myLooper() == Looper.getMainLooper())
    private fun accept(message: Message?, seed: Boolean) {
        checkMain()
        if (closed) return
        if (!host.acceptsRuns || message == null || message.conversationId.value != host.conversationId ||
            message.role != Message.ASSISTANT_ROLE || message.status != RunStatus.RUNNING ||
            !controller.isRunUnfenced(message)) {
            run?.stop(); run = null; text.clear(); tools.clear()
            return
        }
        var current = run
        // Accepted stop can precede the controller's final status notification. Never revive it.
        if (current?.ended == true && current.runId == message.runId.value &&
            current.messageId == message.id.value) return
        if (current == null || current.runId != message.runId.value ||
            current.messageId != message.id.value) {
            current?.stop()
            text.clear(); tools.clear()
            current = host.beginRun(message.runId.value, message.id.value)
            run = current
        }
        val activeRun = checkNotNull(current)
        // A background stop may have raced creation before run was published to the listener.
        if (!controller.isRunUnfenced(message)) { activeRun.stop(); return }
        // Stable item/call IDs dedupe repeated controller notifications and history segment overlap.
        val blocks = (message.historySegments.filter { it.role == Message.ASSISTANT_ROLE }
            .flatMap { it.blocks } + message.blocks).associateBy { block -> when (block) {
                is MessageBlock.Text -> "text:${block.itemId}"
                is MessageBlock.Tool -> "tool:${block.invocation.id.value}"
                is MessageBlock.Image -> "image:${block.hashCode()}"
            } }
        blocks.values.forEach { block -> when (block) {
            is MessageBlock.Text -> {
                val old = text.put(block.itemId, block.text).orEmpty()
                // Snapshot replacement/truncation is not newly generated output.
                if (!seed && block.text.startsWith(old) && block.text.length > old.length) {
                    activeRun.output((graphemes(block.text) - graphemes(old)).coerceAtLeast(0))
                }
            }
            is MessageBlock.Tool -> if (tools.add(block.invocation.id.value) && !seed) {
                activeRun.toolAppeared(block.invocation.id.value)
            }
            is MessageBlock.Image -> Unit
        } }
    }

    /** The UI must supply the identity whose reveal actually advanced; stale frames are ignored. */
    fun visibleAdvance(runId: String, messageId: String) {
        checkMain()
        run?.takeIf { it.runId == runId && it.messageId == messageId }?.visibleAdvance()
    }

    override fun close() {
        checkMain()
        if (closed) return
        closed = true
        controller.unobserve(listener)
        revocation.close()
        hostClosed.close()
        run?.stop(); run = null; text.clear(); tools.clear()
    }

    private fun graphemes(value: String): Int {
        val iterator = BreakIterator.getCharacterInstance()
        iterator.setText(value)
        iterator.first()
        var count = 0
        while (iterator.next() != BreakIterator.DONE) count++
        return count
    }
}
