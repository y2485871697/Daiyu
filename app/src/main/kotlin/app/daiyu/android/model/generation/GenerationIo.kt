package app.daiyu.android.model.generation

import java.io.ByteArrayOutputStream
import java.net.CookieHandler
import java.net.HttpURLConnection
import java.net.URL

/**
 * Blocking I/O intended for a parent-owned worker. There is no retry scheduler or hidden polling.
 * The parent supplies durable attempt accounting, media validation/publication and download URL
 * authorization (including SSRF/DNS policy). Credentials are never sent to output/CDN URLs.
 */
class GenerationIo(
    internal val assets: GenerationAssets,
    private val attemptStore: GenerationAttemptStore,
    private val authorizeDownload: (String) -> Boolean,
    private val http: GenerationHttp = UrlConnectionGenerationHttp(),
    private val isCancelled: () -> Boolean = { false },
    /** Allocate a fresh handle for every parent-owned run; never reset/reuse a cancelled handle. */
    val cancellation: GenerationCancellation = GenerationCancellation(),
) {
    internal fun submit(plan: GenerationRequestPlan, parse: (GenerationHttpResponse) -> Submission): Submission {
        if (cancelled()) return Submission.NotExecuted(GenerationFailure.CANCELLED)
        // Construct every request byte/header before accounting and before any network operation.
        val request = GenerationHttpRequest("POST", plan.endpoint, headers(plan) +
            ("Content-Type" to plan.contentType), plan.body, GenerationRequestPlan.MAX_JSON_BYTES)
        val reservation = try { attemptStore.reserve(plan.attempt) } catch (_: Exception) {
            return Submission.NotExecuted(GenerationFailure.STORE_UNAVAILABLE)
        }
        when (reservation) {
            is GenerationReservation.Existing -> {
                if (reservation.fingerprint != plan.fingerprint)
                    return Submission.NotExecuted(GenerationFailure.IDENTITY_CONFLICT)
                return reservation.submission ?: Submission.Unknown(GenerationFailure.ALREADY_ATTEMPTED)
            }
            GenerationReservation.Started -> Unit
        }
        // Once reserved, cancellation is ambiguous and must never reopen this identity for POST.
        val result = if (cancelled()) Submission.Unknown(GenerationFailure.CANCELLED) else {
            try {
                // The only POST call site. A failure after durable reservation is always ambiguous.
                val response = http.execute(request, cancellation)
                when {
                    cancelled() -> Submission.Unknown(GenerationFailure.CANCELLED)
                    response.statusCode !in 200..299 -> Submission.Unknown(GenerationFailure.HTTP, response.statusCode)
                    response.bytes.isEmpty() -> Submission.Unknown(GenerationFailure.EMPTY_RESPONSE)
                    response.bytes.size > request.maxResponseBytes -> Submission.Unknown(GenerationFailure.PROTOCOL)
                    else -> try { parse(response) } catch (_: Exception) { Submission.Unknown(GenerationFailure.PROTOCOL) }
                }
            } catch (failure: Exception) {
                Submission.Unknown(transportFailure(failure))
            }
        }
        // A failed outcome write must not erase a successful/partial result. The durable reservation
        // remains a tombstone; a later invocation sees Unknown, NEVER another POST.
        try { attemptStore.finish(plan.attempt, result) } catch (_: Exception) { /* keep known result */ }
        return result
    }

    /** One GET of this exact frozen task URL. Repeating this method is read-only, never resubmission. */
    internal fun poll(
        plan: GenerationRequestPlan,
        handle: GenerationHandle,
        parse: (GenerationHttpResponse) -> Submission,
    ): Submission {
        if (!matches(plan, handle)) return Submission.NotExecuted(GenerationFailure.INVALID_REQUEST)
        if (cancelled()) return Submission.Unknown(GenerationFailure.CANCELLED, handle = handle)
        val result = try {
            val response = http.execute(GenerationHttpRequest("GET", handle.pollUrl, headers(plan), null,
                GenerationRequestPlan.MAX_JSON_BYTES), cancellation)
            when {
                cancelled() -> Submission.Unknown(GenerationFailure.CANCELLED, handle = handle)
                response.statusCode !in 200..299 -> Submission.Unknown(GenerationFailure.HTTP, response.statusCode, handle)
                response.bytes.isEmpty() -> Submission.Unknown(GenerationFailure.EMPTY_RESPONSE, handle = handle)
                response.bytes.size > GenerationRequestPlan.MAX_JSON_BYTES -> Submission.Unknown(GenerationFailure.PROTOCOL, handle = handle)
                else -> try {
                    when (val parsed = parse(response)) {
                        is Submission.Unknown -> parsed.copy(handle = handle)
                        else -> parsed
                    }
                } catch (_: Exception) { Submission.Unknown(GenerationFailure.PROTOCOL, handle = handle) }
            }
        } catch (failure: Exception) { Submission.Unknown(transportFailure(failure), handle = handle) }
        // Transient read failures do not overwrite a known pending handle in durable storage.
        if (result is Submission.Completed || result is Submission.Pending ||
            (result is Submission.Unknown && result.reason == GenerationFailure.REMOTE_FAILED)) {
            try { attemptStore.finish(plan.attempt, result) } catch (_: Exception) { /* no resubmit */ }
        }
        return result
    }

    /** Explicit read/asset operation; errors retain the original output in the caller's Completed. */
    fun downloadAndCommit(plan: GenerationRequestPlan, output: GenerationOutput): GenerationAssetResult {
        if (output.index < 0 || output.source == null || output.failure != null)
            return GenerationAssetResult.Failed(GenerationFailure.INVALID_REQUEST)
        if (cancelled()) return GenerationAssetResult.Failed(GenerationFailure.CANCELLED)
        val limit = if (plan.kind == GenerationKind.IMAGE) GenerationRequestPlan.MAX_IMAGE_BYTES
            else GenerationRequestPlan.MAX_VIDEO_BYTES
        val bytes: ByteArray
        val declared: String
        when (val source = output.source) {
            is GenerationSource.Inline -> { bytes = source.bytes; declared = source.mimeType }
            is GenerationSource.Remote -> {
                try {
                    // Parent authorization applies even to the fixed authenticated video URL.
                    if (!authorizeDownload(source.url)) {
                        return GenerationAssetResult.Failed(GenerationFailure.DOWNLOAD_DENIED)
                    }
                    GenerationRequestPlan.checkedDownload(source.url)
                    if (source.authenticated) {
                        // Only a generated /videos/{id}/content URL can receive the bearer token.
                        val prefix = plan.endpoint + "/"
                        require(plan.protocol == GenerationProtocol.OPENAI_VIDEO_MULTIPART)
                        require(source.url.startsWith(prefix) && source.url.endsWith("/content"))
                        val id = source.url.removePrefix(prefix).removeSuffix("/content")
                        require(validTaskId(id))
                    }
                } catch (_: Exception) { return GenerationAssetResult.Failed(GenerationFailure.DOWNLOAD_DENIED) }
                val response = try {
                    http.execute(GenerationHttpRequest("GET", source.url,
                        if (source.authenticated) headers(plan) + ("Accept" to "video/*, application/octet-stream")
                        else emptyMap(), null, limit), cancellation)
                } catch (failure: Exception) { return GenerationAssetResult.Failed(transportFailure(failure)) }
                if (cancelled()) return GenerationAssetResult.Failed(GenerationFailure.CANCELLED)
                if (response.statusCode !in 200..299) return GenerationAssetResult.Failed(GenerationFailure.HTTP)
                bytes = response.bytes
                declared = response.mimeType.substringBefore(';').trim()
            }
        }
        if (cancelled()) return GenerationAssetResult.Failed(GenerationFailure.CANCELLED)
        if (bytes.isEmpty() || bytes.size > limit) return GenerationAssetResult.Failed(GenerationFailure.INVALID_MEDIA)
        return try {
            val info = assets.inspect(plan.kind, bytes.copyOf(), declared)
            require(if (plan.kind == GenerationKind.IMAGE)
                info.mimeType in setOf("image/png", "image/jpeg", "image/webp") && info.width > 0 && info.height > 0
            else info.mimeType in setOf("video/mp4", "video/webm"))
            if (cancelled()) GenerationAssetResult.Failed(GenerationFailure.CANCELLED)
            else GenerationAssetResult.Committed(assets.commit(plan.identity, output.index, plan.kind, bytes, info))
        } catch (_: Exception) { GenerationAssetResult.Failed(GenerationFailure.ASSET_REJECTED) }
    }

    internal fun batch(
        requests: List<GenerationRequest>,
        accepts: (GenerationProtocol) -> Boolean,
        submit: (GenerationRequestPlan) -> Submission,
    ): List<GenerationBatchItem> {
        val snapshot = requests.toList()
        if (snapshot.isEmpty()) return emptyList()
        if (snapshot.size > GenerationRequestPlan.MAX_BATCH || snapshot.map { it.identity }.toSet().size != snapshot.size)
            return snapshot.map { GenerationBatchItem(it.identity, Submission.NotExecuted(GenerationFailure.INVALID_BATCH)) }
        val plans = try {
            snapshot.map {
                if (!accepts(it.protocol)) throw GenerationPreflightException(GenerationFailure.INVALID_REQUEST)
                GenerationRequestPlan.prepare(it, assets)
            }
        } catch (failure: GenerationPreflightException) {
            // No valid sibling is sent if ANY item fails preflight.
            return snapshot.map { GenerationBatchItem(it.identity, Submission.NotExecuted(failure.failure)) }
        }
        return plans.map { GenerationBatchItem(it.identity, submit(it)) }
    }

    private fun cancelled(): Boolean = cancellation.isCancellationRequested ||
        Thread.currentThread().isInterrupted || try { isCancelled() } catch (_: Exception) { true }
    private fun transportFailure(failure: Exception): GenerationFailure =
        if (failure is GenerationCancelledException || cancelled()) GenerationFailure.CANCELLED
        else GenerationFailure.TRANSPORT
    private fun headers(plan: GenerationRequestPlan): Map<String, String> = buildMap {
        put("Accept", "application/json")
        plan.authorization?.let { put("Authorization", it) }
    }
    private fun matches(plan: GenerationRequestPlan, handle: GenerationHandle): Boolean =
        plan.protocol == GenerationProtocol.OPENAI_VIDEO_MULTIPART && plan.identity == handle.identity &&
            plan.fingerprint == handle.planFingerprint && validTaskId(handle.taskId) &&
            handle.pollUrl == plan.endpoint + "/" + handle.taskId && handle.contentUrl == handle.pollUrl + "/content"

    internal companion object {
        fun validTaskId(value: String): Boolean = value.matches(Regex("[A-Za-z0-9_-]{1,256}"))
    }
}

/**
 * Uses the project's existing java.net stack; no new HTTP dependency. Fixed-length streaming is
 * essential: HttpURLConnection cannot replay a streamed POST for redirects/authentication. No
 * redirects, cookies, caches, endpoint fallback, or application retries. The parent must not install
 * a process-wide CookieHandler during this adapter's lifetime. Inject GenerationHttp for tests.
 */
class UrlConnectionGenerationHttp(
    private val connect: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) : GenerationHttp {
    override fun execute(request: GenerationHttpRequest): GenerationHttpResponse =
        execute(request, GenerationCancellation())

    override fun execute(
        request: GenerationHttpRequest,
        cancellation: GenerationCancellation,
    ): GenerationHttpResponse {
        // Begin before connection creation: cancellation cannot claim a delayed factory has exited.
        val execution = cancellation.begin()
        try {
            cancellation.throwIfCancelled()
            require(request.method == "POST" || request.method == "GET")
            require(request.maxResponseBytes > 0)
            GenerationRequestPlan.checkedDownload(request.url)
            check(CookieHandler.getDefault() == null) { "Generation requires isolated cookies" }
            val connection = connect(URL(request.url))
            // Register before any operation that can connect/write/read; late registration aborts.
            execution.register(connection)
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.connectTimeout = 30_000
            connection.readTimeout = 120_000
            connection.requestMethod = request.method
            request.headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
            if (request.method == "POST") {
                val body = requireNotNull(request.body)
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(body.size)
                cancellation.throwIfCancelled()
                val output = connection.outputStream
                execution.register(output)
                try {
                    cancellation.throwIfCancelled()
                    output.write(body)
                } finally { execution.release(output) }
            } else require(request.body == null)
            cancellation.throwIfCancelled()
            val status = connection.responseCode
            cancellation.throwIfCancelled()
            if (status !in 200..299) {
                try {
                    connection.errorStream?.let { error ->
                        execution.register(error)
                        execution.release(error)
                    }
                } catch (failure: Exception) {
                    // Preserve the HTTP status on error-body failures, but never hide cancellation.
                    if (failure is GenerationCancelledException) throw failure
                }
                cancellation.throwIfCancelled()
                return GenerationHttpResponse(status, byteArrayOf())
            }
            require(connection.contentLengthLong <= request.maxResponseBytes)
            cancellation.throwIfCancelled()
            val input = connection.inputStream
            execution.register(input)
            val bytes = try {
                ByteArrayOutputStream().use { out ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        cancellation.throwIfCancelled()
                        val count = input.read(buffer)
                        if (count < 0) break
                        require(out.size().toLong() + count <= request.maxResponseBytes)
                        out.write(buffer, 0, count)
                    }
                    out.toByteArray()
                }
            } finally { execution.release(input) }
            cancellation.throwIfCancelled()
            return GenerationHttpResponse(status, bytes, connection.contentType.orEmpty())
        } finally {
            execution.finish()
        }
    }
}
