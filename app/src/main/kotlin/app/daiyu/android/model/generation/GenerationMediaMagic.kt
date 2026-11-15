package app.daiyu.android.model.generation

import java.net.URI

internal const val GENERATION_IMAGE_LIMIT = GenerationRequestPlan.MAX_IMAGE_BYTES

internal fun ByteArray.hasSupportedImageMagic(): Boolean = sniffGenerationImageMimeType() != ""
internal fun ByteArray.sniffGenerationImageMimeType(): String = when {
    size >= 8 && take(8) == listOf(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a).map { it.toByte() } -> "image/png"
    size >= 3 && this[0] == 0xff.toByte() && this[1] == 0xd8.toByte() && this[2] == 0xff.toByte() -> "image/jpeg"
    size >= 12 && copyOfRange(0, 4).toString(Charsets.US_ASCII) == "RIFF" &&
        copyOfRange(8, 12).toString(Charsets.US_ASCII) == "WEBP" -> "image/webp"
    else -> ""
}

internal object GenerationVideoCodec {
    val FILE_EXTENSIONS = setOf("mp4", "webm", "mov", "m4v", "mkv", "avi")
    fun sniffMime(bytes: ByteArray): String? = when {
        bytes.size >= 12 && bytes.copyOfRange(4, 8).toString(Charsets.US_ASCII) == "ftyp" -> "video/mp4"
        bytes.size >= 4 && bytes.take(4) == listOf(0x1a, 0x45, 0xdf, 0xa3).map { it.toByte() } -> "video/webm"
        else -> null
    }
    fun extensionForMime(mimeType: String, filename: String = ""): String = when (mimeType.substringBefore(';').lowercase()) {
        "video/webm" -> "webm"
        "video/quicktime" -> "mov"
        "video/x-matroska" -> "mkv"
        "video/x-msvideo" -> "avi"
        else -> filename.substringAfterLast('.', "").lowercase().takeIf { it in FILE_EXTENSIONS } ?: "mp4"
    }
    fun isVideoSource(source: String): Boolean = source.startsWith("data:video/", true) || runCatching {
        val uri = URI(source)
        uri.scheme in setOf("http", "https", "content", "file") &&
            FILE_EXTENSIONS.any { uri.path.orEmpty().lowercase().endsWith(".$it") }
    }.getOrDefault(false)
}
