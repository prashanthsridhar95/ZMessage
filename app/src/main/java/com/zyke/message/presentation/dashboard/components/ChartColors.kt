package com.zyke.message.presentation.dashboard.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import com.zyke.message.presentation.theme.AppColors
import androidx.compose.ui.graphics.Color
import com.zyke.message.domain.model.MessageCategory

/**
 * Category hues: fixed mid-tones used only as fills, dots and strokes (never as text), chosen to hold up on light and
 * dark surfaces. The rest share one neutral. Meaning colours (good/warning/bad) come from [AppColors].
 */
internal object ChartColors {
    const val NAMED = 5

    private val palette = listOf(
        Color(0xFF4C8BF5), // blue
        Color(0xFFF5A623), // amber
        Color(0xFF34A853), // green
        Color(0xFFE8505B), // red
        Color(0xFF9B6BDF)  // violet
    )
    val other = Color(0xFF8A9BA8)
    val warning: Color @Composable @ReadOnlyComposable get() = AppColors.warning

    /** [rank] is the category's position in the list sorted by amount, so colours match across the screen. */
    fun forRank(rank: Int): Color = palette.getOrNull(rank) ?: other

    fun forCategory(category: MessageCategory, ranking: List<MessageCategory>): Color =
        forRank(ranking.indexOf(category).let { if (it < 0) Int.MAX_VALUE else it })
}
