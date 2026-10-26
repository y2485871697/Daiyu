package app.daiyu.android.platform

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

/** Source-byte cursors and bounded windows. Non-seekable sources have a separate prefix budget. */
internal object DocumentTextWindow {
    const val MAX_OUTPUT_BYTES = 8192
    const val MAX_SCAN_BYTES = 1024 * 1024
    data class Result(val text: String, val offset: Long, val nextOffset: Long, val truncated: Boolean,
        val firstLine: Long, val lines: Int, val remainingLines: Long = 0,
        val scannedBytes: Int = 0, val partialLine: Boolean = false)

    fun read(input: InputStream, offset: Long = 0, startLine: Long? = null, lineCount: Int = 200,
        query: String? = null, cancelled: () -> Boolean = { false },
        seek: ((Long) -> Boolean)? = null, skipLines: Long = 0): Result {
        require(offset >= 0 && (startLine == null || startLine >= 1) && lineCount in 1..200 && skipLines >= 0)
        require(query == null || query.isNotBlank() && query.length <= 128)
        require(startLine == null || offset == 0L) { "Choose byte offset or line range" }
        fun active() { if (cancelled() || Thread.currentThread().isInterrupted) throw InterruptedException() }
        active()
        if (offset > 0 && seek?.invoke(offset) != true) {
            require(offset <= MAX_SCAN_BYTES) { "non_seekable_offset_limit" }
            var skipped = 0L
            val scratch = ByteArray(64 * 1024)
            while (skipped < offset) {
                active()
                var count = input.read(scratch, 0, minOf(scratch.size.toLong(), offset - skipped).toInt())
                if (count < 0) throw IllegalArgumentException("offset_beyond_eof")
                if (count == 0) { if (input.read() < 0) throw IllegalArgumentException("offset_beyond_eof"); count = 1 }
                skipped += count
            }
        }
        val raw = ByteArray(MAX_SCAN_BYTES + 4)
        var count = 0
        var eof = false
        while (count < raw.size) {
            active()
            var n = input.read(raw, count, raw.size - count)
            if (n < 0) { eof = true; break }
            if (n == 0) { val next = input.read(); if (next < 0) { eof = true; break }; raw[count] = next.toByte(); n = 1 }
            count += n
        }
        var beginning = 0
        if (offset > 0) while (beginning < minOf(count, 3) && raw[beginning].toInt() and 0xc0 == 0x80) beginning++
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val bytes = ByteBuffer.wrap(raw, beginning, count - beginning)
        val chars = CharBuffer.allocate(count)
        val decoded = decoder.decode(bytes, chars, eof)
        if (decoded.isError) decoded.throwException()
        chars.flip()
        val text = chars.toString()
        require('\u0000' !in text) { "binary_document" }
        val validBytes = bytes.position()
        val output = StringBuilder()
        var cursor = 0
        var sourceBytes = beginning.toLong()
        var line = 1L
        var first = 0L
        var emitted = 0
        var outputBytes = 0
        var partial = offset > 0
        val targetLine = startLine ?: (skipLines + 1)
        while (cursor < text.length && emitted < lineCount) {
            active()
            val newline = text.indexOf('\n', cursor).let { if (it < 0) text.length else it + 1 }
            val segment = text.substring(cursor, newline)
            val size = segment.toByteArray(Charsets.UTF_8).size
            val target = line >= targetLine
            val hit = if (query == null) 0 else segment.indexOf(query, ignoreCase = true)
            if (target && hit >= 0) {
                val room = MAX_OUTPUT_BYTES - outputBytes
                if (room == 0) break
                // Search snippets include the hit even for a multi-megabyte physical line.
                var from = if (query != null && size > room) maxOf(0, hit - 128) else 0
                if (from > 0 && segment[from].isLowSurrogate()) from--
                var end = from
                var used = 0
                while (end < segment.length) {
                    val cp = segment.codePointAt(end)
                    val encoded = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).size
                    if (used + encoded > room) break
                    end += Character.charCount(cp); used += encoded
                }
                if (first == 0L) first = line
                output.append(segment, from, end); outputBytes += used; emitted++
                partial = partial || from > 0 || end < segment.length || !segment.endsWith('\n') && !eof
                if (end < segment.length) {
                    sourceBytes += segment.substring(0, end).toByteArray(Charsets.UTF_8).size
                    break
                }
            }
            sourceBytes += size
            cursor = newline
            if (segment.endsWith('\n')) line++
        }
        if (cursor == text.length) sourceBytes = validBytes.toLong()
        // Re-scan a short codepoint-safe suffix for a query split across windows.
        if (query != null && !eof && cursor == text.length && text.isNotEmpty()) {
            var overlap = maxOf(0, text.length - query.length - 1)
            if (overlap > 0 && text[overlap].isLowSurrogate()) overlap--
            val tail = text.substring(overlap)
            val suffix = tail.toByteArray(Charsets.UTF_8).size
            sourceBytes = maxOf(beginning.toLong() + 1, sourceBytes - suffix)
            line -= tail.count { it == '\n' }
        }
        val remaining = maxOf(0, targetLine - line)
        return Result(output.toString(), offset + beginning, offset + sourceBytes,
            !eof || sourceBytes < validBytes, if (first == 0L) line else first, emitted, remaining,
            count, partial)
    }
}
