package app.daiyu.android.model.oauth

import android.content.Context
import kotlinx.coroutines.launch
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
        { token -> CodexOAuthHttp.post(linkedMapOf("grant_type" to "refresh_token", "client_id" to CodexOAuthProtocol.CLIENT_ID, "refresh_token" to token)) }) }
    fun clear(auth: CodexAuth) = resolver.clear(auth)
    private val cleanup = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    private val discarded = java.util.concurrent.ConcurrentHashMap.newKeySet<CodexAuth>()
    fun discard(auth: CodexAuth) {
        discarded.add(auth)
        cleanup.launch { runCatching { clear(auth) }.onSuccess { discarded.remove(auth) } }
    }
    fun exchange(code: String, verifier: String): CodexAuth {
        val response = CodexOAuthHttp.post(linkedMapOf("grant_type" to "authorization_code", "client_id" to CodexOAuthProtocol.CLIENT_ID,
            "code" to code, "redirect_uri" to CodexOAuthProtocol.REDIRECT_URI, "code_verifier" to verifier))
        val tokens = try { CodexTokens.response(response, System.currentTimeMillis()) }
        catch (_: Exception) { throw CodexOAuthFailure(CodexOAuthFailureReason.INVALID_RESPONSE, "token") }
        val auth = CodexAuth(UUID.randomUUID().toString())
        return try { persistCodexLogin(auth, tokens, { binding, value -> synchronized(lock) { storage().save(binding, value) } }, ::discard) }
        catch (cancelled: java.util.concurrent.CancellationException) { throw cancelled }
        catch (_: Exception) { throw CodexOAuthFailure(CodexOAuthFailureReason.STORAGE, "storage") }
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
}
