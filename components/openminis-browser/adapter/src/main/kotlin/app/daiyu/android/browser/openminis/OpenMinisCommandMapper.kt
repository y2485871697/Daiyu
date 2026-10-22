package app.daiyu.android.browser.openminis

import app.daiyu.android.browser.bridge.BrowserCommand
import app.daiyu.android.browser.bridge.BrowserExecutionResult
import com.openminis.app.browser.BrowserAction
import com.openminis.app.browser.BrowserActionInput
import com.openminis.app.browser.BrowserActionResult
import com.openminis.app.browser.ScrollDirection

/** Translation to the fixed manager, not to its global tab pool. No authority arguments. */
internal object OpenMinisCommandMapper {
    val hostActions = setOf("set_user_agent", "set_viewport", "reload", "go_back", "go_forward")
    private val fields = mapOf(
        "navigate" to setOf("url", "timeout_ms"),
        "get_text" to setOf("selector", "offset", "max_chars"),
        "get_readable" to setOf("offset", "max_chars"),
        "find_elements" to setOf("selector"),
        "get_page_info" to emptySet(),
        "get_backbone" to setOf("max_depth"),
        "screenshot" to setOf("full_page", "read_image"),
        "click" to setOf("selector", "coordinate_x", "coordinate_y"),
        "type" to setOf("selector", "coordinate_x", "coordinate_y", "text", "submit"),
        "hover" to setOf("selector", "coordinate_x", "coordinate_y"),
        "scroll" to setOf("selector", "direction", "amount"),
        "execute_js" to setOf("script"),
        "set_user_agent" to setOf("user_agent"),
        "set_viewport" to setOf("viewport_width", "viewport_height", "reset"),
        "wait_for_selector" to setOf("selector", "timeout_ms"),
        "wait_for_dom_stable" to setOf("timeout_ms"),
        "scroll_and_collect" to setOf("item_selector", "scroll_count", "keywords", "fuzzy"),
        "reload" to emptySet(), "go_back" to emptySet(), "go_forward" to emptySet(),
    )

    /** Also usable before dedicated host-method dispatch (viewport/history). Never echoes inputs. */
    fun validate(command: BrowserCommand) {
        require(command.tabId == null || command.tabId in 0..2) { "BROWSER_TAB_ID" }
        require(command.action !in setOf("fetch", "get_cookies", "set_cookies")) {
            "BROWSER_OPENMINIS_DEDICATED_CAPABILITY_REQUIRED"
        }
        val allowed = fields[command.action] ?: error("BROWSER_OPENMINIS_ACTION_UNSUPPORTED")
        val args = command.arguments
        require(args.keys.all { it in allowed }) { "BROWSER_OPENMINIS_ARGUMENT_UNSUPPORTED" }
        args.forEach { (key, value) ->
            require(value != null) { "BROWSER_OPENMINIS_ARGUMENT_NULL" }
            when (key) {
                "offset", "max_chars", "max_depth", "timeout_ms", "amount", "scroll_count",
                "coordinate_x", "coordinate_y", "viewport_width", "viewport_height" -> {
                    require(value is Int) { "BROWSER_OPENMINIS_ARGUMENT_TYPE" }
                    val range = when (key) {
                        "max_chars" -> 1..64_000
                        "max_depth" -> 1..100
                        "timeout_ms" -> 1..60_000
                        "amount" -> 1..10_000
                        "scroll_count" -> 1..50 // Manager clamps; do not silently change a request.
                        "viewport_width", "viewport_height" -> 320..4096
                        else -> 0..1_000_000
                    }
                    require(value in range) { "BROWSER_OPENMINIS_ARGUMENT_RANGE" }
                }
                "full_page", "read_image", "submit", "reset", "fuzzy" ->
                    require(value is Boolean) { "BROWSER_OPENMINIS_ARGUMENT_TYPE" }
                "keywords" -> {
                    require(value is List<*> && value.size <= 32 && value.all {
                        it is String && it.length <= 128
                    }) { "BROWSER_OPENMINIS_KEYWORDS_INVALID" }
                }
                else -> {
                    require(value is String && value.length <= 64_000) { "BROWSER_OPENMINIS_ARGUMENT_TYPE" }
                    if (key != "text") require(value.isNotBlank()) { "BROWSER_OPENMINIS_ARGUMENT_EMPTY" }
                }
            }
        }
        val required = when (command.action) {
            "navigate" -> setOf("url")
            "type" -> setOf("text")
            "execute_js" -> setOf("script")
            "find_elements", "wait_for_selector" -> setOf("selector")
            "scroll" -> setOf("direction")
            "set_user_agent" -> setOf("user_agent")
            "scroll_and_collect" -> setOf("item_selector")
            else -> emptySet()
        }
        require(args.keys.containsAll(required)) { "BROWSER_OPENMINIS_ARGUMENT_REQUIRED" }
        if (command.action in setOf("click", "type", "hover")) {
            val selector = "selector" in args
            val x = "coordinate_x" in args
            val y = "coordinate_y" in args
            require(if (selector) !x && !y else x && y) { "BROWSER_OPENMINIS_TARGET_REQUIRED" }
        }
        if (command.action == "set_viewport") {
            val width = "viewport_width" in args
            val height = "viewport_height" in args
            require(if (args["reset"] == true) !width && !height else width && height) {
                "BROWSER_OPENMINIS_VIEWPORT_MODE"
            }
        }
        args["url"]?.let {
            require(OpenMinisNavigationPolicy.permits(it as String)) { "BROWSER_OPENMINIS_URL_UNSUPPORTED" }
        }
        args["direction"]?.let { require(it in setOf("up", "down")) { "BROWSER_OPENMINIS_DIRECTION" } }
        args["user_agent"]?.let {
            require(it in setOf("desktop_chrome", "mobile_chrome")) { "BROWSER_OPENMINIS_USER_AGENT" }
        }
    }

    /** Backend support is checked before the facade enters its execution port.
     * Dedicated cookie/download routes are validated by the bridge and their own backends.
     */
    fun preflight(command: BrowserCommand) {
        if (command.action in setOf("fetch", "get_cookies", "set_cookies")) return
        if (command.action in hostActions) validate(command) else map(command)
    }

    fun map(command: BrowserCommand): BrowserActionInput {
        validate(command)
        val args = command.arguments
        return when (command.action) {
            "navigate" -> {
                // Manager.navigate has a fixed timeout and never consumes input.timeoutMs.
                require("timeout_ms" !in args) { "BROWSER_OPENMINIS_NAVIGATION_TIMEOUT_UNSUPPORTED" }
                BrowserActionInput(BrowserAction.NAVIGATE, url = args["url"] as String)
            }
            "get_text", "get_readable" -> BrowserActionInput(
                BrowserAction.EXECUTE_JS, script = BrowserOutputBody.script(command),
            )
            "find_elements" -> BrowserActionInput(BrowserAction.FIND_ELEMENTS, selector = args["selector"] as String)
            "get_page_info" -> BrowserActionInput(BrowserAction.GET_PAGE_INFO)
            "get_backbone" -> BrowserActionInput(BrowserAction.GET_BACKBONE, maxDepth = args["max_depth"] as Int?)
            "screenshot" -> {
                require(args["read_image"] != true) { "BROWSER_OPENMINIS_IMAGE_READER_REQUIRED" }
                BrowserActionInput(BrowserAction.SCREENSHOT, fullPage = args["full_page"] == true)
            }
            "click" -> BrowserActionInput(BrowserAction.CLICK, selector = args["selector"] as String?,
                coordinateX = args["coordinate_x"] as Int?, coordinateY = args["coordinate_y"] as Int?)
            "type" -> {
                // Fixed manager.type consumes selector/text only, with no submit mode.
                require("selector" in args && args["submit"] != true) { "BROWSER_OPENMINIS_TYPE_MODE_UNSUPPORTED" }
                BrowserActionInput(BrowserAction.TYPE, selector = args["selector"] as String, text = args["text"] as String)
            }
            "hover" -> {
                require("selector" in args) { "BROWSER_OPENMINIS_HOVER_COORDINATES_UNSUPPORTED" }
                BrowserActionInput(BrowserAction.HOVER, selector = args["selector"] as String)
            }
            "scroll" -> BrowserActionInput(BrowserAction.SCROLL, selector = args["selector"] as String?,
                direction = ScrollDirection.fromString(args["direction"] as String), amount = args["amount"] as Int?)
            "execute_js" -> BrowserActionInput(BrowserAction.EXECUTE_JS, script = args["script"] as String)
            "set_user_agent" -> error("BROWSER_OPENMINIS_USER_AGENT_UNSUPPORTED")
            "wait_for_dom_stable" -> {
                val timeout = args["timeout_ms"] as Int?
                require(timeout == null || timeout >= 1000) { "BROWSER_OPENMINIS_DOM_TIMEOUT_UNSUPPORTED" }
                BrowserActionInput(BrowserAction.WAIT_FOR_DOM_STABLE, timeoutMs = timeout)
            }
            "wait_for_selector" -> {
                val timeout = args["timeout_ms"] as Int? ?: 5000
                // executeJS itself has a fixed 30s limit; leave time for its bridge callback.
                require(timeout <= 29_000) { "BROWSER_OPENMINIS_SELECTOR_TIMEOUT_UNSUPPORTED" }
                val selector = BrowserOutputBody.quote(args["selector"] as String)
                BrowserActionInput(BrowserAction.EXECUTE_JS, script = """
                    const deadline = Date.now() + $timeout;
                    do {
                        if (document.querySelector($selector)) return 'Selector found';
                        if (Date.now() >= deadline) throw new Error('BROWSER_OPENMINIS_SELECTOR_TIMEOUT');
                        await new Promise(resolve => setTimeout(resolve, Math.min(50, Math.max(1, deadline - Date.now()))));
                    } while (true);
                """.trimIndent())
            }
            "scroll_and_collect" -> {
                // Manager always does substring matching and does not consume input.fuzzy.
                require("fuzzy" !in args) { "BROWSER_OPENMINIS_COLLECT_FUZZY_UNSUPPORTED" }
                @Suppress("UNCHECKED_CAST")
                val keywords = (args["keywords"] as List<String>?)?.toList()
                BrowserActionInput(BrowserAction.SCROLL_AND_COLLECT, itemSelector = args["item_selector"] as String,
                    scrollCount = args["scroll_count"] as Int?, keywords = keywords)
            }
            // SET_VIEWPORT is rejected by manager.execute; history has no BrowserAction enum.
            "set_viewport", "reload", "go_back", "go_forward" -> error("BROWSER_OPENMINIS_HOST_ACTION_REQUIRED")
            else -> error("BROWSER_OPENMINIS_ACTION_UNSUPPORTED")
        }
    }

    /** Command-aware publication seam. A failure MUST throw, never return a Confirmed-shaped value. */
    fun result(command: BrowserCommand, answer: BrowserActionResult): BrowserExecutionResult {
        validate(command)
        if (command.action !in hostActions) map(command)
        return BrowserOutput.convert(command, answer)
    }
}
