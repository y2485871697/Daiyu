package app.daiyu.android.model.sessiongateway

import app.daiyu.android.domain.ConversationId
import org.json.JSONObject
import java.net.URL
import java.security.MessageDigest
import java.util.UUID

/** Captured only AFTER the durable conversation is resolved. Never a run or ModelSession ID. */
data class SessionGatewayContext(val conversationId: ConversationId) {
    /** No trusted project identity is currently available. Do not synthesize one from the app name. */
    val project: String? get() = null
    override fun toString(): String = "SessionGatewayContext(conversationId=<redacted>, project=null)"
}

/** Request-local adaptation. Does not select providers, own storage, or grant network permission. */
object SessionGateway {
    fun applyResponses(request: JSONObject, finalUrl: String, rule: SessionGatewayRule,
                       context: SessionGatewayContext?) {
        rule.requireExecutable()
        if (context == null) return
        val model = request.opt("model") as? String ?: return
        if (rule.matches(model, finalUrl) || isCodex(finalUrl)) {
            request.put(rule.safeKeyField(), context.conversationId.value)
        }
    }

    /** Runtime-owned headers: caller must apply after any custom header merge on EVERY POST. */
    fun headers(finalUrl: String, context: SessionGatewayContext?): Map<String, String> {
        if (context == null) return emptyMap()
        val id = context.conversationId.value
        require(id.none { it == '\r' || it == '\n' || it.code < 32 || it.code == 127 }) { "会话标识不能用于请求头" }
        return buildMap {
            put("session-id", id)
            put("thread-id", id)
            if (runCatching { URL(finalUrl).host.lowercase() }.getOrNull() == "opencode.ai") {
                put("x-opencode-session", UUID.nameUUIDFromBytes(id.toByteArray(Charsets.UTF_8)).toString())
            }
        }
    }

    /** Historical protocol constants, not branding: changing either changes gateway identity. */
    fun applyAnthropic(request: JSONObject, context: SessionGatewayContext?) {
        if (context == null) return
        val metadata = if (request.has("metadata")) request.optJSONObject("metadata") ?: return
            else JSONObject().also { request.put("metadata", it) }
        if (metadata.has("user_id")) return
        val digest = MessageDigest.getInstance("SHA-256")
            .digest((ANTHROPIC_DOMAIN + context.conversationId.value).toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        metadata.put("user_id", ANTHROPIC_PREFIX + hex)
    }

    private fun isCodex(url: String): Boolean = runCatching {
        val parsed = URL(url)
        parsed.protocol == "https" && parsed.host.equals("chatgpt.com", ignoreCase = true) &&
            (parsed.path == "/backend-api/codex" || parsed.path.startsWith("/backend-api/codex/"))
    }.getOrDefault(false)

    private const val ANTHROPIC_DOMAIN = "eta-anthropic-session-v1\u0000"
    private const val ANTHROPIC_PREFIX = "eta-session-"
}
