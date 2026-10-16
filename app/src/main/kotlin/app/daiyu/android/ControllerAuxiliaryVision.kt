package app.daiyu.android

import app.daiyu.android.domain.*
import app.daiyu.android.media.MessageImageResolver
import app.daiyu.android.model.auxiliary.AuthorizedVisionObservation
import app.daiyu.android.model.auxiliary.AuxiliaryModelCandidate
import app.daiyu.android.model.auxiliary.AuxiliaryModelSelection
import app.daiyu.android.model.auxiliary.AuxiliaryVisionBridge
import app.daiyu.android.model.auxiliary.AuxiliaryVisionModelAdapter
import app.daiyu.android.model.images.ImageInputEncoder
import java.net.URL
import java.util.Collections
import java.util.concurrent.CancellationException
import javax.net.ssl.HttpsURLConnection

/**
 * Opt-in, one-parent-run ModelPort decorator. Not wired into ChatController yet.
 *
 * [authorizeHistory] receives a detached, UNEXPANDED snapshot on EVERY open, even a cache hit or
 * text-only request. The host must batch-check these original persisted owners/segments against
 * its trusted store, then return one authorization per image in MessageHistory.expand order.
 * Observation IDs and sourceRunId must come from those authorized original rows, not the current
 * parent run, tool JSON, an asset path, or an image's self-asserted owner. This class additionally
 * checks all returned identities before the bridge may resolve bytes or contact a provider.
 *
 * Only request projections cross into [main]; nothing here saves or edits messages/assets.
 * No fallback, HTTP retry, or transport-resume capability is introduced. A failure seals this
 * instance; creating a fresh run is a separate explicit host decision, not recovery/retry.
 */
internal class ControllerAuxiliaryVision private constructor(
    private val main: ModelPort,
    private val bridge: AuxiliaryVisionBridge,
    private val auxiliary: AuxiliaryVisionModelAdapter,
    private val authorizeHistory: (List<Message>) -> List<AuthorizedVisionObservation>,
) : ModelPort, AutoCloseable {
    private val lifecycle = Any()
    private var cancelled = false
    private var failed = false
    private var inFlight = false
    private val sessions = mutableSetOf<ModelSession>()

    override fun open(history: List<Message>): ModelSession = openProjected(history) { main.open(it) }

    override fun open(history: List<Message>, tools: Set<ReadOnlyTool>): ModelSession {
        val frozenTools = Collections.unmodifiableSet(tools.toSet())
        return openProjected(history) { main.open(it, frozenTools) }
    }

    override fun open(history: List<Message>, definitions: List<ToolDefinition>): ModelSession {
        val frozenDefinitions = immutable(definitions)
        return openProjected(history) { main.open(it, frozenDefinitions) }
    }

    // The fourth (frozen-image) overload intentionally retains ModelPort's fail-closed default.
    // This decorator accepts raw persisted rows, not a pre-authorized plan or already projected input.
    private fun openProjected(history: List<Message>, openMain: (List<Message>) -> ModelSession): ModelSession {
        begin()
        var opened: ModelSession? = null
        try {
            val raw = immutable(history.map { row ->
                row.copy(blocks = immutable(row.blocks), protocolItems = immutable(row.protocolItems),
                    historySegments = immutable(row.historySegments.map { segment ->
                        segment.copy(blocks = immutable(segment.blocks), protocolItems = immutable(segment.protocolItems))
                    }))
            })
            // Authorization must precede expansion, bridge preparation, cache lookup and ALL IO.
            val authorized = authorizeHistory(raw).toList()
            checkUsable()
            val images = MessageHistory.expand(raw).flatMap { row ->
                row.blocks.filterIsInstance<MessageBlock.Image>().map { row to it }
            }
            require(authorized.size == images.size) { "Incomplete auxiliary history authorization" }
            images.zip(authorized).forEach { (entry, identity) ->
                val (row, image) = entry
                require(identity.sourceRunId == row.runId && identity.image == image.reference &&
                    identity.image.owner.conversationId == row.conversationId.value &&
                    identity.image.owner.messageId == row.id.value) { "Auxiliary history authorization mismatch" }
            }
            var index = 0
            val projection = bridge.prepare(raw, { _, _ -> authorized[index++] }, ::isCancelled).history
            checkUsable()
            // Never hold lifecycle while an external callback/transport may block. Cancellation
            // seals the run immediately, and a session returned late is cancelled, never published.
            val session = openMain(projection)
            opened = session
            synchronized(lifecycle) {
                checkUsable()
                sessions.add(session)
            }
            return GuardedSession(session)
        } catch (failure: Throwable) {
            opened?.let { runCatching { it.cancel() } }
            sealFailure()
            throw cancellationOr(failure)
        } finally {
            synchronized(lifecycle) { inFlight = false }
        }
    }

    private inner class GuardedSession(private val delegate: ModelSession) : ModelSession {
        override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
            begin()
            try {
                delegate.next(outputs) { event -> checkUsable(); sink(event) }
                checkUsable()
            } catch (failure: Throwable) {
                sealFailure()
                throw cancellationOr(failure)
            } finally {
                synchronized(lifecycle) { inFlight = false }
            }
        }
        override fun cancel() = this@ControllerAuxiliaryVision.cancel()
    }

    private fun begin() = synchronized(lifecycle) {
        checkUsable()
        check(!inFlight) { "Auxiliary parent run already has an operation in progress" }
        inFlight = true
    }

    private fun isCancelled(): Boolean = synchronized(lifecycle) { cancelled } || Thread.currentThread().isInterrupted

    private fun checkUsable() = synchronized(lifecycle) {
        if (isCancelled()) throw CancellationException("Auxiliary parent run cancelled")
        check(!failed) { "Auxiliary parent run failed; automatic retry is disabled" }
    }

    private fun cancellationOr(failure: Throwable): Throwable =
        if (isCancelled() && failure !is CancellationException)
            CancellationException("Auxiliary parent run cancelled").also { it.initCause(failure) }
        else failure

    private fun sealFailure() {
        val active = synchronized(lifecycle) { failed = true; sessions.toList().also { sessions.clear() } }
        runCatching { auxiliary.cancel() }
        active.forEach { runCatching { it.cancel() } }
    }

    /** Sticky stop request, NOT a drain/join/permission to evict parent-run state. Also valid during
     * a blocked open: late sessions are cancelled upon return. The parent owns worker completion.
     * A transport's cancel is expected to be nonblocking; it is never called under our monitor. */
    fun cancel() {
        val active = synchronized(lifecycle) { cancelled = true; sessions.toList().also { sessions.clear() } }
        runCatching { auxiliary.cancel() }
        active.forEach { runCatching { it.cancel() } }
    }

    override fun close() = cancel()

    companion object {
        /**
         * Resolve exactly once against host-frozen registry records. The host must supply actual
         * providerId/modelId records (with that record's credentials, protocol, endpoint, API model
         * and verified capability), never a fabricated singleton derived from the main model.
         * Missing, duplicate, disabled, unknown-capability or unsupported bindings fail closed.
         * Call only on the explicitly enabled auxiliary route; disabled is not a fallback route.
         * [connect] and [encoder] permit no-network tests of the same production adapter path.
         */
        fun create(
            main: ModelPort,
            selection: AuxiliaryModelSelection,
            frozenCandidates: List<AuxiliaryModelCandidate>,
            resolver: MessageImageResolver,
            authorizeHistory: (List<Message>) -> List<AuthorizedVisionObservation>,
            connect: (URL) -> HttpsURLConnection = { it.openConnection() as HttpsURLConnection },
            encoder: ImageInputEncoder = ImageInputEncoder(),
        ): ControllerAuxiliaryVision {
            val binding = selection.copy().resolve(frozenCandidates.toList())
            val adapter = AuxiliaryVisionModelAdapter(binding, connect)
            return ControllerAuxiliaryVision(main, AuxiliaryVisionBridge(binding, resolver, adapter, encoder),
                adapter, authorizeHistory)
        }

        private fun <T> immutable(items: List<T>): List<T> = Collections.unmodifiableList(items.toList())
    }
}
