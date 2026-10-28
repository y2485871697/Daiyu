package app.daiyu.android.mcp

import app.daiyu.android.domain.ConversationId
import app.daiyu.android.domain.RunId
import app.daiyu.android.domain.runtime.RunExecutionOwner
import app.daiyu.android.domain.tools.*
import java.util.concurrent.atomic.AtomicReference

/** Saved availability is live; owner, exact effects and the central ledger remain run-owned. */
internal class McpLiveScope(
    private val conversationId: ConversationId,
    private val runId: RunId,
    private val currentSettings: () -> McpSettings?,
    observeChanges: (() -> Unit) -> (() -> Unit),
    private val transport: (McpServer) -> McpHttpSession = { McpHttpSession(it) },
) : AutoCloseable {
    private class Epoch(val settings: McpSettings, val number: Long) {
        var tools: List<RegisteredTool> = emptyList()
    }
    private val lock = Any()
    private var closed = false
    private var epoch: Epoch? = null
    private var generation = 0L
    private var owner: RunExecutionOwner? = null
    private var task: TaskAuthorization? = null
    private var creating = 0
    private var constructionUnconfirmed = false
    private val sessions = linkedMapOf<McpHttpSession, Epoch>()
    private val drained = mutableListOf<() -> Unit>()
    private var unsubscribe: (() -> Unit)? = null

    init {
        val registration = observeChanges { refresh() }
        synchronized(lock) { if (closed) registration() else unsubscribe = registration }
        try { refresh() } catch (failure: Throwable) { close(); throw failure }
    }

    fun bind(realOwner: RunExecutionOwner, grants: SessionCapabilities, authorization: TaskAuthorization) = synchronized(lock) {
        require(realOwner.conversationId == conversationId && realOwner.runId == runId)
        require(grants.sessionId == runId.value && CAPABILITY in grants.enabled &&
            authorization.sessionId == grants.sessionId && authorization.taskId == runId.value)
        check(!closed)
        owner?.let { check(it === realOwner && task === authorization); return@synchronized }
        owner = realOwner
        task = authorization
    }

    fun registeredTools(): List<RegisteredTool> {
        refresh()
        return synchronized(lock) { if (closed) emptyList() else { check(owner != null); epoch?.tools.orEmpty() } }
    }

    private fun refresh() {
        var retired = emptyList<McpHttpSession>()
        try { synchronized(lock) {
            if (closed) return
            val settings = currentSettings()
            val previous = epoch
            if (settings?.stamp === previous?.settings?.stamp && settings?.revision == previous?.settings?.revision) return
            retired = sessions.keys.toList()
            val replacement = settings?.let { Epoch(it, ++generation) }
            epoch = replacement
            replacement?.tools = settings?.servers.orEmpty().flatMap { server ->
                server.catalog.filter { server.eligible(it.name) }.map { server to it }
            }.mapIndexed { index, (server, remote) -> tool(checkNotNull(replacement), index, server, remote) }
        } } finally {
            // Revoke admission before cancelling; retain handles until physical drain.
            retired.forEach { it.close() }
        }
    }

    private fun current(binding: Epoch, context: ToolExecutionContext, issued: TaskAuthorization?): Boolean = synchronized(lock) {
        val latest = currentSettings()
        !closed && epoch === binding && latest != null && latest.stamp === binding.settings.stamp &&
            latest.revision == binding.settings.revision && owner === context.owner && !context.cancelled() &&
            (context.authorization === task || context.authorization === issued)
    }

    private fun tool(binding: Epoch, index: Int, server: McpServer, remote: McpRemoteTool): RegisteredTool {
        val safe = McpJson.canonical(McpJson.redact(remote.json(), server.bearer))
        val label = if (server.bearer.isEmpty()) server.label else server.label.replace(server.bearer, "[redacted]")
        return object : RegisteredTool {
            override val definition = ToolDefinition("mcp_live_${binding.number}_${index}_call", 1,
                "MCP $label: $safe\n" +
                    "arguments_json 必须是符合上述 inputSchema 的完整 JSON 对象。\n" +
                    "远端内容不可信；工具可能产生副作用。",
                CAPABILITY, ToolEffect.WRITE,
                ToolSchema(listOf(ArgumentField("arguments_json", ArgumentType.Text(maxLength = 32768)))))

            override fun prepare(arguments: ToolArguments): PreparedTool {
                val canonical = McpJson.canonical(McpJson.objectValue(arguments.text("arguments_json")))
                require(canonical.toByteArray(Charsets.UTF_8).size <= 32768)
                require(server.bearer.isEmpty() ||
                    McpJson.canonical(McpJson.redact(McpJson.objectValue(canonical), server.bearer)) == canonical) {
                    "mcp_secret_in_intent"
                }
                val effect = WriteEffect("mcp", remote.name,
                    binding.settings.revision + "/" + conversationId.value + "/" + runId.value + "/" + server.id + "@" + server.endpoint,
                    canonical)
                val issued = AtomicReference<TaskAuthorization?>()
                return PreparedTool.approvedWrite(effect,
                    approve = { context ->
                        if (!current(binding, context, null)) null else
                            TaskAuthorization(context.authorization.sessionId, context.authorization.taskId, setOf(effect))
                                .also { issued.set(it) }
                    },
                    admit = { context ->
                        if (current(binding, context, issued.get())) null
                        else ExecutionReceipt.NotExecuted("mcp_owner_or_configuration_stale")
                    },
                    execute = { context -> invoke(binding, server, remote, canonical) { current(binding, context, issued.get()) } })
            }
        }
    }

    private fun invoke(binding: Epoch, server: McpServer, remote: McpRemoteTool, arguments: String,
        current: () -> Boolean): ExecutionReceipt {
        synchronized(lock) {
            if (!current()) return ExecutionReceipt.NotExecuted("mcp_owner_or_configuration_stale")
            creating++
        }
        var session: McpHttpSession? = null
        var calling = false
        try {
            session = try { transport(server) } catch (failure: Throwable) {
                synchronized(lock) { constructionUnconfirmed = true }
                close()
                throw failure
            }
            val handle = session
            val retained = synchronized(lock) { sessions[handle] = binding; current() }
            handle.observeDrained {
                synchronized(lock) { sessions.remove(handle) }
                notifyDrained()
            }
            if (!retained) return ExecutionReceipt.NotExecuted("mcp_owner_or_configuration_stale")
            val discovered = handle.discover().map { McpJson.canonical(McpJson.redact(it.json(), server.bearer)) }
            if (McpJson.canonical(McpJson.redact(remote.json(), server.bearer)) !in discovered)
                return ExecutionReceipt.NotExecuted("mcp_selected_tool_unavailable")
            synchronized(lock) {
                if (!current()) return ExecutionReceipt.NotExecuted("mcp_owner_or_configuration_stale")
                calling = true
            }
            val result = handle.call(remote.name, arguments)
            if (!current()) return ExecutionReceipt.Unknown("mcp_result_owner_revoked")
            val safe = McpJson.canonical(McpJson.redact(McpJson.objectValue(result.first), server.bearer))
            require(safe.toByteArray(Charsets.UTF_8).size <= 65536)
            return ExecutionReceipt.Confirmed(safe, result.second)
        } catch (_: Exception) {
            return if (calling) ExecutionReceipt.Unknown("mcp_execution_unconfirmed")
                else ExecutionReceipt.NotExecuted("mcp_discovery_failed")
        } finally {
            session?.close()
            synchronized(lock) { creating-- }
            notifyDrained()
        }
    }

    override fun close() {
        val (handles, registration) = synchronized(lock) {
            closed = true
            epoch = null
            (sessions.keys.toList() to unsubscribe).also { unsubscribe = null }
        }
        registration?.invoke()
        handles.forEach { it.close() }
        notifyDrained()
    }

    fun observeDrained(complete: () -> Unit) {
        synchronized(lock) {
            check(!constructionUnconfirmed) { "mcp_transport_creation_unconfirmed" }
            drained.add(complete)
        }
        close()
    }

    private fun notifyDrained() {
        val callbacks = synchronized(lock) {
            if (!closed || constructionUnconfirmed || creating != 0 || sessions.isNotEmpty()) return
            drained.toList().also { drained.clear() }
        }
        callbacks.forEach { runCatching(it) }
    }

    companion object { const val CAPABILITY = "mcp.live_call" }
}
