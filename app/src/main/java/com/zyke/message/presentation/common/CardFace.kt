package com.zyke.message.presentation.common

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import com.zyke.message.presentation.common.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zyke.message.R
import com.zyke.message.domain.accounts.AccountKind
import com.zyke.message.domain.accounts.AccountSummary
import com.zyke.message.domain.cards.CardNetwork
import com.zyke.message.domain.classification.MerchantResolver
import com.zyke.message.domain.model.CardType
import com.zyke.message.domain.model.UserCard
import com.zyke.message.presentation.messages.components.MerchantChip

private val CardInk = Color(0xFFFFFFFF)
private val CardInkSoft = Color(0xFFFFFFFF).copy(alpha = 0.86f)
private const val CARD_RATIO = 1.586f
private const val CARD_MAX_FONT_SCALE = 1.15f
private val BANK_TILE = 30.dp

/**
 * A payment card drawn as a card: gradient from its colour theme, bank at the top, a chip, the masked number, the
 * card's model and the person's name for it, and the network's mark. The text on the card is white on purpose: every
 * theme gradient is dark enough for it. [footer] is a short status such as "Due in 3 days".
 */
@Composable
fun CardFace(card: UserCard, modifier: Modifier = Modifier, footer: String? = null, onClick: (() -> Unit)? = null) {
    val theme = card.theme
    val description = privateSpeech(listOfNotNull(card.spokenDescription, footer).joinToString(". "))
    val shape = RoundedCornerShape(18.dp)
    // The face keeps the proportions of a real card, so its text stops growing at 1.15 times; the details are in the screen-reader text.
    CapFontScale(CARD_MAX_FONT_SCALE) {
    Box(
        modifier
            .fillMaxWidth()
            .aspectRatio(CARD_RATIO)
            .clip(shape)
            .background(Brush.linearGradient(theme.colors.map { Color(it) }))
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .clearAndSetSemantics {
                contentDescription = description
                if (onClick != null) role = Role.Button
            }
    ) {
        // Soft rings, for depth; decoration only.
        Canvas(Modifier.fillMaxSize()) {
            val ring = Color.White.copy(alpha = 0.07f)
            drawCircle(ring, radius = size.height * 0.75f, center = Offset(size.width * 1.0f, size.height * 0.1f))
            drawCircle(ring, radius = size.height * 0.5f, center = Offset(size.width * 0.9f, size.height * 0.95f))
        }
        Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 14.dp), verticalArrangement = Arrangement.SpaceBetween) {
            // Top: the bank's logo on its white tile and its name, the kind of card at the far end.
            val bank = card.bankLabel
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (bank.isNotBlank()) {
                    BankLogoTile(bank)
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    bank.ifBlank { card.type.label }, Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = CardInk, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.width(8.dp))
                Text(typeTag(card.type), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = CardInkSoft, letterSpacing = 1.sp, maxLines = 1)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                ChipGlyph()
                Box(Modifier.weight(1f))
                if (footer != null) {
                    Text(
                        footer,
                        Modifier.clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.3f))
                            .border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = CardInk, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
            }
            // Bottom: the card's model and the last four digits on the left, the network's mark on the right.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    val model = card.variant.ifBlank { card.name }
                    if (model.isNotBlank()) {
                        Text(model, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = CardInkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(
                        "\u2022\u2022\u2022\u2022 ${card.last4.padEnd(4, '\u2022')}",
                        style = MaterialTheme.typography.titleMedium.copy(letterSpacing = 1.5.sp), fontWeight = FontWeight.Medium, color = CardInk, maxLines = 1
                    )
                }
                NetworkMark(card.network.takeIf { card.type.isCard })
            }
        }
    }
    }
}

/** The bank's logo from the same pipeline as the Accounts tab (the person's own logo setting, the cache, the built-in marks), on a white tile so it reads on any gradient; a lettered tile when there is none. */
@Composable
private fun BankLogoTile(bank: String) {
    val resolved = remember(bank) { MerchantResolver().resolve(bank) }
    MerchantChip(resolved.logo, size = BANK_TILE, name = resolved.name, description = null, plate = true)
}

private fun typeTag(type: CardType): String = when (type) {
    CardType.CREDIT -> "CREDIT"
    CardType.DEBIT -> "DEBIT"
    CardType.PREPAID -> "PREPAID"
    CardType.FOREX -> "FOREX"
    CardType.ACCOUNT -> "ACCOUNT"
}

@Composable
private fun ChipGlyph() {
    Box(
        Modifier.size(width = 36.dp, height = 27.dp).clip(RoundedCornerShape(6.dp))
            .background(Brush.linearGradient(listOf(Color(0xFFE9D8A6), Color(0xFFB99A4B))))
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val line = Color(0xFF6B5421).copy(alpha = 0.6f)
            val w = 1.dp.toPx()
            drawLine(line, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), w)
            drawLine(line, Offset(size.width / 3, 0f), Offset(size.width / 3, size.height), w)
            drawLine(line, Offset(size.width * 2 / 3, 0f), Offset(size.width * 2 / 3, size.height), w)
        }
    }
}

/** A network's mark: the vector drawable, its box (as in the file) and how tall it is drawn. Null for no network. */
private class MarkSpec(@DrawableRes val res: Int, val width: Int, val height: Int, val drawnHeight: Dp)

private fun CardNetwork?.markSpec(): MarkSpec? = when (this) {
    null, CardNetwork.OTHER -> null
    CardNetwork.VISA -> MarkSpec(R.drawable.network_visa, 58, 24, 24.dp)
    CardNetwork.MASTERCARD -> MarkSpec(R.drawable.network_mastercard, 40, 24, 28.dp)
    CardNetwork.AMEX -> MarkSpec(R.drawable.network_amex, 28, 28, 34.dp)
    CardNetwork.RUPAY -> MarkSpec(R.drawable.network_rupay, 80, 26, 26.dp)
    CardNetwork.DINERS -> MarkSpec(R.drawable.network_diners, 28, 28, 28.dp)
}

/**
 * The network's mark, drawn from a vector (white on the card, the brand colours where the mark has them), never fetched. It is
 * decoration: the card's own description names the network.
 */
@Composable
private fun NetworkMark(network: CardNetwork?) {
    val spec = network.markSpec() ?: return
    Image(
        painter = painterResource(spec.res),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier.padding(start = 10.dp).height(spec.drawnHeight).aspectRatio(spec.width.toFloat() / spec.height)
    )
}

/** The card an account is drawn as: the person's own card with its details, else one made from what the messages show. */
fun AccountSummary.faceCard(): UserCard {
    val own = userCard
    val base = own?.let { if (bankLabel.isNotBlank() && it.bankName != bankLabel && override?.bank?.isNotBlank() == true) it.copy(bankName = bankLabel) else it } ?: UserCard(
        id = key,
        type = when (kind) {
            AccountKind.CREDIT_CARD -> CardType.CREDIT
            AccountKind.DEBIT_CARD -> CardType.DEBIT
            AccountKind.BANK_ACCOUNT -> CardType.ACCOUNT
        },
        last4 = last4, name = "", bankName = bankLabel
    )
    // The network was settled with the account (your choice, else the messages, else the product), so the face never disagrees with it.
    val shown = network
    return if (base.network == shown) base else base.copy(network = shown)
}

/** One entry in a [CardDeck]. */
data class DeckCard(val key: String, val card: UserCard, val footer: String? = null)

/**
 * Cards side by side, one in focus with the next peeking in from the edge, like a wallet. Swipe to move; tapping a card
 * opens it. Dots beneath say how many there are and which one is in front.
 */
@Composable
fun CardDeck(cards: List<DeckCard>, pagerState: PagerState, onOpen: (DeckCard) -> Unit, modifier: Modifier = Modifier) {
    if (cards.isEmpty()) return
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        HorizontalPager(
            pagerState,
            Modifier.fillMaxWidth(),
            // Less of the next card peeks on a narrow phone, so the front one keeps its width.
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = if (LocalResponsive.current.isCompact) 20.dp else 28.dp),
            pageSpacing = 12.dp,
            key = { cards[it.coerceIn(cards.indices)].key }
        ) { page ->
            val entry = cards[page.coerceIn(cards.indices)]
            CardFace(entry.card, footer = entry.footer, onClick = { onOpen(entry) })
        }
        if (cards.size > 1) {
            Row(
                Modifier.padding(top = 10.dp)
                    .clearAndSetSemantics { contentDescription = "Card ${pagerState.currentPage + 1} of ${cards.size}" },
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                cards.indices.forEach { i ->
                    val on = i == pagerState.currentPage
                    Box(
                        Modifier.height(6.dp).width(if (on) 18.dp else 6.dp).clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = if (on) 1f else 0.25f))
                    )
                }
            }
        }
    }
}
