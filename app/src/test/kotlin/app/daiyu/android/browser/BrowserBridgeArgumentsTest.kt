package app.daiyu.android.browser

import app.daiyu.android.browser.bridge.*
import app.daiyu.android.browser.session.*
import org.junit.Assert.*
import org.junit.Test

class BrowserBridgeArgumentsTest {
    private val sessions = BrowserSessionRegistry()
    private val owner = BrowserOwner("arguments", BrowserOwnerKind.MAIN)
    private val calls = mutableListOf<BrowserCommand>()
    private val bridge = BrowserHostBridge(sessions, object: BrowserExecutionPort {
        override fun execute(command: BrowserCommand, lease: BrowserTabLease): BrowserExecutionResult {
            calls.add(command)
            return BrowserExecutionResult("safe")
        }
    })
    init { sessions.open(BrowserGrant(owner, BrowserAccess.FULL)); sessions.newTab(owner) }
    private fun run(action: String, args: Map<String, Any?>) =
        bridge.executeSafely(owner, BrowserCommand(action, 0, args))

    @Test fun cookieFiltersAndCollectionPassValidatedParametersWithoutMutation() {
        val keywords = mutableListOf("session")
        val command = BrowserCommand("get_cookies", 0, mapOf("keywords" to keywords, "fuzzy" to true))
        keywords.add("changed")
        assertTrue(bridge.executeSafely(owner, command) is BrowserOutcome.Confirmed)
        assertEquals(listOf("session"), calls.single().arguments["keywords"])
        assertTrue(run("scroll_and_collect", mapOf("item_selector" to "article", "scroll_count" to 20L,
            "keywords" to listOf("news"))) is BrowserOutcome.Confirmed)
        assertEquals(20, calls.last().arguments["scroll_count"])
    }
    @Test fun invalidFiltersAndCollectionAreRejectedBeforePort() {
        for (args in listOf(mapOf("keywords" to "a"), mapOf("keywords" to listOf(4)),
            mapOf("keywords" to listOf("")), mapOf("keywords" to List(33) { "x" }),
            mapOf("keywords" to listOf("x".repeat(129))), mapOf("fuzzy" to "true"))) {
            assertTrue(run("get_cookies", args) is BrowserOutcome.NotExecuted)
        }
        for (args in listOf(emptyMap(), mapOf("item_selector" to ""),
            mapOf("item_selector" to "p", "scroll_count" to 0),
            mapOf("item_selector" to "p", "scroll_count" to 21))) {
            assertTrue(run("scroll_and_collect", args) is BrowserOutcome.NotExecuted)
        }
        assertTrue(calls.isEmpty())
    }
    @Test fun readOnlyDeniesCollectionAndCookiesBeforePort() {
        val registry = BrowserSessionRegistry()
        registry.open(BrowserGrant(owner, BrowserAccess.READ_ONLY));registry.newTab(owner)
        val b = BrowserHostBridge(registry, object:BrowserExecutionPort {
            override fun execute(command:BrowserCommand, lease:BrowserTabLease):BrowserExecutionResult =
                error("must not execute")
        })
        assertTrue(b.executeSafely(owner, BrowserCommand("get_cookies",0)) is BrowserOutcome.NotExecuted)
        assertTrue(b.executeSafely(owner, BrowserCommand("scroll_and_collect",0,
            mapOf("item_selector" to "p"))) is BrowserOutcome.NotExecuted)
    }
}
