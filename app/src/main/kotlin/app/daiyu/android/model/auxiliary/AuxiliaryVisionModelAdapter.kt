package app.daiyu.android.model.auxiliary

import app.daiyu.android.domain.*
import app.daiyu.android.model.MessagesModel
import app.daiyu.android.model.ProviderProtocol
import app.daiyu.android.model.ResponsesModel
import org.json.JSONObject
import java.net.URL
import java.util.concurrent.CancellationException
import javax.net.ssl.HttpsURLConnection

/**
 * Run-local, blocking adapter from the bridge's authorized request to the real ModelPort transports.
 * Pass this instance as AuxiliaryVisionBridge's describe function. The host MUST call cancel/close
 * on parent cancellation (including while next is blocked), and close when the parent run ends.
 * A cancellation predicate or a thread interrupt alone cannot disconnect a blocked HTTP read.
 *
 * No registry/settings are read here, no tools are advertised or executed, and no request is retried.
 * Custom request bodies are rejected rather than silently stripped: they can override the frozen
 * API model or system policy. The native instructions/system field is reserved for the bridge.
 *
 * ModelSession has no close method. Closing this adapter cancels its actual active ModelSession;
 * each call also cancels in finally. The real transports own and close their streams/connections
 * in next's finally. This is a component, not a production host integration or a drain guarantee.
 */
internal class AuxiliaryVisionModelAdapter(
    private val binding: AuxiliaryModelBinding,
    private val connect: (URL) -> HttpsURLConnection = { it.openConnection() as HttpsURLConnection },
    private val beginUsage: (() -> app.daiyu.android.model.usage.AuxiliaryUsageAttempt)? = null,
) : (AuxiliaryVisionRequest) -> String, AutoCloseable {
    private val lifecycle = Any()
    private var closed = false
    private var active: ModelSession? = null
    private var activeUsage: app.daiyu.android.model.usage.AuxiliaryUsageAttempt? = null

    init {
        require(binding.snapshot.imageInputSupported == true) { "Auxiliary binding cannot read images" }
        require(binding.snapshot.requestOptions.customBody.isEmpty()) {
            "Auxiliary vision does not allow custom request bodies"
        }
        require(binding.snapshot.protocol in setOf(ProviderProtocol.RESPONSES, ProviderProtocol.MESSAGES)) {
            "Unsupported auxiliary vision protocol"
        }
    }

    fun isCancelled(): Boolean = synchronized(lifecycle) { closed } || Thread.currentThread().isInterrupted

    private fun checkCancelled() {
        if (isCancelled()) throw CancellationException("Auxiliary vision cancelled")
    }

    override fun invoke(request: AuxiliaryVisionRequest): String {
        checkCancelled()
        // Object identity matters: an equivalent record from another run is not this authorization.
        require(request.binding === binding) { "Auxiliary request has a different frozen binding" }
        require(request.instructions.isNotBlank()) { "Auxiliary system instructions are required" }
        val history = request.history.map { it.copy(blocks = it.blocks.toList(), protocolItems = it.protocolItems.toList()) }
        val row = history.single()
        val observation = request.observation
        require(row.role == Message.USER_ROLE && row.protocolItems.isEmpty() && row.historySegments.isEmpty() &&
            row.blocks.all { it is MessageBlock.Text || it is MessageBlock.Image }) {
            "Auxiliary input must be one explicit user observation"
        }
        require(row.runId == observation.sourceRunId &&
            row.conversationId.value == observation.image.owner.conversationId &&
            row.id.value == observation.image.owner.messageId &&
            row.blocks.filterIsInstance<MessageBlock.Image>().map { it.reference } == listOf(observation.image)) {
            "Auxiliary observation identity mismatch"
        }
        val snapshot = binding.snapshot
        val settings = snapshot.withoutNativeSearch().asModelSettings()
        require(request.images.hasImages) { "Auxiliary input requires a frozen image" }
        request.images.validate(settings, snapshot.protocol, snapshot.imageInputSupported, history)

        val session = synchronized(lifecycle) {
            checkCancelled()
            check(active == null) { "Auxiliary vision request already in progress" }
            val port: ModelPort = when (snapshot.protocol) {
                ProviderProtocol.RESPONSES -> ResponsesModel(settings, connect, snapshot.imageInputSupported, request.instructions)
                ProviderProtocol.MESSAGES -> MessagesModel(settings, 4096, connect, snapshot.imageInputSupported, request.instructions)
                else -> error("Unsupported auxiliary vision protocol")
            }
            // Never use open(history): it would inherit the provider's enabled tools.
            val usage = beginUsage?.invoke()
            activeUsage = usage
            try { port.open(history, emptyList<ToolDefinition>(), request.images).also { active = it } }
            catch (failure: Throwable) { usage?.close(); activeUsage = null; throw failure }
        }
        try {
            val text = linkedMapOf<String, String>()
            var terminalText: String? = null
            fun record(id: String, value: String, final: Boolean) {
                require(id.isNotBlank()) { "Auxiliary text item has no identity" }
                val updated = if (final) value else text.getOrDefault(id, "") + value
                require(updated.length <= MAX_DESCRIPTION_CHARS &&
                    text.filterKeys { it != id }.values.sumOf { it.length } + updated.length <= MAX_DESCRIPTION_CHARS) {
                    "Auxiliary description is too large"
                }
                text[id] = updated
            }
            checkCancelled()
            session.next(emptyList()) { event ->
                checkCancelled()
                when (event) {
                    is ModelEvent.ToolReady -> throw ModelRequestException(ModelFailure.PROTOCOL)
                    is ModelEvent.TextDelta -> record(event.itemId, event.text, false)
                    is ModelEvent.TextFinal -> record(event.itemId, event.text, true)
                    is ModelEvent.ProtocolOutput -> {
                        check(terminalText == null) { "Duplicate auxiliary terminal output" }
                        terminalText = completedText(event.items)
                    }
                    is ModelEvent.Usage -> synchronized(lifecycle) { checkCancelled(); activeUsage?.observe(event) }
                    is ModelEvent.ReasoningDelta, is ModelEvent.ReasoningFinal -> Unit
                }
            }
            // A TextFinal can itself be a partial stream snapshot (notably Responses). Only a
            // successful return from next establishes a protocol terminal; never return from sink.
            return synchronized(lifecycle) {
                checkCancelled()
                val result = terminalText?.trim()
                require(!result.isNullOrEmpty() && result.length <= MAX_DESCRIPTION_CHARS) {
                    "Auxiliary vision did not return a complete bounded description"
                }
                result
            }
        } catch (failure: Exception) {
            if (failure is InterruptedException || isCancelled()) {
                throw CancellationException("Auxiliary vision cancelled").also { it.initCause(failure) }
            }
            throw failure
        } finally {
            // Real session cleanup, including sink failures, partial output and cancellation.
            try { session.cancel() } finally {
                synchronized(lifecycle) {
                    if (active === session) {
                        active = null
                        activeUsage?.close()
                        activeUsage = null
                    }
                }
            }
        }
    }

    /**
     * Read only the transport's confirmed output, not provisional TextFinal snapshots. Responses
     * emits TextFinal even for deltas, including IDs absent from response.completed.output.
     * These envelopes come from our fresh session's first/only next, never stored replay history.
     */
    private fun completedText(items: List<String>): String {
        val parts = mutableListOf<String>()
        fun add(value: String) {
            require(value.length <= MAX_DESCRIPTION_CHARS &&
                parts.sumOf { it.length + 1 } + value.length <= MAX_DESCRIPTION_CHARS) {
                "Auxiliary description is too large"
            }
            parts.add(value)
        }
        when (binding.snapshot.protocol) {
            ProviderProtocol.RESPONSES -> items.forEach { raw ->
                val item = JSONObject(raw)
                when (item.getString("type")) {
                    "reasoning" -> Unit
                    "message" -> {
                        require(item.getString("role") == Message.ASSISTANT_ROLE &&
                            item.optString("status", "completed") == "completed") {
                            "Auxiliary response message is not complete"
                        }
                        val content = item.getJSONArray("content")
                        for (index in 0 until content.length()) {
                            val part = content.getJSONObject(index)
                            when (part.getString("type")) {
                                "output_text" -> add(part.getString("text"))
                                "refusal" -> add(part.getString("refusal"))
                                else -> throw ModelRequestException(ModelFailure.PROTOCOL)
                            }
                        }
                    }
                    else -> throw ModelRequestException(ModelFailure.PROTOCOL)
                }
            }
            ProviderProtocol.MESSAGES -> {
                val item = JSONObject(items.single())
                require(item.getString("role") == Message.ASSISTANT_ROLE) { "Auxiliary response role mismatch" }
                val content = item.getJSONArray("content")
                for (index in 0 until content.length()) {
                    val part = content.getJSONObject(index)
                    if (part.getString("type") != "text") throw ModelRequestException(ModelFailure.PROTOCOL)
                    add(part.getString("text"))
                }
            }
            else -> error("Unsupported auxiliary vision protocol")
        }
        return parts.joinToString("\n")
    }

    /** Sticky for this parent run. Safe to call from a different thread while next blocks. */
    fun cancel() {
        val (session, usage) = synchronized(lifecycle) {
            closed = true
            (active to activeUsage).also { activeUsage = null }
        }
        // A failed durable close must never prevent disconnecting a blocked transport.
        try { usage?.close() } finally { session?.cancel() }
    }

    override fun close() = cancel()

    private companion object {
        const val MAX_DESCRIPTION_CHARS = 16_000
    }
}
