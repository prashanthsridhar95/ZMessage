package com.zyke.message.presentation.common

import com.zyke.message.presentation.theme.Spacing
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import com.zyke.message.domain.device.ScreenClass
import kotlin.math.max

/*
 * The one place that knows how big the screen is, and how the layout should bend for it. Budget phones (Redmi, POCO,
 * Galaxy A0x) are 320 to 411 dp wide and about 700 to 760 dp tall, often with text at 1.15 to 1.3 times; the screens
 * were drawn on a 393 x 850 dp phone. See docs/LOWEND.md.
 */

/** The window the app is drawn in, in dp, and the text scale. Thresholds live in the domain's [ScreenClass]. */
@Immutable
data class Responsive(
    val widthDp: Int,
    val heightDp: Int,
    val fontScale: Float,
    val density: Float,
    val isLowEnd: Boolean
) {
    private val screen: ScreenClass get() = ScreenClass(widthDp, heightDp, fontScale)

    /** Narrower than 360 dp. */
    val isCompact: Boolean get() = screen.isCompact

    /** Shorter than 640 dp (landscape, split screen). */
    val isShort: Boolean get() = screen.isShort

    /** Text at 1.3 or more. */
    val isLargeText: Boolean get() = screen.isLargeText

    /** Side-by-side pairs should become stacked ones. */
    val stackRows: Boolean get() = screen.stackRows

    companion object {
        /** The phone the screens were drawn on, used where nothing provides a value (previews). */
        val Default = Responsive(393, 850, 1f, 2.75f, false)
    }
}

val LocalResponsive = staticCompositionLocalOf { Responsive.Default }

/**
 * Lite mode: no looping animations, no glow or blur, no off-screen layers, short fades. Decided from the person's
 * Performance setting and the phone (see LiteMode in the domain). Implies reduced motion for decorative animation.
 */
val LocalLite = staticCompositionLocalOf { false }

/** The size of the pretend phone while the developer's simulator is on, in the pretend density's dp; null otherwise. */
val LocalSimulatedWindow = compositionLocalOf<IntSize?> { null }

/** Provides [LocalResponsive] from the real window, or from the simulated one. Put it inside the simulator's density. */
@Composable
fun ProvideResponsive(isLowEnd: Boolean, content: @Composable () -> Unit) {
    val config = LocalConfiguration.current
    val density = LocalDensity.current
    val simulated = LocalSimulatedWindow.current
    val widthDp = simulated?.width ?: config.screenWidthDp
    val heightDp = simulated?.height ?: config.screenHeightDp
    val value = remember(widthDp, heightDp, density.fontScale, density.density, isLowEnd) {
        Responsive(widthDp, heightDp, density.fontScale, density.density, isLowEnd)
    }
    CompositionLocalProvider(LocalResponsive provides value, content = content)
}

/**
 * Chrome (nav labels, tab labels, chips, the toolbar) follows the person's text size up to 1.3 and no further, so a
 * 1.5 or "Largest" setting cannot push the bars off the screen. Never below what they chose; body text and amounts
 * are outside it and keep following the system size, and their screens scroll.
 */
@Composable
fun ChromeScale(content: @Composable () -> Unit) = CapFontScale(ScreenClass.CHROME_MAX_FONT_SCALE, content)

/**
 * Text inside [content] follows the system size up to [max] (and never below what the person chose). For fixed-shape
 * things such as a payment card's face, whose size is a picture's and cannot grow with the text.
 */
@Composable
fun CapFontScale(max: Float, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    if (density.fontScale <= max) {
        content()
    } else {
        CompositionLocalProvider(LocalDensity provides Density(density.density, max), content = content)
    }
}

/** At most [max] wide, but never wider than the room there is: a fixed 360.dp would overflow a 320 dp phone. */
fun Modifier.fitWidthMax(max: Dp): Modifier = this.widthIn(max = max).fillMaxWidth()

/** Gaps that tighten on a narrow phone. */
@Immutable
data class CompactSpacing(val gutter: Dp, val card: Dp, val gap: Dp)

@Composable
fun rememberCompactSpacing(): CompactSpacing {
    val compact = LocalResponsive.current.isCompact
    return remember(compact) { if (compact) CompactSpacing(Spacing.md, 14.dp, Spacing.sm) else CompactSpacing(Spacing.lg, Spacing.xl, Spacing.md) }
}

/** What a cell of a [ResponsiveRow] asks for: a share of the row, or the full width once stacked. */
interface ResponsiveRowScope {
    val stacked: Boolean
    fun Modifier.cell(): Modifier
}

/**
 * Two or three things side by side that turn into a column on a narrow phone or at large text (the "bill row" bug
 * class: weights beside fixed-size siblings squeeze text to nothing). Give each child [ResponsiveRowScope.cell].
 */
@Composable
fun ResponsiveRow(
    modifier: Modifier = Modifier,
    spacing: Dp = Spacing.md,
    content: @Composable ResponsiveRowScope.() -> Unit
) {
    if (LocalResponsive.current.stackRows) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(spacing)) {
            val scope = remember {
                object : ResponsiveRowScope {
                    override val stacked = true
                    override fun Modifier.cell(): Modifier = this.fillMaxWidth()
                }
            }
            scope.content()
        }
    } else {
        Row(modifier, horizontalArrangement = Arrangement.spacedBy(spacing)) {
            val row = this
            val scope = remember(row) {
                object : ResponsiveRowScope {
                    override val stacked = false
                    override fun Modifier.cell(): Modifier = with(row) { this@cell.weight(1f) }
                }
            }
            scope.content()
        }
    }
}

/**
 * One line of text that shrinks to fit the room instead of wrapping or cutting off: for the big amounts, card
 * numbers and chips. The size is measured once per text and width (not per frame), down to [minScale] of the asked
 * size; below that it ends in an ellipsis. It goes through the app's [Text], so amounts stay masked while hidden.
 * Text size still follows the person's font setting; this only takes back what does not fit.
 */
@Composable
fun FitText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    fontWeight: FontWeight? = null,
    textAlign: TextAlign? = null,
    minScale: Float = DEFAULT_MIN_SCALE,
    /** Size to fit this text instead of [text] (a counting figure is sized for its final value, so it does not breathe). */
    sizeAgainst: String? = null
) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val shown = privateText(sizeAgainst ?: text)
    val base = if (fontWeight != null) style.copy(fontWeight = fontWeight) else style
    BoxWithConstraints(modifier) {
        val bounded = constraints.hasBoundedWidth
        val room = constraints.maxWidth
        val scale = if (!bounded) 1f else remember(shown, base, room, density, direction) {
            fitScale(measurer, shown, base, room, minScale, density, direction)
        }
        val fitted = if (scale >= 1f) base else base.scaledBy(scale)
        Text(
            text,
            if (bounded && textAlign != null) Modifier.fillMaxWidth() else Modifier,
            color = color,
            style = fitted,
            textAlign = textAlign,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private const val DEFAULT_MIN_SCALE = 0.55f
private const val SAFETY = 0.98f

private fun TextStyle.scaledBy(scale: Float): TextStyle = copy(
    fontSize = if (fontSize.isSpecified) fontSize * scale else fontSize,
    lineHeight = if (lineHeight.isSpecified) lineHeight * scale else lineHeight
)

/** The factor (at most 1, at least [minScale]) that makes [text] one line no wider than [room] pixels. Pure of composition, so it can be tested. */
internal fun fitScale(
    measurer: TextMeasurer,
    text: String,
    style: TextStyle,
    room: Int,
    minScale: Float,
    density: Density,
    direction: LayoutDirection
): Float {
    if (text.isEmpty() || room <= 0) return 1f
    val width = measurer.measure(
        text, style, TextOverflow.Clip, false, 1, constraints = Constraints(), layoutDirection = direction, density = density
    ).size.width
    if (width <= room || width == 0) return 1f
    return max(minScale, room.toFloat() / width * SAFETY)
}
