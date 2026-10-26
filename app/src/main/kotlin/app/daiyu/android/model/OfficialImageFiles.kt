package app.daiyu.android.model

import app.daiyu.android.model.images.FrozenMessageImagePlan
import app.daiyu.android.storage.ModelSettings
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URL
import java.util.UUID
import javax.net.ssl.HttpsURLConnection

/** Official Responses only. Compatible gateways and OAuth never inherit file-upload support.
 * One session owns the remote IDs; reuse across tool rounds, then delete on terminal/cancel. */
internal class OfficialImageFiles(
    private val settings: ModelSettings,
    private val connect: (URL) -> HttpsURLConnection,
    private val cancelled: () -> Boolean,
    private val onConnection: (HttpsURLConnection?) -> Unit,
) {
    private val files = linkedMapOf<String, String>()

    fun resolve(request: JSONObject, images: FrozenMessageImagePlan?): Set<String> {
        if (images == null || !supported(settings)) return emptySet()
        val sources = images.media.associateBy { it.token }
        val replaced = mutableSetOf<String>()
        val input = requireNotNull(request.optJSONArray("input"))
        val slots = mutableListOf<Pair<JSONObject, StreamingMedia>>()
        for (row in 0 until input.length()) {
            val message = input.optJSONObject(row) ?: continue
            if (message.optString("role") != "user") continue
            val content = message.optJSONArray("content") ?: continue
            for (index in 0 until content.length()) {
                val node = content.optJSONObject(index) ?: continue
                if (node.optString("type") != "input_image") continue
                val url = node.opt("image_url") as? String ?: continue
                val source = sources[url.substringAfter("base64,", "")] ?: continue
                require(url.matches(Regex("data:image/(png|jpeg|webp);base64,${source.token}")))
                require(replaced.add(source.token)) { "Duplicate frozen image input" }
                slots += node to source
            }
        }
        require(replaced == sources.keys) { "Frozen image inputs changed before upload" }
        for ((node, source) in slots) {
            active()
            val mime = node.getString("image_url").substringAfter("data:").substringBefore(';')
            val id = synchronized(files) { files[source.token] } ?: upload(source, mime).also { newId ->
                synchronized(files) { files[source.token] = newId }
            }
            active()
            node.remove("image_url"); node.put("file_id", id)
        }
        require(replaced == sources.keys) { "Frozen image inputs changed before upload" }
        return replaced
    }

    private fun upload(source: StreamingMedia, mime: String): String {
        active()
        val boundary = "daiyu-${UUID.randomUUID()}"
        val prefix = ("--$boundary\r\nContent-Disposition: form-data; name=\"purpose\"\r\n\r\nvision\r\n" +
            "--$boundary\r\nContent-Disposition: form-data; name=\"expires_after[anchor]\"\r\n\r\ncreated_at\r\n" +
            "--$boundary\r\nContent-Disposition: form-data; name=\"expires_after[seconds]\"\r\n\r\n3600\r\n" +
            "--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"image.${when (mime) { "image/png" -> "png"; "image/webp" -> "webp"; else -> "jpg" }}\"\r\n" +
            "Content-Type: $mime\r\n\r\n").toByteArray(Charsets.UTF_8)
        val suffix = "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)
        val connection = connect(URL(FILES))
        try {
            onConnection(connection); active()
            configure(connection, "POST")
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            connection.setFixedLengthStreamingMode(prefix.size.toLong() + source.bytes + suffix.size)
            connection.outputStream.use { output ->
                output.write(prefix)
                source.open().use { input ->
                    val digest = java.security.MessageDigest.getInstance("SHA-256")
                    val buffer = ByteArray(64 * 1024)
                    var count = 0L
                    while (true) {
                        active()
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        count += read
                        require(count <= source.bytes)
                        digest.update(buffer, 0, read); output.write(buffer, 0, read)
                    }
                    require(count == source.bytes && digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) } == source.sha256)
                }
                output.write(suffix)
            }
            require(connection.responseCode in 200..299) { "文件上传失败；未发送模型请求" }
            val response = connection.inputStream.use { input ->
                val out = ByteArrayOutputStream(); val buffer = ByteArray(4096)
                while (true) {
                    active(); val count = input.read(buffer)
                    if (count < 0) break
                    require(out.size() + count <= 32768)
                    out.write(buffer, 0, count)
                }
                JSONObject(out.toString("UTF-8"))
            }
            val id = response.getString("id")
            require(id.matches(Regex("file-[A-Za-z0-9_-]{1,200}"))) { "无效文件上传回执" }
            return id
        } finally { onConnection(null); connection.disconnect() }
    }

    /** Cleanup never changes the already received model result or retries an upload. */
    fun close() {
        val ids = synchronized(files) { files.values.toList().also { files.clear() } }
        ids.forEach { id ->
            try {
                val connection = connect(URL("$FILES/$id"))
                try { configure(connection, "DELETE"); connection.connectTimeout = 3000; connection.readTimeout = 3000; connection.responseCode }
                finally { connection.disconnect() }
            } catch (_: Exception) { /* The remote file may expire or require manual deletion. */ }
        }
    }
    fun closeAsync() {
        // One bounded cleanup worker; cancel itself never waits for remote DELETE.
        if (synchronized(files) { files.isEmpty() }) return
        CLEANUP.execute { close() }
    }
    private fun configure(connection: HttpsURLConnection, method: String) {
        connection.requestMethod = method
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 20_000; connection.readTimeout = 60_000
        connection.setRequestProperty("Authorization", "Bearer ${settings.apiKey}")
    }
    private fun active() { if (cancelled() || Thread.currentThread().isInterrupted) throw InterruptedException() }

    companion object {
        private val CLEANUP = java.util.concurrent.Executors.newSingleThreadExecutor { task ->
            Thread(task, "daiyu-file-cleanup").apply { isDaemon = true }
        }
        private const val FILES = "https://api.openai.com/v1/files"
        fun supported(settings: ModelSettings): Boolean {
            if (settings.protocol != ProviderProtocol.RESPONSES || settings.oauth != null ||
                !settings.customHeaders.isEmpty || settings.apiKey.isBlank()) return false
            val endpoint = URI(settings.requestEndpoint())
            return endpoint.scheme == "https" && endpoint.host == "api.openai.com" &&
                endpoint.port == -1 && endpoint.rawUserInfo == null && endpoint.rawPath == "/v1/responses" &&
                endpoint.rawQuery == null && endpoint.rawFragment == null
        }
    }
}
