package app.daiyu.android.shizuku

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

/** Owns publication and close requests, not physical remote-cleanup confirmation. */
internal class ShizukuConnectionLifecycle(private val release: () -> Unit) : AutoCloseable {
    private sealed interface State
    private data object Empty : State
    private data object Closed : State
    private class Opening(val scope: String) : State { val done = CountDownLatch(1) }
    private class Open(val scope: String, val backend: ShizukuBackend) : State
    private val state = AtomicReference<State>(Empty)
    val isOpen: Boolean get() = state.get() !== Closed

    fun open(scope: String, create: () -> ShizukuBackend): ShizukuBackend {
        require(scope.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        while (true) {
            when (val observed = state.get()) {
                Closed -> error("shizuku_scope_revoked")
                Empty -> {
                    val opening = Opening(scope)
                    if (!state.compareAndSet(observed, opening)) continue
                    try {
                        val candidate = create()
                        if (!state.compareAndSet(opening, Open(scope, candidate))) {
                            candidate.close()
                            error("shizuku_scope_revoked")
                        }
                        return candidate
                    } finally {
                        state.compareAndSet(opening, Empty)
                        opening.done.countDown()
                    }
                }
                is Opening -> {
                    check(observed.scope == scope) { "shizuku_scope_already_bound" }
                    observed.done.await()
                }
                is Open -> {
                    check(observed.scope == scope) { "shizuku_scope_already_bound" }
                    return observed.backend
                }
            }
        }
    }
    override fun close() {
        val previous = state.getAndSet(Closed)
        if (previous === Closed) return
        if (previous is Opening) previous.done.countDown()
        try { if (previous is Open) previous.backend.close() } finally { release() }
    }
}
