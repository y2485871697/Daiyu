package app.daiyu.android.context

import app.daiyu.android.domain.ModelEvent
import app.daiyu.android.domain.runtime.RequestCheckpoint
import app.daiyu.android.domain.runtime.RequestOperation
import app.daiyu.android.domain.runtime.UsageRequestSource
import app.daiyu.android.model.usage.SQLiteAdditionalUsageStore
import java.util.UUID

/** One explicitly consented conversation operation. No parent, send, handoff, or POST recovery.
 * The host's existing lease remains reserved until the worker drains its finally block. */
internal class ManualSummaryUsageOperation(
    private val transactions: DatabaseTransactions,
    private val lease: ContextManagementLease,
) {
    val operationId: String = UUID.randomUUID().toString()
    private val conversation = lease.owner.conversationId.value
    private val process = SQLiteAdditionalUsageStore.manualProcessOwner
    private val request = RequestCheckpoint(UUID.randomUUID().toString(), UUID.randomUUID().toString(),
        UUID.randomUUID().toString(), RequestOperation.NEXT)
    private val lock = Any()
    private var admitted = false
    private var ended = false
    private var latest: ModelEvent.Usage? = null

    val port = BoundSummaryUsagePort(conversation, beforeClaim = {
        // Do not acquire the host monitor while holding a SQLite transaction.
        lease.checkCurrent()
        synchronized(lock) {
            check(!admitted && !ended)
            lease.cancellation.check()
            transactions.run { db ->
                lease.cancellation.check()
                SQLiteAdditionalUsageStore.recoverManual(db)
                SQLiteAdditionalUsageStore.admit(db, conversation, null, null, request,
                    UsageRequestSource.MANUAL_SUMMARY, process, operationId)
            }
            admitted = true
        }
    }, record = { usage ->
        synchronized(lock) {
            check(admitted && !ended) { "manual_summary_terminal" }
            lease.cancellation.check() // Reject even an identical late observation after cancellation.
            transactions.run { db ->
                lease.cancellation.check()
                SQLiteAdditionalUsageStore.observe(db, conversation, null, null, request,
                    UsageRequestSource.MANUAL_SUMMARY, process, usage, false, operationId)
            }
            latest = usage // Only update memory after the durable whole-snapshot replacement.
        }
    })

    fun finish(success: Boolean): Unit = synchronized(lock) {
        if (!ended) {
            ended = true // Even a failed durable close must not permit a late callback to mutate it.
            if (admitted) transactions.run { db ->
                // Deletion already removed this operation. Never resurrect a deleted conversation.
                val exists = db.rawQuery("SELECT request_id FROM context_usage WHERE request_id=?",
                    arrayOf(request.requestId)).use { it.moveToFirst() }
                if (exists) SQLiteAdditionalUsageStore.observe(db, conversation, null, null, request,
                    UsageRequestSource.MANUAL_SUMMARY, process, latest, true, operationId,
                    if (lease.cancellation.isCancelled || Thread.currentThread().isInterrupted) "CANCELLED"
                    else if (success) "SUCCEEDED" else "FAILED")
            }
        }
    }
}
