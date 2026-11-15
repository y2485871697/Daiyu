package app.daiyu.android.ui.screens.chat.conversation

import org.json.JSONObject

/** One presentation owns one decode; every consumer still enforces its original input budget. */
internal class ToolPresentationJson(
    private val source: String?,
    private val parser: (String) -> JSONObject = { JSONObject(it) },
) {
    private val decoded by lazy { runCatching { parser(requireNotNull(source)) }.getOrNull() }

    fun read(limit: Int): JSONObject? {
        val value = source ?: return null
        if (value.length > limit || !value.trimStart().startsWith('{')) return null
        return decoded
    }
}
