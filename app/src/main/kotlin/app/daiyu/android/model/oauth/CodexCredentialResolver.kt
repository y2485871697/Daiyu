package app.daiyu.android.model.oauth

import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** Serialize rotating refresh tokens per login binding, never under provider/catalog locks. */
internal class CodexCredentialResolver(
    private val read: (CodexAuth) -> CodexTokens?,
    private val write: (CodexAuth, CodexTokens) -> Unit,
    private val remove: (CodexAuth) -> Unit,
    private val refresh: (String) -> JSONObject,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val locks = ConcurrentHashMap<CodexAuth, Any>()
    private val pending = ConcurrentHashMap<CodexAuth, CodexTokens>()
    private val refreshAfter = ConcurrentHashMap<CodexAuth, Long>()
    private fun lock(auth: CodexAuth) = locks.computeIfAbsent(auth) { Any() }
    fun clear(auth: CodexAuth) = synchronized(lock(auth)) { remove(auth); pending.remove(auth); refreshAfter.remove(auth); Unit }
    fun resolve(auth: CodexAuth): CodexTokens = synchronized(lock(auth)) {
        pending[auth]?.let { rotated -> write(auth, rotated); pending.remove(auth) }
        var tokens = checkNotNull(read(auth)) { "OAuth 登录已失效，请重新登录" }
        val now = clock()
        if (tokens.expiresAt - now < 4 * 3600_000L && tokens.refresh.isNotBlank() && now >= (refreshAfter[auth] ?: 0L)) {
            val updated = try { CodexTokens.response(refresh(tokens.refresh), now, tokens) }
            catch (cancelled: java.util.concurrent.CancellationException) { throw cancelled }
            catch (_: Exception) {
                check(now < tokens.expiresAt) { "OAuth 登录已过期，请重新登录" }
                null
            }
            refreshAfter[auth] = now + 60_000L
            if (updated != null) { pending[auth] = updated; write(auth, updated); pending.remove(auth); tokens = updated }
        }
        check(clock() < tokens.expiresAt) { "OAuth 登录已过期，请重新登录" }
        tokens
    }
}

/** The creator retains the binding until storage succeeds, including partial-save cleanup. */
internal fun persistCodexLogin(auth: CodexAuth, tokens: CodexTokens,
    save: (CodexAuth, CodexTokens) -> Unit, discard: (CodexAuth) -> Unit): CodexAuth {
    try { save(auth, tokens) }
    catch (failure: Exception) { discard(auth); throw failure }
    return auth
}
