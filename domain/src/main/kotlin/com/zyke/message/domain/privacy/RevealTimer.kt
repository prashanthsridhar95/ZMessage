package com.zyke.message.domain.privacy

/**
 * How long hidden amounts stay visible after they are asked for. Pure and clock-injected: [clock] is any monotonic
 * millisecond reading (the app passes elapsed-realtime), so tests move time by hand.
 */
class RevealTimer(
    private val clock: () -> Long,
    /** How long a reveal after the screen-lock check lasts; null means until it is hidden by hand or on leaving. */
    private val authenticatedWindowMillis: Long? = AUTHENTICATED_MILLIS,
    private val tapWindowMillis: Long = TAP_MILLIS
) {
    /** The clock reading until which amounts are visible; 0 when hidden. */
    var revealedUntil: Long = 0L
        private set

    /** Opens a window: the long one after a successful screen-lock check, the short one for a plain tap. Returns its end. */
    fun grant(authenticated: Boolean): Long =
        if (authenticated) grantFor(authenticatedWindowMillis) else grantFor(tapWindowMillis)

    /** Opens a window of [windowMillis]; null means one with no end of its own (closed by [hide]). Returns its end. */
    fun grantFor(windowMillis: Long?): Long {
        revealedUntil = if (windowMillis == null) NO_END else clock() + windowMillis
        return revealedUntil
    }

    fun hide() { revealedUntil = 0L }

    fun isRevealed(): Boolean = remainingMillis() > 0L

    fun remainingMillis(): Long = if (revealedUntil == NO_END) NO_END else (revealedUntil - clock()).coerceAtLeast(0L)

    /** True when the open window has no end of its own. */
    val isOpenEnded: Boolean get() = revealedUntil == NO_END

    companion object {
        const val AUTHENTICATED_MILLIS = 60_000L
        const val TAP_MILLIS = 5_000L

        /** [revealedUntil] of a window that lasts until it is closed by hand. */
        const val NO_END = Long.MAX_VALUE

        /** "0:45" for 44.2 seconds left (rounded up, so the label never shows 0:00 while still visible). */
        fun label(remainingMillis: Long): String {
            if (remainingMillis == NO_END) return "on"

            val seconds = ((remainingMillis.coerceAtLeast(0L) + 999L) / 1000L).toInt()
            return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
        }
    }
}
