// Codex OAuth protocol, PKCE and callback validation; see docs/来源清单.md.
package app.daiyu.android.model.oauth

import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

/** Stable credential reference, not an access token or a model capability declaration. */
data class CodexAuth(val id: String) {
    init { require(UUID.fromString(id).toString() == id) { "OAuth 引用无效" } }
    override fun toString() = "CodexAuth(<redacted>)"
}

internal class CodexTokens(val access: String, val refresh: String, val expiresAt: Long, val account: String?) {
    init {
        require(access.isNotBlank() && access.length <= 32768 && refresh.length <= 32768)
        require(listOfNotNull(access, refresh, account).all { value -> value.none { it.code < 32 || it.code == 127 } })
        require(account == null || account.length <= 512)
    }
    fun encode() = JSONObject().put("access_token", access).put("refresh_token", refresh)
        .put("expire_at", expiresAt).put("account_id", account ?: JSONObject.NULL).toString()
    override fun toString() = "CodexTokens(<redacted>)"
    companion object {
        fun read(raw: String): CodexTokens {
            val value = JSONObject(raw)
            return CodexTokens(value.getString("access_token"), value.getString("refresh_token"), value.getLong("expire_at"),
                (value.opt("account_id") as? String)?.takeIf { it.isNotBlank() })
        }
        fun response(value: JSONObject, now: Long, previous: CodexTokens? = null): CodexTokens {
            val seconds = (value.opt("expires_in") as? Number)?.toLong() ?: 0
            require(seconds in 1..31_536_000) { "OAuth 有效期无效" }
            val account = (value.opt("account_id") as? String)?.takeIf { it.isNotBlank() }
                ?: CodexOAuthProtocol.accountId(value.optString("id_token")) ?: previous?.account
            return CodexTokens(value.getString("access_token"),
                (value.opt("refresh_token") as? String) ?: previous?.refresh.orEmpty(),
                Math.addExact(now, Math.multiplyExact(seconds, 1000L)), account)
        }
    }
}

internal object CodexOAuthProtocol {
    const val BASE_URL = "https://chatgpt.com/backend-api/codex"
    const val CLIENT_VERSION = "0.144.1"
    const val CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
    const val TOKEN_URL = "https://auth.openai.com/oauth/token"
    const val REDIRECT_URI = "http://localhost:1455/auth/callback"
    private const val SCOPES = "openid profile email offline_access api.connectors.read api.connectors.invoke"
    fun browserUserAgent(default: String): String = default.replace("; wv", "").replace(" Version/4.0", "")
    fun nonce(bytes: Int = 32): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(bytes).also { SecureRandom().nextBytes(it) })
    fun challenge(verifier: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(
        MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
    fun form(values: Map<String, String>) = values.entries.joinToString("&") { (key, value) ->
        URLEncoder.encode(key, "UTF-8") + "=" + URLEncoder.encode(value, "UTF-8")
    }
    fun authorizationUrl(verifier: String, state: String) = "https://auth.openai.com/oauth/authorize?" + form(linkedMapOf(
        "client_id" to CLIENT_ID, "redirect_uri" to REDIRECT_URI, "response_type" to "code", "scope" to SCOPES,
        "state" to state, "code_challenge" to challenge(verifier), "code_challenge_method" to "S256",
        "originator" to "codex_cli_rs", "id_token_add_organizations" to "true"))
    fun isCallback(url: String): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme == "http" && uri.host in setOf("localhost", "127.0.0.1", "[::1]", "::1") &&
            uri.port == 1455 && uri.rawPath == "/auth/callback" && uri.userInfo == null && uri.fragment == null
    }.getOrDefault(false)
    fun code(url: String, expectedState: String): String {
        require(isCallback(url)) { "登录回调地址无效" }
        val fields = linkedMapOf<String, String>()
        URI(url).rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.forEach {
            val pair = it.split('=', limit = 2)
            val key = URLDecoder.decode(pair[0], "UTF-8")
            require(key !in fields) { "登录回调参数重复" }
            fields[key] = URLDecoder.decode(pair.getOrElse(1) { "" }, "UTF-8")
        }
        require(expectedState.isNotBlank() && fields["state"] == expectedState) { "OAuth state 不匹配" }
        fields["error"]?.takeIf { it.isNotEmpty() }?.let { throw CodexOAuthFailure.server(it, stage = "authorization") }
        return checkNotNull(fields["code"]?.takeIf { it.isNotBlank() && it.length <= 8192 }) { "登录回调缺少授权码" }
    }
    fun requireEndpoint(endpoint: String, path: String) {
        require(endpoint == BASE_URL && path == "/responses") { "OAuth 仅支持 OpenAI Codex Responses 地址" }
    }
    fun accountId(token: String): String? = runCatching {
        val payload = token.split('.').getOrNull(1) ?: return null
        val json = JSONObject(String(Base64.getUrlDecoder().decode(payload), Charsets.UTF_8))
        (json.opt("chatgpt_account_id") as? String) ?: (json.optJSONObject("https://api.openai.com/auth")?.opt("chatgpt_account_id") as? String)
    }.getOrNull()?.takeIf { it.isNotBlank() }
}
