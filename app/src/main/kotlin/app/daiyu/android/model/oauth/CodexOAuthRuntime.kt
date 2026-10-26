package app.daiyu.android.model.oauth

import android.content.Context
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

internal object CodexOAuthRuntime {
    @Volatile private var store: CodexTokenStore? = null
    private val lock = Any()
    fun initialize(context: Context) {
        if (store == null) synchronized(lock) { if (store == null) store = CodexTokenStore(context) }
        discarded.toList().forEach(::discard)
    }
    private fun storage() = checkNotNull(store) { "OAuth 尚未初始化" }
    private val resolver by lazy { CodexCredentialResolver(
        { storage().read(it) }, { auth, tokens -> storage().save(auth, tokens) }, { storage().clear(it) },
        { token -> post(linkedMapOf("grant_type" to "refresh_token", "client_id" to CodexOAuthProtocol.CLIENT_ID, "refresh_token" to token)) }) }
    fun clear(auth: CodexAuth) = resolver.clear(auth)
    private val cleanup = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    private val discarded = java.util.concurrent.ConcurrentHashMap.newKeySet<CodexAuth>()
    fun discard(auth: CodexAuth) {
        discarded.add(auth)
        cleanup.launch { runCatching { clear(auth) }.onSuccess { discarded.remove(auth) } }
    }
    fun exchange(code: String, verifier: String): CodexAuth {
        val tokens = CodexTokens.response(post(linkedMapOf("grant_type" to "authorization_code", "client_id" to CodexOAuthProtocol.CLIENT_ID,
            "code" to code, "redirect_uri" to CodexOAuthProtocol.REDIRECT_URI, "code_verifier" to verifier)), System.currentTimeMillis())
        val auth = CodexAuth(UUID.randomUUID().toString())
        return persistCodexLogin(auth, tokens, { binding, value -> synchronized(lock) { storage().save(binding, value) } }, ::discard)
    }
    fun headers(auth: CodexAuth): Map<String, String> {
        val tokens = resolver.resolve(auth)
        return buildMap {
            put("Authorization", "Bearer ${tokens.access}")
            put("User-Agent", "codex_cli_rs/${CodexOAuthProtocol.CLIENT_VERSION} (Android; arm64)")
            put("Originator", "codex_cli_rs"); put("Version", CodexOAuthProtocol.CLIENT_VERSION)
            tokens.account?.let { put("ChatGPT-Account-ID", it) }
        }
    }
    private fun post(values: Map<String, String>): JSONObject {
        val conn = URL(CodexOAuthProtocol.TOKEN_URL).openConnection() as HttpURLConnection
        try {
            conn.instanceFollowRedirects = false; conn.requestMethod = "POST"; conn.doOutput = true
            conn.connectTimeout = 15_000; conn.readTimeout = 15_000
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            val body = CodexOAuthProtocol.form(values).toByteArray(Charsets.UTF_8); conn.setFixedLengthStreamingMode(body.size)
            conn.outputStream.use { it.write(body) }
            check(conn.responseCode in 200..299) { "OAuth 令牌交换失败 (${conn.responseCode})" }
            val bytes = conn.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(4096)
                while (true) {
                    val count = input.read(buffer); if (count < 0) break
                    require(output.size() + count <= 128 * 1024); output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            return JSONObject(String(bytes, Charsets.UTF_8))
        } catch (cancelled: java.util.concurrent.CancellationException) { throw cancelled }
        catch (_: Exception) { throw IllegalStateException("OAuth 令牌交换失败，请重新登录或检查网络") }
        finally { conn.disconnect() }
    }
}
