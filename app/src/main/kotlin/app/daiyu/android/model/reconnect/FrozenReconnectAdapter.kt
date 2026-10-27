package app.daiyu.android.model.reconnect

import app.daiyu.android.domain.ModelFailure
import app.daiyu.android.domain.ModelEvent
import app.daiyu.android.domain.ModelImagePlan
import app.daiyu.android.domain.ModelPort
import app.daiyu.android.domain.ModelRequestException
import app.daiyu.android.domain.ModelSession
import app.daiyu.android.domain.ReadOnlyTool
import app.daiyu.android.domain.Message
import app.daiyu.android.domain.ToolDefinition
import app.daiyu.android.domain.ToolOutput
import app.daiyu.android.domain.runtime.ReconnectPolicy
import app.daiyu.android.model.ProviderProtocol

/** One immutable run-local preference/capability decision. Only the snapshot overload may
 * authorize official Responses background streams. No preference re-read, open/repost recovery,
 * attempt gate or tool execution lives here; Runner owns attempts and the original session. */
class FrozenReconnectAdapter private constructor(
    val settings: ReconnectSettings,
    val capability: ReconnectCapability,
    val responsesBackground: FrozenResponsesBackground? = null,
) {
    /** Retained total window from the FIRST failure, null = continuous, 0 = NONE.
     * It must NOT be assigned to ReconnectPolicy.delayMillis or converted into attempt counts. */
    val windowMillis: Long? = settings.duration.windowMillis

    /** All supported protocols share a retained total window, not a fixed attempt count. */
    fun runnerPolicy(): ReconnectPolicy = runnerPolicyWithDiagnostics {}

    internal fun runnerPolicyWithDiagnostics(emit: (ReconnectChanged) -> Unit): ReconnectPolicy {
        if (!settings.enabled || !capability.supported) return ReconnectPolicy()
        return ReconnectPolicy(delayMillis = 1_000, failures = setOf(ModelFailure.NETWORK, ModelFailure.TIMEOUT, ModelFailure.SERVER, ModelFailure.RATE_LIMIT, ModelFailure.AUTHENTICATION), budgetFactory = app.daiyu.android.domain.runtime.TransportReconnectBudgetFactory { round, reason, diagnostic ->
            val retained = ModelErrorReconnect(round, windowMillis, SystemReconnectTiming, emit, reason, diagnostic)
            retained.start()
            object : app.daiyu.android.domain.runtime.TransportReconnectBudget {
                override fun updateReason(reason: ModelFailure, diagnostic: app.daiyu.android.domain.ModelRequestDiagnostic?) = retained.updateReason(reason, diagnostic)
                override fun check() = retained.check()
                override fun setPaused(paused: Boolean, revision: Long) = retained.setPaused(paused, revision)
                override fun attach(disconnect: () -> Unit): AutoCloseable {
                    val scope = ReconnectTransportScope(disconnect)
                    retained.attach(scope)
                    return AutoCloseable { retained.detach(scope) }
                }
                override fun finish(status: String) = retained.finish(status)
            }
        })
    }

    /** Append to the original fixed model failure message; do not replace auth/protocol errors
     * with a misleading reconnect error, and never include a server body or exception message.
     * This describes a failure only; it does not authorize a new request. */
    fun unavailableMessage(failure: ModelFailure): String? =
        if (settings.enabled && !capability.supported && failure in setOf(ModelFailure.NETWORK, ModelFailure.TIMEOUT))
            capability.userMessage else null

    /** Optional identity-scoped diagnostic bridge. Delegates every open/next/cancel EXACTLY once
     * and rethrows the original failure, preserving Runner classification/terminal/checkpoints.
     * The host must gate onUnavailable by the original run owner; callback failure is ignored.
     * Approved checkpointed sessions retain their exact capability; ordinary transports do not. */
    fun observeFailures(port: ModelPort, onUnavailable: (String) -> Unit): ModelPort {
        fun report(failure: ModelRequestException) {
            unavailableMessage(failure.failure)?.let { message ->
                try { onUnavailable(message) } catch (_: Exception) { /* observer is not recovery */ }
            }
        }
        fun session(open: () -> ModelSession): ModelSession {
            val retained = try { open() } catch (failure: ModelRequestException) { report(failure); throw failure }
            if (settings.enabled && (retained is app.daiyu.android.domain.RequestRetryModelSession ||
                (responsesBackground != null && retained is app.daiyu.android.domain.CheckpointedResumableModelSession)))
                return retained
            return object : ModelSession {
                override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                    try { retained.next(outputs, sink) }
                    catch (failure: ModelRequestException) { report(failure); throw failure }
                }
                override fun cancel() = retained.cancel()
            }
        }
        return object : ModelPort {
            override fun open(history: List<Message>): ModelSession = session { port.open(history) }
            override fun open(history: List<Message>, tools: Set<ReadOnlyTool>): ModelSession =
                session { port.open(history, tools) }
            override fun open(history: List<Message>, definitions: List<ToolDefinition>): ModelSession =
                session { port.open(history, definitions) }
            override fun open(history: List<Message>, definitions: List<ToolDefinition>, images: ModelImagePlan): ModelSession =
                session { port.open(history, definitions, images) }
        }
    }

    /** Optional explicit validation (e.g. before enabling a feature in a capability-aware host).
     * Do not use it to open a replacement session or to silently change the selected model. */
    fun requireSupported() {
        if (settings.enabled && !capability.supported) throw ReconnectUnavailableException(capability)
    }

    companion object {
        /** Freeze once before assembling model/media wrappers. Only this overload authorizes background. */
        fun freeze(settings: ReconnectSettings, provider: app.daiyu.android.model.ProviderSnapshot): FrozenReconnectAdapter {
            val background = FrozenResponsesBackground.freeze(settings, provider)
            return FrozenReconnectAdapter(settings.copy(), if (background != null) ReconnectCapability.TRANSPORT_RESUME
                else ReconnectCapabilities.forProtocol(provider.protocol), background)
        }
        /** Call once with the exact run's frozen provider protocol, not a later active setting. */
        fun freeze(settings: ReconnectSettings, protocol: ProviderProtocol): FrozenReconnectAdapter =
            FrozenReconnectAdapter(settings.copy(), ReconnectCapabilities.forProtocol(protocol))
    }
}
