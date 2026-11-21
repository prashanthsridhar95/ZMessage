package com.zyke.message.domain.triage

/** How a card or tag reads: colour is never the only signal, the words say it too. */
enum class TriageTone { NEUTRAL, GOOD, WARNING, DANGER }

/** A small chip on a card: a due date, an instrument, "Overdue", "Refund processed", "Matched to bill". */
data class TriageTag(val text: String, val tone: TriageTone = TriageTone.NEUTRAL)

/**
 * One card of a stack. Everything is read from what the classifier already found; nothing here is parsed again.
 *
 * [messageId] is the anchor of a payment group (the lead message; its repeats and followers are not cards of their own and are
 * listed in [followerIds] so "Got it" reads them too). [headline] is the bold one-liner, [summary] one or two short lines
 * separated by a line break, built from structured fields (for a plain message, the first 120 characters of its text).
 */
data class TriageCard(
    val messageId: Long,
    val stackId: String,
    val title: String,
    /** The sender's header had the shape of a registered one (see SenderDisplay); never claimed for a payee. */
    val verified: Boolean,
    val dateMillis: Long,
    val headline: String,
    val summary: String,
    val tags: List<TriageTag>,
    val tone: TriageTone,
    val followerIds: List<Long> = emptyList()
) {
    /** Every message this card stands for: the anchor and what it holds. */
    val allIds: List<Long> get() = listOf(messageId) + followerIds
}

/**
 * A stack of cards with its effective [mode]. [items] are the cards still to look at (newest first): for a KEEP stack they count
 * in "to go"; for a QUIET stack, or an AUTO_CLEAR stack, they wait under Quiet (an AUTO_CLEAR stack holds only what the person
 * restored; the rest is in [autoCleared]).
 */
data class TriageStack(
    val id: String,
    val title: String,
    val mode: TriageMode,
    val items: List<TriageCard>,
    val autoCleared: List<TriageCard> = emptyList()
) {
    val newCount: Int get() = items.size

    /** The one line a tile shows: the newest card's headline. */
    val preview: String get() = items.firstOrNull()?.headline.orEmpty()

    /** The cards wait under Quiet rather than counting. */
    val isQuiet: Boolean get() = mode != TriageMode.KEEP
}

/** What Home's deck shows. */
data class TriageDeck(
    val stacks: List<TriageStack>,
    /** Unread, unarchived cards in KEEP stacks. */
    val toGo: Int,
    /** Cards waiting in QUIET stacks (and the ones restored from Auto cleared). */
    val quietCount: Int,
    /** Cleared silently, newest first; each can be restored. */
    val autoCleared: List<TriageCard>,
    /** Messages of the deck's kinds, within the last 30 days, that were read or archived. */
    val readCount: Int
) {
    /** The stacks that have something to go through, in the person's order: the tiles on Home. */
    val tiles: List<TriageStack> get() = stacks.filter { it.mode == TriageMode.KEEP && it.items.isNotEmpty() }

    val quietStacks: List<TriageStack> get() = stacks.filter { it.isQuiet && it.items.isNotEmpty() }

    /** The cards of the Fresh (KEEP) or Quiet side, optionally of one stack. */
    fun cards(quiet: Boolean, stackId: String? = null): List<TriageCard> =
        stacks.filter { it.isQuiet == quiet && (stackId == null || it.id == stackId) }.flatMap { it.items }

    fun stackOf(id: String): TriageStack? = stacks.firstOrNull { it.id == id }

    /** Every message id a card on this deck stands for (what is shown and what is waiting), for settling an optimistic hide. */
    val shownIds: Set<Long> by lazy(LazyThreadSafetyMode.PUBLICATION) { stacks.flatMapTo(HashSet()) { s -> s.items.flatMap { it.allIds } } }

    /**
     * The deck with the cards of [hidden] message ids taken off at once, before the saved marks have come back through the pipeline
     * (the same optimistic step as MessageActions). Counts follow; what was taken off is counted as read.
     */
    fun without(hidden: Set<Long>): TriageDeck {
        if (hidden.isEmpty()) return this
        var removed = 0
        var toGo = 0
        var quiet = 0
        val next = stacks.map { s ->
            val kept = s.items.filter { it.messageId !in hidden }
            removed += s.items.size - kept.size
            if (s.mode == TriageMode.KEEP) toGo += kept.size else quiet += kept.size
            if (kept.size == s.items.size) s else s.copy(items = kept)
        }
        return if (removed == 0) this else copy(stacks = next, toGo = toGo, quietCount = quiet, readCount = readCount + removed)
    }

    companion object {
        val EMPTY = TriageDeck(emptyList(), 0, 0, emptyList(), 0)
    }
}

/** Where a gesture on the front card leads. */
enum class TriageMove { NONE, SWEEP, KEEP, PREVIOUS, NEXT }

/** The deck's gesture and cursor rules, pure so they are tested without a screen. */
object TriageGestures {
    /** A sideways drag past this share of the card's width commits (the same third the Accounts deck uses). */
    const val COMMIT_FRACTION = 0.30f

    /** A vertical drag past this share of the card's height moves to another card. */
    const val MOVE_FRACTION = 0.20f

    /** A flick faster than this (px per second) in the direction of the drag commits whatever the distance. */
    const val FLICK_PX_PER_S = 1_200f

    /**
     * What letting go does. A drag counts as sideways or vertical by its larger part. Left sweeps (archives), right keeps (marks read);
     * dragging down moves to the next card and up to the previous one. With fewer than two cards there is nowhere to move.
     */
    fun decide(dx: Float, dy: Float, widthPx: Float, heightPx: Float, vx: Float, vy: Float, count: Int): TriageMove {
        if (count <= 0 || dx.isNaN() || dy.isNaN()) return TriageMove.NONE
        val w = widthPx.coerceAtLeast(1f)
        val h = heightPx.coerceAtLeast(1f)
        if (Math.abs(dx) / w >= Math.abs(dy) / h) {
            val flicked = Math.abs(vx) > FLICK_PX_PER_S && (vx < 0f) == (dx < 0f)
            if (dx == 0f || !(Math.abs(dx) > w * COMMIT_FRACTION || flicked)) return TriageMove.NONE
            return if (dx < 0f) TriageMove.SWEEP else TriageMove.KEEP
        }
        if (count < 2 || dy == 0f) return TriageMove.NONE
        val flicked = Math.abs(vy) > FLICK_PX_PER_S && (vy < 0f) == (dy < 0f)
        if (!(Math.abs(dy) > h * MOVE_FRACTION || flicked)) return TriageMove.NONE
        return if (dy > 0f) TriageMove.NEXT else TriageMove.PREVIOUS
    }

    /** The card after [index], wrapping from the last to the first. */
    fun next(index: Int, count: Int): Int = if (count <= 0) 0 else (index + 1).mod(count)

    /** The card before [index], wrapping from the first to the last. */
    fun previous(index: Int, count: Int): Int = if (count <= 0) 0 else (index - 1).mod(count)

    /** Where the front card is after the one at [index] was acted on and left: the next card slides into the same place. */
    fun afterRemoval(index: Int, remaining: Int): Int = if (remaining <= 0) 0 else index.coerceIn(0, remaining - 1)

    /** "3 of 8"; "" when there is nothing. */
    fun counter(index: Int, count: Int): String = if (count <= 0) "" else "${index.coerceIn(0, count - 1) + 1} of $count"
}
