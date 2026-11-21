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

/** How many tiles Home shows; the default stacks are eight and two of them start out quiet or cleared, so this is a ceiling, not a cut. */
private const val MAX_TILES = 8

/** The least height of a tile; it grows with large text, and the two tiles of a row are always the same height. */
private val TILE_MIN_HEIGHT = 148.dp

/** How many first-showing slots [DeckSection] uses: its header and one per row of tiles (see enterOnce). */
internal fun deckSlots(deck: TriageDeck): Int = 1 + (deck.tiles.take(MAX_TILES).size + 1) / 2

/**
 * "Your deck": how many cards are left to look at, a button that goes through all of them, a calm line, then the stacks as tiles
 * (two to a row, equal size, each its own tonal colour), then compact rows for what is waiting (Quiet), what was cleared for the
 * person (Auto cleared) and what was read or swept. The cards themselves are swiped in [TriageScreen]; nothing here changes a message.
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
    onOpen: (quiet: Boolean, stackId: String?) -> Unit,
    onOpenAutoCleared: () -> Unit,
    onOpenRead: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val responsive = LocalResponsive.current
    val stacked = responsive.stackRows || responsive.fontScale >= 1.5f
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        Column(Modifier.enterOnce(gate, firstSlot)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "YOUR DECK", Modifier.weight(1f).heading(), style = TypeRole.section, color = mutedColor(),
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Box(
                    Modifier.size(48.dp).tappable(onClick = onSettings, onClickLabel = "Make it yours: order and modes of the stacks"),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(R.drawable.baseline_settings_24), contentDescription = "Deck settings",
                        modifier = Modifier.size(24.dp), colorFilter = ColorFilter.tint(mutedColor())
                    )
                }
            }
            if (deck.toGo > 0) {
                val count = rememberCountUpInt(deck.toGo, fromZero = intro)
                val title: @Composable (Modifier) -> Unit = { m ->
                    Text(
                        "$count to go", m.semantics { contentDescription = "${deck.toGo} to go" }, style = TypeRole.hero,
                        color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
                val swipeAll: @Composable (Modifier) -> Unit = { m ->
                    AppButton("Swipe all →", { onOpen(false, null) }, m, style = ButtonStyle.OUTLINE)
                }
                if (stacked) {
                    title(Modifier.fillMaxWidth())
                    swipeAll(Modifier.fillMaxWidth().padding(top = Spacing.sm))
                } else {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                        title(Modifier.weight(1f))
                        swipeAll(Modifier)
                    }
                }
                Caption("A few things need a look.", Modifier.padding(top = Spacing.xs), maxLines = 2)
            }
        }
        if (deck.toGo == 0) {
            EmptyState(
                "All caught up", "Nothing new needs a look. Cleared cards stay in Messages.",
                Modifier.enterOnce(gate, firstSlot + 1), icon = R.drawable.baseline_check_24
            )
        }
        deck.tiles.take(MAX_TILES).chunked(2).forEachIndexed { row, pair ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max).enterOnce(gate, firstSlot + 1 + row), horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                pair.forEach { stack -> StackTile(stack, Modifier.weight(1f).fillMaxHeight()) { onOpen(false, stack.id) } }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        if ((deck.quietCount > 0 || deck.autoCleared.isNotEmpty() || deck.readCount > 0)) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                if (deck.quietCount > 0) DeckLine("Quiet", "${deck.quietCount} waiting for later", R.drawable.baseline_list_24) { onOpen(true, null) }
                if (deck.autoCleared.isNotEmpty()) DeckLine("Auto cleared", "${deck.autoCleared.size} handled for you", R.drawable.baseline_archive_24, onOpenAutoCleared)
                if (deck.readCount > 0) DeckLine("Read", "${deck.readCount} read or swept", R.drawable.baseline_message_24, onOpenRead)
            }
        }
    }
}

/**
 * One stack: its mode, how many are new, its name and the newest card's headline on one line. A paper edge (an opaque slab of the
 * same tone, a little darker) peeks out above it so it reads as a stack. The tile dips while held, and speaks as one button.
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
        Box(
            Modifier.align(Alignment.TopCenter).padding(horizontal = Spacing.lg).fillMaxWidth().height(Spacing.md)
                .background(tone.edge, RoundedCornerShape(topStart = Radius.chip, topEnd = Radius.chip))
        )
        Column(
            Modifier.padding(top = Spacing.sm).fillMaxWidth().fillMaxHeight().heightIn(min = TILE_MIN_HEIGHT)
                .clip(AppShapes.card).background(tone.container).padding(Spacing.md)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                ModeTag(stack.mode.label, tone)
                Text(
                    "${stack.newCount} new", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                    color = tone.ink, maxLines = 1
                )
            }
            Spacer(Modifier.weight(1f).heightIn(min = Spacing.md))
            Text(
                stack.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = tone.ink,
                maxLines = scaledLines(2), overflow = TextOverflow.Ellipsis
            )
            Text(
                stack.preview, Modifier.padding(top = Spacing.xs), style = TypeRole.caption, color = tone.ink.copy(alpha = 0.85f),
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

/** A compact row under the tiles: an icon tile, a title, one line saying how many, and a chevron; the whole row is one button. */
@Composable
private fun DeckLine(title: String, summary: String, icon: Int, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(AppShapes.control).background(tonalFill(Tonal.Quiet))
            .tappable(onClick = onClick, onClickLabel = "Open $title")
            .semantics(mergeDescendants = true) { contentDescription = "$title, $summary" }
            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(24.dp),
            colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary)
        )
        Column(Modifier.weight(1f).padding(horizontal = Spacing.md)) {
            RowTitle(title)
            Caption(summary)
        }
        Image(
            painter = painterResource(R.drawable.baseline_chevron_right_24), contentDescription = null, modifier = Modifier.size(24.dp),
            colorFilter = ColorFilter.tint(mutedColor())
        )
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
