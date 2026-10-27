package app.daiyu.android.model.oauth

import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CancellationException

internal enum class CodexOAuthFailureReason {
    AUTHORIZATION_DENIED, REGION_UNSUPPORTED, AUTHORIZATION_EXPIRED, FORBIDDEN,
    RATE_LIMITED, SERVER, NETWORK, INVALID_RESPONSE, TOKEN_REJECTED, STORAGE, TIMEOUT,
}

internal class CodexOAuthFailure(
    val reason: CodexOAuthFailureReason,
    val stage: String,
    val httpStatus: Int? = null,
    val serverCode: String? = null,
) : IllegalStateException(when (reason) {
    CodexOAuthFailureReason.AUTHORIZATION_DENIED -> "OpenAI 授权未获批准，请重新登录"
    CodexOAuthFailureReason.REGION_UNSUPPORTED -> "OpenAI 服务端拒绝了当前地区，当前登录无法继续"
    CodexOAuthFailureReason.AUTHORIZATION_EXPIRED -> "OpenAI 授权已失效，请重新登录"
    CodexOAuthFailureReason.FORBIDDEN -> "OpenAI 拒绝了当前登录请求，请检查账号与服务可用性"
    CodexOAuthFailureReason.RATE_LIMITED -> "OpenAI 请求过于频繁，请稍后重新登录"
    CodexOAuthFailureReason.SERVER -> "OpenAI 服务暂时不可用，请稍后重新登录"
    CodexOAuthFailureReason.NETWORK -> "无法连接 OpenAI，请检查网络后重新登录"
    CodexOAuthFailureReason.INVALID_RESPONSE -> "OpenAI 登录响应无效，请重新登录"
    CodexOAuthFailureReason.TOKEN_REJECTED -> "OpenAI 令牌请求被拒绝，请重新登录"
    CodexOAuthFailureReason.STORAGE -> "无法保存 OpenAI 登录凭据，请检查本地存储"
    CodexOAuthFailureReason.TIMEOUT -> "OpenAI 登录超时，请重新登录"
} + (httpStatus?.let { "（HTTP $it）" } ?: "")) {
    companion object {
        fun server(code: String?, status: Int? = null, stage: String = "token"): CodexOAuthFailure {
            val known = code?.takeIf { it in setOf("unsupported_country_region_territory", "access_denied", "invalid_grant",
                "login_required", "request_forbidden", "server_error", "temporarily_unavailable") }
            val reason = if (status == 403 && known != "unsupported_country_region_territory") {
                CodexOAuthFailureReason.FORBIDDEN
            } else when (known) {
                "unsupported_country_region_territory" -> CodexOAuthFailureReason.REGION_UNSUPPORTED
                "access_denied" -> CodexOAuthFailureReason.AUTHORIZATION_DENIED
                "invalid_grant", "login_required" -> CodexOAuthFailureReason.AUTHORIZATION_EXPIRED
                "request_forbidden" -> CodexOAuthFailureReason.FORBIDDEN
                "server_error", "temporarily_unavailable" -> CodexOAuthFailureReason.SERVER
                else -> when {
                    stage == "authorization" -> CodexOAuthFailureReason.AUTHORIZATION_DENIED
                    status == 401 -> CodexOAuthFailureReason.AUTHORIZATION_EXPIRED
                    status == 403 -> CodexOAuthFailureReason.FORBIDDEN
                    status == 429 -> CodexOAuthFailureReason.RATE_LIMITED
                    status != null && status >= 500 -> CodexOAuthFailureReason.SERVER
                    else -> CodexOAuthFailureReason.TOKEN_REJECTED
                }
            }
            return CodexOAuthFailure(reason, stage, status, known)
        }
    }
}

internal object CodexOAuthHttp {
    fun post(values: Map<String, String>, connection: () -> HttpURLConnection = {
        URL(CodexOAuthProtocol.TOKEN_URL).openConnection() as HttpURLConnection
    }): JSONObject {
        val conn = try { connection() } catch (_: IOException) {
            throw CodexOAuthFailure(CodexOAuthFailureReason.NETWORK, "token")
        }
        try {
            conn.instanceFollowRedirects = false; conn.requestMethod = "POST"; conn.doOutput = true
            conn.connectTimeout = 15_000; conn.readTimeout = 15_000
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            val body = CodexOAuthProtocol.form(values).toByteArray(Charsets.UTF_8)
            conn.setFixedLengthStreamingMode(body.size)
            conn.outputStream.use { it.write(body) }
            val status = conn.responseCode
            if (status !in 200..299) {
                val code = try {
                    conn.errorStream?.use { input ->
                        val json = JSONObject(String(readBounded(input, 8192, truncate = true), Charsets.UTF_8))
                        (json.opt("error") as? String) ?: json.optJSONObject("error")?.let { error ->
                            (error.opt("code") as? String) ?: (error.opt("type") as? String)
                        }
                    }
                } catch (_: IOException) { null } catch (_: org.json.JSONException) { null }
                throw CodexOAuthFailure.server(code, status)
            }
            val bytes = conn.inputStream.use { readBounded(it, 128 * 1024) }
            return try { JSONObject(String(bytes, Charsets.UTF_8)) }
            catch (_: org.json.JSONException) { throw CodexOAuthFailure(CodexOAuthFailureReason.INVALID_RESPONSE, "token", status) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: CodexOAuthFailure) { throw failure }
        catch (_: IOException) { throw CodexOAuthFailure(CodexOAuthFailureReason.NETWORK, "token") }
        finally { conn.disconnect() }
    }
    private fun readBounded(input: InputStream, limit: Int, truncate: Boolean = false): ByteArray {
        val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(4096)
        while (true) {
            if (truncate && output.size() == limit) break
            val count = input.read(buffer, 0, minOf(buffer.size, limit - output.size() + if (truncate) 0 else 1))
            if (count < 0) break
            if (output.size() + count > limit) throw CodexOAuthFailure(CodexOAuthFailureReason.INVALID_RESPONSE, "token")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
}
