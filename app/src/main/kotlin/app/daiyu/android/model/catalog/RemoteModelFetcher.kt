package app.daiyu.android.model.catalog

import app.daiyu.android.model.ProviderProtocol
import app.daiyu.android.model.ProviderRequestOptions
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URISyntaxException
import java.net.UnknownHostException
import java.util.concurrent.CancellationException
import javax.net.ssl.SSLException

/** Wire formats supported by the model catalogue endpoint. */
enum class RemoteModelProtocol {
    /** OpenAI and OpenAI-compatible services (`data[].id`). */
    OPENAI,
    /** Alias for services implementing the OpenAI models resource. */
    OPENAI_COMPATIBLE,
    /** Anthropic's models resource (`data[].id`, with display_name). */
    ANTHROPIC,
    /** Google Generative Language models resource (`models[].name`). */
    GEMINI,
    /** Alias for Google's Generative Language API. */
    GOOGLE
}

data class RemoteModel(
    val id: String,
    val displayName: String = id,
    val ownedBy: String? = null
)

data class RemoteModelRequest(
    val baseUrl: String,
    val apiKey: String,
    val protocol: RemoteModelProtocol,
    /** Optional path relative to baseUrl. An empty value selects the protocol default. */
    val modelsPath: String = "",
    val timeoutMillis: Int = RemoteModelFetcher.DEFAULT_TIMEOUT_MILLIS,
    val anthropicVersion: String? = null,
) {
    override fun toString(): String = "RemoteModelRequest(protocol=$protocol, apiKey=<redacted>)"
}

enum class RemoteModelFailure {
    INVALID_ENDPOINT,
    AUTHENTICATION,
    PERMISSION,
    ENDPOINT,
    RATE_LIMIT,
    SERVER,
    REDIRECT,
    TIMEOUT,
    NETWORK,
    TLS,
    PROTOCOL,
    REQUEST,
    CANCELLED
}

/**
 * A deliberately non-sensitive error. Response bodies and URLs containing credentials are not
 * copied into its message or external cause chain. The HTTP status is retained only when the
 * server supplied one. Fetch failures never retain external JSON or transport exceptions.
 */
class RemoteModelException(
    val failure: RemoteModelFailure,
    val statusCode: Int? = null,
    cause: Throwable? = null
) : IOException(failure.name, cause) {
    val kind: RemoteModelFailure get() = failure
    val error: RemoteModelFailure get() = failure
}

typealias RemoteModelError = RemoteModelFailure

/**
 * Fetches a real remote model catalogue. This class intentionally has no fallback or bundled
 * models: an empty response is a valid empty catalogue, and transport/protocol failures fail.
 * The connection factory exists so tests can use a deterministic HttpURLConnection.
 */
class RemoteModelFetcher(
    private val connect: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }
) {

    fun fetch(request: RemoteModelRequest): List<RemoteModel> = fetch(request, isCancelled = { false })

    fun fetch(
        baseUrl: String,
        apiKey: String,
        protocol: RemoteModelProtocol,
        modelsPath: String = "",
        timeoutMillis: Int = DEFAULT_TIMEOUT_MILLIS,
        isCancelled: () -> Boolean = { false }
    ): List<RemoteModel> = execute(
        RemoteModelRequest(baseUrl, apiKey, protocol, modelsPath, timeoutMillis), isCancelled
    ) { body -> parseModels(body, protocol) }

    /** Pure transport/parser seam: facts from an injected connection are NOT runtime evidence. */
    internal fun fetchOpenRouterVideoFacts(
        request: RemoteModelRequest,
        exactModel: String,
        isCancelled: () -> Boolean = { false },
    ): OpenRouterVideoModelFacts {
        if (request.protocol != RemoteModelProtocol.OPENAI ||
            remoteModelsEndpoint(request.baseUrl, request.protocol, request.modelsPath) != OPENROUTER_MODELS_ENDPOINT) {
            throw RemoteModelException(RemoteModelFailure.INVALID_ENDPOINT)
        }
        return execute(request, isCancelled) { body ->
            parseOpenRouterVideoFacts(body, OPENROUTER_MODELS_ENDPOINT, exactModel)
        }
    }

    private fun <T> execute(
        request: RemoteModelRequest,
        isCancelled: () -> Boolean,
        parse: (String) -> T,
    ): T {
        val (baseUrl, apiKey, protocol, modelsPath, timeoutMillis) = request
        val endpoint = try {
            remoteModelsEndpoint(baseUrl, protocol, modelsPath)
        } catch (_: IllegalArgumentException) {
            throw RemoteModelException(RemoteModelFailure.INVALID_ENDPOINT)
        }
        // Empty credentials are allowed for local/public catalogues; authenticated services
        // classify their response (normally 401) rather than being skipped client-side.
        if (apiKey.any { it == '\r' || it == '\n' }) {
            throw RemoteModelException(RemoteModelFailure.AUTHENTICATION)
        }
        if (timeoutMillis !in 1..MAX_TIMEOUT_MILLIS) {
            throw RemoteModelException(RemoteModelFailure.INVALID_ENDPOINT)
        }
        try {
            ProviderRequestOptions(anthropicVersion = request.anthropicVersion).validate(
                if (protocol == RemoteModelProtocol.ANTHROPIC) ProviderProtocol.MESSAGES else ProviderProtocol.RESPONSES)
        } catch (_: IllegalArgumentException) {
            throw RemoteModelException(RemoteModelFailure.REQUEST)
        }
        checkCancelled(isCancelled)

        var connection: HttpURLConnection? = null
        var configuringRequest = true
        try {
            connection = connect(URL(endpoint))
            // Never let the platform follow a Location header with the bearer credential.
            connection.instanceFollowRedirects = false
            connection.connectTimeout = timeoutMillis
            connection.readTimeout = timeoutMillis
            connection.requestMethod = "GET"
            connection.doInput = true
            connection.setRequestProperty("Accept", "application/json")
            if (apiKey.isNotEmpty()) when (protocol) {
                RemoteModelProtocol.ANTHROPIC -> {
                    connection.setRequestProperty("x-api-key", apiKey)
                    connection.setRequestProperty("anthropic-version", request.anthropicVersion ?: ANTHROPIC_VERSION)
                }
                RemoteModelProtocol.GEMINI, RemoteModelProtocol.GOOGLE ->
                    connection.setRequestProperty("x-goog-api-key", apiKey)
                else -> connection.setRequestProperty("Authorization", "Bearer $apiKey")
            } else if (protocol == RemoteModelProtocol.ANTHROPIC) {
                // The version header is part of Anthropic's protocol even for a public test server.
                connection.setRequestProperty("anthropic-version", request.anthropicVersion ?: ANTHROPIC_VERSION)
            }
            configuringRequest = false
            checkCancelled(isCancelled)
            val status = connection.responseCode
            checkCancelled(isCancelled)
            if (status !in 200..299) {
                // Do not read untrusted error bodies, but release any error stream already supplied.
                try { connection.errorStream?.close() } catch (_: Exception) { /* preserve status */ }
                val failure = if (status in 300..399) RemoteModelFailure.REDIRECT else statusFailure(status)
                throw RemoteModelException(failure, status)
            }
            val body = readBody(connection, isCancelled)
            checkCancelled(isCancelled)
            val models = parse(body)
            checkCancelled(isCancelled)
            return models
        } catch (_: CancellationException) {
            throw CancellationException()
        } catch (_: InterruptedException) {
            throw InterruptedException()
        } catch (failure: RemoteModelException) {
            checkCancelled(isCancelled)
            // use() may have attached an external close failure as a suppressed exception.
            throw RemoteModelException(failure.failure, failure.statusCode)
        } catch (_: java.net.SocketTimeoutException) {
            checkCancelled(isCancelled)
            throw RemoteModelException(RemoteModelFailure.TIMEOUT)
        } catch (_: SSLException) {
            checkCancelled(isCancelled)
            throw RemoteModelException(RemoteModelFailure.TLS)
        } catch (_: java.io.InterruptedIOException) {
            checkCancelled(isCancelled)
            throw RemoteModelException(RemoteModelFailure.TIMEOUT)
        } catch (_: UnknownHostException) {
            checkCancelled(isCancelled)
            throw RemoteModelException(RemoteModelFailure.NETWORK)
        } catch (_: IOException) {
            checkCancelled(isCancelled)
            throw RemoteModelException(RemoteModelFailure.NETWORK)
        } catch (failure: RuntimeException) {
            // URLConnection configuration can reject a header/value, an already-connected
            // instance, or a security policy. Only sanitize these expected setup failures:
            // arbitrary runtime bugs (including those from later I/O) must remain visible.
            if (!configuringRequest || (failure !is IllegalArgumentException &&
                    failure !is IllegalStateException && failure !is SecurityException)) {
                throw failure
            }
            throw RemoteModelException(RemoteModelFailure.REQUEST)
        } finally {
            try { connection?.disconnect() } catch (_: Exception) { /* cleanup cannot mask result */ }
        }
    }

    /**
     * Convenience overload for callers that use a cancellation flag. The flag is checked between
     * blocking operations and when I/O fails; it cannot unblock an active read. Cancellation still
     * waits for that I/O to return (or reach the configured timeout), then closes and disconnects.
     * Cancellation takes precedence over transport, HTTP-status and protocol failures, but not
     * validation or runtime request-configuration rejections. Unexpected programming errors
     * are propagated unchanged.
     */
    fun fetch(
        request: RemoteModelRequest,
        isCancelled: () -> Boolean
    ): List<RemoteModel> = execute(request, isCancelled) { body -> parseModels(body, request.protocol) }

    private fun checkCancelled(isCancelled: () -> Boolean) {
        if (Thread.currentThread().isInterrupted || isCancelled()) throw InterruptedException()
    }

    private fun readBody(connection: HttpURLConnection, isCancelled: () -> Boolean): String {
        val bytes = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        connection.inputStream.use { input ->
            while (true) {
                checkCancelled(isCancelled)
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > MAX_BODY_BYTES) throw RemoteModelException(RemoteModelFailure.PROTOCOL)
                bytes.write(buffer, 0, count)
            }
        }
        return bytes.toString(Charsets.UTF_8.name())
    }

    private fun parseModels(body: String, protocol: RemoteModelProtocol): List<RemoteModel> {
        try {
            val root = JSONObject(body)
            // Pagination is not implemented: never present a partial page as a complete catalogue.
            val hasMore = root.opt("has_more")
            if (hasMore != null && hasMore !== JSONObject.NULL && hasMore != false) {
                throw RemoteModelException(RemoteModelFailure.PROTOCOL)
            }
            val nextPage = root.opt("nextPageToken")
            if (nextPage != null && nextPage !== JSONObject.NULL && nextPage != "") {
                throw RemoteModelException(RemoteModelFailure.PROTOCOL)
            }
            val values = when (protocol) {
                RemoteModelProtocol.GEMINI, RemoteModelProtocol.GOOGLE -> root.getJSONArray("models")
                else -> root.optJSONArray("data") ?: root.getJSONArray("models")
            }
            val result = ArrayList<RemoteModel>(values.length())
            val seen = LinkedHashSet<String>()
            for (index in 0 until values.length()) {
                val item = values.optJSONObject(index) ?: continue
                val idValue = when (protocol) {
                    RemoteModelProtocol.GEMINI, RemoteModelProtocol.GOOGLE -> item.opt("name")
                    else -> item.opt("id") ?: item.opt("slug")
                }
                // JSONObject.optString coerces numbers, booleans and objects into selectable IDs.
                val rawId = (idValue as? String)?.trim().orEmpty()
                if (rawId.isEmpty() || !seen.add(rawId)) continue
                val display = when (protocol) {
                    RemoteModelProtocol.ANTHROPIC -> stringField(item, "display_name")
                    RemoteModelProtocol.GEMINI, RemoteModelProtocol.GOOGLE -> stringField(item, "displayName")
                    else -> stringField(item, "display_name") ?: stringField(item, "displayName")
                        ?: stringField(item, "name")
                } ?: rawId
                val owner = stringField(item, "owned_by") ?: stringField(item, "ownedBy")
                result += RemoteModel(rawId, display, owner)
            }
            return result
        } catch (_: JSONException) {
            throw RemoteModelException(RemoteModelFailure.PROTOCOL)
        }
    }

    private fun stringField(item: JSONObject, key: String): String? =
        (item.opt(key) as? String)?.trim()?.takeIf { it.isNotEmpty() }

    private fun statusFailure(status: Int): RemoteModelFailure = when (status) {
        401 -> RemoteModelFailure.AUTHENTICATION
        403 -> RemoteModelFailure.PERMISSION
        404, 405 -> RemoteModelFailure.ENDPOINT
        408 -> RemoteModelFailure.TIMEOUT
        429 -> RemoteModelFailure.RATE_LIMIT
        in 500..599 -> RemoteModelFailure.SERVER
        else -> RemoteModelFailure.REQUEST
    }

    companion object {
        const val DEFAULT_TIMEOUT_MILLIS: Int = 20_000
        const val MAX_TIMEOUT_MILLIS: Int = 120_000
        private const val MAX_BODY_BYTES: Int = 2 * 1024 * 1024
        private const val ANTHROPIC_VERSION = "2023-06-01"
    }
}

/**
 * Resolves a model-list resource without probing the network. Explicit paths are restricted to
 * paths on the validated HTTPS authority, preventing query-string API-key leaks and redirects.
 */
fun remoteModelsEndpoint(
    baseUrl: String,
    protocol: RemoteModelProtocol,
    modelsPath: String = ""
): String {
    val base = parseHttpsBase(baseUrl)
    if (modelsPath.isNotEmpty()) {
        require(modelsPath.startsWith("/") && !modelsPath.startsWith("//"))
        val relative = parseRelativePath(modelsPath)
        return base.toString().trimEnd('/') + relative
    }
    val path = base.path.orEmpty().trimEnd('/')
    val defaultSuffix = when (protocol) {
        RemoteModelProtocol.GEMINI, RemoteModelProtocol.GOOGLE -> "/models"
        else -> "/models"
    }
    val withVersion = when {
        path.endsWith("/models") -> base.toString().trimEnd('/')
        path.endsWith("/v1") || path.endsWith("/v1beta") -> base.toString().trimEnd('/') + defaultSuffix
        path.isEmpty() -> base.toString().trimEnd('/') +
            if (protocol == RemoteModelProtocol.GEMINI || protocol == RemoteModelProtocol.GOOGLE) "/v1beta/models" else "/v1/models"
        else -> base.toString().trimEnd('/') + defaultSuffix
    }
    // Re-parse the result so encoded or accidentally malformed paths cannot escape validation.
    parseHttpsBase(withVersion)
    return withVersion
}

private fun parseHttpsBase(value: String): URI {
    val uri = try { URI(value) } catch (_: URISyntaxException) {
        throw IllegalArgumentException("请输入有效的 HTTPS 服务地址")
    }
    require(uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank())
    require(uri.userInfo == null && uri.query == null && uri.fragment == null)
    require(uri.port == -1 || uri.port in 1..65535)
    require(uri.rawPath.none { it.code < 0x20 || it == ' ' })
    rejectTraversal(uri.path)
    return uri
}

private fun parseRelativePath(path: String): String {
    val uri = try { URI(path) } catch (_: URISyntaxException) {
        throw IllegalArgumentException("模型目录路径无效")
    }
    require(uri.scheme == null && uri.rawAuthority == null && uri.query == null && uri.fragment == null)
    rejectTraversal(uri.path)
    return uri.rawPath.trimEnd('/').ifEmpty { "/" }
}

private fun rejectTraversal(path: String) {
    require(path.split('/').none { it == "." || it == ".." })
}
