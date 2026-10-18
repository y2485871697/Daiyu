package app.daiyu.android.mcp

import app.daiyu.android.domain.ConversationId
import app.daiyu.android.domain.RunId
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class McpPolicyTest {
    @Test fun defaultDisabledAndBothPolicyGatesRejectBeforeCreatingAnyTransport() {
        val tool = McpRemoteTool("erase", "erase")
        for (server in listOf(
            McpServer("server", "fixture", "https://mcp.invalid/rpc", ""),
            McpServer("server", "fixture", "https://mcp.invalid/rpc", "", catalog = listOf(tool), allowedTools = setOf("erase")),
            McpServer("server", "fixture", "https://mcp.invalid/rpc", "", enabled = true, catalog = listOf(tool)))) {
            assertFalse(server.eligible("erase"))
            assertThrows(IllegalArgumentException::class.java) {
                McpRunScope(McpSettings("policy", listOf(server)), listOf(McpSelection("server", "erase", "{}")),
                    ConversationId("conversation"), RunId("run"), { true }, { error("disabled transport") })
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            McpServer("server", "fixture", "https://mcp.invalid/rpc", "", true, emptyList(), setOf("erase"))
        }
    }
    @Test fun metadataRoundTripIsCanonicalBoundedAndRecursivelyRedactsCredentialEchoes() {
        val secret = "synthetic-token"
        val tool = McpRemoteTool("read-$secret", "echo $secret",
            """{"type":"object","properties":{"$secret":{"default":"$secret"}}}""", true)
        val safe = tool.redacted(secret)
        assertFalse(McpJson.canonical(safe.json()).contains(secret))
        assertTrue(safe.sameMetadata(McpRemoteTool.fromJson(safe.json())))
        assertFalse(safe.sameMetadata(McpRemoteTool(safe.name, "changed", safe.inputSchema, true)))
        assertFalse(safe.sameMetadata(McpRemoteTool(safe.name, safe.description, safe.inputSchema, false)))
        assertFalse(safe.sameMetadata(McpRemoteTool(safe.name, safe.description, """{"type":"object"}""", true)))
        assertThrows(RuntimeException::class.java) {
            McpRemoteTool.fromJson(JSONObject("""{"name":"read","inputSchema":{"type":"object"},"annotations":{"readOnlyHint":"true"}}"""))
        }
        assertThrows(IllegalArgumentException::class.java) { McpRemoteTool("read", "read", """{"type":"array"}""") }
    }
    @Test fun configurationInvalidationClosesPreviewWithoutConnectingAndUnsubscribesOnce() {
        var invalidate: (() -> Unit)? = null
        var unsubscribed = 0
        val server = McpServer("server", "fixture", "https://mcp.invalid/rpc", "", true,
            listOf(McpRemoteTool("erase", "erase")), setOf("erase"))
        val scope = McpRunScope(McpSettings("policy", listOf(server)), listOf(McpSelection("server", "erase", "{}")),
            ConversationId("conversation"), RunId("run"), { true }, { error("preview must not connect") },
            observeConfiguration = { invalidate = it; { unsubscribed++ } })
        assertTrue(scope.isCurrent()); checkNotNull(invalidate).invoke()
        assertFalse(scope.isCurrent()); assertEquals(1, unsubscribed)
        var drained = false; scope.observeDrained { drained = true }; assertTrue(drained)
        scope.close(); assertEquals(1, unsubscribed)
    }
    @Test fun alreadyInvalidatedRegistrationCannotPublishCurrentScope() {
        var unsubscribed = 0
        val server = McpServer("server", "fixture", "https://mcp.invalid/rpc", "", true,
            listOf(McpRemoteTool("erase", "erase")), setOf("erase"))
        val scope = McpRunScope(McpSettings("policy", listOf(server)), listOf(McpSelection("server", "erase", "{}")),
            ConversationId("conversation"), RunId("run"), { true }, { error("stale preview transport") },
            observeConfiguration = { callback -> callback(); { unsubscribed++ } })
        assertFalse(scope.isCurrent()); assertEquals(1, unsubscribed)
        scope.close(); assertEquals(1, unsubscribed)
    }
}
