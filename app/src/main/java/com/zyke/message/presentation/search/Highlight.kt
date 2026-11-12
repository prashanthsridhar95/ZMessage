package com.zyke.message.presentation.search

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import com.zyke.message.domain.search.TextHighlight

/**
 * [text] with every place one of [words] appears drawn in [style]. The places come from the domain
 * (`TextHighlight.ranges`, tested); callers keep the result in `remember(text, words)` so it is built once per row.
 */
internal fun highlighted(text: String, words: List<String>, style: SpanStyle): AnnotatedString {
    val ranges = TextHighlight.ranges(text, words)
    if (ranges.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        var at = 0
        for (range in ranges) {
            if (range.first > at) append(text, at, range.first)
            withStyle(style) { append(text, range.first, range.last + 1) }
            at = range.last + 1
        }
        if (at < text.length) append(text, at, text.length)
    }
}
