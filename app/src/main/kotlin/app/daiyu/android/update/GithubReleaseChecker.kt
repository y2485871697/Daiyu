package app.daiyu.android.update

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

internal const val RELEASE_API_URL = "https://api.github.com/repos/y2485871697/Daiyu/releases/latest"
internal const val RELEASE_WEB_URL = "https://github.com/y2485871697/Daiyu/releases"
internal const val MAX_RELEASE_BYTES = 256 * 1024

internal sealed interface ReleaseCheckResult {
    data class Available(val tag: String, val page: String, val comparison: Int?) : ReleaseCheckResult
    data class Failed(val reason: ReleaseCheckFailure) : ReleaseCheckResult
}
internal enum class ReleaseCheckFailure { NETWORK, NOT_FOUND, RATE_LIMITED, HTTP, INVALID_RESPONSE, TOO_LARGE }
internal fun interface ReleaseCheck { suspend fun check(currentVersion: String): ReleaseCheckResult }

internal class GithubReleaseChecker(private val calls: Call.Factory = client) : ReleaseCheck {
    override suspend fun check(currentVersion: String): ReleaseCheckResult {
        val request = Request.Builder().url(RELEASE_API_URL).get()
            .header("Accept", "application/vnd.github+json").header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "Daiyu-update-checker").header("Cache-Control", "no-store")
            .tag(OneShot::class.java, OneShot()).build()
        return suspendCancellableCoroutine { continuation ->
            val call = calls.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resume(ReleaseCheckResult.Failed(ReleaseCheckFailure.NETWORK))
                }
                override fun onResponse(call: Call, response: Response) {
                    val result = try { response.use { read(it, currentVersion) } }
                    catch (_: TooLarge) { ReleaseCheckResult.Failed(ReleaseCheckFailure.TOO_LARGE) }
                    catch (_: CharacterCodingException) { ReleaseCheckResult.Failed(ReleaseCheckFailure.INVALID_RESPONSE) }
                    catch (_: IOException) { ReleaseCheckResult.Failed(ReleaseCheckFailure.NETWORK) }
                    catch (_: Exception) { ReleaseCheckResult.Failed(ReleaseCheckFailure.INVALID_RESPONSE) }
                    if (continuation.isActive) continuation.resume(result)
                }
            })
        }
    }
    private fun read(response: Response, currentVersion: String): ReleaseCheckResult {
        if (response.code != 200) return ReleaseCheckResult.Failed(when {
            response.code == 404 -> ReleaseCheckFailure.NOT_FOUND
            response.code == 429 || (response.code == 403 && response.header("X-RateLimit-Remaining") == "0") -> ReleaseCheckFailure.RATE_LIMITED
            else -> ReleaseCheckFailure.HTTP
        })
        val body = response.body ?: return ReleaseCheckResult.Failed(ReleaseCheckFailure.INVALID_RESPONSE)
        if (body.contentLength() > MAX_RELEASE_BYTES) throw TooLarge()
        val bytes = ByteArrayOutputStream()
        body.byteStream().use { input ->
            val chunk = ByteArray(4096)
            while (true) {
                val count = input.read(chunk)
                if (count < 0) break
                if (bytes.size() + count > MAX_RELEASE_BYTES) throw TooLarge()
                bytes.write(chunk, 0, count)
            }
        }
        return decodeStableRelease(bytes.toByteArray(), currentVersion)
    }
    private class TooLarge : IOException()
    private class OneShot { val used = AtomicBoolean() }
    companion object {
        internal fun newClient(): OkHttpClient = OkHttpClient.Builder()
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
            .cookieJar(CookieJar.NO_COOKIES).cache(null)
            .connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS)
            .addNetworkInterceptor { chain ->
                val gate = chain.request().tag(OneShot::class.java) ?: throw IOException("missing_update_permit")
                if (!gate.used.compareAndSet(false, true)) throw IOException("update_already_requested")
                val response = chain.proceed(chain.request())
                if (response.code in setOf(408, 503)) response.newBuilder().header("Retry-After", "2147483647").build()
                else response
            }.build()
        private val client by lazy { newClient() }
    }
}

internal fun decodeStableRelease(bytes: ByteArray, currentVersion: String): ReleaseCheckResult.Available {
    require(bytes.size <= MAX_RELEASE_BYTES)
    val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    val parser = JSONTokener(text)
    val json = parser.nextValue() as? JSONObject ?: error("release_object_required")
    require(parser.nextClean() == '\u0000')
    require(json.get("draft") == false && json.get("prerelease") == false)
    val tag = json.get("tag_name") as? String ?: error("release_tag_required")
    require(tag.length in 1..128 && tag.isNotBlank() && tag.none { it.isISOControl() })
    val page = RELEASE_WEB_URL.toHttpUrl().newBuilder().addPathSegment("tag").addPathSegment(tag).build().toString()
    return ReleaseCheckResult.Available(tag, page, compareStableVersions(currentVersion, tag))
}

/** Null means the tag cannot be compared; development commit ordering is not inferred. */
internal fun compareStableVersions(current: String, release: String): Int? {
    val pattern = Regex("v?(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?")
    fun parts(value: String): List<BigInteger>? = value.takeIf { it.length in 1..128 }
        ?.let { pattern.matchEntire(it) }?.groupValues?.drop(1)?.map { BigInteger(it) }
    val installed = parts(current) ?: return null
    val published = parts(release) ?: return null
    return published.zip(installed).firstNotNullOfOrNull { (latest, local) -> latest.compareTo(local).takeIf { it != 0 } } ?: 0
}
