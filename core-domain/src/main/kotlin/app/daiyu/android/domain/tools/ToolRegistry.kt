package app.daiyu.android.domain.tools

import app.daiyu.android.domain.ToolInvocation
import app.daiyu.android.domain.runtime.RunExecutionOwner
import java.security.MessageDigest
import java.util.Collections

enum class ToolEffect { READ_ONLY, WRITE, USER_BARRIER }
data class ToolDefinition(
    val name: String,
    val version: Int,
    val description: String,
    val capability: String,
    val effect: ToolEffect,
    val schema: ToolSchema
) {
    init {
        require(name.matches(Regex("[a-z][a-z0-9_]*")))
        require(version > 0 && description.isNotBlank() && capability.isNotBlank())
        require(name != "ask_user" || effect == ToolEffect.USER_BARRIER)
    }
}

/** A semantic identity shared by adapters with equivalent side effects, NOT the model call ID.
 * change must encode the complete normalized desired change (amount, destination, content, etc.). */
data class WriteEffect(val namespace: String, val operation: String, val target: String, val change: String) {
    init { require(listOf(namespace, operation, target, change).all { it.isNotBlank() && wellFormedUnicode(it) }) }
    internal fun key(): String = fingerprint(namespace, operation, target, change)
}

/** Host-created at session startup. Copying prevents later settings changes from broadening access. */
class SessionCapabilities(val sessionId: String, enabled: Set<String>) {
    val enabled: Set<String> = Collections.unmodifiableSet(enabled.toSet())
    init { require(sessionId.isNotBlank()) }
}

/** Host-issued for ONE task in ONE session. Capabilities never manufacture these grants.
 * Every grant names the full effect, not merely a tool, broad domain or target wildcard. */
class TaskAuthorization(val sessionId: String, val taskId: String, allowedWrites: Set<WriteEffect> = emptySet()) {
    val allowedWrites: Set<WriteEffect> = Collections.unmodifiableSet(allowedWrites.toSet())
    init { require(sessionId.isNotBlank() && taskId.isNotBlank()) }
}

sealed interface ExecutionReceipt {
    /** Execution has definitely completed, including a confirmed negative service response. */
    data class Confirmed(val payload: String, val isError: Boolean = false) : ExecutionReceipt
    /** Adapter MUST guarantee no side effect was attempted. After sending, return Unknown instead. */
    data class NotExecuted(val code: String) : ExecutionReceipt
    data class Unknown(val code: String = "execution_unconfirmed") : ExecutionReceipt
}

/** prepare is validation only: no I/O, prompting, permission requests or side effects. */
interface RegisteredTool {
    val definition: ToolDefinition
    fun prepare(arguments: ToolArguments): PreparedTool
}
/** Dispatcher-created execution data; no model-supplied identity or reconstructed authorization. */
class ToolExecutionContext internal constructor(
    val owner: RunExecutionOwner,
    val invocation: ToolInvocation,
    val authorization: TaskAuthorization,
    val cancelled: () -> Boolean
)

class PreparedTool private constructor(
    val writeEffect: WriteEffect?,
    val execute: () -> ExecutionReceipt,
    private val contextualExecution: ((ToolExecutionContext) -> ExecutionReceipt)?
) {
    constructor(writeEffect: WriteEffect? = null, execute: () -> ExecutionReceipt) : this(writeEffect, execute, null)
    internal val requiresContext: Boolean get() = contextualExecution != null
    internal fun execute(context: ToolExecutionContext?): ExecutionReceipt =
        contextualExecution?.invoke(checkNotNull(context) { "tool_execution_context_required" }) ?: execute()

    companion object {
        /** Context becomes available only after whole-batch validation and ledger reservation. */
        fun contextual(writeEffect: WriteEffect? = null, execute: (ToolExecutionContext) -> ExecutionReceipt): PreparedTool =
            PreparedTool(writeEffect, { error("tool_execution_context_required") }, execute)
    }
}

/** Registration is explicit; unknown modules do not receive placeholder-success implementations. */
class ToolRegistry {
    private val tools = linkedMapOf<String, Pair<ToolDefinition, RegisteredTool>>()
    @Synchronized fun register(tool: RegisteredTool): ToolRegistry {
        val definition = tool.definition
        require(definition.name !in tools) { "duplicate_tool" }
        tools[definition.name] = definition to tool
        return this
    }
    @Synchronized fun snapshot(): ToolCatalog = ToolCatalog(tools.toMap())
}
class ToolCatalog internal constructor(tools: Map<String, Pair<ToolDefinition, RegisteredTool>>) {
    // Capture definitions at registration; adapters cannot swap their getter later.
    private val tools = tools.toMap()
    val definitions: List<ToolDefinition> = Collections.unmodifiableList(this.tools.values.map { it.first })
    fun available(session: SessionCapabilities): List<ToolDefinition> = definitions.filter { it.capability in session.enabled }
    internal fun find(name: String): Pair<ToolDefinition, RegisteredTool>? = tools[name]
}

internal fun fingerprint(vararg parts: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    for (part in parts) {
        val bytes = part.toByteArray(Charsets.UTF_8)
        digest.update("${bytes.size}:".toByteArray(Charsets.US_ASCII)); digest.update(bytes)
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
}
internal fun invocationKey(invocation: ToolInvocation, definition: ToolDefinition, arguments: ToolArguments): String =
    fingerprint(invocation.name, definition.version.toString(), arguments.canonicalJson())
