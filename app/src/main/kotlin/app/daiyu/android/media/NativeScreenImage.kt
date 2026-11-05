package app.daiyu.android.media

import org.json.JSONObject
import java.nio.ByteBuffer
import java.util.Base64

/** Only native screen receipts are images; arbitrary tool JSON, paths and URLs are not. */
internal class NativeScreenImage private constructor(
    val bytes: ByteArray,
    val width: Int,
    val height: Int,
    val metadata: String,
) {
    companion object {
        const val MAX_BYTES = 8 * 1024 * 1024
        const val MAX_RESULT_CHARS = MAX_BYTES * 4 / 3 + 4096
        private val signature = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
        private val missingRoots = setOf("APPLICATION_WINDOW_NOT_ACTIVE_NOT_EXECUTED",
            "APPLICATION_WINDOW_ROOT_UNAVAILABLE_NOT_EXECUTED", "NO_ACTIVE_EXTERNAL_WINDOW")

        fun isVisualFallback(output: String?): Boolean = output != null && output.length <= MAX_RESULT_CHARS &&
            runCatching { isVisualFallback(JSONObject(output)) }.getOrDefault(false)

        private fun isVisualFallback(data: JSONObject): Boolean =
            data.opt("screen_read_mode") == "visual_fallback" && data.opt("node_tree_available") == false &&
                data.opt("node_failure_code") in missingRoots && data.opt("visual_actions_available") == true &&
                data.opt("mime_type") == "image/png" && !data.has("nodes") && !data.has("observation_id") &&
                (data.opt("capture_id") as? String)?.isNotBlank() == true &&
                (data.opt("data_base64") as? String)?.isNotEmpty() == true

        fun parse(name: String, arguments: String, output: String): NativeScreenImage? {
            return runCatching {
                if (name != "screen" || output.length > MAX_RESULT_CHARS) return@runCatching null
                val args = JSONObject(arguments)
                val data = JSONObject(output)
                when (args.opt("operation")) {
                    "screenshot" -> Unit
                    "observe" -> if (!isVisualFallback(data)) return@runCatching null
                    else -> return@runCatching null
                }
                if (data.opt("mime_type") != "image/png") return@runCatching null
                val observation = data.opt("observation_id") as? String
                val capture = data.opt("capture_id") as? String
                if (observation != null) {
                    if (observation.isBlank() || data.has("capture_id")) return@runCatching null
                    if (args.has("observation_id") && args.opt("observation_id") != observation) return@runCatching null
                } else if (data.has("observation_id") || capture.isNullOrBlank() || args.has("observation_id")) return@runCatching null
                val width = data.opt("width").let { if (it is Int || it is Long) (it as Number).toLong() else return@runCatching null }
                val height = data.opt("height").let { if (it is Int || it is Long) (it as Number).toLong() else return@runCatching null }
                if (width !in 1..8192 || height !in 1..8192 || width * height > 32_000_000L) return@runCatching null
                val encoded = data.opt("data_base64") as? String ?: return@runCatching null
                if (encoded.isEmpty() || encoded.length > MAX_BYTES * 4 / 3 + 4) return@runCatching null
                val bytes = Base64.getDecoder().decode(encoded)
                if (bytes.size !in 33..MAX_BYTES || !bytes.copyOfRange(0, 8).contentEquals(signature)) return@runCatching null
                val header = ByteBuffer.wrap(bytes)
                if (header.getInt(8) != 13 || header.getInt(12) != 0x49484452 ||
                    header.getInt(16).toLong() != width || header.getInt(20).toLong() != height) return@runCatching null
                data.remove("data_base64")
                NativeScreenImage(bytes, width.toInt(), height.toInt(), data.toString())
            }.getOrNull()
        }
    }
}
