package app.daiyu.android.integration.tools

import app.daiyu.android.domain.runtime.RunExecutionOwner
import app.daiyu.android.domain.tools.SessionCapabilities
import app.daiyu.android.domain.tools.WriteEffect
import app.daiyu.android.vd.OwnerRunBinding
import app.daiyu.android.vd.runtime.OwnerV2Contract
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.security.MessageDigest
import java.util.Collections

/** Host-only finite requests. No display selector, shell, envelope, token or classpath API. */
internal sealed interface R08VirtualDisplayAction {
    data object Start : R08VirtualDisplayAction
    data object Initialize : R08VirtualDisplayAction
    data class Launch(val packageName: String) : R08VirtualDisplayAction
    data class Tap(val x: Int, val y: Int) : R08VirtualDisplayAction
    data class Swipe(val x1: Int, val y1: Int, val x2: Int, val y2: Int, val durationMs: Int = 300) : R08VirtualDisplayAction
    data class Text(val text: String) : R08VirtualDisplayAction
    data class Key(val keyCode: Int) : R08VirtualDisplayAction
    data object Release : R08VirtualDisplayAction
}
internal data class R08VirtualDisplayWrite(val id: String, val action: R08VirtualDisplayAction)
internal data class R08VirtualDisplayRead(val id: String, val snapshot: Boolean = false,
                                        val include: Boolean = true, val maxBytes: Int = 6 * 1024 * 1024)

internal class R08VirtualDisplayOperation internal constructor(
    val id: String, val operation: String, val canonicalParameters: String, val effect: WriteEffect?
) {
    fun parameters(): JSONObject = JSONObject(canonicalParameters)
    override fun toString() = "R08VirtualDisplayOperation($id, $operation)"
}

/** Pure preparation. The actual Runner-issued owner and exact capabilities object are pinned here.
 * Generation is allocated durably by the parent, never by this plan or by model arguments. */
internal class R08VirtualDisplayPlan private constructor(
    val owner: RunExecutionOwner,
    val capabilities: SessionCapabilities,
    val generation: String,
    val binding: OwnerRunBinding,
    val bindingKey: String,
    writes: List<R08VirtualDisplayOperation>,
    reads: List<R08VirtualDisplayOperation>
) {
    val writes: List<R08VirtualDisplayOperation> = Collections.unmodifiableList(writes.toList())
    val reads: List<R08VirtualDisplayOperation> = Collections.unmodifiableList(reads.toList())
    val effects: Set<WriteEffect> = Collections.unmodifiableSet(writes.map { checkNotNull(it.effect) }.toSet())

    /** Valid owner protocol ID, derived from the REAL invocation.id, stable across journal reload.
     * The immutable canonical request is separately checked by the journal on any ID reuse. */
    fun intent(invocationId: String, operation: R08VirtualDisplayOperation): R08VirtualDisplayIntent {
        require(invocationId.isNotBlank())
        return R08VirtualDisplayIntent(bindingKey, generation, invocationId,
            r08Hash(bindingKey, generation, invocationId), operation.operation, operation.canonicalParameters)
    }

    override fun toString() = "R08VirtualDisplayPlan(host-only)"

    companion object {
        fun prepare(owner: RunExecutionOwner, capabilities: SessionCapabilities, generation: String,
                    writes: List<R08VirtualDisplayWrite>, reads: List<R08VirtualDisplayRead> = emptyList()): R08VirtualDisplayPlan {
            require(generation.matches(Regex("[A-Za-z0-9_-]{1,64}")))
            require(writes.size <= 64 && reads.size <= 64 && (writes.isNotEmpty() || reads.isNotEmpty()))
            require((writes.map { it.id } + reads.map { it.id }).all { it.matches(Regex("[A-Za-z0-9_-]{1,64}")) })
            require(writes.map { it.id }.distinct().size == writes.size && reads.map { it.id }.distinct().size == reads.size)
            val binding = OwnerRunBinding(capabilities.sessionId, owner.runId.value, owner.conversationId.value,
                owner.runId.value, owner.assistantMessageId.value, owner.ownerId)
            val key = OwnerV2Contract.bindingDigest(binding.trusted)
            val target = "$key/$generation"
            val ws = writes.map { step ->
                val (op, input) = when (val a = step.action) {
                    R08VirtualDisplayAction.Start -> "start" to JSONObject()
                    R08VirtualDisplayAction.Initialize -> "initialize" to JSONObject()
                    is R08VirtualDisplayAction.Launch -> {
                        require(a.packageName.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")))
                        "launch" to JSONObject().put("package", a.packageName)
                    }
                    is R08VirtualDisplayAction.Tap -> {
                        require(a.x >= 0 && a.y >= 0)
                        "input" to JSONObject().put("kind", "tap").put("x", a.x).put("y", a.y)
                    }
                    is R08VirtualDisplayAction.Swipe -> {
                        require(listOf(a.x1, a.y1, a.x2, a.y2).all { it >= 0 } && a.durationMs in 1..60_000)
                        "input" to JSONObject().put("kind", "swipe").put("x1", a.x1).put("y1", a.y1)
                            .put("x2", a.x2).put("y2", a.y2).put("durationMs", a.durationMs)
                    }
                    is R08VirtualDisplayAction.Text -> {
                        require(a.text.isNotEmpty() && a.text.length <= 4096 && a.text.none { it.code < 32 || it.code == 127 })
                        "input" to JSONObject().put("kind", "text").put("text", a.text)
                    }
                    is R08VirtualDisplayAction.Key -> {
                        require(a.keyCode in 0..1000)
                        "input" to JSONObject().put("kind", "key").put("keyCode", a.keyCode)
                    }
                    R08VirtualDisplayAction.Release -> "release" to JSONObject()
                }
                val parameters = if (op == "start" || op == "initialize") input else OwnerV2Contract.normalize(op, input)
                val digest = if (op == "start" || op == "initialize")
                    r08Hash(op, OwnerV2Contract.generationDigest(generation, binding.trusted))
                else OwnerV2Contract.identity(generation, "effect", op, binding.trusted, parameters)
                R08VirtualDisplayOperation(step.id, op, r08Canonical(parameters),
                    WriteEffect("r08.virtual_display", op, target, digest))
            }
            val rs = reads.map { read ->
                val op = if (read.snapshot) "snapshot" else "status"
                val input = if (read.snapshot) JSONObject().put("include", read.include).put("maxBytes", read.maxBytes) else JSONObject()
                R08VirtualDisplayOperation(read.id, op, r08Canonical(OwnerV2Contract.normalize(op, input)), null)
            }
            return R08VirtualDisplayPlan(owner, capabilities, generation, binding, key, ws, rs)
        }
    }
}

/** These values are durable host journal data, NEVER tool arguments. */
internal data class R08VirtualDisplayIntent(val bindingKey: String, val generation: String,
    val invocationId: String, val operationId: String, val operation: String, val canonicalParameters: String)
internal enum class R08VirtualDisplayPermit { EXECUTE_ONCE, LOOKUP_ONLY, UNKNOWN }
internal enum class R08VirtualDisplayEvidence { CONFIRMED, NOT_EXECUTED, UNKNOWN }

/** Parent implementation MUST atomically persist+fsync the entire immutable intent BEFORE returning
 * EXECUTE_ONCE, and return it at most once across all threads/processes/restarts. Any existing claim,
 * including an interrupted claim, is LOOKUP_ONLY, never another execution permit. Reject changed
 * binding/generation/operation/parameters for the same invocation/operation ID. No in-memory production
 * implementation is supplied. Failure/ambiguity is UNKNOWN. complete() never erases a claim.
 * finalOutcome() records the CENTRAL ledger result, which can downgrade an apparent local success.
 */
internal interface R08VirtualDisplayJournal {
    fun claim(intent: R08VirtualDisplayIntent): R08VirtualDisplayPermit
    fun complete(intent: R08VirtualDisplayIntent, evidence: R08VirtualDisplayEvidence)
    fun finalOutcome(bindingKey: String, generation: String, invocationId: String, evidence: R08VirtualDisplayEvidence)
}

private fun r08Hash(vararg parts: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    parts.forEach { part ->
        val bytes = part.toByteArray(Charsets.UTF_8)
        digest.update("${bytes.size}:".toByteArray(Charsets.US_ASCII)); digest.update(bytes)
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
}

/** Only ordering/number rendering here; defaults and effective parameters belong to OwnerV2Contract. */
private fun r08Canonical(value: Any): String = when (value) {
    is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") {
        JSONObject.quote(it) + ":" + r08Canonical(value.get(it))
    }
    is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { r08Canonical(value.get(it)) }
    is String -> JSONObject.quote(value)
    is Number -> BigDecimal(value.toString()).stripTrailingZeros().toPlainString()
    else -> value.toString()
}
