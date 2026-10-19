package app.daiyu.android.title

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** One settings visit, never saved/restored. Queuing does not authorize disk/key access.
 * Revoke wins against queued work; already admitted writes may finish, but cannot publish
 * into this or a later visit. No lock or join is held by the UI during persistence. */
class TitleModelSettingsEditor(private val store: () -> TitleModelSettingsStore) {
    private sealed interface State
    private class Ready(val identity: Any? = null) : State
    private class Queued(val identity: Any) : State
    private class Admitted(val identity: Any) : State
    private data object Revoked : State
    private val state = AtomicReference<State>(Ready())
    class Result internal constructor(internal val identity: Any, val settings: TitleModelSettings?,
        val failed: Boolean = false, val revoked: Boolean = false)
    class Work internal constructor(private val execute: () -> Result) {
        private val used = AtomicBoolean(false)
        fun runWorker(): Result { check(used.compareAndSet(false, true)); return execute() }
    }
    fun prepareLoad(): Work? = prepare { it.snapshot() }
    fun prepareDisable(): Work? = prepare { it.disable(); it.snapshot() }
    fun prepareSave(draft: TitleModelDraft): Work? = prepare {
        it.save(draft.profile(), protocolChangeAuthorized = draft.protocolChangeAuthorized,
            requestOptionsReplacementAuthorized = draft.requestOptionsReplacementAuthorized)
        it.snapshot()
    }
    internal fun prepareSelectProfile(profile: () -> TitleModelProfile, saved: () -> Unit): Work? = prepare {
        it.save(profile(), protocolChangeAuthorized = true, requestOptionsReplacementAuthorized = true)
        saved()
        it.snapshot()
    }
    private fun prepare(operation: (TitleModelSettingsStore) -> TitleModelSettings): Work? {
        while (true) {
            val before = state.get()
            if (before !is Ready) return null
            val queued = Queued(Any())
            if (state.compareAndSet(before, queued)) return Work {
                val admitted = Admitted(queued.identity)
                if (!state.compareAndSet(queued, admitted)) Result(queued.identity, null, revoked = true)
                else try {
                    try { Result(queued.identity, operation(store())) }
                    catch (_: Exception) { Result(queued.identity, null, failed = true) }
                } finally { state.compareAndSet(admitted, Ready(queued.identity)) }
            }
        }
    }
    fun revoke() { state.set(Revoked) }
    fun isCurrent(result: Result): Boolean = when (val now = state.get()) {
        is Ready -> now.identity === result.identity
        is Queued -> now.identity === result.identity
        is Admitted -> now.identity === result.identity
        Revoked -> false
    }
}
