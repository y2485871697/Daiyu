package app.daiyu.android.balance

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONTokener
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

fun interface BalanceQuery {
    suspend fun query(configuration: BalanceConfiguration): BalanceResult
}

/** A GET using the explicitly saved independent config and preserved URL rules; no model calls/logging/follow-ups. */
class BalanceHttpQuery internal constructor(private val calls: Call.Factory) : BalanceQuery {
    constructor() : this(newClient())

    override suspend fun query(configuration: BalanceConfiguration): BalanceResult {
        configuration.validate()
        if (!configuration.queryEnabled) return BalanceResult.Unknown
        val request = Request.Builder().url(configuration.resolvedEndpoint()).get()
            .apply { configuration.customHeaders.forEach { (name, value) -> header(name, value) } }
            .apply { configuration.resolvedToken().takeIf { it.isNotBlank() }?.let { header("Authorization", "Bearer $it") } }
            .header("Accept", "application/json")
            .header("Cache-Control", "no-store")
            .tag(OneShotGate::class.java, OneShotGate()).build()
        return suspendCancellableCoroutine { continuation ->
            val call = calls.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resume(BalanceResult.Failed(BalanceFailure.NETWORK))
                }
                override fun onResponse(call: Call, response: Response) {
                    val result = try { response.use { readResponse(it, configuration) } }
                    catch (_: TooLarge) { BalanceResult.Failed(BalanceFailure.RESPONSE_TOO_LARGE) }
                    catch (_: IOException) { BalanceResult.Failed(BalanceFailure.NETWORK) }
                    catch (_: Exception) { BalanceResult.Failed(BalanceFailure.INVALID_RESPONSE) }
                    if (continuation.isActive) continuation.resume(result)
                }
            })
        }
    }

    private fun readResponse(response: Response, configuration: BalanceConfiguration): BalanceResult {
        if (!response.isSuccessful) return BalanceResult.Failed(when (response.code) {
            401, 403 -> BalanceFailure.AUTHORIZATION
            429 -> BalanceFailure.RATE_LIMITED
            else -> BalanceFailure.HTTP // includes every redirect; never follow it with a credential.
        })
        val body = response.body ?: return BalanceResult.Unknown
        if (body.contentLength() > MAX_RESPONSE_BYTES) throw TooLarge()
        val bytes = ByteArrayOutputStream()
        body.byteStream().use { input ->
            val chunk = ByteArray(4096)
            while (true) {
                val count = input.read(chunk)
                if (count < 0) break
                if (bytes.size() + count > MAX_RESPONSE_BYTES) throw TooLarge()
                bytes.write(chunk, 0, count)
            }
        }
        return BalanceResponseDecoder.decode(bytes.toByteArray(), configuration, System.currentTimeMillis())
    }

    private class TooLarge : IOException()
    private class OneShotGate { val used = AtomicBoolean(false) }

    companion object {
        const val MAX_RESPONSE_BYTES = 256 * 1024
        internal fun newClient(): OkHttpClient = OkHttpClient.Builder()
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
            .cookieJar(CookieJar.NO_COOKIES).cache(null)
            .connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS).callTimeout(ProviderBalanceFetcher.TOTAL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .addNetworkInterceptor { chain ->
                val gate = chain.request().tag(OneShotGate::class.java) ?: throw IOException("Missing one-shot permit")
                if (!gate.used.compareAndSet(false, true)) throw IOException("One-shot request already used")
                val response = chain.proceed(chain.request())
                // OkHttp can follow a 503 + Retry-After: 0 even when connection retry is disabled.
                // Suppress that built-in follow-up; the gate also forbids any second wire request.
                if (response.code == 503 || response.code == 408) {
                    response.newBuilder().header("Retry-After", "2147483647").build()
                } else response
            }.build()
    }
}

/** Exact provider-selected scalar only. Currency is explicitly configured, never guessed or converted. */
internal object BalanceResponseDecoder {
    fun decode(bytes: ByteArray, configuration: BalanceConfiguration, now: Long): BalanceResult {
        if (bytes.size > BalanceHttpQuery.MAX_RESPONSE_BYTES) return BalanceResult.Failed(BalanceFailure.RESPONSE_TOO_LARGE)
        return try {
            require(now >= 0)
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            val option = configuration.option?.resolved()
            val number = if (option != null) ProviderBalanceFetcher.extractValue(text, option.resultPath) else {
                val raw = BalanceJsonSyntax(text).select(configuration.amountPointer) ?: return BalanceResult.Unknown
                if (raw.startsWith('"')) JSONTokener(raw).nextValue() as String else raw
            }
            if (number == "null") return BalanceResult.Unknown
            require(number.trim().length in 1..64)
            val amount = BigDecimal(number.trim())
            BalanceResult.Available(BalanceMoney(amount, configuration.currency), now)
        } catch (_: Exception) { BalanceResult.Failed(BalanceFailure.INVALID_RESPONSE) }
    }
}
