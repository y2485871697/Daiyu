package app.daiyu.android.context

/** A single reserved parent send. Created without I/O; admission happens only on actual summary
 * open, before any provider connection. seal() is required after prepare and before database.send.
 * close() revokes an unused/failed/cancelled reservation; it never erases accepted Usage.
 * The runtime lease owns the callbacks and serializes them with the Runner's checkpoint writer. */
internal class RuntimeSummaryPreparation internal constructor(
    val usagePort: BoundSummaryUsagePort,
    cancellation: ContextPreparationCancellation,
    private val sealReservation: () -> Unit,
    private val revokeReservation: () -> Unit,
    val nextUsagePort: () -> BoundSummaryUsagePort = { error("summary_preparation_chunks_unavailable") },
) : AutoCloseable {
    private val registration = cancellation.register { revokeReservation() }
    fun seal() = sealReservation()
    override fun close() {
        registration.close()
        revokeReservation()
    }
}
