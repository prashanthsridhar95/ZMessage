package com.zyke.message.domain.feed

/** What a swipe on a message row does, with the words the row, the screen reader and the Undo snackbar use for it. */
enum class SwipeAction(val label: String, val doneMessage: String) {
    MARK_READ("Mark as read", "Marked as read"),
    MARK_UNREAD("Mark as unread", "Marked as unread"),
    ARCHIVE("Archive", "Message archived"),
    RESTORE("Restore", "Message restored")
}

/**
 * The two swipes of one message row: to the right (toward the end) and to the left. Pure, so the decision is tested
 * without a screen.
 *
 * Right is about reading: an unread row is marked read; a row the person marked read is marked unread again, but only when
 * the message is not already read in the phone's own messaging app (that flag is not ours to take back), in which case the
 * row has no right swipe. Left is about the inbox: archive, or restore in the archive.
 *
 * For a payment row that holds its repeats ("+2 related"), the swipe is the anchor message's: archive and restore apply to
 * the anchor only, and "read" is judged for the row as a whole ([isUnread] true when the anchor or any repeat is unread).
 */
data class RowSwipe(val toRight: SwipeAction?, val toLeft: SwipeAction) {

    companion object {
        /** How far across the row a swipe must go before it counts; a shorter one springs back and does nothing. */
        const val COMMIT_FRACTION = 0.70f

        /**
         * @param isUnread the row as a whole is unread (the anchor or one of its repeats)
         * @param readInPhone the anchor message is already read in the phone's messaging app
         */
        fun of(isUnread: Boolean, readInPhone: Boolean, isArchived: Boolean): RowSwipe = RowSwipe(
            toRight = when {
                isUnread -> SwipeAction.MARK_READ
                readInPhone -> null
                else -> SwipeAction.MARK_UNREAD
            },
            toLeft = if (isArchived) SwipeAction.RESTORE else SwipeAction.ARCHIVE
        )

        /** Whether a drag of [fraction] of the row's width is far enough to take effect. NaN and negatives are not. */
        fun committed(fraction: Float): Boolean = !fraction.isNaN() && fraction >= COMMIT_FRACTION

        /** The share of the row's width a drag of [offsetPx] covers, 0 while the row has no width yet. */
        fun fraction(offsetPx: Float, widthPx: Float): Float =
            if (widthPx <= 0f || offsetPx.isNaN()) 0f else (kotlin.math.abs(offsetPx) / widthPx).coerceAtMost(1f)
    }
}
