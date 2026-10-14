package app.daiyu.android.domain.tools

import java.util.UUID

enum class LedgerState { UNKNOWN, CONFIRMED, NOT_EXECUTED }
data class LedgerEntry(
    val sessionId: String,
    val callId: String,
    val taskId: String,
    val requestKey: String,
    val effectKey: String?,
    val owner: String,
    val state: LedgerState = LedgerState.UNKNOWN,
    val payload: String = "",
    val isError: Boolean = false,
    val code: String = "execution_unconfirmed"
)

/** Transactions must serialize across ALL users of a store and commit durably BEFORE returning.
 * Exceptions must roll back, never silently reset a corrupt/unavailable ledger to empty.
 * The mutable list and callback are transaction-local; implementations must not retain them. */
interface ToolLedgerStore {
    fun <T> transaction(block: (MutableList<LedgerEntry>) -> T): T
}

/** For JVM tests / deliberately non-durable hosts only. Android production uses SQLiteToolLedgerStore. */
class InMemoryToolLedgerStore : ToolLedgerStore {
    private var entries = listOf<LedgerEntry>()
    @Synchronized override fun <T> transaction(block: (MutableList<LedgerEntry>) -> T): T {
        val copy = entries.toMutableList()
        val result = block(copy)
        entries = copy.toList()
        return result
    }
}

internal data class LedgerRequest(val callId: String, val requestKey: String, val effectKey: String?)
internal sealed interface Reservation {
    data class Accepted(val entries: List<LedgerEntry>, val owner: String) : Reservation
    data class Rejected(val code: String, val unknownIndices: Set<Int> = emptySet()) : Reservation
}

/** A durable UNKNOWN reservation is written before invoking ANY adapter. A crash needs no reset/recovery pass. */
class ToolLedger(private val store: ToolLedgerStore) {
    internal fun reserve(sessionId: String, taskId: String, requests: List<LedgerRequest>): Reservation = store.transaction { entries ->
        check(entries.map { it.sessionId to it.callId }.distinct().size == entries.size) { "invalid_ledger" }
        check(entries.all { listOf(it.sessionId, it.callId, it.taskId, it.requestKey, it.owner).all(String::isNotBlank) }) { "invalid_ledger" }
        val activeEffects = entries.filter { it.effectKey != null && it.state != LedgerState.NOT_EXECUTED }.groupBy { it.effectKey }
        check(activeEffects.values.all { group ->
            group.size == 1 || group.all { it.state == LedgerState.CONFIRMED && it.payload == group.first().payload && it.isError == group.first().isError }
        }) { "invalid_ledger" }
        val matches = requests.map { request ->
            val byId = entries.singleOrNull { it.sessionId == sessionId && it.callId == request.callId }
            if (byId != null && (byId.requestKey != request.requestKey || byId.effectKey != request.effectKey || byId.taskId != taskId))
                return@transaction Reservation.Rejected("call_id_conflict")
            byId ?: request.effectKey?.let { key -> entries.firstOrNull { it.effectKey == key && it.state != LedgerState.NOT_EXECUTED } }
        }
        val unknown = matches.withIndex().filter { it.value?.state == LedgerState.UNKNOWN }.map { it.index }.toSet()
        if (unknown.isNotEmpty()) return@transaction Reservation.Rejected("unknown_effect_protected", unknown)
        val owner = UUID.randomUUID().toString()
        val reserved = requests.mapIndexed { index, request ->
            val previous = matches[index]
            if (previous != null && previous.sessionId == sessionId && previous.callId == request.callId) previous
            else LedgerEntry(sessionId, request.callId, taskId, request.requestKey, request.effectKey, owner,
                previous?.state ?: LedgerState.UNKNOWN, previous?.payload.orEmpty(), previous?.isError ?: false,
                previous?.code ?: "execution_unconfirmed").also { entries.add(it) }
        }
        Reservation.Accepted(reserved, owner)
    }

    internal fun finish(entry: LedgerEntry, receipt: ExecutionReceipt) = store.transaction { entries ->
        val index = entries.indexOfFirst { it.sessionId == entry.sessionId && it.callId == entry.callId }
        check(index >= 0 && entries[index] == entry && entry.state == LedgerState.UNKNOWN) { "ledger_conflict" }
        entries[index] = when (receipt) {
            is ExecutionReceipt.Confirmed -> entry.copy(state = LedgerState.CONFIRMED, payload = receipt.payload, isError = receipt.isError, code = "")
            is ExecutionReceipt.NotExecuted -> entry.copy(state = LedgerState.NOT_EXECUTED, code = receipt.code)
            is ExecutionReceipt.Unknown -> entry.copy(code = receipt.code)
        }
    }
}
