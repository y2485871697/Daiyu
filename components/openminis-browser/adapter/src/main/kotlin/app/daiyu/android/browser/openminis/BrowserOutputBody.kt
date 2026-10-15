package app.daiyu.android.browser.openminis

import app.daiyu.android.browser.bridge.BrowserCommand

/** Private length-framed string, so manager.executeJS does NOT pretty-print/truncate JSON text. */
internal object BrowserOutputBody {
    const val PREFIX = "__DAIYU_BROWSER_BODY_V1__\n"
    const val DEFAULT_MAX_CHARS = 12_000
    private const val METADATA_RESERVE = 256

    fun offset(command: BrowserCommand): Int = command.arguments["offset"] as Int? ?: 0
    fun limit(command: BrowserCommand): Int = minOf(
        command.arguments["max_chars"] as Int? ?: DEFAULT_MAX_CHARS,
        BrowserOutput.MAX_OUTPUT_CHARS - METADATA_RESERVE,
    )

    /** JSON string literal without org.json/Android, including JS line separators and lone surrogates. */
    fun quote(value: String): String = buildString {
        append('"')
        value.forEach { c ->
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                else -> if (c.code < 32 || c == '\u2028' || c == '\u2029' || c.isSurrogate()) {
                    append("\\u").append(c.code.toString(16).padStart(4, '0'))
                } else append(c)
            }
        }
        append('"')
    }

    fun script(command: BrowserCommand): String {
        val select = if (command.action == "get_readable") {
            // Mirrors the pinned getReadable selection/whitespace rules, WITHOUT its pre-truncation.
            """
                let el = null;
                const candidates = ['article', '[role="main"]', 'main', '.post-content',
                    '.article-body', '.entry-content', '#content', '.content'];
                for (const selector of candidates) {
                    const found = document.querySelector(selector);
                    if (found && window.getComputedStyle(found).display !== 'none' && (found.innerText || '').length > 0) {
                        el = found; break;
                    }
                }
                if (!el) el = document.body;
                if (!el) throw new Error('BROWSER_OPENMINIS_BODY_MISSING');
                const body = (el.innerText || '').replace(/\s+/g, ' ').trim();
            """.trimIndent()
        } else {
            val selector = command.arguments["selector"] as String?
            val target = selector?.let { "document.querySelector(${quote(it)})" } ?: "document.body"
            """
                const el = $target;
                if (!el) throw new Error('BROWSER_OPENMINIS_BODY_MISSING');
                const body = el.innerText || '';
            """.trimIndent()
        }
        return """
            $select
            const offset = ${offset(command)};
            const page = body.slice(offset, offset + ${limit(command)});
            return ${quote(PREFIX)} + body.length + '\n' + offset + '\n' + page.length + '\n' + page;
        """.trimIndent()
    }

    fun convert(command: BrowserCommand, framed: String): String {
        check(framed.startsWith(PREFIX)) { "BROWSER_OPENMINIS_BODY_RESULT_INVALID" }
        var cursor = PREFIX.length
        fun number(): Int {
            val end = framed.indexOf('\n', cursor)
            check(end >= cursor && end - cursor in 1..10) { "BROWSER_OPENMINIS_BODY_RESULT_INVALID" }
            val raw = framed.substring(cursor, end)
            check(raw.all { it in '0'..'9' } && (raw == "0" || !raw.startsWith('0'))) {
                "BROWSER_OPENMINIS_BODY_RESULT_INVALID"
            }
            cursor = end + 1
            return checkNotNull(raw.toIntOrNull()) { "BROWSER_OPENMINIS_BODY_RESULT_INVALID" }
        }
        val total = number()
        val actualOffset = number()
        val length = number()
        check(actualOffset == offset(command)) { "BROWSER_OPENMINIS_BODY_OFFSET_MISMATCH" }
        val expected = minOf(limit(command), (total - actualOffset).coerceAtLeast(0))
        check(length == expected && framed.length - cursor == length) { "BROWSER_OPENMINIS_BODY_LENGTH_MISMATCH" }
        val end = minOf(total.toLong(), actualOffset.toLong() + length).toInt()
        val next = if (end < total) end.toString() else "none"
        val body = framed.substring(cursor)
        // Header/title never consumes a body offset or max_chars allowance. Units are UTF-16 chars.
        return body + "\n[body offset=$actualOffset returned=$length total=$total next_offset=$next; UTF-16 chars]"
    }
}
