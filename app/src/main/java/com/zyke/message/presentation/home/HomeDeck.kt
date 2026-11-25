package com.zyke.message.presentation.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zyke.message.R
import com.zyke.message.domain.triage.TriageCard
import com.zyke.message.domain.triage.TriageDeck
import com.zyke.message.domain.triage.TriageStack
import com.zyke.message.presentation.common.AppButton
import com.zyke.message.presentation.common.ButtonSize
import com.zyke.message.presentation.common.ButtonStyle
import com.zyke.message.presentation.common.EmptyState
import com.zyke.message.presentation.common.LinkButton
import com.zyke.message.presentation.common.LocalResponsive
import com.zyke.message.presentation.common.AppPage
import com.zyke.message.presentation.common.Text
import com.zyke.message.presentation.common.heading
import com.zyke.message.presentation.common.privateSpeech
import com.zyke.message.presentation.common.scaledLines
import com.zyke.message.presentation.common.tappable
import com.zyke.message.presentation.format.DisplayFormatters
import com.zyke.message.presentation.messages.components.Caption
import com.zyke.message.presentation.messages.components.RowTitle
import com.zyke.message.presentation.messages.components.mutedColor
import com.zyke.message.domain.tour.TourAnchor
import com.zyke.message.presentation.tour.tourAnchor
import com.zyke.message.presentation.motion.EnterGate
import com.zyke.message.presentation.motion.enterOnce
import com.zyke.message.presentation.motion.rememberCountUpInt
import com.zyke.message.presentation.theme.AppShapes
import com.zyke.message.presentation.theme.Radius
import com.zyke.message.presentation.theme.Spacing
import com.zyke.message.presentation.theme.Tonal
import com.zyke.message.presentation.theme.TypeRole
import com.zyke.message.presentation.theme.StackTone
import com.zyke.message.presentation.theme.stackTone
import com.zyke.message.presentation.theme.tonalFill

/** How many tiles Home shows: the first four stacks that have something new, in the person's order. The triage page has them all. */
private const val MAX_TILES = 4

/** The least height of a tile; it grows with large text, and the two tiles of a row are always the same height. */
private val TILE_MIN_HEIGHT = 112.dp

/** How many first-showing slots [DeckSection] uses: its header and one per row of tiles (see enterOnce). */
internal fun deckSlots(deck: TriageDeck): Int = 1 + (deck.tiles.take(MAX_TILES).size + 1) / 2

/**
 * "Your deck", compact: a header, "N to go" with "Swipe all", at most four tiles (two to a row, equal size, each its own tonal
 * colour), and one quiet line for what waits (Quiet) and what was cleared for the person (Auto cleared), each opening its page. When
 * something needs review a single quiet row says so under it; that row stays even when the deck itself is switched off in Customize Home
 * ([showDeck] false), because the review page opens from nowhere else. The cards themselves are swiped in [TriageScreen]; nothing here
 * changes a message.
 *
 * Entrance: the header and each row of tiles rise in once, in turn (the Home screen's gate); the number counts up from zero on that
 * first showing only and afterwards counts to its new value. Nothing loops.
 */
@Composable
internal fun DeckSection(
    deck: TriageDeck,
    gate: EnterGate,
    firstSlot: Int,
    intro: Boolean,
    showDeck: Boolean,
    reviewCount: Int,
    onOpen: (quiet: Boolean, stackId: String?) -> Unit,
    onOpenAutoCleared: () -> Unit,
    onOpenReview: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val responsive = LocalResponsive.current
    val stacked = responsive.stackRows || responsive.fontScale >= 1.5f
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        if (showDeck) {
            Column(Modifier.enterOnce(gate, firstSlot)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        HomeLabel("Your deck")
                        if (deck.toGo > 0) {
                            val count = rememberCountUpInt(deck.toGo, fromZero = intro)
                            Text(
                                "$count to go", Modifier.padding(top = Spacing.xs).semantics { contentDescription = "${deck.toGo} to go" },
                                style = TypeRole.title, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        } else {
                            Caption("All caught up. Cleared cards stay in Messages.", Modifier.padding(top = Spacing.xs), maxLines = 2)
                        }
                    }
                    if (!stacked && deck.toGo > 0) LinkButton("Swipe all", { onOpen(false, null) })
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
                if (stacked && deck.toGo > 0) LinkButton("Swipe all", { onOpen(false, null) })
            }
            val shown = deck.tiles.take(MAX_TILES)
            shown.chunked(2).forEachIndexed { row, pair ->
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max).enterOnce(gate, firstSlot + 1 + row), horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    pair.forEach { stack -> StackTile(stack, Modifier.weight(1f).fillMaxHeight()) { onOpen(false, stack.id) } }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            if (deck.tiles.size > MAX_TILES) {
                LinkButton("All stacks", { onOpen(false, null) })
            }
            if (deck.quietCount > 0 || deck.autoCleared.isNotEmpty()) {
                val quiet: @Composable () -> Unit = {
                    if (deck.quietCount > 0) QuietLink("Quiet ${deck.quietCount}", "Quiet: ${deck.quietCount} waiting for later") { onOpen(true, null) }
                }
                val cleared: @Composable () -> Unit = {
                    if (deck.autoCleared.isNotEmpty()) QuietLink("Auto cleared ${deck.autoCleared.size}", "Auto cleared: ${deck.autoCleared.size} handled for you", onOpenAutoCleared)
                }
                if (stacked) {
                    Column { quiet(); cleared() }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        quiet()
                        if (deck.quietCount > 0 && deck.autoCleared.isNotEmpty()) {
                            Text("\u00B7", Modifier.padding(horizontal = Spacing.sm), style = TypeRole.caption, color = mutedColor())
                        }
                        cleared()
                    }
                }
            }
        }
        if (reviewCount > 0) {
            ReviewRow(reviewCount, onOpenReview)
        }
    }
}

/** One part of the quiet footer line under the deck: muted text on a 48 dp target. */
@Composable
private fun QuietLink(text: String, spoken: String, onClick: () -> Unit) {
    Box(
        Modifier.heightIn(min = 48.dp).tappable(onClick = onClick, onClickLabel = "Open")
            .semantics(mergeDescendants = true) { contentDescription = spoken },
        contentAlignment = Alignment.CenterStart
    ) {
        Text(text, style = TypeRole.caption, color = mutedColor(), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** "N things to review": one quiet row; the whole row opens the review page. */
@Composable
private fun ReviewRow(count: Int, onOpen: () -> Unit) {
    val label = DisplayFormatters.count(count, "thing", "things") + " to review"
    HomeCard(
        Modifier.semantics(mergeDescendants = true) { contentDescription = label }.tourAnchor(TourAnchor.HOME_REVIEW),
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
 * One stack: its mode, how many are new, its name and the newest card's headline on one line. Quiet and flat: no slab above it, a 16 dp inset, the name in `titleMedium`. The tile dips while held, and speaks as one button.
 */
@Composable
private fun StackTile(stack: TriageStack, modifier: Modifier, onClick: () -> Unit) {
    val tone = stackTone(stack.id)
    val spoken = privateSpeech("${stack.title}, ${stack.mode.label}, ${stack.newCount} new. ${stack.preview}")
    Box(
        modifier
            .tappable(onClick = onClick, onClickLabel = "Open ${stack.title}")
            .semantics(mergeDescendants = true) { contentDescription = spoken }
    ) {
        Column(
            Modifier.fillMaxWidth().fillMaxHeight().heightIn(min = TILE_MIN_HEIGHT)
                .clip(AppShapes.card).background(tone.container).padding(Spacing.lg)
        ) {
            ModeTag("${stack.newCount} new", tone, Modifier.align(Alignment.Start))
            Spacer(Modifier.weight(1f).heightIn(min = Spacing.lg))
            Text(
                stack.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = tone.ink,
                maxLines = scaledLines(2), overflow = TextOverflow.Ellipsis
            )
            Text(
                stack.preview, Modifier.padding(top = Spacing.xs), style = TypeRole.caption, color = tone.ink.copy(alpha = 0.8f),
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** The small chip with a stack's mode ("Keep", "Quiet"), on its own tone. */
@Composable
internal fun ModeTag(label: String, tone: StackTone, modifier: Modifier = Modifier) {
    Text(
        label, modifier.background(tone.ink.copy(alpha = 0.12f), AppShapes.chip).padding(horizontal = Spacing.sm, vertical = Spacing.xs),
        style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = tone.ink, maxLines = 1
    )
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
