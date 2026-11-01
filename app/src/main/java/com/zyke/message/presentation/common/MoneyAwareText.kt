package com.zyke.message.presentation.common

import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import com.zyke.message.domain.privacy.AmountMasker
import androidx.compose.material3.Text as MaterialText

/*
 * Every screen imports THIS Text instead of Material's. While amounts are hidden it swaps every money amount inside the
 * text (a sentence, a caption, a message body, a chip) for dots, and TalkBack hears "amount hidden" instead of the figure.
 * With amounts visible it is Material's Text and costs one boolean read. SourceScanTest keeps Material's Text out of the
 * screens, so new text is covered without anyone having to remember.
 */

@Composable
fun Text(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
    style: TextStyle = LocalTextStyle.current
) {
    val hidden = LocalPrivacyMode.current.hidden
    val shown = if (hidden) remember(text) { AmountMasker.mask(text) } else text
    if (shown !== text) {
        val spoken = remember(text) { AmountMasker.maskSpoken(text) }
        MaterialText(
            shown, modifier.semantics { contentDescription = spoken }, color, fontSize, fontStyle, fontWeight, fontFamily,
            letterSpacing, textDecoration, textAlign, lineHeight, overflow, softWrap, maxLines, minLines, onTextLayout, style
        )
    } else {
        MaterialText(
            text, modifier, color, fontSize, fontStyle, fontWeight, fontFamily,
            letterSpacing, textDecoration, textAlign, lineHeight, overflow, softWrap, maxLines, minLines, onTextLayout, style
        )
    }
}

@Composable
fun Text(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    onTextLayout: (TextLayoutResult) -> Unit = {},
    style: TextStyle = LocalTextStyle.current
) {
    val hidden = LocalPrivacyMode.current.hidden
    val shown = if (hidden) remember(text) { maskAnnotated(text) } else text
    if (shown !== text) {
        val spoken = remember(text) { AmountMasker.maskSpoken(text.text) }
        MaterialText(
            shown, modifier.semantics { contentDescription = spoken }, color, fontSize, fontStyle, fontWeight, fontFamily,
            letterSpacing, textDecoration, textAlign, lineHeight, overflow, softWrap, maxLines, minLines, emptyMap(), onTextLayout, style
        )
    } else {
        MaterialText(
            text, modifier, color, fontSize, fontStyle, fontWeight, fontFamily,
            letterSpacing, textDecoration, textAlign, lineHeight, overflow, softWrap, maxLines, minLines, emptyMap(), onTextLayout, style
        )
    }
}

/** What a screen reader should say for [text]: the same words, with any amount replaced while amounts are hidden. */
@Composable
fun privateSpeech(text: String): String {
    val hidden = LocalPrivacyMode.current.hidden
    return if (hidden) remember(text) { AmountMasker.maskSpoken(text) } else text
}

/** [text] with amounts swapped for dots while hidden, for the few places that draw text without [Text] (canvas, notifications). */
@Composable
fun privateText(text: String): String {
    val hidden = LocalPrivacyMode.current.hidden
    return if (hidden) remember(text) { AmountMasker.mask(text) } else text
}

/** Masks the amounts in [text] and moves its styled ranges to where the words now sit. Same instance when nothing is masked. */
internal fun maskAnnotated(text: AnnotatedString): AnnotatedString {
    val spans = AmountMasker.find(text.text)
    if (spans.isEmpty()) return text
    val placeholder = AmountMasker.PLACEHOLDER
    val out = StringBuilder(text.length)
    var from = 0
    for (s in spans) { out.append(text.text, from, s.first).append(placeholder); from = s.last + 1 }
    out.append(text.text, from, text.length)

    fun map(offset: Int, isEnd: Boolean): Int {
        var shift = 0
        for (s in spans) {
            val start = s.first
            val end = s.last + 1
            when {
                offset >= end -> shift += placeholder.length - (end - start)
                offset > start -> return start + shift + if (isEnd) placeholder.length else 0
                else -> break
            }
        }
        return offset + shift
    }
    return AnnotatedString(
        out.toString(),
        text.spanStyles.map { AnnotatedString.Range(it.item, map(it.start, false), map(it.end, true)) },
        text.paragraphStyles.map { AnnotatedString.Range(it.item, map(it.start, false), map(it.end, true)) }
    )
}
