package app.daiyu.android.references

import java.io.File

internal data class ReferenceTextPage(val offset: Long, val content: String, val nextOffset: Long?) {
    companion object {
        fun read(file: File, offset: Long, limit: Int, total: Long): ReferenceTextPage {
            require(offset in 0..total && limit in 1..16000)
            var start = offset
            val text = file.bufferedReader(Charsets.UTF_8).use { reader ->
                var remaining = (offset - 1).coerceAtLeast(0)
                while (remaining > 0) { val n = reader.skip(remaining); check(n > 0); remaining -= n }
                val value = CharArray(limit + 2)
                var count = 0
                if (offset > 0) {
                    val previous = reader.read().toChar()
                    if (previous.isHighSurrogate()) { start--; value[count++] = previous }
                }
                while (count < limit + 1) {
                    val n = reader.read(value, count, limit + 1 - count)
                    if (n < 0) break
                    count += n
                }
                var end = minOf(count, limit)
                if (end > 0 && value[end - 1].isHighSurrogate() && end < count && value[end].isLowSurrogate()) {
                    if (end == 1) end++ else end--
                }
                String(value, 0, end)
            }
            val next = start + text.length
            check(next > start || start == total) { "reference_snapshot_truncated" }
            return ReferenceTextPage(start, text, next.takeIf { it < total })
        }
    }
}
