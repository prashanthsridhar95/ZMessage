package com.zyke.message.presentation.common

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics

/** Marks a title as a heading, so screen-reader users can jump between sections. */
fun Modifier.heading(): Modifier = semantics { heading() }

/**
 * True when decorative motion should be skipped: the person has turned system animations off ("Remove animations" /
 * animator scale 0), or lite mode is on (see [LocalLite]): every screen that already honours reduced motion then
 * also drops its looping and decorative animation on a slow phone.
 */
@Composable
fun rememberReduceMotion(): Boolean {
    val lite = LocalLite.current
    val resolver = LocalContext.current.contentResolver
    val system = remember {
        runCatching { Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)
    }
    return lite || system
}

private const val LARGE_FONT_SCALE = 1.3f

/** One more line for text when the person has made system text large, so it wraps instead of being cut off. */
@Composable
fun scaledLines(base: Int): Int = if (LocalDensity.current.fontScale > LARGE_FONT_SCALE) base + 1 else base
