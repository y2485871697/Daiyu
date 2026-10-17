package app.daiyu.android

import app.daiyu.android.integration.tools.GenerationDownloadAuthorization
import app.daiyu.android.model.generation.GenerationCancellation
import app.daiyu.android.model.generation.GenerationCancelledException
import app.daiyu.android.model.generation.GenerationHttp
import app.daiyu.android.model.generation.GenerationHttpRequest
import app.daiyu.android.model.generation.GenerationHttpResponse
import app.daiyu.android.model.generation.GenerationRequestPlan
import app.daiyu.android.model.generation.UrlConnectionGenerationHttp
import okhttp3.Authenticator
import okhttp3.ConnectionPool
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.Closeable
import java.net.InetAddress
import java.net.Proxy
import java.net.URI
import java.net.UnknownHostException
import java.util.Collections
import java.util.concurrent.TimeUnit

/** Dedicated generation transport; never derive from the model client (auth/interceptors/cookies).
 * Construction performs no DNS/I/O. Inject THIS instance into Resources, not its policy alone.
 * Every execution has a new pool and pinned DNS snapshot: no pooled/coalesced connection bypasses
 * validation. TLS uses OkHttp's platform trust and ORIGINAL hostname verification, never an IP URL.
 */
internal class GenerationPublicNetworkHttp : GenerationHttp {
    internal val downloadAuthorization = GenerationDownloadAuthorization { _, url ->
        // Only structural admission here. Security depends on the paired execute(), not pre-DNS.
        runCatching { checkedHost(url); true }.getOrDefault(false)
    }

    override fun execute(request: GenerationHttpRequest): GenerationHttpResponse =
        execute(request, GenerationCancellation())

    override fun execute(request: GenerationHttpRequest, cancellation: GenerationCancellation): GenerationHttpResponse {
        val execution = cancellation.begin()
        var pool: ConnectionPool? = null
        try {
            cancellation.throwIfCancelled()
            val host = checkedHost(request.url)
            require(request.method == "GET" || request.method == "POST")
            require(request.maxResponseBytes in 1..GenerationRequestPlan.MAX_VIDEO_BYTES)
            require(request.headers.keys.all { it in ALLOWED_HEADERS })
            require(request.headers.values.all { value -> value.length <= 8192 && value.all { it.code in 32..126 } })
            val body = if (request.method == "POST") requireNotNull(request.body).toRequestBody(null)
                else { require(request.body == null); null }
            val wire = Request.Builder().url(request.url).method(request.method, body)
                .header("Accept-Encoding", "identity")
            request.headers.forEach { (key, value) -> wire.header(key, value) }
            val client = clientFor(host, isCancelled = { cancellation.isCancellationRequested },
                closeRejectedResponse = { response ->
                    val close = GenerationResponseClose(response)
                    execution.register(close)
                    execution.release(close)
                })
            pool = client.connectionPool
            val call = client.newCall(wire.build())
            // Registered before DNS/connect; Call.cancel is sticky even before execute begins.
            execution.registerAbort(call::cancel)
            cancellation.throwIfCancelled()
            val response = call.execute()
            val responseClose = GenerationResponseClose(response)
            execution.register(responseClose)
            try {
                cancellation.throwIfCancelled()
                if (response.code !in 200..299) return GenerationHttpResponse(response.code, byteArrayOf())
                val content = requireNotNull(response.body)
                require(content.contentLength() <= request.maxResponseBytes)
                // No transparent decompression and no unbounded bytes()/string() call.
                require(response.header("Content-Encoding").let { it == null || it.equals("identity", true) })
                val bytes = readBounded(content.byteStream(), request.maxResponseBytes, cancellation)
                cancellation.throwIfCancelled()
                val mime = response.header("Content-Type").orEmpty()
                require(mime.length <= 256)
                return GenerationHttpResponse(response.code, bytes, mime)
            } finally {
                // No reuse is needed; abort before close so rejected/oversize bodies cannot drain.
                try { call.cancel() } finally { execution.release(responseClose) }
            }
        } catch (failure: Exception) {
            if (failure is GenerationCancelledException || cancellation.isCancellationRequested)
                throw GenerationCancelledException()
            if (failure is GenerationHttpStatusException)
                return GenerationHttpResponse(failure.statusCode, byteArrayOf())
            // OkHttp/DNS exceptions may contain signed URLs or addresses; never retain the cause.
            throw IOException("generation_secure_transport_failed")
        } finally {
            try { execution.finish() } finally { pool?.evictAll() }
        }
    }

    companion object {
        private val ALLOWED_HEADERS = setOf("Accept", "Authorization", "Content-Type")

        /** Response owns the stream; its registered close runs even when this reader fails. */
        internal fun readBounded(input: java.io.InputStream, limit: Int,
            cancellation: GenerationCancellation): ByteArray {
            require(limit in 1..GenerationRequestPlan.MAX_VIDEO_BYTES)
            return ByteArrayOutputStream().use { output ->
                val buffer = ByteArray(8192)
                while (true) {
                    cancellation.throwIfCancelled()
                    // Read at most one byte past the budget, including unknown/chunked lengths.
                    val remaining = limit - output.size()
                    val count = input.read(buffer, 0, minOf(buffer.size, remaining + 1))
                    if (count < 0) break
                    require(count in 1..remaining)
                    output.write(buffer, 0, count)
                }
                cancellation.throwIfCancelled()
                output.toByteArray()
            }
        }

        internal fun checkedHost(url: String): String {
            require(url.length <= 16_384)
            val uri = URI(url)
            val host = requireNotNull(uri.host)
            require(uri.scheme == "https" && uri.rawUserInfo == null && uri.rawFragment == null)
            require(uri.port == -1 || uri.port in 1..65535)
            require(host.length <= 253 && host == host.lowercase(java.util.Locale.ROOT))
            require(host.contains('.') && !host.endsWith('.') && host.any { it in 'a'..'z' })
            require(host.split('.').all { it.matches(Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")) })
            require(host != "localhost" && !host.endsWith(".localhost") &&
                listOf("local", "internal", "lan", "home", "invalid", "test").none { host.endsWith(".$it") })
            return host
        }

        internal fun clientFor(host: String, resolver: Dns = Dns.SYSTEM,
            isCancelled: () -> Boolean = { false },
            closeRejectedResponse: (okhttp3.Response) -> Unit = { GenerationResponseClose(it).close() }): OkHttpClient = OkHttpClient.Builder()
            .dns(GenerationPinnedPublicDns(host, resolver, isCancelled))
            .proxy(Proxy.NO_PROXY)
            .connectionPool(ConnectionPool(0, 1, TimeUnit.MILLISECONDS))
            .protocols(listOf(Protocol.HTTP_1_1)) // no HTTP/2 cross-host connection coalescing
            .cookieJar(CookieJar.NO_COOKIES).cache(null)
            .authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .connectTimeout(30, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS).callTimeout(180, TimeUnit.SECONDS)
            .addNetworkInterceptor { chain ->
                rejectFollowUp(chain.proceed(chain.request()), chain.call()::cancel, closeRejectedResponse)
            }.build()

        /** Runs BEFORE RetryAndFollowUpInterceptor can replay GET 408/421/503 or redirects/auth.
         * Closing, then throwing a non-recoverable IOException also covers Retry-After:0.
         */
        internal fun rejectFollowUp(response: okhttp3.Response, abort: () -> Unit = {},
            close: (okhttp3.Response) -> Unit = { GenerationResponseClose(it).close() }): okhttp3.Response {
            if (response.code !in 200..299) {
                val status = response.code
                // Abort before close, so close cannot drain an untrusted unbounded error body.
                try { abort() } finally { close(response) }
                throw GenerationHttpStatusException(status)
            }
            return response
        }
    }
}

/** ResponseBody.close() uses closeQuietly in OkHttp 4.12. Close its source explicitly so failures
 * reach GenerationCancellation cleanupFailures rather than falsely reporting a clean drain.
 */
internal class GenerationResponseClose(private val response: okhttp3.Response) : Closeable {
    override fun close() {
        try { response.body?.source()?.close() } finally { response.close() }
    }
}

/** Only these factories can pair policy and HTTP. An extra parent policy is narrowing only. */
internal class GenerationDownloadNetworkBinding private constructor(
    val authorization: GenerationDownloadAuthorization,
    val http: GenerationHttp,
) {
    companion object {
        fun denied() = GenerationDownloadNetworkBinding(
            GenerationDownloadAuthorization { _, _ -> false }, UrlConnectionGenerationHttp())

        fun publicNetwork(transport: GenerationPublicNetworkHttp,
            additional: GenerationDownloadAuthorization? = null) = GenerationDownloadNetworkBinding(
            GenerationDownloadAuthorization { owner, url ->
                transport.downloadAuthorization.allows(owner, url) &&
                    (additional == null || runCatching { additional.allows(owner, url) }.getOrDefault(false))
            }, transport)
    }
}

internal class GenerationHttpStatusException(val statusCode: Int) : IOException("generation_http_status")

/** OkHttp calls this while selecting the actual socket route. Never resolve in authorization.
 * Reject the ENTIRE mixed answer, not just its private members. Subsequent lookup cannot rebind.
 */
internal class GenerationPinnedPublicDns(private val host: String, private val resolver: Dns,
    private val isCancelled: () -> Boolean = { false }) : Dns {
    private var originalAddresses: List<InetAddress>? = null
    private var pinned: List<InetAddress>? = null
    private var failed = false

    @Synchronized override fun lookup(hostname: String): List<InetAddress> {
        if (hostname != host || failed || isCancelled()) throw UnknownHostException("generation_dns_denied")
        pinned?.let { return it }
        try {
            // Keep the transport execution active while noninterruptible native DNS is in flight.
            val addresses = resolver.lookup(hostname)
            require(!isCancelled()) // no socket route can escape a cancelled DNS lookup
            require(addresses.isNotEmpty() && addresses.size <= 64 && addresses.all(::isPublicAddress))
            val snapshot = Collections.unmodifiableList(addresses.map {
                // Copy address bytes; this overload does NOT perform another lookup.
                InetAddress.getByAddress(host, it.address.copyOf())
            }.distinct())
            originalAddresses = snapshot
            // Validate/preserve the entire answer, but expose ONE route: no address fallback retry.
            return Collections.singletonList(snapshot.first()).also { pinned = it }
        } catch (_: Exception) {
            failed = true
            throw UnknownHostException("generation_dns_denied")
        }
    }

    companion object {
        internal fun isPublicAddress(address: InetAddress): Boolean {
            val b = address.address.map { it.toInt() and 255 }
            if (b.size == 4) return when {
                b[0] in setOf(0, 10, 127) || b[0] >= 224 -> false
                b[0] == 100 && b[1] in 64..127 -> false // carrier-grade NAT
                b[0] == 169 && b[1] == 254 -> false
                b[0] == 172 && b[1] in 16..31 -> false
                b[0] == 192 && (b[1] == 168 || b[1] == 0 && b[2] in setOf(0, 2) || b[1] == 88 && b[2] == 99) -> false
                b[0] == 198 && (b[1] in 18..19 || b[1] == 51 && b[2] == 100) -> false
                b[0] == 203 && b[1] == 0 && b[2] == 113 -> false
                else -> true
            }
            if (b.size != 16 || b[0] !in 0x20..0x3f) return false // only global-unicast 2000::/3
            if (b[0] == 0x20 && b[1] == 0x01 && b[2] in 0..1) return false // special 2001::/23
            if (b[0] == 0x20 && b[1] == 0x01 && b[2] == 0x0d && b[3] == 0xb8) return false
            if (b[0] == 0x20 && b[1] == 0x02) return false // 6to4 may embed a private destination
            if (b[0] == 0x3f && b[1] == 0xfe) return false // retired 6bone 3ffe::/16
            if (b[0] == 0x3f && b[1] == 0xff && b[2] in 0..15) return false // documentation 3fff::/20
            return true
        }
    }
}
