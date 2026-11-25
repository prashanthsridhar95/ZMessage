package com.zyke.message.presentation.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zyke.message.R
import com.zyke.message.domain.accounts.AccountsOverview
import com.zyke.message.presentation.common.LocalContentInset
import com.zyke.message.presentation.common.LocalResponsive
import com.zyke.message.presentation.common.Text
import com.zyke.message.presentation.common.heading
import com.zyke.message.presentation.common.rememberGrowingState
import com.zyke.message.presentation.format.DisplayFormatters
import com.zyke.message.presentation.messages.components.Caption
import com.zyke.message.presentation.messages.components.mutedColor
import com.zyke.message.presentation.motion.AnimatedAmount
import com.zyke.message.presentation.motion.pressFeedback
import com.zyke.message.presentation.theme.AppShapes
import com.zyke.message.presentation.theme.LocalTrueBlack
import com.zyke.message.presentation.theme.Spacing
import com.zyke.message.presentation.theme.TypeRole
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/*
 * Home's own polish layer (docs/DESIGN.md, "Home polish"). Home-only, so the cards other tabs use (FintechCard, CardHeader) stay as they are:
 * one surface (a step of the theme's surface containers, not a tint of the text colour), one label style, one pill, one progress bar,
 * and the hero. Tokens and theme colours only; no shadows, no blur, nothing that moves except what the motion package already moves once.
 */

/** True at 320 dp, at large text and wherever [LocalResponsive] says rows stack: Home puts side-by-side pieces on top of each other. */
@Composable
internal fun homeStacked(): Boolean {
    val responsive = LocalResponsive.current
    return responsive.stackRows || responsive.fontScale >= 1.5f
}

/**
 * Home's card. The fill is a step up the theme's surface containers (so dark mode gets depth from tone, not from a border); true black
 * keeps the hairline FintechCard also adds there. [fill] swaps the tone for a brush (the hero). A card with [onClick] dips while held,
 * keeps its ripple and speaks as one button. Rows inside ripple edge to edge ([LocalContentInset]).
 */
@Composable
internal fun HomeCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    shape: Shape = AppShapes.card,
    fill: Brush? = null,
    horizontal: Dp = Spacing.xl,
    vertical: Dp = Spacing.xl,
    content: @Composable ColumnScope.() -> Unit
) {
    val source = remember { MutableInteractionSource() }
    val scheme = MaterialTheme.colorScheme
    val container = scheme.surfaceContainer
    val edge = scheme.onSurface.copy(alpha = 0.16f)
    val trueBlack = LocalTrueBlack.current
    CompositionLocalProvider(LocalContentInset provides horizontal) {
        Column(
            modifier
                .then(if (onClick != null) Modifier.pressFeedback(source) else Modifier)
                .fillMaxWidth()
                .clip(shape)
                .then(if (fill != null) Modifier.background(fill, shape) else Modifier.background(container, shape))
                .then(if (trueBlack) Modifier.border(1.dp, edge, shape) else Modifier)
                .then(
                    if (onClick != null) Modifier.clickable(
                        interactionSource = source, indication = LocalIndication.current, onClickLabel = onClickLabel, role = Role.Button, onClick = onClick
                    ) else Modifier
                )
                .padding(horizontal = horizontal, vertical = vertical),
            content = content
        )
    }
}

/** The small spaced capitals above a block ("TODAY", "YOUR DECK"); a heading for screen readers. */
@Composable
internal fun HomeLabel(text: String, modifier: Modifier = Modifier, color: Color = mutedColor()) {
    Text(text.uppercase(), modifier.heading(), style = TypeRole.section, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** A small rounded tag: a status ("Due tomorrow") or a count ("3 accounts"). [dot] adds a leading dot in the ink colour. */
@Composable
internal fun HomePill(text: String, ink: Color, modifier: Modifier = Modifier, background: Color = ink.copy(alpha = 0.12f), dot: Boolean = false) {
    Row(
        modifier.background(background, CircleShape).padding(horizontal = Spacing.sm, vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (dot) {
            Box(Modifier.size(6.dp).background(ink, CircleShape))
            Spacer(Modifier.width(Spacing.xs))
        }
        Text(text, style = MaterialTheme.typography.labelMedium, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A progress bar 8 dp thick: a quiet track and a rounded fill that grows once (instant when reduced); the fill is the semantic [color]. */
@Composable
internal fun HomeProgress(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    val shown = rememberGrowingState(fraction.coerceIn(0.02f, 1f))
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    Box(modifier.fillMaxWidth().height(Spacing.sm).background(track, CircleShape)) {
        Box(
            Modifier.fillMaxSize().drawBehind {
                drawRoundRect(color, size = Size(size.width * shown.value, size.height), cornerRadius = CornerRadius(size.height / 2f))
            }
        )
    }
}

/** "Sat, 28 Nov". */
internal fun homeDate(date: LocalDate): String = DateTimeFormatter.ofPattern("EEE, d MMM", Locale.getDefault()).format(date)

/** "Updated today", "Updated yesterday", "Updated 24 Nov": how fresh the newest balance reading is. */
internal fun updatedLabel(date: LocalDate, today: LocalDate = LocalDate.now()): String {
    val days = ChronoUnit.DAYS.between(date, today)
    return when {
        days <= 0L -> "Updated today"
        days == 1L -> "Updated yesterday"
        else -> "Updated " + DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()).format(date)
    }
}

/**
 * The one hero of Home: the balance, large and in the text colour (the accent only tints the card, a soft diagonal from stronger to
 * fainter), a label with a count pill and a chevron that says it opens, and how fresh the reading is. Tap opens Accounts. The figure
 * counts up once on Home's first showing ([intro]).
 */
@Composable
internal fun HomeBalanceHero(overview: AccountsOverview, modifier: Modifier = Modifier, intro: Boolean = false, onOpen: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val black = LocalTrueBlack.current
    val wash = Brush.linearGradient(
        listOf(scheme.primary.copy(alpha = if (black) 0.26f else 0.18f), scheme.primary.copy(alpha = if (black) 0.1f else 0.07f))
    )
    val stacked = homeStacked()
    val total = overview.totalBalance
    val asOf = remember(overview.accounts) {
        overview.accounts.filter { it.balance != null }.mapNotNull { it.balanceAsOf }.maxByOrNull { it.toEpochDay() }
    }
    val freshness = remember(asOf) { asOf?.let { updatedLabel(it) } }
    HomeCard(modifier, onClick = onOpen, onClickLabel = "Open accounts", shape = AppShapes.hero, fill = wash) {
        if (stacked) {
            HomeLabel("Total balance", Modifier.fillMaxWidth())
            if (total != null) Box(Modifier.padding(top = Spacing.sm)) { HomePill(DisplayFormatters.count(overview.countedBalances, "account", "accounts"), scheme.secondary, background = scheme.onSurface.copy(alpha = 0.07f)) }
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                HomeLabel("Total balance", Modifier.weight(1f))
                if (total != null) {
                    HomePill(DisplayFormatters.count(overview.countedBalances, "account", "accounts"), scheme.secondary, background = scheme.onSurface.copy(alpha = 0.07f))
                    Spacer(Modifier.width(Spacing.xs))
                }
                Image(
                    painter = painterResource(R.drawable.baseline_chevron_right_24), contentDescription = null, modifier = Modifier.size(20.dp),
                    colorFilter = ColorFilter.tint(mutedColor())
                )
            }
        }
        if (total != null) {
            AnimatedAmount(
                total,
                Modifier.padding(top = Spacing.md),
                style = MaterialTheme.typography.displayMedium.copy(fontFeatureSettings = "tnum", letterSpacing = (-0.5).sp),
                fontWeight = FontWeight.Bold,
                color = scheme.onSurface,
                maxLines = 1,
                fit = true,
                fromZero = intro
            )
            if (freshness != null) Caption(freshness, Modifier.padding(top = Spacing.sm))
        } else {
            Text("Your accounts", Modifier.padding(top = Spacing.md), style = MaterialTheme.typography.headlineMedium, color = scheme.onSurface)
            Caption("No balances yet", Modifier.padding(top = Spacing.sm))
        }
    }
}
