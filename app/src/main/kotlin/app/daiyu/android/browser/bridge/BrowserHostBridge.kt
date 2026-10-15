package app.daiyu.android.browser.bridge

import app.daiyu.android.browser.session.BrowserOwner
import app.daiyu.android.browser.session.BrowserSessionRegistry
import app.daiyu.android.browser.session.BrowserTabLease
import java.util.Collections
import java.net.URI

class BrowserCommand(val action: String, val tabId: Int?, arguments: Map<String, Any?> = emptyMap()) {
    val arguments: Map<String, Any?> = Collections.unmodifiableMap(arguments.mapValues { (_, value) ->
        when {
            value is Long && value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() -> value.toInt()
            value is List<*> -> Collections.unmodifiableList(value.toList())
            else -> value
        }
    })
}
data class BrowserExecutionResult(val text: String, val filePath: String? = null)
sealed interface BrowserOutcome {
    data class Confirmed(val result: BrowserExecutionResult) : BrowserOutcome
    data class NotExecuted(val code: String) : BrowserOutcome
    data object Unknown : BrowserOutcome
}
interface BrowserExecutionPort {
    fun execute(command: BrowserCommand, lease: BrowserTabLease): BrowserExecutionResult
}

/** The caller supplies the frozen owner, not a model-controlled owner argument. */
class BrowserHostBridge(
    private val sessions: BrowserSessionRegistry,
    private val port: BrowserExecutionPort,
) {
    fun execute(owner: BrowserOwner, command: BrowserCommand): BrowserExecutionResult = executeChecked(owner, command)

    /** Any failure after entering the port is unknown, never an invitation to replay. */
    fun executeSafely(owner: BrowserOwner, command: BrowserCommand): BrowserOutcome {
        var entered = false
        return try {
            BrowserOutcome.Confirmed(executeChecked(owner, command) { entered = true })
        } catch (e: Exception) {
            if (entered) BrowserOutcome.Unknown else BrowserOutcome.NotExecuted("BROWSER_PRECHECK_REJECTED")
        }
    }
    private fun executeChecked(
        owner: BrowserOwner, command: BrowserCommand, entering: () -> Unit = {},
    ): BrowserExecutionResult {
        validate(command)
        val id = command.tabId ?: error("BROWSER_TAB_REQUIRED")
        val lease = sessions.lease(owner, id)
        sessions.assertAllowed(lease, command.action)
        entering()
        val result = port.execute(command, lease)
        return sessions.publish(lease) { result }
    }

    private fun validate(command: BrowserCommand) {
        require(command.tabId == null || command.tabId in 0..2) { "BROWSER_TAB_ID" }
        val allowed = argumentsByAction[command.action] ?: error("BROWSER_ACTION_UNKNOWN")
        require(command.arguments.keys.all { it in allowed }) { "BROWSER_ARGUMENT_UNKNOWN" }
        command.arguments.forEach { (key, value) ->
            require(value != null) { "BROWSER_ARGUMENT_NULL:$key" }
            when (key) {
                "amount", "offset", "max_chars", "max_depth", "timeout_ms",
                "viewport_width", "viewport_height", "coordinate_x", "coordinate_y", "scroll_count" -> {
                    require(value is Int) { "BROWSER_ARGUMENT_TYPE:$key" }
                    val range = when (key) {
                        "amount" -> 1..10000
                        "scroll_count" -> 1..20
                        "max_chars" -> 1..64000
                        "max_depth" -> 1..100
                        "timeout_ms" -> 1..60000
                        "viewport_width", "viewport_height" -> 320..4096
                        else -> 0..1000000
                    }
                    require(value in range) { "BROWSER_ARGUMENT_RANGE:$key" }
                }
                "keywords" -> require(value is List<*> && value.size <= 32 &&
                    value.all { it is String && it.isNotBlank() && it.length <= 128 }) {
                    "BROWSER_ARGUMENT_TYPE:$key"
                }
                "full_page", "read_image", "submit", "reset", "fuzzy" ->
                    require(value is Boolean) { "BROWSER_ARGUMENT_TYPE:$key" }
                else -> {
                    require(value is String && value.length <= 64000) { "BROWSER_ARGUMENT_TYPE:$key" }
                    if (key in setOf("url", "selector", "item_selector", "direction", "user_agent"))
                        require(value.isNotBlank()) { "BROWSER_ARGUMENT_EMPTY:$key" }
                }
            }
        }
        val required = when (command.action) {
            "navigate", "fetch" -> setOf("url")
            "execute_js" -> setOf("script")
            "type" -> setOf("text")
            "wait_for_selector" -> setOf("selector")
            "scroll_and_collect" -> setOf("item_selector")
            "set_cookies" -> setOf("cookies")
            "set_user_agent" -> setOf("user_agent")
            "scroll" -> setOf("direction")
            else -> emptySet()
        }
        require(command.arguments.keys.containsAll(required)) { "BROWSER_ARGUMENT_REQUIRED" }
        if (command.action in setOf("click", "type", "hover")) {
            val selector = "selector" in command.arguments
            val point = "coordinate_x" in command.arguments && "coordinate_y" in command.arguments
            require(selector.xor(point)) { "BROWSER_TARGET_REQUIRED" }
            require(!selector || ("coordinate_x" !in command.arguments && "coordinate_y" !in command.arguments))
        }
        if (command.action == "set_viewport") {
            val reset = command.arguments["reset"] == true
            val width = "viewport_width" in command.arguments
            val height = "viewport_height" in command.arguments
            require(if (reset) !width && !height else width && height) { "BROWSER_VIEWPORT_MODE" }
        }
        command.arguments["url"]?.let {
            val url = URI(it as String)
            require(url.scheme in setOf("https", "http") && !url.host.isNullOrBlank() && url.userInfo == null) {
                "BROWSER_URL_NOT_ALLOWED"
            }
        }
        command.arguments["direction"]?.let { require(it in setOf("up", "down")) }
        command.arguments["user_agent"]?.let { require(it in setOf("desktop_chrome", "mobile_chrome")) }
    }
    companion object {
        private val argumentsByAction = mapOf(
            "navigate" to setOf("url", "timeout_ms"),
            "get_readable" to setOf("offset", "max_chars"),
            "get_text" to setOf("selector", "offset", "max_chars"),
            "find_elements" to setOf("selector"),
            "get_page_info" to emptySet(),
            "get_backbone" to setOf("max_depth"),
            "screenshot" to setOf("full_page", "read_image"),
            "click" to setOf("selector", "coordinate_x", "coordinate_y"),
            "type" to setOf("selector", "coordinate_x", "coordinate_y", "text", "submit"),
            "hover" to setOf("selector", "coordinate_x", "coordinate_y"),
            "scroll" to setOf("selector", "direction", "amount"),
            "execute_js" to setOf("script"),
            "fetch" to setOf("url"),
            "get_cookies" to setOf("keywords", "fuzzy"),
            "scroll_and_collect" to setOf("item_selector", "scroll_count", "keywords"),
            "set_cookies" to setOf("cookies"),
            "set_user_agent" to setOf("user_agent"),
            "set_viewport" to setOf("viewport_width", "viewport_height", "reset"),
            "wait_for_selector" to setOf("selector", "timeout_ms"),
            "wait_for_dom_stable" to setOf("timeout_ms"),
            "reload" to emptySet(), "go_back" to emptySet(), "go_forward" to emptySet(),
        )
    }
}
