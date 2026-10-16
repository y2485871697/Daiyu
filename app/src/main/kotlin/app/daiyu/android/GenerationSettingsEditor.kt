package app.daiyu.android

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.daiyu.android.model.generation.GenerationSettingsException
import app.daiyu.android.model.generation.GenerationSettingsFailure
import app.daiyu.android.model.generation.GenerationSettingsStore
import app.daiyu.android.ui.screens.settings.GenerationSettingsDraftUi
import app.daiyu.android.ui.screens.settings.toGenerationSettingsDraft
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** App-private settings owner, not a run/approval owner. One Page per route visit, never reused.
 * All public Page event methods and state reads belong to the UI thread. The admission predicate
 * also runs on the I/O thread: ownerCurrent must be fast, thread-safe, side-effect-free and fail closed.
 * No disk operation, waiting, or cancellation of pending work runs in revoke(). In-flight admitted saves
 * drain through the store's real readback even after disposal; their results cannot revive a Page.
 */
internal class GenerationSettingsEditor private constructor(
    storeFactory: () -> GenerationSettingsStore,
    private val ioDispatcher: CoroutineDispatcher,
    private val uiDispatcher: CoroutineDispatcher,
) {
    constructor(
        store: GenerationSettingsStore,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        uiDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    ) : this({ store }, ioDispatcher, uiDispatcher)

    constructor(context: Context) : this(
        { GenerationSettingsStore(context) }, Dispatchers.IO, Dispatchers.Main.immediate,
    )

    // Even preference/vault construction happens on the first worker, never in composition.
    private val store by lazy(LazyThreadSafetyMode.SYNCHRONIZED, storeFactory)

    fun openPage(ownerCurrent: () -> Boolean): Page = Page(ownerCurrent)

    internal inner class Page internal constructor(private val ownerCurrent: () -> Boolean) {
        private val ownerGate = Any()
        private var revoked = false
        private var started = false
        private var pending = 0
        // Not a composition/lifecycle scope: cancellation must not discard an admitted readback.
        private val workers = CoroutineScope(SupervisorJob() + ioDispatcher)

        var state by mutableStateOf(GenerationSettingsPageState())
            private set

        private fun current(): Boolean = synchronized(ownerGate) {
            if (revoked) false else {
                val accepted = try { ownerCurrent() } catch (_: Exception) { false }
                // Latch even a worker-observed departure. A later true predicate must not
                // revive a Page whose queued save was already denied admission.
                if (!accepted) revoked = true
                accepted
            }
        }

        /** Called by the store ONCE, inside its shared synchronized lock before any side effect. */
        private fun admitSave(): Boolean = current()

        fun checkOwner() {
            if (!current()) revoke()
        }

        fun start() {
            if (started) return
            started = true
            load()
        }

        /** Explicit reload discards the unsaved draft; it is never an automatic corruption repair. */
        fun reload() {
            if (state.loading || state.saving) return
            started = true
            load()
        }

        private fun load() {
            if (!current()) { revoke(); return }
            state = GenerationSettingsPageState(loading = true)
            work {
                val loaded = try {
                    val snapshot = store.loadForEditing()
                    GenerationSettingsPageState(
                        loading = false,
                        draft = snapshot.toGenerationSettingsDraft(),
                        credentialUnavailableIds = snapshot.credentialUnavailableIds,
                        incompleteSave = snapshot.incompleteSave,
                    )
                } catch (failure: GenerationSettingsException) {
                    if (failure.reason == GenerationSettingsFailure.MISSING) {
                        // This is the ONLY path allowed to synthesize an empty new draft.
                        GenerationSettingsPageState(loading = false, draft = GenerationSettingsDraftUi())
                    } else GenerationSettingsPageState(loading = false, failure = failure.reason)
                } catch (_: Exception) {
                    GenerationSettingsPageState(loading = false, failure = GenerationSettingsFailure.CORRUPT)
                }
                publish { loaded }
            }
        }

        fun edit(draft: GenerationSettingsDraftUi) {
            if (!current()) { revoke(); return }
            if (state.loading || state.saving || state.draft == null) return
            // Stable scope/revision come only from the last readback, not an event payload.
            val previous = requireNotNull(state.draft)
            state = state.copy(draft = draft.copy(expectedRevision = previous.expectedRevision,
                accountScope = previous.accountScope), failure = null, saved = false)
        }

        fun save() {
            if (!current()) { revoke(); return }
            if (state.loading || state.saving) return
            val draft = state.draft ?: return
            state = state.copy(saving = true, failure = null, saved = false)
            work {
                val result = try {
                    // Conversion and store validation are off-main too; preserve every raw field.
                    val snapshot = store.save(draft.toSettingsValues(), draft.expectedRevision,
                        draft.confirmIncompleteSaveRecovery, ::admitSave)
                    // Only this actual, verified readback can become the new editor revision.
                    GenerationSettingsPageState(loading = false,
                        draft = snapshot.toGenerationSettingsDraft(), saved = true)
                } catch (failure: GenerationSettingsException) {
                    GenerationSettingsPageState(loading = false, failure = failure.reason)
                } catch (_: IllegalArgumentException) {
                    GenerationSettingsPageState(loading = false, failure = GenerationSettingsFailure.INVALID_CONFIGURATION)
                } catch (_: Exception) {
                    GenerationSettingsPageState(loading = false, failure = GenerationSettingsFailure.SAVE_UNCONFIRMED)
                }
                publish { previous ->
                    if (result.failure == null) result
                    else previous.copy(saving = false, failure = result.failure, saved = false)
                }
            }
        }

        /** Synchronous, memory-only, terminal revocation; it never waits on the store lock. */
        fun revoke() {
            synchronized(ownerGate) { revoked = true }
            state = GenerationSettingsPageState(loading = false)
            if (pending == 0) workers.cancel()
        }

        private suspend fun publish(update: (GenerationSettingsPageState) -> GenerationSettingsPageState) {
            withContext(uiDispatcher) {
                if (current()) state = update(state) else revoke()
            }
        }

        private fun work(block: suspend () -> Unit) {
            pending++
            workers.launch {
                try { block() } finally {
                    withContext(uiDispatcher) {
                        pending--
                        if (!current()) revoke()
                    }
                }
            }
        }
    }
}

/** Memory-only Compose-observable page value. Never save/parcel/log any of its fields. */
internal data class GenerationSettingsPageState(
    val loading: Boolean = true,
    val saving: Boolean = false,
    val draft: GenerationSettingsDraftUi? = null,
    val credentialUnavailableIds: Set<String> = emptySet(),
    val incompleteSave: Boolean = false,
    val failure: GenerationSettingsFailure? = null,
    val saved: Boolean = false,
) {
    // Deliberately create text from the enum, NEVER Throwable.message/toString or raw input.
    val errorText: String? get() = failure?.let { GenerationSettingsException(it).message }
    override fun toString() = "GenerationSettingsPageState(<redacted>)"
}
