package app.daiyu.android.voice

import java.io.IOException

/** Finalize streamed PCM WAV sizes only after the complete response has been received. */
internal object SpeechAudioFile {
    private const val UNKNOWN_SIZE = 0xffff_ffffL

    fun normalize(bytes: ByteArray): ByteArray {
        fun tag(offset: Int, value: String) = offset >= 0 && offset + 4 <= bytes.size &&
            value.indices.all { bytes[offset + it] == value[it].code.toByte() }
        if (bytes.size < 12 || !tag(0, "RIFF") || !tag(8, "WAVE")) return bytes
        fun invalid(): Nothing = throw IOException("voice_wave_header_invalid")
        fun uint(offset: Int, width: Int = 4): Long = (0 until width).fold(0L) { value, index ->
            value or ((bytes[offset + index].toLong() and 255L) shl (index * 8))
        }
        val riffSize = uint(4)
        var offset = 12
        var pcmBlock = 0L
        var dataSeen = false
        var dataSizeOffset = -1
        var dataSize = 0L
        var extendedSize = false
        while (offset < bytes.size) {
            if (bytes.size - offset < 8) invalid()
            val size = uint(offset + 4)
            val start = offset + 8
            if (tag(offset, "ds64")) extendedSize = true
            if (tag(offset, "fmt ") && size >= 16 && size <= bytes.size.toLong() - start) {
                val channels = uint(start + 2, 2)
                val rate = uint(start + 4)
                val block = uint(start + 12, 2)
                pcmBlock = if (uint(start, 2) == 1L && uint(start + 14, 2) == 16L &&
                    channels > 0 && rate > 0 && block == channels * 2 && uint(start + 8) == rate * block) block else 0L
            }
            if (tag(offset, "data")) {
                if (dataSeen) invalid()
                dataSeen = true
                dataSize = if (size == UNKNOWN_SIZE) bytes.size.toLong() - start else size
                if (size == UNKNOWN_SIZE) {
                    if (pcmBlock == 0L || dataSize == 0L || dataSize % pcmBlock != 0L) invalid()
                    // An unbounded data chunk consumes the rest of this completed response.
                    dataSizeOffset = offset + 4
                    offset = bytes.size
                    continue
                }
            } else if (size == UNKNOWN_SIZE) invalid()
            val end = start.toLong() + size + (size and 1L)
            if (end > bytes.size) invalid()
            offset = end.toInt()
        }
        if (riffSize != UNKNOWN_SIZE && dataSizeOffset < 0) return bytes
        if (extendedSize || !dataSeen || pcmBlock == 0L || dataSize == 0L || dataSize % pcmBlock != 0L ||
            (riffSize != UNKNOWN_SIZE && riffSize != bytes.size.toLong() - 8)) invalid()
        return bytes.copyOf().also { result ->
            fun write(offset: Int, value: Long) {
                repeat(4) { result[offset + it] = (value ushr (it * 8)).toByte() }
            }
            if (riffSize == UNKNOWN_SIZE) write(4, bytes.size.toLong() - 8)
            if (dataSizeOffset >= 0) write(dataSizeOffset, dataSize)
        }
    }
}
