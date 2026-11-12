package com.zyke.message.presentation.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * Design tokens: the few numbers every screen is built from. Use these in shared components and new screens instead of
 * writing 12.dp or RoundedCornerShape(24.dp) again; docs/DESIGN.md says which token to use where. The app is flat: no
 * shadows or blur (LowEndPolicyTest), so depth is made with the tonal levels below, not with elevation.
 */

/** The spacing scale. Gaps between cards are [md]; side gutters [lg]; inner padding of a card [xl]. */
object Spacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 20.dp
    val xxl = 24.dp
    val xxxl = 32.dp
}

/** Corner radii. */
object Radius {
    /** Chips, small tags, the pieces of a segmented control. */
    val chip = 12.dp

    /** Buttons, choice cards, text fields, snackbars. */
    val control = 16.dp

    /** The ordinary card. */
    val card = 24.dp

    /** The one hero card of a screen. */
    val hero = 28.dp

    /** The top corners of a sheet. */
    val sheet = 28.dp
}

object AppShapes {
    val chip = RoundedCornerShape(Radius.chip)
    val control = RoundedCornerShape(Radius.control)
    val card = RoundedCornerShape(Radius.card)
    val hero = RoundedCornerShape(Radius.hero)
}

/** Icon sizes. */
object IconSize {
    /** Inline with a caption. */
    val sm = 16.dp

    /** Inline with body text, in a chip. */
    val md = 20.dp

    /** The standard icon. */
    val lg = 24.dp

    /** The icon of a shortcut tile. */
    val tile = 28.dp

    /** The round icon of an empty state. */
    val feature = 32.dp

    /** The disc behind an empty state's icon. */
    val disc = 64.dp
}

/** How raised a surface looks. Made from a tint of the text colour (or the accent for [Hero]), never a shadow. */
enum class Tonal {
    /** The ordinary card: a faint tint of the text colour. */
    Quiet,

    /** A card sitting on a card, or one that is pressed or selected. */
    Raised,

    /** The one card a screen is about: a tint of the accent. */
    Hero
}

/** The fill of a surface at [level]; a little stronger on true black, where a faint tint no longer shows. */
@Composable
@ReadOnlyComposable
fun tonalFill(level: Tonal): Color {
    val black = LocalTrueBlack.current
    val scheme = MaterialTheme.colorScheme
    return when (level) {
        Tonal.Quiet -> scheme.onSurface.copy(alpha = if (black) 0.09f else 0.05f)
        Tonal.Raised -> scheme.onSurface.copy(alpha = if (black) 0.14f else 0.09f)
        Tonal.Hero -> scheme.primary.copy(alpha = if (black) 0.2f else 0.12f)
    }
}

/**
 * The text roles. Use these for what a piece of text IS, so the same thing looks the same on every screen. All of them
 * are the theme's Plus Jakarta Sans styles; a screen never picks a font family of its own.
 */
object TypeRole {
    /** The one big figure of a card (a balance, today's budget). */
    val hero: TextStyle @Composable @ReadOnlyComposable get() = MaterialTheme.typography.displayMedium.copy(fontWeight = FontWeight.Bold)

    /** A card's or a page section's title. */
    val title: TextStyle @Composable @ReadOnlyComposable get() = MaterialTheme.typography.titleMedium

    /** The small spaced label above a card's content ("TODAY'S BUDGET"). */
    val section: TextStyle
        @Composable @ReadOnlyComposable
        get() = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.8.sp)

    /** Running text and row titles. */
    val body: TextStyle @Composable @ReadOnlyComposable get() = MaterialTheme.typography.bodyLarge

    /** The quiet line under a title (account, time, hint). */
    val caption: TextStyle @Composable @ReadOnlyComposable get() = MaterialTheme.typography.bodySmall
}
