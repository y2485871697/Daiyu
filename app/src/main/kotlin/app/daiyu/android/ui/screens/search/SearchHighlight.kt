package app.daiyu.android.ui.screens.search

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString

internal fun highlightSearchBody(roleLabel: String, body: String, query: String, highlight: SpanStyle): AnnotatedString =
    buildAnnotatedString {
        append("$roleLabel · ")
        val bodyStart = length
        for (char in body) append(if (char.isWhitespace()) ' ' else char)
        val needle = query.trim()
        if (needle.isEmpty()) return@buildAnnotatedString
        var from = 0
        while (from <= body.length - needle.length) {
            val at = body.indexOf(needle, startIndex = from, ignoreCase = true)
            if (at < 0) break
            addStyle(highlight, bodyStart + at, bodyStart + at + needle.length)
            from = at + needle.length
        }
    }
