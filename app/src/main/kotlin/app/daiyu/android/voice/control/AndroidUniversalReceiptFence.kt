package app.daiyu.android.voice.control

/**
 * Per-original-send receipt bridge. The leaf consumes only its first matching receipt; therefore a
 * provider/parent which reports Unknown and later discovers durable Accepted still needs exact
 * cleanup here, not a second send and not an ignored second leaf receipt. Control-thread confined.
 */
internal class AndroidUniversalReceiptFence(
    private val turn: VoiceControlTurn,
    private val deliver: (VoiceControlReceipt) -> Unit,
    private val stopExtraAccepted: (VoiceAssistantOwner) -> Unit,
) {
    private var seen = false
    private var firstAccepted: VoiceAssistantOwner? = null
    fun accept(receipt: VoiceControlReceipt) {
        val accepted = (receipt as? VoiceControlReceipt.Accepted)?.takeIf {
            it.requestId == turn.requestId && it.receiptId.isNotBlank() &&
                it.assistant.conversationId == turn.permit.conversationId
        }
        if (receipt.requestId != turn.requestId) {
            if (!seen) deliver(receipt) // Leaf rejects mismatch; never stop an uncorrelated owner.
            return
        }
        if (seen) {
            if (accepted != null && accepted.assistant != firstAccepted) stopExtraAccepted(accepted.assistant)
            return
        }
        seen = true
        firstAccepted = accepted?.assistant
        deliver(receipt) // First late Accepted after revoke is still handled by leaf's exact cleanup.
    }
}
