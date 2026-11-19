package app.daiyu.android.platform

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Locale

/** Provider MIME is authoritative unless missing or generic; names only admit plain text. */
internal object DocumentReadPolicy {
    private fun mime(value: String?) = value.orEmpty().substringBefore(';').trim().lowercase(Locale.ROOT)
    private val textTypes = setOf("application/json", "application/xml", "application/javascript",
        "application/x-yaml", "application/yaml", "application/sql", "application/x-ndjson")

    fun needsName(value: String?) = mime(value) in setOf("", "application/octet-stream")
    fun supports(value: String?, name: String?): Boolean {
        val type = mime(value)
        if (type.startsWith("text/") || type in textTypes) return true
        return needsName(value) && name?.substringAfterLast('.', "")?.lowercase(Locale.ROOT) in setOf("log", "txt")
    }

    fun validUriEncoding(value: String): Boolean {
        var index = 0
        while (index < value.length) {
            if (value[index] != '%') { index++; continue }
            val bytes = ByteArray((value.length - index) / 3)
            var count = 0
            while (index < value.length && value[index] == '%') {
                if (index + 2 >= value.length) return false
                val high = Character.digit(value[index + 1], 16)
                val low = Character.digit(value[index + 2], 16)
                if (high < 0 || low < 0) return false
                bytes[count++] = ((high shl 4) or low).toByte()
                index += 3
            }
            try {
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes, 0, count))
            } catch (_: java.nio.charset.CharacterCodingException) { return false }
        }
        return true
    }

    fun binaryHeader(bytes: ByteArray, count: Int): Boolean = binarySignatures.any { signature ->
        count >= signature.size && signature.indices.all { bytes[it].toInt() and 0xff == signature[it] }
    }
    private val binarySignatures = listOf(
        intArrayOf(0x25, 0x50, 0x44, 0x46, 0x2d), // PDF
        intArrayOf(0x50, 0x4b, 0x03, 0x04), intArrayOf(0x50, 0x4b, 0x05, 0x06), intArrayOf(0x50, 0x4b, 0x07, 0x08),
        intArrayOf(0x89, 0x50, 0x4e, 0x47), intArrayOf(0xff, 0xd8, 0xff),
        intArrayOf(0x47, 0x49, 0x46, 0x38), intArrayOf(0x7f, 0x45, 0x4c, 0x46),
        intArrayOf(0x1f, 0x8b), intArrayOf(0x37, 0x7a, 0xbc, 0xaf, 0x27, 0x1c),
        intArrayOf(0x52, 0x61, 0x72, 0x21, 0x1a, 0x07), intArrayOf(0xd0, 0xcf, 0x11, 0xe0),
        intArrayOf(0x53, 0x51, 0x4c, 0x69, 0x74, 0x65, 0x20, 0x66), intArrayOf(0x52, 0x49, 0x46, 0x46)
    )
}
