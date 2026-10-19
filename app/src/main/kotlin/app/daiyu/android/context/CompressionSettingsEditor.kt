package app.daiyu.android.context

import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** A single page's non-visual owner. It owns neither an executor nor a main-thread dispatcher.
 * Queue [Work.runWorker] on the parent's worker, and check [isCurrent] on the UI thread before
 * consuming its result. A revoked owner is never reusable, including when a save is in progress.
 */
class CompressionSettingsEditor internal constructor(
    private val compressionStore: () -> ProductionCompressionSettingsStore,
    private val profileStore: () -> SummaryProfileStore,
) {
    /** No preferences or credentials are opened until a worker has won admission. */
    constructor(context: Context) : this(
        compressionStore = { ProductionCompressionSettingsStore(context.applicationContext) },
        profileStore = { SummaryProfileStore(context.applicationContext) },
    )

    /** All fields are explicit immutable values; no main-model lookup or budget inference.
     * The parent creates a new binding once per new draft, or preserves the loaded binding.
     * Authorization flags must represent explicit user decisions, not simply a Save click.
     */
    data class SaveRequest(
        val settings: ProductionCompressionSettings,
        val profile: SummaryProfileSettings? = null,
        val protocolChangeAuthorized: Boolean = false,
        val requestOptionsReplacementAuthorized: Boolean = false,
    )

    data class Snapshot(
        val settings: ProductionCompressionSettings,
        val profile: SummaryProfileSettings?,
        /** Load can still show/edit the compression settings when the selected profile is broken. */
        val profileUnavailableReason: SummaryProfileUnavailableReason? = null,
    )

    enum class Stage { LOAD_SETTINGS, LOAD_PROFILE, VALIDATE_SAVE, SAVE_PROFILE, SAVE_SETTINGS, READ_BACK }

    sealed interface Outcome {
        data class Loaded(val snapshot: Snapshot) : Outcome
        data class Saved(val snapshot: Snapshot) : Outcome
        /** No exception/message is retained: providers may include credentials in failures.
         * Saves are not a transaction across both stores. A failed save must never be presented
         * as success, rolled back implicitly, or automatically retried.
         */
        data class Failed(
            val stage: Stage,
            val writesMayHaveOccurred: Boolean,
            val profileUnavailableReason: SummaryProfileUnavailableReason? = null,
        ) : Outcome
        data object Revoked : Outcome
    }

    /** Opaque page AND operation identity; identity comparison is intentional. */
    class Identity internal constructor(internal val owner: Any)
    class Result internal constructor(val identity: Identity, val outcome: Outcome)

    /** A queued, one-shot operation, not yet permission to access persistence. */
    class Work internal constructor(val identity: Identity, private val execute: () -> Result) {
        private val consumed = AtomicBoolean(false)
        fun runWorker(): Result {
            check(consumed.compareAndSet(false, true)) { "设置任务只能执行一次" }
            return execute()
        }
    }

    private sealed interface State
    private class Ready(val identity: Identity? = null) : State
    private class Queued(val identity: Identity) : State
    private class Admitted(val identity: Identity) : State
    private data object Revoked : State

    private val ownerIdentity = Any()
    private val state = AtomicReference<State>(Ready())

    /** Null means busy or permanently revoked. Disable competing actions while work is pending. */
    fun prepareLoad(): Work? = prepare(null)
    fun prepareSave(request: SaveRequest): Work? = prepare(request)

    /** Selection is durable even while the existing backend has no capacity record for the model. */
    internal fun prepareSelection(settings: ProductionCompressionSettings, selected: () -> Unit): Work? =
        prepare(SaveRequest(settings), selected)

    private fun prepare(request: SaveRequest?, selected: (() -> Unit)? = null): Work? {
        while (true) {
            val before = state.get()
            if (before !is Ready) return null
            val queued = Queued(Identity(ownerIdentity))
            if (state.compareAndSet(before, queued)) {
                return Work(queued.identity) { runWorker(queued, request, selected) }
            }
        }
    }

    /** Linearizes with worker admission. Already admitted work finishes, including read-back;
     * queued work can never touch a store after this wins. No blocking/joining on the UI thread.
     */
    fun revoke() {
        while (true) {
            val before = state.get()
            if (before === Revoked || state.compareAndSet(before, Revoked)) return
        }
    }

    /** Invoke at result consumption time, not before posting a callback to the UI thread.
     * Also rejects an older completed operation once newer work has been prepared on this page.
     */
    fun isCurrent(result: Result): Boolean {
        if (result.identity.owner !== ownerIdentity) return false
        return when (val current = state.get()) {
            is Ready -> current.identity === result.identity
            is Queued -> current.identity === result.identity
            is Admitted -> current.identity === result.identity
            Revoked -> false
        }
    }

    private fun runWorker(queued: Queued, request: SaveRequest?, selected: (() -> Unit)?): Result {
        val admitted = Admitted(queued.identity)
        if (!state.compareAndSet(queued, admitted)) return Result(queued.identity, Outcome.Revoked)
        val outcome = try {
            if (selected != null) {
                try { selected() } catch (_: Exception) {
                    return Result(queued.identity, Outcome.Failed(Stage.VALIDATE_SAVE, false))
                }
            }
            if (request == null) load() else save(request, selectionOnly = selected != null)
        } finally {
            // In particular, never resurrect an owner revoked during admitted I/O.
            state.compareAndSet(admitted, Ready(queued.identity))
        }
        return Result(queued.identity, outcome)
    }

    private fun load(): Outcome {
        val settings = try { compressionStore().snapshot() }
        catch (_: Exception) { return Outcome.Failed(Stage.LOAD_SETTINGS, false) }
        val binding = settings.summaryModel ?: return Outcome.Loaded(Snapshot(settings, null))
        return try {
            Outcome.Loaded(Snapshot(settings, profileStore().load(binding)))
        } catch (failure: SummaryProfileUnavailableException) {
            Outcome.Loaded(Snapshot(settings, null, failure.reason))
        } catch (_: Exception) {
            Outcome.Failed(Stage.LOAD_PROFILE, false)
        }
    }

    private fun save(request: SaveRequest, selectionOnly: Boolean = false): Outcome {
        // Only relationship validation belongs here. All numeric/protocol/tool validation is
        // performed by the existing settings/profile types and stores, not a parallel policy.
        if (request.profile != null && request.settings.summaryModel != request.profile.binding) {
            return Outcome.Failed(Stage.VALIDATE_SAVE, false)
        }
        if (request.profile != null) {
            try {
                profileStore().save(request.profile, request.protocolChangeAuthorized,
                    request.requestOptionsReplacementAuthorized)
            } catch (failure: Exception) {
                return failed(Stage.SAVE_PROFILE, failure)
            }
        }
        try { compressionStore().save(request.settings) }
        catch (failure: Exception) { return failed(Stage.SAVE_SETTINGS, failure) }

        // Do not report success from an optimistic draft or a successful commit alone. Read both
        // persisted selections back, preserving store fencing for unknown/partial writes.
        return try {
            val settings = compressionStore().snapshot()
            check(settings == request.settings)
            val profile = try { settings.summaryModel?.let { profileStore().load(it) } }
            catch (failure: SummaryProfileUnavailableException) {
                if (!selectionOnly) throw failure
                return Outcome.Saved(Snapshot(settings, null, failure.reason))
            }
            if (request.profile != null) check(profile == request.profile)
            Outcome.Saved(Snapshot(settings, profile))
        } catch (failure: Exception) {
            failed(Stage.READ_BACK, failure)
        }
    }

    private fun failed(stage: Stage, failure: Exception) = Outcome.Failed(
        stage = stage,
        writesMayHaveOccurred = true,
        profileUnavailableReason = (failure as? SummaryProfileUnavailableException)?.reason,
    )
}
