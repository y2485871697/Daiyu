package app.daiyu.android.media

import org.json.JSONObject
import java.nio.ByteBuffer

/** Only a real read_image receipt and its run-bound asset can supply file-image pixels. */
internal class NativeFileImage private constructor(
    val bytes: ByteArray,
    val width: Int,
    val height: Int,
    val metadata: String,
) {
    fun inputLabel(callId: String) =
        "read_image文件图像（call_id=$callId，${width}×${height}像素），不是设备屏幕截图。"

    companion object {
        private const val MAX_RESULT_CHARS = 4096
        private val signature = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
        private val resultKeys = setOf("image_source", "mime_type", "width", "height", "image_asset")

        fun pngDimensions(bytes: ByteArray): Pair<Int, Int>? = runCatching {
            if (bytes.size !in 33..NativeScreenImage.MAX_BYTES ||
                !bytes.copyOfRange(0, 8).contentEquals(signature)) return@runCatching null
            val header = ByteBuffer.wrap(bytes)
            if (header.getInt(8) != 13 || header.getInt(12) != 0x49484452) return@runCatching null
            val width = header.getInt(16)
            val height = header.getInt(20)
            if (width !in 1..8192 || height !in 1..8192 || width.toLong() * height > 32_000_000L) return@runCatching null
            width to height
        }.getOrNull()

        fun metadata(name: String, arguments: String, output: String): String? = runCatching {
            if (name != "read_image" || output.length > MAX_RESULT_CHARS) return@runCatching null
            val args = JSONObject(arguments)
            if (args.keys().asSequence().toSet() != setOf("path") ||
                (args.opt("path") as? String)?.isNotBlank() != true) return@runCatching null
            val data = JSONObject(output)
            if (data.keys().asSequence().toSet() != resultKeys || data.opt("image_source") != "read_image" ||
                data.opt("mime_type") != "image/png" ||
                data.optJSONObject("image_asset")?.opt("temporary") != true) return@runCatching null
            val width = data.opt("width").let { if (it is Int || it is Long) (it as Number).toLong() else return@runCatching null }
            val height = data.opt("height").let { if (it is Int || it is Long) (it as Number).toLong() else return@runCatching null }
            if (width !in 1L..8192L || height !in 1L..8192L || width * height > 32_000_000L) return@runCatching null
            data.remove("image_asset")
            data.toString()
        }.getOrNull()

        fun parse(name: String, arguments: String, output: String, readAsset: (String) -> ByteArray?): NativeFileImage? = runCatching {
            val metadata = metadata(name, arguments, output) ?: return@runCatching null
            val data = JSONObject(metadata)
            val bytes = readAsset(output) ?: return@runCatching null
            val size = pngDimensions(bytes) ?: return@runCatching null
            if (size.first != data.getInt("width") || size.second != data.getInt("height")) return@runCatching null
            NativeFileImage(bytes, size.first, size.second, metadata)
        }.getOrNull()
    }
}
