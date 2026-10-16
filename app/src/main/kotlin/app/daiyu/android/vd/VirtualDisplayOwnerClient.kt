package app.daiyu.android.vd

import app.daiyu.android.vd.runtime.OwnerProtocol
import app.daiyu.android.vd.runtime.OwnerV2Contract
import org.json.JSONObject

/** Host-only identity. Supply from authenticated run/journal state, NEVER model arguments. */
class OwnerRunBinding(session: String, task: String, conversation: String, run: String,
                      assistantMessage: String, runOwner: String) {
    private val values = listOf(session, task, conversation, run, assistantMessage, runOwner)
    internal val trusted = safeInput { OwnerV2Contract.binding(values) }
    internal fun json(): JSONObject = JSONObject().apply {
        listOf("session", "task", "conversation", "run", "assistantMessage", "runOwner")
            .zip(values).forEach { (key, value) -> put(key, value) }
    }
    override fun toString() = "OwnerRunBinding(host-only)"
}

/** No raw transport/JSON exception or chained cause can carry a private bootstrap token. */
class OwnerClientException internal constructor() : Exception("owner request invalid")
internal inline fun <T> safeInput(block: () -> T): T = try { block() } catch (_: Exception) {
    throw OwnerClientException()
}

/** Results are host-only, not a model/tool API. Receipt-only success is NOT execution success. */
class OwnerReply internal constructor(
    val outcome: Outcome,
    private val body: JSONObject? = null,
) {
    enum class Outcome { REPLIED, UNKNOWN }
    fun payloadCopy(): JSONObject? = body?.let { JSONObject(it.toString()) }
    override fun toString() = "OwnerReply($outcome)"
}

internal interface OwnerTransport {
    /** Exactly one write and one bounded response read; never reconnects or retries. */
    fun exchange(line: String): String
    fun disconnect()
}

/**
 * Synchronous worker-only v2 client. All operation IDs/generation/binding are supplied by the
 * trusted parent. Constructing the client does nothing; initialize is explicit and one-shot.
 * A failed/ambiguous exchange poisons this connection. An explicit host reconnect is lookup-only
 * until the parent deliberately chooses another call; there is no replay, generated ID or cleanup.
 */
class VirtualDisplayOwnerClient internal constructor(
    val generation: String,
    private val binding: OwnerRunBinding,
    private val credential: OwnerCredential,
    private val transport: OwnerTransport,
) {
    private var disconnected = false
    private var initializeSent = false
    private val sentIds = mutableSetOf<String>()

    init { safeInput { require(ID.matches(generation)) } }

    @Synchronized fun initialize(): OwnerReply {
        checkWorker()
        if (initializeSent) throw OwnerClientException()
        initializeSent = true
        return request("initialize", null, null, false)
    }

    /** This method sends ONLY receipt, even before initialize; it cannot create a display. */
    @Synchronized fun lookupGeneration(): OwnerReply {
        checkWorker()
        return request("receipt", null, null, true)
    }

    /** Parameters are detached/default-completed with the actual Java OwnerProtocol. */
    @Synchronized fun lookup(operationId: String, operation: String, parameters: JSONObject): OwnerReply {
        checkWorker()
        val args = arguments(operationId, operation, parameters)
        return request(operation, operationId, args, true)
    }

    @Synchronized fun execute(operationId: String, operation: String, parameters: JSONObject): OwnerReply {
        checkWorker()
        val args = arguments(operationId, operation, parameters)
        // Even an uncertain send consumes the local permit. Same-ID inspection is lookup only.
        if (!sentIds.add(operationId)) throw OwnerClientException()
        return request(operation, operationId, args, false)
    }

    private fun arguments(id: String, operation: String, input: JSONObject): JSONObject = safeInput {
        require(ID.matches(id) && operation in BUSINESS)
        OwnerV2Contract.normalize(operation, input)
    }

    private fun request(operation: String, id: String?, parameters: JSONObject?, lookup: Boolean): OwnerReply {
        if (disconnected) return OwnerReply(OwnerReply.Outcome.UNKNOWN)
        val wireOp = if (lookup) "receipt" else operation
        val envelope = JSONObject().put("v", 2).put("op", wireOp).put("generation", generation)
        if (lookup || operation == "initialize") envelope.put("binding", binding.json())
        if (id != null) {
            envelope.put("operationId", id).put("parameters", parameters)
            if (lookup) envelope.put("operation", operation)
        }
        val digest = if (id == null) OwnerV2Contract.generationDigest(generation, binding.trusted)
            else OwnerV2Contract.identity(generation, id, operation, binding.trusted, parameters!!)
        return try {
            credential.authorize(envelope)
            val line = envelope.toString()
            // Enforce the complete strict v2 envelope, not just the input parameter object.
            OwnerProtocol.parseWireRequest(line)
            val response = transport.exchange(line + "\n")
            credential.rejectEcho(response)
            val body = OwnerResponseValidator.validate(response, wireOp, operation, generation,
                id, digest, OwnerV2Contract.bindingDigest(binding.trusted), parameters)
            credential.rejectEcho(body.toString()) // includes decoded JSON escapes, not only raw bytes
            OwnerReply(OwnerReply.Outcome.REPLIED, body)
        } catch (_: Exception) {
            disconnectTransport()
            OwnerReply(OwnerReply.Outcome.UNKNOWN)
        } finally {
            envelope.remove("token")
        }
    }

    /** Socket closure proves neither release, durable receipt completion nor process exit. */
    @Synchronized fun disconnectTransport() {
        disconnected = true
        try { transport.disconnect() } catch (_: Exception) { /* observation remains UNKNOWN */ }
    }

    override fun toString() = "VirtualDisplayOwnerClient(host-only)"
    companion object {
        internal val ID = Regex("[A-Za-z0-9_-]{1,64}")
        internal val BUSINESS = setOf("status", "launch", "input", "snapshot", "handoff", "release")
        internal fun checkWorker() {
            // Unit transports can run without an Android looper; Android calls must use a worker.
            if (android.os.Looper.getMainLooper()?.thread === Thread.currentThread())
                throw OwnerClientException()
        }
    }
}

/** Deliberately not a data class, no getter, serialization, debug dump or exception cause. */
internal class OwnerCredential(private val secret: String) {
    fun authorize(envelope: JSONObject) { envelope.put("token", secret) }
    fun rejectEcho(response: String) { require(!response.contains(secret)) }
    override fun toString() = "OwnerCredential(REDACTED)"
}
