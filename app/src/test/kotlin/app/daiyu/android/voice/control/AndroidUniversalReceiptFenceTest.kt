package app.daiyu.android.voice.control

import org.junit.Assert.*
import org.junit.Test

/** No persistence/network assertions: exercises correlation and late receipt cleanup only. */
class AndroidUniversalReceiptFenceTest {
    private val profile = VoiceControlProfile("conversation", 1, "Conversation")
    private val permit = VoiceControlPermit("permit", "chat", "lifecycle", profile, VoiceControlLimits(), 100_000)
    private val turn = VoiceControlTurn(permit, 1, "request")
    private val owner = VoiceAssistantOwner("chat", "run", "message")
    private fun accepted(assistant: VoiceAssistantOwner = owner) = VoiceControlReceipt.Accepted(turn.requestId, "receipt", assistant)

    @Test fun unknownThenDurableAcceptedStillStopsExactOwnerWithoutSecondSend() {
        val delivered = mutableListOf<VoiceControlReceipt>()
        val stopped = mutableListOf<VoiceAssistantOwner>()
        val fence = AndroidUniversalReceiptFence(turn, { delivered += it }, { stopped += it })
        fence.accept(VoiceControlReceipt.Unknown(turn.requestId))
        fence.accept(accepted())
        assertEquals(listOf(VoiceControlReceipt.Unknown(turn.requestId)), delivered)
        assertEquals(listOf(owner), stopped)
    }
    @Test fun rejectedThenAcceptedIsCleanupNotReplay() {
        val delivered = mutableListOf<VoiceControlReceipt>()
        val stopped = mutableListOf<VoiceAssistantOwner>()
        val fence = AndroidUniversalReceiptFence(turn, { delivered += it }, { stopped += it })
        fence.accept(VoiceControlReceipt.Rejected(turn.requestId))
        fence.accept(accepted())
        assertEquals(1, delivered.size)
        assertEquals(listOf(owner), stopped)
    }
    @Test fun duplicateAcceptedCannotStopTheLiveOriginalOwner() {
        val delivered = mutableListOf<VoiceControlReceipt>()
        val stopped = mutableListOf<VoiceAssistantOwner>()
        val fence = AndroidUniversalReceiptFence(turn, { delivered += it }, { stopped += it })
        fence.accept(accepted())
        fence.accept(accepted())
        fence.accept(VoiceControlReceipt.Unknown(turn.requestId))
        assertEquals(listOf(accepted()), delivered)
        assertTrue(stopped.isEmpty())
    }
    @Test fun unrelatedRequestOrConversationIsNeverStopped() {
        val stopped = mutableListOf<VoiceAssistantOwner>()
        val fence = AndroidUniversalReceiptFence(turn, {}, { stopped += it })
        fence.accept(VoiceControlReceipt.Unknown(turn.requestId))
        fence.accept(accepted().copy(requestId = "different"))
        fence.accept(accepted(VoiceAssistantOwner("other-chat", "run", "message")))
        fence.accept(accepted().copy(receiptId = ""))
        assertTrue(stopped.isEmpty())
    }
    @Test fun extraAcceptedOwnerIsStoppedButFirstOwnerIsNot() {
        val delivered = mutableListOf<VoiceControlReceipt>()
        val stopped = mutableListOf<VoiceAssistantOwner>()
        val extra = VoiceAssistantOwner("chat", "other-run", "other-message")
        val fence = AndroidUniversalReceiptFence(turn, { delivered += it }, { stopped += it })
        fence.accept(accepted())
        fence.accept(accepted(extra))
        assertEquals(listOf(accepted()), delivered)
        assertEquals(listOf(extra), stopped)
    }
}
