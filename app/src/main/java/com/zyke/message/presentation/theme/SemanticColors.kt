package com.zyke.message.presentation.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * Meaning-carrying colours (good / caution / bad / informational) with one value per theme. The light values are
 * dark enough to be text on a light surface and the dark values light enough to be text on a dark one, both at
 * least 4.5:1 (checked by SemanticColorsTest). Use these instead of a literal green or red, so a screen
 * cannot end up with a colour chosen for one theme only.
 */
internal data class SemanticPalette(val success: Color, val warning: Color, val danger: Color, val info: Color)

internal object SemanticColors {
    val light = SemanticPalette(
        success = Color(0xFF0B7A55),
        warning = Color(0xFF8A5A00),
        danger = Color(0xFFC62828),
        info = Color(0xFF1E5FBF)
    )
    val dark = SemanticPalette(
        success = Color(0xFF3DD6A0),
        warning = Color(0xFFF5B942),
        danger = Color(0xFFFF6B6B),
        info = Color(0xFF7AB0FF)
    )
}

/** True when the current Material colour scheme is a dark one (so it also holds for dynamic and Black schemes). */
@Composable
@ReadOnlyComposable
internal fun isDarkScheme(): Boolean = MaterialTheme.colorScheme.surface.luminance() < 0.5f

object AppColors {
    private val palette: SemanticPalette
        @Composable @ReadOnlyComposable get() = if (isDarkScheme()) SemanticColors.dark else SemanticColors.light

    val success: Color @Composable @ReadOnlyComposable get() = palette.success
    val warning: Color @Composable @ReadOnlyComposable get() = palette.warning
    val danger: Color @Composable @ReadOnlyComposable get() = palette.danger
    val info: Color @Composable @ReadOnlyComposable get() = palette.info
}

/** WCAG contrast ratio between two opaque colours. */
internal fun contrastRatio(a: Color, b: Color): Float {
    val l1 = a.luminance()
    val l2 = b.luminance()
    val hi = maxOf(l1, l2)
    val lo = minOf(l1, l2)
    return (hi + 0.05f) / (lo + 0.05f)
}

/** Black or white, whichever reads better on top of this colour (for text or an icon on a branded fill). */
fun Color.onColor(): Color =
    if (contrastRatio(this, Color.Black) >= contrastRatio(this, Color.White)) Color.Black else Color.White
