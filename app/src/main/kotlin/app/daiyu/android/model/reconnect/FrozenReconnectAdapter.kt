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

/** One immutable run-local preference/capability decision for the CURRENT production models.
 * No Context/store/port/session is retained or re-read by this object. Normal sends are allowed;
 * a retryable failure explicitly explains why automatic recovery was unavailable. No retry loop,
 * attempt/callback gate or tool execution is implemented here. The optional observer delegates
 * original opens exactly once; it never opens a recovery request. */
class FrozenReconnectAdapter private constructor(
    val settings: ReconnectSettings,
    val capability: ReconnectCapability,
) {
    /** Retained total window from the FIRST failure, null = continuous, 0 = NONE.
     * It must NOT be assigned to ReconnectPolicy.delayMillis or converted into attempt counts. */
    val windowMillis: Long? = settings.duration.windowMillis

    /** Production Responses/Messages do not implement ResumableModelSession. Keep Runner's
     * compatible default even if the user requested reconnect; NEVER infer resume from protocol. */
    fun runnerPolicy(): ReconnectPolicy = ReconnectPolicy()

    /** Append to the original fixed model failure message; do not replace auth/protocol errors
     * with a misleading reconnect error, and never include a server body or exception message.
     * This describes a failure only; it does not authorize a new request. */
    fun unavailableMessage(failure: ModelFailure): String? =
        if (settings.enabled && failure in setOf(ModelFailure.NETWORK, ModelFailure.TIMEOUT))
            capability.userMessage else null

    /** Optional identity-scoped diagnostic bridge. Delegates every open/next/cancel EXACTLY once
     * and rethrows the original failure, preserving Runner classification/terminal/checkpoints.
     * The host must gate onUnavailable by the original run owner; callback failure is ignored.
     * This wrapper deliberately does NOT implement ResumableModelSession for current transports. */
    fun observeFailures(port: ModelPort, onUnavailable: (String) -> Unit): ModelPort {
        fun report(failure: ModelRequestException) {
            unavailableMessage(failure.failure)?.let { message ->
                try { onUnavailable(message) } catch (_: Exception) { /* observer is not recovery */ }
            }
        }
        fun session(open: () -> ModelSession): ModelSession {
            val retained = try { open() } catch (failure: ModelRequestException) { report(failure); throw failure }
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
        /** Call once with the exact run's frozen provider protocol, not a later active setting. */
        fun freeze(settings: ReconnectSettings, protocol: ProviderProtocol): FrozenReconnectAdapter =
            FrozenReconnectAdapter(settings.copy(), ReconnectCapabilities.forProtocol(protocol))
    }
}
