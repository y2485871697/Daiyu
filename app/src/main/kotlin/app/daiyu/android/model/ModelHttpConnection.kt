package app.daiyu.android.model

import okhttp3.Call
import okhttp3.EventListener
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URL
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection

/** The provider owns a single request; the client and connection pool live across tool rounds. */
internal interface ModelHttpConnection {
    var requestMethod: String
    var doOutput: Boolean
    var instanceFollowRedirects: Boolean
    var connectTimeout: Int
    var readTimeout: Int
    fun setRequestProperty(name: String, value: String)
    fun send(size: Long, write: (OutputStream) -> Unit)
    fun responseCode(onRequestSent: () -> Unit = {}): Int
    val contentType: String?
    val inputStream: InputStream
    val errorStream: InputStream?
    fun disconnect()
}

internal object ModelHttpTransport {
    // Match the original model transport, without giving OkHttp ownership of request replay.
    internal val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(600, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
        .addNetworkInterceptor { chain ->
            val once = requireNotNull(chain.request().tag(RequestSent::class.java))
            if (!once.networkStarted.compareAndSet(false, true)) throw IOException("Model request replay blocked")
            val response = chain.proceed(chain.request())
            // OkHttp can otherwise replay a bodyless cursor GET for 503 Retry-After: 0.
            if (response.code == 503 && response.header("Retry-After") == "0")
                response.newBuilder().removeHeader("Retry-After").build() else response
        }
        .eventListenerFactory {
            object : EventListener() {
                override fun requestBodyEnd(call: Call, byteCount: Long) {
                    call.request().tag(RequestSent::class.java)?.completed?.invoke()
                }
            }
        }
        .build()

    fun open(url: URL, legacy: ((URL) -> HttpsURLConnection)?, beforeConnect: () -> Unit = {}): ModelHttpConnection {
        beforeConnect()
        val connection = legacy?.let { LegacyModelConnection(it(url)) } ?: OkHttpModelConnection(url, client)
        try { beforeConnect(); return connection }
        catch (failure: Exception) { runCatching { connection.disconnect() }; throw failure }
    }

    private class RequestSent(val completed: () -> Unit) {
        val networkStarted = java.util.concurrent.atomic.AtomicBoolean(false)
    }

    internal class OkHttpModelConnection(private val url: URL, private val client: OkHttpClient) : ModelHttpConnection {
        override var requestMethod = "GET"
        override var doOutput = false
        override var instanceFollowRedirects = false
        override var connectTimeout = 20_000
        override var readTimeout = 600_000
        private val headers = okhttp3.Headers.Builder()
        private var writer: ((OutputStream) -> Unit)? = null
        private var size = 0L
        private val lock = Any()
        private var disconnected = false
        private var executed = false
        private var call: Call? = null
        @Volatile private var response: Response? = null

        override fun setRequestProperty(name: String, value: String) { headers.set(name, value) }
        override fun send(size: Long, write: (OutputStream) -> Unit) {
            require(size >= 0)
            this.size = size
            writer = write
        }
        override fun responseCode(onRequestSent: () -> Unit): Int {
            synchronized(lock) {
                response?.let { return it.code }
                check(!executed) { "Model request already attempted" }
                executed = true
            }
            check(!instanceFollowRedirects) { "Model redirects require explicit provider approval" }
            val frozenHeaders = headers.build()
            val write = writer
            val body = if (doOutput) object : RequestBody() {
                override fun contentType() = frozenHeaders["Content-Type"]?.toMediaTypeOrNull()
                override fun contentLength() = size
                // Also prevents status-driven retries (408/503), not just connection retries.
                override fun isOneShot() = true
                override fun writeTo(sink: BufferedSink) { requireNotNull(write)(sink.outputStream()) }
            } else null
            val request = Request.Builder().url(url).headers(frozenHeaders)
                .method(requestMethod, body).tag(RequestSent::class.java, RequestSent(onRequestSent)).build()
            val selected = if (connectTimeout == 20_000 && readTimeout == 600_000) client else
                client.newBuilder().connectTimeout(connectTimeout.toLong(), TimeUnit.MILLISECONDS)
                    .readTimeout(readTimeout.toLong(), TimeUnit.MILLISECONDS).build()
            val current = selected.newCall(request)
            synchronized(lock) {
                if (disconnected) throw IOException("Model request cancelled")
                call = current
            }
            if (body == null) onRequestSent()
            val received = current.execute()
            val accepted = synchronized(lock) {
                if (disconnected) false else { response = received; true }
            }
            if (!accepted) {
                received.close()
                throw IOException("Model request cancelled")
            }
            return received.code
        }
        override val contentType: String?
            get() {
                val current = requireNotNull(response)
                val type = current.header("Content-Type")
                // Original Eta accepts successful SSE replies with an omitted Content-Type only.
                return type?.takeIf { it.isNotBlank() } ?: if (current.isSuccessful &&
                    headers["Accept"] == "text/event-stream") "text/event-stream" else type
            }
        override val inputStream: InputStream get() = requireNotNull(requireNotNull(response).body).byteStream()
        override val errorStream: InputStream? get() = response?.takeIf { !it.isSuccessful }?.body?.byteStream()
        override fun disconnect() {
            val owned = synchronized(lock) { disconnected = true; call to response }
            owned.first?.cancel()
            owned.second?.close()
        }
    }
}

/** Keeps the existing deterministic connection fixtures; production never opens a platform URLConnection. */
private class LegacyModelConnection(private val connection: HttpsURLConnection) : ModelHttpConnection {
    override var requestMethod: String
        get() = connection.requestMethod
        set(value) { connection.requestMethod = value }
    override var doOutput: Boolean
        get() = connection.doOutput
        set(value) { connection.doOutput = value }
    override var instanceFollowRedirects: Boolean
        get() = connection.instanceFollowRedirects
        set(value) { connection.instanceFollowRedirects = value }
    override var connectTimeout: Int
        get() = connection.connectTimeout
        set(value) { connection.connectTimeout = value }
    override var readTimeout: Int
        get() = connection.readTimeout
        set(value) { connection.readTimeout = value }
    override fun setRequestProperty(name: String, value: String) = connection.setRequestProperty(name, value)
    override fun send(size: Long, write: (OutputStream) -> Unit) {
        connection.setFixedLengthStreamingMode(size)
        connection.outputStream.useProviderResource { write(it) }
    }
    override fun responseCode(onRequestSent: () -> Unit): Int { onRequestSent(); return connection.responseCode }
    override val contentType: String? get() = connection.contentType
    override val inputStream: InputStream get() = connection.inputStream
    override val errorStream: InputStream? get() = connection.errorStream
    override fun disconnect() = connection.disconnect()
}
