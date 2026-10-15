package app.daiyu.android.model

import app.daiyu.android.domain.*
import app.daiyu.android.storage.ModelSettings
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HttpsURLConnection

/** Anthropic Messages transport; selected through the frozen provider configuration. */
class MessagesModel internal constructor(
    private val settings: ModelSettings,
    private val maxTokens: Int,
    private val connect: (URL) -> HttpsURLConnection
) : ModelPort {
    constructor(settings: ModelSettings, maxTokens: Int = 4096) :
        this(settings, maxTokens, { it.openConnection() as HttpsURLConnection })
    internal constructor(settings: ModelSettings, connect: (URL) -> HttpsURLConnection) : this(settings, 4096, connect)

    override fun open(history: List<Message>): ModelSession = open(history, settings.enabledTools())
    override fun open(history: List<Message>, tools: Set<ReadOnlyTool>): ModelSession =
        open(history, MessagesRequest.definitions(tools))
    override fun open(history: List<Message>, definitions: List<ToolDefinition>): ModelSession =
        Session(settings, maxTokens, history, definitions.toList(), connect)

    private class Session(
        private val settings: ModelSettings,
        private val maxTokens: Int,
        history: List<Message>,
        private val tools: List<ToolDefinition>,
        private val connect: (URL) -> HttpsURLConnection
    ) : ModelSession {
        private val endpoint = MessagesRequest.endpoint(settings)
        private val messages = MessagesRequest.history(history)
        private val ownItems = mutableListOf<String>()
        private val cancelled = AtomicBoolean(false)
        @Volatile private var connection: HttpsURLConnection? = null
        private var usable = true
        private var started = false
        private var pending = emptySet<String>()

        init { MessagesRequest.body(settings.model, messages, tools, maxTokens) }
        private fun checkCancelled() { if (cancelled.get()) throw InterruptedException() }

        override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
            checkCancelled()
            check(usable && (!started || pending.isNotEmpty())) { "Messages session cannot be replayed" }
            require(outputs.map { it.callId.value }.toSet() == pending && outputs.size == pending.size) {
                "Tool outputs must match all pending tool calls exactly once"
            }
            started = true
            // After any failure, no repost of an unconfirmed request or tool output is allowed.
            usable = false
            if (outputs.isNotEmpty()) {
                val content = JSONArray()
                outputs.forEach { content.put(MessagesRequest.result(it.callId.value, it.output)) }
                val item = MessagesRequest.message("user", content)
                messages.put(item); ownItems.add(item.toString())
            }
            val request = settings.requestOptions.apply(MessagesRequest.body(settings.model, messages, tools, maxTokens), ProviderProtocol.MESSAGES)
            var deliveryFailure: Exception? = null
            val deliver: (ModelEvent) -> Unit = { event ->
                checkCancelled()
                try { sink(event) } catch (failure: Exception) { deliveryFailure = failure; throw failure }
            }
            var ownedConnection: HttpsURLConnection? = null
            try {
                checkCancelled()
                val conn = connect(URL(endpoint))
                ownedConnection = conn; connection = conn
                checkCancelled()
                conn.requestMethod = "POST"; conn.doOutput = true; conn.instanceFollowRedirects = false
                conn.connectTimeout = 20_000; conn.readTimeout = 60_000
                conn.setRequestProperty("x-api-key", settings.apiKey)
                conn.setRequestProperty("anthropic-version", "2023-06-01")
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Accept", "text/event-stream")
                conn.outputStream.use { it.write(request.toString().toByteArray(Charsets.UTF_8)) }
                checkCancelled()
                val status = conn.responseCode
                checkCancelled()
                if (status !in 200..299) throw ModelRequestException(when (status) {
                    401 -> ModelFailure.AUTHENTICATION
                    403 -> ModelFailure.PERMISSION
                    404, 405 -> ModelFailure.ENDPOINT
                    429 -> ModelFailure.RATE_LIMIT
                    in 300..399 -> ModelFailure.REDIRECT
                    in 500..599 -> ModelFailure.SERVER
                    else -> ModelFailure.REQUEST
                })
                if (conn.contentType?.substringBefore(';')?.trim()?.equals("text/event-stream", ignoreCase = true) != true)
                    throw ModelRequestException(ModelFailure.PROTOCOL)
                conn.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    val raw = MessagesStream.read(reader, cancelled::get, deliver)
                    checkCancelled()
                    val item = JSONObject(raw)
                    val content = item.getJSONArray("content")
                    val calls = mutableSetOf<String>()
                    for (i in 0 until content.length()) {
                        val block = content.getJSONObject(i)
                        if (block.getString("type") == "tool_use") calls.add(block.getString("id"))
                    }
                    messages.put(item); ownItems.add(raw)
                    deliver(ModelEvent.ProtocolOutput(ownItems.toList()))
                    checkCancelled()
                    pending = calls
                }
                checkCancelled()
            } catch (failure: Exception) {
                // Caller failures retain identity even when the sink also cancels the session.
                if (failure === deliveryFailure) throw failure
                checkCancelled()
                throw when (failure) {
                    is java.net.SocketTimeoutException -> ModelRequestException(ModelFailure.TIMEOUT)
                    is javax.net.ssl.SSLException -> ModelRequestException(ModelFailure.TLS)
                    is java.io.IOException -> ModelRequestException(ModelFailure.NETWORK)
                    else -> failure
                }
            } finally {
                // Do not read or expose provider error bodies. Close any already available error stream.
                try { ownedConnection?.errorStream?.close() } catch (_: Exception) {}
                try { ownedConnection?.disconnect() } catch (_: Exception) {} finally { connection = null }
            }
            checkCancelled()
            usable = true
        }

        override fun cancel() {
            cancelled.set(true)
            try { connection?.disconnect() } catch (_: Exception) { /* Best effort; cancellation stays primary. */ }
        }
    }
}
