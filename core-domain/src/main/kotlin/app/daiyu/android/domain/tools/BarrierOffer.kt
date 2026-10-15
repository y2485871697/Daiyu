package app.daiyu.android.domain.tools

import app.daiyu.android.domain.runtime.PendingToolCheckpoint

sealed interface ReceiptBatchStart {
    data class Completed(val receipts: List<ToolDispatchResult>) : ReceiptBatchStart
    data class Suspended(val offer: BarrierOffer) : ReceiptBatchStart
}

class BarrierOffer internal constructor(
    val context: ToolExecutionContext,
    val pending: PendingToolCheckpoint,
    private val finishReceipt: (ExecutionReceipt) -> ToolDispatchResult
) {
    @Volatile var waitingCommitted = false
        private set
    private var consumed = false

    @Synchronized internal fun markWaitingCommitted() {
        check(!waitingCommitted && !consumed) { "barrier_offer_already_committed_or_consumed" }
        waitingCommitted = true
    }

    @Synchronized internal fun finish(receipt: ExecutionReceipt): ToolDispatchResult {
        check(!consumed) { "barrier_offer_already_consumed" }
        consumed = true
        return finishReceipt(receipt)
    }
}
