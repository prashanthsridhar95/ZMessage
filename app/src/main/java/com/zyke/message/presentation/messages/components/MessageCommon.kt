package com.zyke.message.presentation.messages.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import com.zyke.message.presentation.motion.pressFeedback
import com.zyke.message.presentation.theme.Spacing
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import com.zyke.message.presentation.common.Text
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.zyke.message.presentation.common.LocalImageStore
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.zyke.message.presentation.common.scaledLines
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zyke.message.R
import com.zyke.message.domain.model.MerchantLogo
import com.zyke.message.domain.model.MessageCategory

@DrawableRes
internal fun MerchantLogo.drawableRes(): Int = when (this) {
    MerchantLogo.UPI -> R.drawable.upi
    MerchantLogo.PERSON -> R.drawable.baseline_person_24
    MerchantLogo.HDFC -> R.drawable.hdfc
    MerchantLogo.FEDERAL_BANK -> R.drawable.federal_bank
    MerchantLogo.AXIS_BANK -> R.drawable.axis_bank
    MerchantLogo.BLUE_DART -> R.drawable.bluedart
    MerchantLogo.FASTAG -> R.drawable.fastag
    MerchantLogo.BROADWAY_CINEMAS -> R.drawable.broadway_cinemas
    MerchantLogo.SWIGGY -> R.drawable.swiggy
    MerchantLogo.TATA_PLAY -> R.drawable.tataplay
    MerchantLogo.BOOMERANG -> R.drawable.boomerang
    MerchantLogo.DINEOUT -> R.drawable.dineout
    MerchantLogo.SKYLINK -> R.drawable.skylink
    MerchantLogo.FUEL -> R.drawable.fuel
    MerchantLogo.AMAZON -> R.drawable.amazon
    MerchantLogo.BAKERY -> R.drawable.bakery
    MerchantLogo.TEA -> R.drawable.tea
    MerchantLogo.SIMPL -> R.drawable.simpl
    MerchantLogo.LAZYPAY -> R.drawable.lazypay
    MerchantLogo.SWIRLYO -> R.drawable.swirlyo
    MerchantLogo.BURGERMAN -> R.drawable.burgerman
    MerchantLogo.AMEX -> R.drawable.amex
    MerchantLogo.LENSKART -> R.drawable.lenskart
    MerchantLogo.AIRTEL -> R.drawable.airtel_logo_icon
    MerchantLogo.BSNL -> R.drawable.bsnl_logo_icon
    MerchantLogo.JIO -> R.drawable.jio_logo_icon
    MerchantLogo.VI -> R.drawable.vi_mobile_icon
}

/**
 * Quiet text (captions, hints). Contrast against the surface is kept at WCAG AA (4.5:1): the
 * secondary colour is only faded as far as the theme's brightness allows.
 */
@Composable
internal fun mutedColor(): Color {
    val isLight = MaterialTheme.colorScheme.surface.luminance() > 0.5f
    return MaterialTheme.colorScheme.secondary.copy(alpha = if (isLight) 0.9f else 0.72f)
}

/** Lets a card hand its one-sentence screen-reader description to the row that draws it. */
internal val LocalSpokenSummary = compositionLocalOf<String?> { null }

/** The leading tile of every message row: one size, so titles line up down the whole list. */
internal val RowTile = 48.dp

/** Where a row's text starts: gutter, tile, gap. The "+N earlier" line under a group lines up with it. */
internal val RowTextInset = Spacing.lg + RowTile + Spacing.lg

/**
 * The one anatomy of a message row: a 48 dp tile, a title with a caption under it (one line each), and what sits at the end.
 * Side gutters are [Spacing.lg], the vertical padding [Spacing.md]. A tap dips the row a little (pressFeedback) and keeps
 * its ripple. [clickModifier] gets the shared interaction source, so a row with its own gestures (the one-time code's long
 * press) still dips.
 */
@Composable
internal fun MessageRow(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    verticalAlignment: Alignment.Vertical = Alignment.CenterVertically,
    clickModifier: @Composable (MutableInteractionSource) -> Modifier = { source ->
        Modifier.clickable(
            interactionSource = source, indication = LocalIndication.current,
            role = androidx.compose.ui.semantics.Role.Button, onClickLabel = "Show message", onClick = onClick
        )
    },
    content: @Composable RowScope.() -> Unit
) {
    val spoken = LocalSpokenSummary.current
    val swipeActions = LocalRowCustomActions.current
    val source = remember { MutableInteractionSource() }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .pressFeedback(source)
            .then(clickModifier(source))
            .padding(horizontal = Spacing.lg, vertical = Spacing.md)
            .semantics(mergeDescendants = true) {
                if (spoken != null) contentDescription = spoken
                if (swipeActions.isNotEmpty()) customActions = swipeActions
            }
    ) {
        // With a summary, the pieces are hidden from screen readers so it is not read twice.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (spoken != null) Modifier.clearAndSetSemantics { } else Modifier),
            verticalAlignment = verticalAlignment,
            content = content
        )
    }
}

@Composable
internal fun MerchantChip(
    logo: MerchantLogo,
    modifier: Modifier = Modifier,
    description: String? = null,
    isUnread: Boolean = false,
    size: Dp = 60.dp,
    /** The person's own picture for this sender / receiver, if they chose one. */
    imageKey: String? = null,
    /** Used to look up a brand logo, for names the app has no built-in icon for. */
    name: String? = null,
    /** Sit on a white plate with a quiet hairline, so any logo reads over a coloured surface such as a card's gradient. */
    plate: Boolean = false
) {
    val shape = RoundedCornerShape(size / 4)
    val picture = rememberSenderPicture(imageKey, name, wantsLogo = logo.isTinted)
    // A brand logo sits on white so transparent icons read in both themes; a person's own photo fills the tile.
    val isBrandLogo = picture != null && imageKey == null
    val monogram = if (picture == null && logo == MerchantLogo.PERSON) monogramFor(name) else null
    val tileColor = when {
        isBrandLogo || (plate && monogram == null) -> Color.White
        monogram != null -> monogram.second
        else -> MaterialTheme.colorScheme.tertiary
    }

    Box(modifier.size(size)) {
        Box(
            Modifier
                .matchParentSize()
                .border(0.5.dp, if (plate) Color.White.copy(alpha = 0.4f) else MaterialTheme.colorScheme.tertiary, shape)
                .background(tileColor, shape)
                .clip(shape),
            contentAlignment = Alignment.Center
        ) {
            when {
                picture != null -> Image(
                    bitmap = picture,
                    contentScale = if (imageKey != null) ContentScale.Crop else ContentScale.Fit,
                    modifier = if (imageKey != null) Modifier.matchParentSize() else Modifier.padding(size / 6),
                    contentDescription = description
                )
                monogram != null -> Text(
                    text = monogram.first,
                    color = Color.White,
                    fontSize = (size.value * MONOGRAM_SCALE).sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.semantics { if (description != null) contentDescription = description }
                )
                else -> Image(
                    painter = painterResource(logo.drawableRes()),
                    colorFilter = if (logo.isTinted) ColorFilter.tint(if (plate) PLATE_INK else MaterialTheme.colorScheme.onTertiary) else null,
                    contentScale = ContentScale.Fit,
                    modifier = if (plate) Modifier.padding(size / 6) else Modifier,
                    contentDescription = description
                )
            }
        }

        if (isUnread) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .size(12.dp)
                    .background(MaterialTheme.colorScheme.surface, CircleShape)
                    .padding(2.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape)
                    .semantics { contentDescription = "Unread" }
            )
        }
    }
}

/**
 * An airline's logo: its picture from the same cached, opt-in logo pipeline as a brand's (see [com.zyke.message.domain.flight.Airlines.logoDomain]),
 * on a white tile so a transparent icon reads in both themes; or, until it arrives, when logos are off, offline, in the demo or the
 * airline has none, a tonal tile with the two-character airline code. Purely decorative: the airline is always named in text beside it.
 */
@Composable
internal fun AirlineLogo(code: String, modifier: Modifier = Modifier, size: Dp = 44.dp) {
    val shape = RoundedCornerShape(size / 4)
    val domain = remember(code) { com.zyke.message.domain.flight.Airlines.logoDomain(code) }
    val picture = rememberDomainLogo(domain)
    // A steady tone per airline, drawn from the theme's own meaning colours (never a literal), so the fallback is not a grey blob.
    val tones = listOf(
        MaterialTheme.colorScheme.primary, com.zyke.message.presentation.theme.AppColors.info,
        com.zyke.message.presentation.theme.AppColors.success, com.zyke.message.presentation.theme.AppColors.warning
    )
    val tone = tones[(code.hashCode() and Int.MAX_VALUE) % tones.size]
    Box(
        modifier.size(size).clearAndSetSemantics { }
            .border(0.5.dp, MaterialTheme.colorScheme.tertiary, shape)
            .background(if (picture != null) Color.White else tone.copy(alpha = 0.16f), shape)
            .clip(shape),
        contentAlignment = Alignment.Center
    ) {
        if (picture != null) {
            Image(bitmap = picture, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.padding(size / 6))
        } else {
            Text(code.uppercase(), color = tone, fontSize = (size.value * 0.34f).sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

@Composable
private fun rememberDomainLogo(domain: String?): ImageBitmap? {
    val store = LocalImageStore.current
    if (store == null || domain == null) return null
    val version by store.version.collectAsState()
    val cached = remember(domain, version) { store.peekLogoDomain(domain)?.asImageBitmap() }
    if (cached != null) return cached
    val loaded by produceState<ImageBitmap?>(initialValue = null, domain, version) { value = store.loadLogoDomain(domain)?.asImageBitmap() }
    return loaded
}

/** Primary line of a row: who or what. */
@Composable
internal fun RowTitle(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    Text(
        modifier = modifier,
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        maxLines = scaledLines(1),
        overflow = TextOverflow.Ellipsis,
        color = color
    )
}

/** Faint secondary line: account, time, hints. */
@Composable
internal fun Caption(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = mutedColor(),
    maxLines: Int = 1,
    textAlign: TextAlign? = null
) {
    Text(
        modifier = modifier,
        text = text,
        textAlign = textAlign,
        style = MaterialTheme.typography.bodySmall,
        maxLines = scaledLines(maxLines),
        overflow = TextOverflow.Ellipsis,
        color = color
    )
}

@Composable
internal fun CategoryTag(category: MessageCategory, modifier: Modifier = Modifier) {
    Text(
        modifier = modifier
            .padding(top = 4.dp)
            .border(0.5.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp),
        text = category.displayString,
        fontSize = 14.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        color = mutedColor()
    )
}

private const val AMOUNT_ROW_SHARE = 0.5f

/** Big right-aligned figure such as "- ₹500". */
@Composable
internal fun AmountText(text: String, color: Color, modifier: Modifier = Modifier, fit: Boolean = false) {
    // The amount may take at most half the row, so the name beside it never shrinks to nothing (the "bill row" bug on
    // a 320 to 360 dp phone); it steps down a size there and may use a second line at large text.
    val screen = com.zyke.message.presentation.common.LocalResponsive.current
    com.zyke.message.presentation.common.PrivateAmount(
        modifier = modifier.widthIn(max = (screen.widthDp * AMOUNT_ROW_SHARE).dp),
        text = text,
        // A fitted figure takes only the width of its digits. With an alignment set, FitText fills all the room it is given, so the
        // amount's box grew to half the row and left the name beside it about 40 dp wide ("Googl…", "Bajaj / Finserv").
        textAlign = if (fit) null else TextAlign.End,
        style = if (screen.isCompact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
        maxLines = if (screen.stackRows && !fit) 2 else if (fit) 1 else scaledLines(1),
        overflow = TextOverflow.Ellipsis,
        color = color,
        fit = fit
    )
}

/** The end of a message row: the figure on one line, shrinking to fit its half of the row instead of wrapping or cutting off. */
@Composable
internal fun RowAmount(text: String, color: Color, modifier: Modifier = Modifier) = AmountText(text, color, modifier, fit = true)

/** Small right-aligned "icon + caption" line (balance, card number, account). */
@Composable
internal fun IconCaption(@DrawableRes icon: Int, text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            modifier = Modifier.size(18.dp),
            painter = painterResource(icon),
            contentDescription = null,
            colorFilter = ColorFilter.tint(mutedColor())
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
            color = mutedColor()
        )
    }
}


/** The person's chosen picture, else a cached brand logo for a well-known name, else null (the built-in icon). */
@Composable
private fun rememberSenderPicture(imageKey: String?, name: String?, wantsLogo: Boolean): ImageBitmap? {
    val store = LocalImageStore.current ?: return null
    val version by store.version.collectAsState()
    // A picture already in memory is used on the very first composition, so a row scrolling in never flashes the
    // placeholder icon first. Only a picture that has to be read from storage (or downloaded) arrives a moment later.
    val cached = remember(imageKey, name, wantsLogo, version) {
        when {
            imageKey != null -> store.peekChosen(imageKey)
            wantsLogo && !name.isNullOrBlank() -> store.peekLogo(name)
            else -> null
        }?.asImageBitmap()
    }
    if (cached != null) return cached
    val loaded by produceState<ImageBitmap?>(initialValue = null, imageKey, name, wantsLogo, version) {
        value = null
        value = when {
            imageKey != null -> store.loadChosen(imageKey)?.asImageBitmap()
            wantsLogo && !name.isNullOrBlank() -> store.loadLogo(name)?.asImageBitmap()
            else -> null
        }
    }
    return loaded
}


private const val MONOGRAM_SCALE = 0.42f

/** The ink of a glyph drawn on the white plate. */
private val PLATE_INK = Color(0xFF3C4150)

/** Deep, evenly weighted colours that keep white letters readable. */
private val MONOGRAM_COLORS = listOf(
    Color(0xFF5B6ABF), Color(0xFF2E7D6B), Color(0xFFB0573B), Color(0xFF7B4F9E),
    Color(0xFF3F7FA6), Color(0xFF9A6B1F), Color(0xFFA64466), Color(0xFF4F7A3A)
)

/** First letter of the name and a colour that stays the same for that name; null if it has no letter to show. */
private fun monogramFor(name: String?): Pair<String, Color>? {
    val clean = name?.trim().orEmpty()
    val letter = clean.firstOrNull { it.isLetter() } ?: return null
    val color = MONOGRAM_COLORS[(clean.lowercase().hashCode() and Int.MAX_VALUE) % MONOGRAM_COLORS.size]
    return letter.uppercase() to color
}
