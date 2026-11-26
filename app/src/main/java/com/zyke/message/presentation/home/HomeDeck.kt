package com.zyke.message.presentation.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zyke.message.R
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.triage.TriageCard
import com.zyke.message.domain.triage.TriageDeck
import com.zyke.message.domain.triage.TriageGestures
import com.zyke.message.domain.triage.TriageMove
import com.zyke.message.presentation.common.AppButton
import com.zyke.message.presentation.common.ButtonSize
import com.zyke.message.presentation.common.ButtonStyle
import com.zyke.message.presentation.common.EmptyState
import com.zyke.message.presentation.common.AppPage
import com.zyke.message.presentation.common.Text
import com.zyke.message.presentation.common.DeckDots
import com.zyke.message.presentation.common.privateSpeech
import com.zyke.message.presentation.common.rememberLedgerActions
import com.zyke.message.presentation.common.scaledLines
import com.zyke.message.presentation.common.tappable
import com.zyke.message.presentation.format.DisplayFormatters
import com.zyke.message.presentation.home.HomeContract.Intent
import com.zyke.message.presentation.messages.components.Caption
import com.zyke.message.presentation.messages.components.mutedColor
import com.zyke.message.domain.tour.TourAnchor
import com.zyke.message.presentation.tour.tourAnchor
import com.zyke.message.presentation.motion.EnterGate
import com.zyke.message.presentation.motion.enterOnce
import com.zyke.message.presentation.motion.rememberCountUpInt
import com.zyke.message.presentation.motion.rememberHaptics
import com.zyke.message.presentation.motion.rememberMotion
import com.zyke.message.presentation.theme.AppShapes
import com.zyke.message.presentation.theme.Spacing
import com.zyke.message.presentation.theme.TypeRole

/** The height of the front card on Home: the same card the triage page shows, with a two-line headline and summary. It never grows with text (the card caps font scale at 1.3). */
private val INLINE_CARD_HEIGHT = 272.dp

/** The widest a footer segment may be squeezed to before the footer goes one item per row. */
private val FOOTER_MIN_SEGMENT = 120.dp

/** How many first-showing slots [DeckSection] uses: the whole container rises in once, as one piece (see enterOnce). */
internal fun deckSlots(@Suppress("UNUSED_PARAMETER") deck: TriageDeck): Int = 1

/** One tappable item of the footer: its words, what a screen reader says, whether it is the accent one, and where it goes. */
private class FooterItem(val label: String, val spoken: String, val strong: Boolean, val onClick: () -> Unit, val anchor: Modifier = Modifier)

/**
 * "Your deck" on Home: ONE card with three segments told apart by hairline dividers (the same container as every other Home block).
 *
 *  - HEADER: "Your deck", what is left ("N to go", or "N to review" when the review is the body), the position and the settings gear.
 *  - BODY, one of
 *      - Something is new (the KEEP stacks have cards): a real stack, the front card with the next two peeking behind it, clipped inside
 *        the card. Swipe it left to archive, right to keep (mark read), up or down to move around the deck: the same gestures, hints and
 *        haptics as the triage page. Every gesture is also a screen-reader action; the triage page has the buttons. The page dots and the
 *        quiet swipe hint sit under the stack.
 *      - Nothing is new but the app has questions about the money (the review queue): one card that opens the review.
 *      - Neither: a compact "All caught up".
 *  - FOOTER: equal tappable segments split by hairlines: "N to review" (accent, only while the stack is the body), "Quiet N",
 *    "Auto-cleared N" and "All stacks". Two to a row at most, one per row at 320 dp or large text. No wrapping chips.
 * When the deck is switched off in Customize Home ([showDeck] false) only the review row remains, because the review page opens from nowhere else.
 *
 * Entrance: the container rises in once (the Home screen's gate); the number counts up from zero on that first showing only. Nothing loops.
 */
@Composable
internal fun DeckSection(
    deck: TriageDeck,
    messages: Map<Long, ClassifiedMessage>,
    gate: EnterGate,
    firstSlot: Int,
    intro: Boolean,
    showDeck: Boolean,
    reviewCount: Int,
    onIntent: (Intent) -> Unit,
    onOpen: (quiet: Boolean, stackId: String?) -> Unit,
    onOpenAutoCleared: () -> Unit,
    onOpenReview: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Whichever of the review card, the footer item or the review row is on screen carries the tour's anchor (only one is ever composed).
    val reviewAnchor = Modifier.tourAnchor(TourAnchor.HOME_REVIEW)
    if (!showDeck) {
        if (reviewCount > 0) ReviewRow(reviewCount, onOpenReview, modifier.then(reviewAnchor))
        return
    }
    val cards = remember(deck) { deck.cards(false) }
    var index by rememberSaveable { mutableIntStateOf(0) }
    val i = TriageGestures.afterRemoval(index, cards.size)
    val front = cards.getOrNull(i)
    val deckState = remember { TriageDeckState() }
    val motion = rememberMotion()
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val ledger = rememberLedgerActions()
    val act: (TriageMove) -> Unit = { move ->
        val card = front
        if (card != null && move != TriageMove.NONE && !(cards.size < 2 && (move == TriageMove.NEXT || move == TriageMove.PREVIOUS))) {
            haptics.tick()
            deckState.fling(scope, move, card, motion) {
                when (move) {
                    TriageMove.SWEEP -> {
                        onIntent(Intent.TriageSweep(card.messageId))
                        ledger.withUndo("Archived · ${card.headline}") { onIntent(Intent.TriageUndo(listOf(card.messageId), swept = true)) }
                    }
                    TriageMove.KEEP -> {
                        onIntent(Intent.TriageGotIt(card.allIds))
                        ledger.withUndo("Marked read · ${card.headline}") { onIntent(Intent.TriageUndo(card.allIds, swept = false)) }
                    }
                    TriageMove.NEXT -> index = TriageGestures.next(i, cards.size)
                    TriageMove.PREVIOUS -> index = TriageGestures.previous(i, cards.size)
                    TriageMove.NONE -> Unit
                }
            }
        } else if (move != TriageMove.NONE) deckState.springBack(scope, motion)
    }
    val items = buildList {
        // The review count is the strongest item, and not repeated when the review card is the body.
        if (reviewCount > 0 && cards.isNotEmpty()) add(FooterItem("$reviewCount to review", "$reviewCount to review, open the review", true, onOpenReview, reviewAnchor))
        if (deck.quietCount > 0) add(FooterItem("Quiet ${deck.quietCount}", "Quiet: ${deck.quietCount} waiting for later", false, { onOpen(true, null) }))
        if (deck.autoCleared.isNotEmpty()) add(FooterItem("Auto-cleared ${deck.autoCleared.size}", "Auto cleared: ${deck.autoCleared.size} handled for you", false, onOpenAutoCleared))
        add(FooterItem("All stacks", "Open all stacks", false, { onOpen(false, null) }))
    }
    val divider = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)

    HomeCard(modifier.fillMaxWidth().enterOnce(gate, firstSlot), horizontal = 0.dp, vertical = 0.dp) {
        // HEADER: the label, what is left, and the settings gear.
        Row(Modifier.fillMaxWidth().padding(start = Spacing.xl, end = Spacing.sm, top = Spacing.xs, bottom = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(vertical = Spacing.sm)) {
                HomeLabel("Your deck")
                val big = if (cards.isNotEmpty()) deck.toGo else reviewCount
                if (big > 0) {
                    val count = rememberCountUpInt(big, fromZero = intro)
                    val unit = if (cards.isNotEmpty()) "to go" else "to review"
                    Text(
                        "$count $unit", Modifier.padding(top = Spacing.xs).semantics { contentDescription = "$big $unit" },
                        style = TypeRole.title, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (front != null && cards.size > 1) Caption(TriageGestures.counter(i, cards.size), Modifier.padding(end = Spacing.xs))
            Box(
                Modifier.size(48.dp).tappable(onClick = onSettings, onClickLabel = "Make it yours: order and modes of the stacks"),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(R.drawable.baseline_settings_24), contentDescription = "Deck settings",
                    modifier = Modifier.size(20.dp), colorFilter = ColorFilter.tint(mutedColor())
                )
            }
        }
        DeckDivider(divider)
        // BODY: the stack (clipped inside the card), the review card, or the caught-up line.
        Box(Modifier.fillMaxWidth().clipToBounds().padding(horizontal = Spacing.xl, vertical = Spacing.md)) {
            when {
                front != null -> Column(Modifier.fillMaxWidth()) {
                    TriageDeck(
                        front, cards.size, deckState, act,
                        Modifier.fillMaxWidth().padding(top = DECK_PEEK * 2).height(INLINE_CARD_HEIGHT)
                    ) { card, m, isFront ->
                        CardFace(
                            card, messages[card.messageId], i, cards.size, if (isFront) deckState else null, m, act,
                            onCategory = {}, onOpen = { onIntent(Intent.PeekMessage(card.messageId)) }, compact = true
                        )
                    }
                    // Where you are, and what a swipe does, said once and quietly.
                    Column(Modifier.fillMaxWidth().padding(top = Spacing.md), horizontalAlignment = Alignment.CenterHorizontally) {
                        if (cards.size > 1) DeckDots(cards.size, i, active = MaterialTheme.colorScheme.primary)
                        Caption(
                            "Swipe left to archive, right to keep", Modifier.padding(top = Spacing.xs).clearAndSetSemantics { },
                            maxLines = 2, textAlign = TextAlign.Center
                        )
                    }
                }
                reviewCount > 0 -> ReviewCard(reviewCount, onOpenReview, reviewAnchor)
                else -> CaughtUpBody()
            }
        }
        DeckDivider(divider)
        // FOOTER: equal tappable segments split by hairlines.
        DeckFooter(items, divider)
    }
}

@Composable
private fun DeckDivider(color: Color) {
    Box(Modifier.fillMaxWidth().height(1.dp).background(color))
}

/** The footer: segments of equal width in rows of two at most (one per row when [homeStacked] or when two would be narrower than [FOOTER_MIN_SEGMENT]), hairlines between. */
@Composable
private fun DeckFooter(items: List<FooterItem>, divider: Color) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val perRow = if (homeStacked() || maxWidth < FOOTER_MIN_SEGMENT * 2) 1 else 2
        Column(Modifier.fillMaxWidth()) {
            items.chunked(perRow).forEachIndexed { r, row ->
                if (r > 0) DeckDivider(divider)
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                    row.forEachIndexed { c, item ->
                        if (c > 0) Box(Modifier.fillMaxHeight().width(1.dp).background(divider))
                        FooterSegment(item, Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/** One footer segment: centred text on a 48 dp target; the strong one carries the accent. */
@Composable
private fun FooterSegment(item: FooterItem, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier.then(item.anchor).heightIn(min = 48.dp).tappable(onClick = item.onClick, onClickLabel = "Open")
            .semantics(mergeDescendants = true) { contentDescription = item.spoken }.padding(horizontal = Spacing.md, vertical = Spacing.sm),
        contentAlignment = Alignment.Center
    ) {
        Text(
            item.label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
            color = if (item.strong) scheme.primary else scheme.onSurface, maxLines = scaledLines(1), overflow = TextOverflow.Ellipsis
        )
    }
}

/** Nothing is new and nothing needs review: a compact body inside the same card. */
@Composable
private fun CaughtUpBody() {
    Column(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics(mergeDescendants = true) { contentDescription = "All caught up. Cleared cards stay in Messages." },
        verticalArrangement = Arrangement.Center
    ) {
        Text("All caught up", style = TypeRole.body, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Caption("Cleared cards stay in Messages.", maxLines = 2)
    }
}

/**
 * Nothing is new but the ledger has questions: the review as one card in the deck's own shape (a seal, a title, a short summary, a quiet
 * footer). The whole card opens the review page. It is not swipeable: there is nothing to archive, only to answer.
 */
@Composable
private fun ReviewCard(count: Int, onOpen: () -> Unit, anchor: Modifier) {
    val scheme = MaterialTheme.colorScheme
    val label = DisplayFormatters.count(count, "thing", "things") + " to review"
    val face = lerp(scheme.surface, scheme.onSurface, 0.03f)
    val shape = AppShapes.card
    Column(
        anchor.fillMaxWidth().clip(shape).background(face).border(1.dp, scheme.onSurface.copy(alpha = 0.10f), shape)
            .tappable(onClick = onOpen, onClickLabel = "Open review")
            .semantics(mergeDescendants = true) { contentDescription = "$label. Unclear messages, possible duplicates and overdue bills." }
            .padding(Spacing.lg)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).background(scheme.primary.copy(alpha = 0.14f), CircleShape).clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
                Text("?", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = scheme.primary)
            }
            Column(Modifier.weight(1f).padding(start = Spacing.md).clearAndSetSemantics { }) {
                Text("Needs a look", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Caption("From your payments")
            }
        }
        Text(
            label, Modifier.padding(top = Spacing.lg), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
            color = scheme.primary, maxLines = 2, overflow = TextOverflow.Ellipsis
        )
        Text(
            "Unclear messages, possible duplicates and overdue bills.", Modifier.padding(top = Spacing.sm), style = TypeRole.body,
            color = scheme.onSurface.copy(alpha = 0.86f), maxLines = 3, overflow = TextOverflow.Ellipsis
        )
        Box(Modifier.padding(top = Spacing.md).fillMaxWidth().height(1.dp).background(scheme.onSurface.copy(alpha = 0.08f)))
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Caption("Checked on this phone", Modifier.weight(1f), maxLines = 2)
            Text("Open review", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = scheme.primary, maxLines = 1)
        }
    }
}

/** "N things to review" as one quiet row, for when the deck is switched off; the whole row opens the review page. */
@Composable
private fun ReviewRow(count: Int, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val label = DisplayFormatters.count(count, "thing", "things") + " to review"
    HomeCard(
        modifier.semantics(mergeDescendants = true) { contentDescription = label },
        onClick = onOpen, onClickLabel = "Open review", vertical = Spacing.xs
    ) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), style = TypeRole.body, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Image(
                painter = painterResource(R.drawable.baseline_chevron_right_24), contentDescription = null, modifier = Modifier.size(24.dp),
                colorFilter = ColorFilter.tint(mutedColor())
            )
        }
    }
}

/**
 * "Auto cleared": what was cleared without a look, newest first, each with a Restore button of the same width. Only expired codes and
 * offers (when the person chose to clear them) ever land here. Restoring brings the card back under Quiet; an Undo follows. Nothing
 * here was deleted: every one of these is still in Messages.
 */
@Composable
internal fun AutoClearedPage(
    cards: List<TriageCard>,
    onRestore: (TriageCard) -> Unit,
    onOpen: (TriageCard) -> Unit,
    onBack: () -> Unit
) {
    AppPage("Auto cleared", onBack) {
        Caption("Expired codes and offers you chose to clear. They are still in Messages.", Modifier.padding(bottom = Spacing.md), maxLines = 3)
        if (cards.isEmpty()) {
            EmptyState("Nothing cleared", "Expired codes and cleared offers land here.", icon = R.drawable.baseline_archive_24)
        }
        cards.forEach { card ->
            androidx.compose.runtime.key(card.messageId) {
                val spoken = privateSpeech("${card.headline}. ${card.title}")
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(vertical = Spacing.xs),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        Modifier.weight(1f).heightIn(min = 48.dp).tappable(onClick = { onOpen(card) }, onClickLabel = "Open message")
                            .semantics(mergeDescendants = true) { contentDescription = spoken }.padding(vertical = Spacing.xs),
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(card.headline, style = TypeRole.body, color = MaterialTheme.colorScheme.primary, maxLines = scaledLines(2), overflow = TextOverflow.Ellipsis)
                        Caption("${stackTitle(card.stackId)} · ${DisplayFormatters.listStamp(card.dateMillis)}")
                    }
                    Spacer(Modifier.size(Spacing.md))
                    AppButton("Restore", { onRestore(card) }, Modifier.widthIn(min = 96.dp), style = ButtonStyle.OUTLINE, size = ButtonSize.COMPACT)
                }
            }
        }
    }
}

internal fun stackTitle(stackId: String): String = com.zyke.message.domain.triage.TriageStackSpec.byId(stackId)?.title ?: "Everything else"
