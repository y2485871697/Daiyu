package app.daiyu.android.mcp

import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Settings-only discovery. It never creates a run, grants an effect, or calls a remote tool. */
internal class McpManagement(private val store: McpSettingsStore,
    private val transport: (McpServer) -> McpHttpSession = { McpHttpSession(it) }) {
    fun refresh(expected: McpSettings, serverId: String, owner: McpSettingsOwner): McpSettings {
        check(Looper.myLooper() != Looper.getMainLooper()) { "mcp_worker_required" }
        check(owner.current() && store.isCurrent(expected)) { "mcp_settings_stale" }
        val server = expected.servers.single { it.id == serverId }
        val session = transport(server)
        val pageRegistration = owner.observeRevoked { session.close() }
        val configurationRegistration = store.observeInvalidated(expected) { session.close() }
        val tools = try {
            try {
                session.discover().map { tool ->
                    // Metadata is untrusted and may echo the connection credential.
                    tool.redacted(server.bearer)
                }.also { values -> require(values.map { it.name }.distinct().size == values.size) }
            } finally {
                session.close()
                val drained = CountDownLatch(1)
                session.observeDrained { drained.countDown() }
                check(drained.await(5, TimeUnit.SECONDS)) { "mcp_discovery_cleanup_unconfirmed" }
            }
        } catch (_: Exception) {
            throw IllegalStateException("mcp_discovery_failed")
        } finally { pageRegistration(); configurationRegistration() }
        check(owner.current() && store.isCurrent(expected)) { "mcp_settings_stale" }
        val names = tools.map { it.name }.toSet()
        val refreshed = server.withPolicy(enabled = true, catalog = tools, allowedTools = names,
            disabledTools = server.disabledTools.intersect(names))
        return store.save(expected.servers.map { if (it.id == serverId) refreshed else it }, owner, expected)
    }
}
