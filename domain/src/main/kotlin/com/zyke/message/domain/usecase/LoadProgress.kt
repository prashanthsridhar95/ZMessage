package com.zyke.message.domain.usecase

/** How far the inbox has been read: the newest month first, then back month by month. */
data class LoadProgress(
    val monthsLoaded: Int = 0,
    val monthsTotal: Int = 0,
    val messagesLoaded: Int = 0,
    val messagesTotal: Int = 0,
    /** Start of the oldest month read so far, in epoch millis; null before the first one. */
    val oldestLoaded: Long? = null,
    val isDone: Boolean = true
) {
    /** Older months are still being read. */
    val isWorking: Boolean get() = !isDone
}
