package app.daiyu.android.browser.openminis

import app.daiyu.android.browser.bridge.BrowserCommand
import app.daiyu.android.browser.bridge.BrowserExecutionPort
import app.daiyu.android.browser.bridge.BrowserExecutionResult
import app.daiyu.android.browser.bridge.BrowserOutcome
import app.daiyu.android.browser.host.BrowserTabLifecycle
import app.daiyu.android.browser.host.BrowserToolSession
import app.daiyu.android.browser.session.BrowserAccess
import app.daiyu.android.browser.session.BrowserGrant
import app.daiyu.android.browser.session.BrowserOwner
import app.daiyu.android.browser.session.BrowserOwnerKind
import app.daiyu.android.browser.session.BrowserSessionRegistry
import app.daiyu.android.browser.session.BrowserTabLease
import org.junit.Assert.*
import org.junit.Test

class OpenMinisPreflightTest {
    private class Fixture(preflight: (BrowserCommand) -> Unit = OpenMinisCommandMapper::preflight) : AutoCloseable {
        val live = mutableSetOf<BrowserTabLease>()
        val calls = mutableListOf<BrowserCommand>()
        var failPort = false
        val session = BrowserToolSession(
            BrowserGrant(BrowserOwner("preflight", BrowserOwnerKind.MAIN), BrowserAccess.FULL),
            BrowserSessionRegistry(),
            object : BrowserTabLifecycle {
                override fun create(lease: BrowserTabLease) { live.add(lease) }
                override fun close(lease: BrowserTabLease) { live.remove(lease) }
            },
            object : BrowserExecutionPort {
                override fun execute(command: BrowserCommand, lease: BrowserTabLease): BrowserExecutionResult {
                    check(lease in live)
                    calls.add(command)
                    if (failPort) error("native outcome unavailable")
                    return BrowserExecutionResult("existing page")
                }
            }, preflight,
        )
        fun call(action: String, vararg args: Pair<String, Any?>) = session.execute(mapOf("action" to action, *args))
        override fun close() = session.close()
    }

    @Test fun unsupportedModesDoNotEnterPortOrLoseTheCurrentResource() {
        Fixture().use { f ->
            assertTrue(f.call("new_tab") is BrowserOutcome.Confirmed)
            assertTrue(f.call("navigate", "url" to "https://example.invalid/") is BrowserOutcome.Confirmed)
            val lease = f.session.currentTab
            val cases = listOf(
                mapOf("action" to "navigate", "url" to "https://example.invalid/", "timeout_ms" to 1000),
                mapOf("action" to "type", "selector" to "input", "text" to "test", "submit" to true),
                mapOf("action" to "type", "coordinate_x" to 1, "coordinate_y" to 1, "text" to "test"),
                mapOf("action" to "hover", "coordinate_x" to 1, "coordinate_y" to 1),
                mapOf("action" to "screenshot", "read_image" to true),
                mapOf("action" to "wait_for_dom_stable", "timeout_ms" to 500),
                mapOf("action" to "wait_for_selector", "selector" to "body", "timeout_ms" to 30000),
                mapOf("action" to "scroll_and_collect", "item_selector" to "p", "fuzzy" to false),
            )
            for (args in cases) {
                val before = f.calls.size
                val outcome = f.session.execute(args)
                assertTrue("$args -> $outcome", outcome is BrowserOutcome.NotExecuted)
                assertEquals(before, f.calls.size)
                assertEquals(lease, f.session.currentTab)
                assertEquals(setOf(lease), f.live)
                assertTrue(f.call("get_text") is BrowserOutcome.Confirmed)
            }
        }
    }

    @Test fun nativeConfigurationAndHistoryCommandsEnterTheExactExistingLease() {
        Fixture().use { f ->
            f.call("new_tab")
            f.call("navigate", "url" to "https://example.invalid/")
            val original = f.session.currentTab
            val commands = listOf(
                mapOf("action" to "set_user_agent", "user_agent" to "mobile_chrome"),
                mapOf("action" to "set_user_agent", "user_agent" to "desktop_chrome"),
                mapOf("action" to "set_viewport", "viewport_width" to 800, "viewport_height" to 600),
                mapOf("action" to "set_viewport", "reset" to true),
                mapOf("action" to "reload"), mapOf("action" to "go_back"), mapOf("action" to "go_forward"),
            )
            for (args in commands) {
                val before = f.calls.size
                assertTrue("$args", f.session.execute(args) is BrowserOutcome.Confirmed)
                assertEquals(before + 1, f.calls.size)
                assertEquals(args.getValue("action"), f.calls.last().action)
                assertEquals(original, f.session.currentTab)
                assertEquals(setOf(original), f.live)
            }
            val before = f.calls.size
            assertTrue(f.call("set_viewport", "viewport_width" to 800) is BrowserOutcome.NotExecuted)
            assertTrue(f.call("set_user_agent", "user_agent" to "forged") is BrowserOutcome.NotExecuted)
            assertEquals(before, f.calls.size)
        }
    }

    @Test fun tabManagementDoesNotInvokeBackendPreflight() {
        Fixture { error("must not validate tab management as manager commands") }.use { f ->
            assertTrue(f.call("new_tab") is BrowserOutcome.Confirmed)
            assertTrue(f.call("list_tabs") is BrowserOutcome.Confirmed)
            assertTrue(f.call("close_tab") is BrowserOutcome.Confirmed)
            assertTrue(f.live.isEmpty())
            assertTrue(f.calls.isEmpty())
        }
    }

    @Test fun dedicatedRoutesRemainAvailableAndOrdinaryArgumentsAreNormalized() {
        Fixture().use { f ->
            f.call("new_tab")
            assertTrue(f.call("fetch", "url" to "https://example.invalid/file") is BrowserOutcome.Confirmed)
            assertTrue(f.call("get_cookies", "keywords" to listOf("test")) is BrowserOutcome.Confirmed)
            assertTrue(f.call("set_cookies", "cookies" to "[]") is BrowserOutcome.Confirmed)
            assertTrue(f.call("get_text", "offset" to 1L, "max_chars" to 5L) is BrowserOutcome.Confirmed)
            assertEquals(1, f.calls.last().arguments["offset"])
            assertEquals(5, f.calls.last().arguments["max_chars"])
            val before = f.calls.size
            assertTrue(f.call("fetch", "url" to "https://example.invalid/file", "owner" to "forged")
                is BrowserOutcome.NotExecuted)
            assertEquals(before, f.calls.size)
        }
    }

    @Test fun exceptionsAfterPortEntryRemainUnknownWithoutRetry() {
        Fixture().use { f ->
            f.call("new_tab")
            f.failPort = true
            assertEquals(BrowserOutcome.Unknown, f.call("get_text"))
            assertEquals(1, f.calls.size)
        }
    }
}
