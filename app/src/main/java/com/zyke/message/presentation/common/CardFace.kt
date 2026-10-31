package com.zyke.message.presentation.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zyke.message.domain.accounts.AccountKind
import com.zyke.message.domain.accounts.AccountSummary
import com.zyke.message.domain.cards.CardNetwork
import com.zyke.message.domain.model.CardType
import com.zyke.message.domain.model.UserCard

private val CardInk = Color(0xFFFFFFFF)
private val CardInkSoft = Color(0xFFFFFFFF).copy(alpha = 0.86f)
private const val CARD_RATIO = 1.586f

/**
 * A payment card drawn as a card: gradient from its colour theme, bank at the top, a chip, the masked number, the
 * card's model and the person's name for it, and the network's mark. The text on the card is white on purpose: every
 * theme gradient is dark enough for it. [footer] is a short status such as "Due in 3 days".
 */
@Composable
fun CardFace(card: UserCard, modifier: Modifier = Modifier, footer: String? = null, onClick: (() -> Unit)? = null) {
    val theme = card.theme
    val description = listOfNotNull(card.spokenDescription, footer).joinToString(". ")
    val shape = RoundedCornerShape(18.dp)
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
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    card.bankLabel.ifBlank { card.type.label }, Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = CardInk, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text(typeTag(card.type), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = CardInkSoft, letterSpacing = 1.sp)
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
            Text(
                "••••  ••••  ••••  ${card.last4.padEnd(4, '•')}",
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium, color = CardInk, letterSpacing = 1.sp, maxLines = 1
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    if (card.variant.isNotBlank()) {
                        Text(card.variant, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = CardInk, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (card.name.isNotBlank() && card.name != card.variant) {
                        Text(card.name.uppercase(), style = MaterialTheme.typography.labelSmall, color = CardInkSoft, letterSpacing = 0.8.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                NetworkMark(card.network)
            }
        }
    }
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

/** The network's wordmark, drawn rather than shipped as an image. */
@Composable
private fun NetworkMark(network: CardNetwork?) {
    when (network) {
        null, CardNetwork.OTHER -> Unit
        CardNetwork.MASTERCARD -> Box(Modifier.size(width = 44.dp, height = 28.dp)) {
            Box(Modifier.size(28.dp).align(Alignment.CenterStart).clip(CircleShape).background(Color(0xFFEB001B)))
            Box(Modifier.size(28.dp).align(Alignment.CenterEnd).clip(CircleShape).background(Color(0xFFF79E1B).copy(alpha = 0.9f)))
        }
        CardNetwork.VISA -> Text("VISA", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic, color = CardInk, letterSpacing = 1.sp)
        CardNetwork.RUPAY -> Text("RuPay", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic, color = CardInk)
        CardNetwork.AMEX -> Text(
            "AMEX", Modifier.border(1.5.dp, CardInk, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Black, color = CardInk, letterSpacing = 1.sp
        )
        CardNetwork.DINERS -> Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(22.dp).border(2.dp, CardInk, CircleShape))
            Text(" Diners", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = CardInk)
        }
    }
}

/** The card an account is drawn as: the person's own card with its details, else one made from what the messages show. */
fun AccountSummary.faceCard(): UserCard = userCard ?: UserCard(
    id = key,
    type = when (kind) {
        AccountKind.CREDIT_CARD -> CardType.CREDIT
        AccountKind.DEBIT_CARD -> CardType.DEBIT
        AccountKind.BANK_ACCOUNT -> CardType.ACCOUNT
    },
    last4 = last4, name = "", bankName = bankName.displayString
)

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
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 28.dp),
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
