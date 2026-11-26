package app.daiyu.android.domain.delegation

enum class ChildRequestState { STARTED, SENT, RESPONDING, RESUMING, COMPLETED, FAILED }

data class ChildRequestObservation(
    val requestId: String,
    val profileId: String,
    val model: ChildRequestModel,
    val state: ChildRequestState,
    val responseObserved: Boolean = false,
    val sentObserved: Boolean = false,
)

/** Bounded process-local evidence scoped to the complete task identity, independent of diagnostic export. */
class ChildRequestHistory : ChildTaskDiagnostics {
    private val tasks = LinkedHashMap<ChildRunOwner, LinkedHashMap<String, ChildRequestObservation>>()

    @Synchronized override fun record(event: ChildTaskDiagnosticEvent) {
        val id = event.request?.requestId ?: return
        val model = event.model ?: return
        val owner = event.owner
        if (event.phase == ChildTaskDiagnosticPhase.REQUEST_STARTED) {
            val requests = tasks.remove(owner) ?: linkedMapOf()
            requests.putIfAbsent(id, ChildRequestObservation(id, event.profileId, model, ChildRequestState.STARTED))
            if (requests.size > MAX_REQUESTS) requests.remove(requests.keys.first())
            tasks[owner] = requests
            if (tasks.size > MAX_TASKS) tasks.remove(tasks.keys.first())
            return
        }
        val requests = tasks[owner] ?: return
        val previous = requests[id] ?: return
        if (previous.model != model || previous.state == ChildRequestState.COMPLETED) return
        val state = when (event.phase) {
            ChildTaskDiagnosticPhase.REQUEST_SENT -> ChildRequestState.SENT
            ChildTaskDiagnosticPhase.FIRST_EVENT -> ChildRequestState.RESPONDING
            ChildTaskDiagnosticPhase.REQUEST_RESUMED -> ChildRequestState.RESUMING
            ChildTaskDiagnosticPhase.REQUEST_COMPLETED -> ChildRequestState.COMPLETED
            ChildTaskDiagnosticPhase.REQUEST_FAILED -> ChildRequestState.FAILED
            else -> return
        }
        val response = previous.responseObserved || state == ChildRequestState.RESPONDING || state == ChildRequestState.COMPLETED
        requests[id] = previous.copy(state = state, responseObserved = response,
            sentObserved = previous.sentObserved || response || state == ChildRequestState.SENT)
    }

    @Synchronized fun recent(owner: ChildRunOwner): List<ChildRequestObservation> =
        tasks[owner]?.values?.toList().orEmpty()

    private companion object {
        const val MAX_TASKS = 128
        const val MAX_REQUESTS = 5
    }
}

/** The synchronous model POST reports body delivery to its own child, never to a cursor resume. */
object ChildModelPostObservation {
    private val listener = ThreadLocal<() -> Unit>()

    internal fun <T> observe(onSent: () -> Unit, request: () -> T): T {
        val previous = listener.get()
        listener.set(onSent)
        try { return request() }
        finally { if (previous == null) listener.remove() else listener.set(previous) }
    }

    fun sent() { listener.get()?.invoke() }
}
