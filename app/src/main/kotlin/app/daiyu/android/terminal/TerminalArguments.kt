package app.daiyu.android.terminal

import org.json.JSONObject

internal object TerminalArguments {
    fun execution(arguments: JSONObject): JSONObject = JSONObject(arguments.toString()).apply {
        val mode = optString("mode", "sync")
        require(mode in setOf("sync", "async", "daemon"))
        val timeout = optLong("timeout_ms", 30000)
        require(timeout in 1..120000)
        put("mode", mode)
        put("timeout_ms", timeout)
    }

    fun identity(arguments: JSONObject): JSONObject = JSONObject(arguments.toString()).apply {
        remove("timeout_ms") // Waiting policy does not identify the launched command.
        if (getString("mode") != "daemon") put("mode", "session")
    }
}
