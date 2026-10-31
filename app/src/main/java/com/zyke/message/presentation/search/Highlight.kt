package com.zyke.message.presentation.search

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle

/**
 * [text] with every place one of [words] appears drawn in [style]. Plain `indexOf`, no regular expression; callers keep
 * the result in `remember(text, words)` so it is built once per row, not on every recomposition.
 */
internal fun highlighted(text: String, words: List<String>, style: SpanStyle): AnnotatedString {
    if (words.isEmpty() || text.isEmpty()) return AnnotatedString(text)
    // Marks which characters to draw; overlapping words merge.
    val marked = BooleanArray(text.length)
    var any = false
    for (word in words) {
        if (word.isEmpty()) continue
        var at = text.indexOf(word, 0, ignoreCase = true)
        while (at >= 0) {
            for (i in at until at + word.length) marked[i] = true
            any = true
            at = text.indexOf(word, at + word.length, ignoreCase = true)
        }
    }
    if (!any) return AnnotatedString(text)
    return buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            var j = i
            while (j < text.length && marked[j] == marked[i]) j++
            if (marked[i]) withStyle(style) { append(text, i, j) } else append(text, i, j)
            i = j
        }
    }
}
