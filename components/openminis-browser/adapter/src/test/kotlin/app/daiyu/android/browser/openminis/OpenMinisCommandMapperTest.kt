package app.daiyu.android.browser.openminis

import app.daiyu.android.browser.bridge.BrowserCommand
import com.openminis.app.browser.BrowserAction
import com.openminis.app.browser.ScrollDirection
import org.junit.Assert.*
import org.junit.Test

class OpenMinisCommandMapperTest {
    private fun command(action: String, vararg args: Pair<String, Any?>) = BrowserCommand(action, 0, mapOf(*args))
    private fun rejected(command: BrowserCommand) {
        try { OpenMinisCommandMapper.map(command); fail("unsupported command") }
        catch (_: IllegalArgumentException) { }
        catch (_: IllegalStateException) { }
    }

    @Test fun mapsActualManagerFieldsWithoutAuthorityOrOutputOnlyFields() {
        val click = OpenMinisCommandMapper.map(command("click", "coordinate_x" to 123, "coordinate_y" to 456))
        assertEquals(BrowserAction.CLICK, click.action)
        assertEquals(123, click.coordinateX)
        assertEquals(456, click.coordinateY)
        assertNull(click.tabId)
        val type = OpenMinisCommandMapper.map(command("type", "selector" to "#input", "text" to "hello", "submit" to false))
        assertEquals(BrowserAction.TYPE, type.action)
        assertEquals("#input", type.selector)
        assertEquals("hello", type.text)
        assertEquals(BrowserAction.HOVER, OpenMinisCommandMapper.map(command("hover", "selector" to "a")).action)
        val scroll = OpenMinisCommandMapper.map(command("scroll", "selector" to "main", "direction" to "up", "amount" to 120))
        assertEquals(BrowserAction.SCROLL, scroll.action)
        assertEquals("main", scroll.selector)
        assertEquals(ScrollDirection.UP, scroll.direction)
        assertEquals(120, scroll.amount)
        assertEquals("return 1", OpenMinisCommandMapper.map(command("execute_js", "script" to "return 1")).script)
        assertEquals(100, OpenMinisCommandMapper.map(command("get_backbone", "max_depth" to 100)).maxDepth)
        assertEquals(BrowserAction.GET_PAGE_INFO, OpenMinisCommandMapper.map(command("get_page_info")).action)
        assertEquals("a", OpenMinisCommandMapper.map(command("find_elements", "selector" to "a")).selector)
    }

    @Test fun nativeHostCommandsBypassManagerMappingButStillValidateResults() {
        fun reject(block: () -> Unit) {
            try { block(); fail("must reject") }
            catch (_: IllegalArgumentException) { }
            catch (_: IllegalStateException) { }
        }
        for (profile in listOf("desktop_chrome", "mobile_chrome")) {
            val command = command("set_user_agent", "user_agent" to profile)
            OpenMinisCommandMapper.preflight(command)
            try { OpenMinisCommandMapper.map(command); fail("UA must not reach fixed manager") }
            catch (failure: IllegalStateException) { assertEquals("BROWSER_OPENMINIS_USER_AGENT_UNSUPPORTED", failure.message) }
            assertEquals("Switched", OpenMinisCommandMapper.result(command,
                com.openminis.app.browser.BrowserActionResult("Switched")).text)
            for (answer in listOf(
                com.openminis.app.browser.BrowserActionResult.error("native failure"),
                com.openminis.app.browser.BrowserActionResult("Error: native failure"),
            )) {
                try { OpenMinisCommandMapper.result(command, answer); fail("native failure must not publish") }
                catch (failure: IllegalStateException) { assertEquals("BROWSER_OPENMINIS_ACTION_FAILED", failure.message) }
            }
        }
        for (command in listOf(
            command("set_viewport", "viewport_width" to 800, "viewport_height" to 600),
            command("set_viewport", "reset" to true),
        )) {
            OpenMinisCommandMapper.preflight(command)
            rejected(command)
            assertEquals("Resized", OpenMinisCommandMapper.result(command,
                com.openminis.app.browser.BrowserActionResult("Resized")).text)
        }
        for (command in listOf(
            command("set_user_agent", "user_agent" to "forged"),
            command("set_user_agent", "user_agent" to "desktop_chrome", "owner" to "forged"),
            command("set_viewport", "viewport_width" to 800),
            command("unknown_action"),
        )) {
            reject { OpenMinisCommandMapper.preflight(command) }
            reject { OpenMinisCommandMapper.result(command,
                com.openminis.app.browser.BrowserActionResult("forged success")) }
        }
    }

    @Test fun bodyPaginationReadsBeyondUpstreamTenThousandCharacterCutoff() {
        val input = OpenMinisCommandMapper.map(command("get_text", "selector" to "main", "offset" to 50_000, "max_chars" to 40))
        assertEquals(BrowserAction.EXECUTE_JS, input.action)
        assertNull(input.selector)
        assertNull(input.amount)
        assertTrue(input.script!!.contains("const offset = 50000"))
        assertTrue(input.script!!.contains("offset + 40"))
        assertFalse(input.script!!.contains("substring(0, 10000)"))
        val readable = OpenMinisCommandMapper.map(command("get_readable", "offset" to 1_000_000, "max_chars" to 64_000))
        assertTrue(readable.script!!.contains("getComputedStyle"))
        assertTrue(readable.script!!.contains("replace(/\\s+/g, ' ').trim()"))
        assertTrue(readable.script!!.contains("offset + 15744"))
    }

    @Test fun bodyStringsAreSafeJavaScriptLiterals() {
        val selector = "a'\"\\\n\u2028\u2029\uD800"
        val quoted = BrowserOutputBody.quote(selector)
        assertTrue(quoted.contains("\\\""))
        assertTrue(quoted.contains("\\\\"))
        assertTrue(quoted.contains("\\u000a"))
        assertTrue(quoted.contains("\\u2028"))
        assertTrue(quoted.contains("\\ud800"))
        assertFalse(quoted.contains('\n'))
        assertTrue(OpenMinisCommandMapper.map(command("get_text", "selector" to selector)).script!!.contains(quoted))
    }

    @Test fun unsupportedModesAreNotSilentlyDropped() {
        rejected(command("navigate", "url" to "https://example.com", "timeout_ms" to 100))
        rejected(command("type", "selector" to "input", "text" to "x", "submit" to true))
        rejected(command("type", "coordinate_x" to 1, "coordinate_y" to 2, "text" to "x"))
        rejected(command("hover", "coordinate_x" to 1, "coordinate_y" to 2))
        rejected(command("screenshot", "read_image" to true))
        rejected(command("set_viewport", "viewport_width" to 800, "viewport_height" to 600))
        for (action in listOf("go_back", "go_forward", "reload", "get_cookies", "set_cookies", "fetch")) rejected(command(action))
        val screenshot = OpenMinisCommandMapper.map(command("screenshot", "full_page" to true, "read_image" to false))
        assertEquals(BrowserAction.SCREENSHOT, screenshot.action)
        assertTrue(screenshot.fullPage)
    }

    @Test fun waitsRespectManagerLimitsRatherThanItsSilentClamping() {
        assertEquals(1000, OpenMinisCommandMapper.map(command("wait_for_dom_stable", "timeout_ms" to 1000)).timeoutMs)
        assertEquals(60_000, OpenMinisCommandMapper.map(command("wait_for_dom_stable", "timeout_ms" to 60_000)).timeoutMs)
        rejected(command("wait_for_dom_stable", "timeout_ms" to 999))
        val wait = OpenMinisCommandMapper.map(command("wait_for_selector", "selector" to "a", "timeout_ms" to 29_000))
        assertEquals(BrowserAction.EXECUTE_JS, wait.action)
        assertTrue(wait.script!!.contains("Date.now() + 29000"))
        assertTrue(wait.script!!.contains("throw new Error"))
        rejected(command("wait_for_selector", "selector" to "a", "timeout_ms" to 30_000))
    }

    @Test fun scrollCollectUsesOnlyFieldsActuallyConsumedByExecute() {
        val keywords = mutableListOf("one", "two")
        val input = OpenMinisCommandMapper.map(command("scroll_and_collect", "item_selector" to ".item",
            "scroll_count" to 50, "keywords" to keywords))
        assertEquals(BrowserAction.SCROLL_AND_COLLECT, input.action)
        assertEquals(".item", input.itemSelector)
        assertEquals(50, input.scrollCount)
        keywords.clear()
        assertEquals(listOf("one", "two"), input.keywords)
        rejected(command("scroll_and_collect", "item_selector" to "a", "scroll_count" to 51))
        rejected(command("scroll_and_collect", "item_selector" to "a", "keywords" to List(33) { "a" }))
        rejected(command("scroll_and_collect", "item_selector" to "a", "keywords" to listOf(1)))
        rejected(command("scroll_and_collect", "item_selector" to "a", "keywords" to listOf("a".repeat(129))))
        rejected(command("scroll_and_collect", "item_selector" to "a", "fuzzy" to false))
    }

    @Test fun validatesOutputOnlyAndTargetParametersBeforeDispatch() {
        for ((key, value) in listOf("offset" to -1, "offset" to 1_000_001, "offset" to 1.0,
            "max_chars" to 0, "max_chars" to 64_001, "max_chars" to "20", "max_chars" to null)) {
            rejected(command("get_text", key to value))
        }
        rejected(command("get_text", "unexpected" to 1))
        rejected(command("get_readable", "selector" to "main"))
        rejected(command("click", "selector" to "a", "coordinate_x" to 1))
        rejected(command("click", "coordinate_x" to 1))
        rejected(command("find_elements"))
        rejected(command("scroll", "direction" to "left"))
        rejected(command("set_user_agent", "user_agent" to "custom"))
        rejected(command("screenshot", "full_page" to "true"))
        rejected(BrowserCommand("get_page_info", 3))
    }

    @Test fun navigationRejectsLocalSchemesCredentialsAndExternalGoogleAuth() {
        for (url in listOf("file:///workspace/index.html", "minis://workspace/a", "about:blank",
            "javascript:alert(1)", "https://user:secret@example.com", "https://accounts.google.com/login")) {
            assertFalse(url, OpenMinisNavigationPolicy.permits(url))
            rejected(command("navigate", "url" to url))
        }
        assertTrue(OpenMinisNavigationPolicy.permits("https://example.com/path?q=1"))
        assertTrue(OpenMinisNavigationPolicy.permits("http://127.0.0.1/"))
        assertTrue(OpenMinisNavigationPolicy.permits("https://notaccounts.google.com/"))
        assertEquals("https://example.com", OpenMinisCommandMapper.map(command("navigate", "url" to "https://example.com")).url)
    }
}
