package app.daiyu.android.model

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.Base64

/** Wire-only projection: canonical receipts, protocol snapshots and raw terminal logs stay intact. */
internal object TerminalToolResultProjection {
    private val terminalTools = setOf("terminal_exec", "terminal_read", "terminal_job_start",
        "terminal_job_read", "terminal_pty_step", "terminal_pty_read")
    fun output(name: String?, raw: String): String {
        if (name !in terminalTools || !raw.contains("\"stdout_base64\"")) return raw
        val result = try { MessagesRequest.jsonObject(raw, rejectDuplicateKeys = true) }
        catch (_: IllegalArgumentException) { return raw }
        catch (_: JSONException) { return raw }
        val text = result.opt("stdout") as? String ?: return raw
        val encoded = result.opt("stdout_base64") as? String ?: return raw
        // Preserve binary/non-UTF-8 output when the text is not a lossless representation.
        if (Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8)) != encoded) return raw
        result.remove("stdout_base64")
        return result.toString()
    }

    fun request(body: JSONObject, protocol: ProviderProtocol): JSONObject {
        val field = when (protocol) {
            ProviderProtocol.RESPONSES -> "input"
            ProviderProtocol.GEMINI -> "contents"
            else -> "messages"
        }
        val rows = body.optJSONArray(field) ?: return body
        val projected = when (protocol) {
            ProviderProtocol.RESPONSES -> responses(rows)
            ProviderProtocol.MESSAGES -> messages(rows)
            ProviderProtocol.CHAT_COMPLETIONS -> chat(rows)
            ProviderProtocol.GEMINI -> gemini(rows)
            else -> return body
        }
        return if (projected === rows) body else copy(body).put(field, projected)
    }

    private fun responses(rows: JSONArray): JSONArray {
        val calls = mutableMapOf<String, String>()
        return map(rows) { item ->
            when (item.optString("type")) {
                "function_call" -> {
                    remember(calls, item.optString("call_id"), item.optString("name")); item
                }
                "function_call_output" -> project(item, "output", calls.remove(item.optString("call_id")))
                else -> item
            }
        }
    }

    private fun messages(rows: JSONArray): JSONArray {
        val calls = mutableMapOf<String, String>()
        return map(rows) { row ->
            val content = row.optJSONArray("content")
            if (row.optString("role") == "assistant" && content != null) {
                for (index in 0 until content.length()) {
                    val call = content.optJSONObject(index) ?: continue
                    if (call.optString("type") == "tool_use") remember(calls, call.optString("id"), call.optString("name"))
                }
            }
            if (row.optString("role") != "user" || content == null) row
            else replace(row, "content", content, map(content) { result ->
                if (result.optString("type") != "tool_result") result
                else {
                    val name = calls.remove(result.optString("tool_use_id"))
                    val parts = result.optJSONArray("content")
                    if (parts == null) project(result, "content", name)
                    else replace(result, "content", parts, map(parts) { part ->
                        if (part.optString("type") == "text") project(part, "text", name) else part
                    })
                }
            })
        }
    }

    private fun chat(rows: JSONArray): JSONArray {
        val calls = mutableMapOf<String, String>()
        return map(rows) { row ->
            if (row.optString("role") == "assistant") row.optJSONArray("tool_calls")?.let { tools ->
                for (index in 0 until tools.length()) {
                    val call = tools.optJSONObject(index) ?: continue
                    val function = call.optJSONObject("function") ?: continue
                    remember(calls, call.optString("id"), function.optString("name"))
                }
            }
            if (row.optString("role") == "tool") project(row, "content", calls.remove(row.optString("tool_call_id"))) else row
        }
    }

    private fun gemini(rows: JSONArray): JSONArray {
        val calls = mutableMapOf<String, String>()
        return map(rows) { row ->
            val parts = row.optJSONArray("parts")
            if (row.optString("role") == "model" && parts != null) {
                for (index in 0 until parts.length()) {
                    val call = parts.optJSONObject(index)?.optJSONObject("functionCall") ?: continue
                    remember(calls, call.optString("id"), call.optString("name"))
                }
            }
            if (row.optString("role") != "user" || parts == null) row
            else replace(row, "parts", parts, map(parts) { part ->
                val function = part.optJSONObject("functionResponse")
                val response = function?.optJSONObject("response")
                val name = function?.let { calls.remove(it.optString("id")) }
                if (function == null || response == null || name != function.optString("name")) part
                else {
                    val projected = project(response, "output", name)
                    if (projected === response) part
                    else copy(part).put("functionResponse", copy(function).put("response", projected))
                }
            })
        }
    }

    private fun remember(calls: MutableMap<String, String>, id: String, name: String) {
        if (id.isNotEmpty()) calls[id] = if (calls[id]?.let { it != name } == true) "" else name
    }

    private fun project(node: JSONObject, key: String, name: String?): JSONObject {
        val raw = node.opt(key) as? String ?: return node
        val projected = output(name, raw)
        return if (projected == raw) node else copy(node).put(key, projected)
    }

    private fun replace(node: JSONObject, key: String, original: JSONArray, projected: JSONArray): JSONObject =
        if (original === projected) node else copy(node).put(key, projected)

    private fun copy(node: JSONObject) = JSONObject().also { result ->
        node.keys().forEach { key -> result.put(key, node.get(key)) }
    }

    private fun map(rows: JSONArray, transform: (JSONObject) -> JSONObject): JSONArray {
        var result: JSONArray? = null
        for (index in 0 until rows.length()) {
            val original = rows.get(index)
            val projected = (original as? JSONObject)?.let(transform) ?: original
            if (projected !== original && result == null) {
                result = JSONArray()
                for (previous in 0 until index) result.put(rows.get(previous))
            }
            result?.put(projected)
        }
        return result ?: rows
    }
}
