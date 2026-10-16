package app.daiyu.android.integration.tools

import app.daiyu.android.domain.tools.RegisteredTool

/** Lifecycle adapter only. The parent owns the sole registry, dispatcher and ledger. */
internal class GenerationCatalogLeaf(private val tools: RunGenerationTools) : AutoCloseable {
    val registeredTools: List<RegisteredTool> get() = tools.registeredTools
    private val lock = Any()
    private var started = false
    private var result: RunCatalogCloseResult? = null
    private val observers = mutableListOf<(RunCatalogCloseResult) -> Unit>()

    fun revokeAdmission() = tools.revokeAdmission()

    override fun close() {
        tools.revokeAdmission()
        synchronized(lock) {
            if (started) return
            started = true
        }
        // Leaf cleanup may wait for a publication validator. Never run it on the control
        // thread or under the parent's lifecycle/setup locks. One cleanup/observation attempt;
        // a validator that never exits leaves cleanup pending, never falsely drained.
        try {
            Thread({
                val closed = try {
                    tools.close()
                    when (val state = tools.awaitClose(30_000)) {
                        GenerationToolsCloseResult.Drained -> RunCatalogCloseResult.Drained
                        is GenerationToolsCloseResult.Unknown -> RunCatalogCloseResult.Unknown(state.reason)
                    }
                } catch (failure: Exception) {
                    if (failure is InterruptedException) Thread.currentThread().interrupt()
                    RunCatalogCloseResult.Unknown("generation_close_unconfirmed")
                }
                complete(closed)
            }, "generation-catalog-close").apply { isDaemon = true; start() }
        } catch (failure: Exception) {
            complete(RunCatalogCloseResult.Unknown("generation_cleanup_unavailable"))
            throw failure
        }
    }

    /** Parent registers only after its dispatch receipts and in-flight creations settle. */
    fun observeClose(done: (RunCatalogCloseResult) -> Unit) {
        val closed = synchronized(lock) {
            result.also { if (it == null) observers.add(done) }
        }
        if (closed != null) done(closed)
    }

    private fun complete(closed: RunCatalogCloseResult) {
        val callbacks = synchronized(lock) {
            if (result != null) return
            result = closed
            observers.toList().also { observers.clear() }
        }
        callbacks.forEach { callback -> runCatching { callback(closed) } }
    }
}
