package com.zyke.message.domain.privacy

/**
 * How long hidden amounts stay visible after they are asked for. Pure and clock-injected: [clock] is any monotonic
 * millisecond reading (the app passes elapsed-realtime), so tests move time by hand.
 */
class RevealTimer(
    private val clock: () -> Long,
    private val authenticatedWindowMillis: Long = AUTHENTICATED_MILLIS,
    private val tapWindowMillis: Long = TAP_MILLIS
) {
    /** The clock reading until which amounts are visible; 0 when hidden. */
    var revealedUntil: Long = 0L
        private set

    /** Opens a window: the long one after a successful screen-lock check, the short one for a plain tap. Returns its end. */
    fun grant(authenticated: Boolean): Long {
        revealedUntil = clock() + if (authenticated) authenticatedWindowMillis else tapWindowMillis
        return revealedUntil
    }

    fun hide() { revealedUntil = 0L }

    fun isRevealed(): Boolean = remainingMillis() > 0L

    fun remainingMillis(): Long = (revealedUntil - clock()).coerceAtLeast(0L)

    companion object {
        const val AUTHENTICATED_MILLIS = 60_000L
        const val TAP_MILLIS = 5_000L

        /** "0:45" for 44.2 seconds left (rounded up, so the label never shows 0:00 while still visible). */
        fun label(remainingMillis: Long): String {
            val seconds = ((remainingMillis.coerceAtLeast(0L) + 999L) / 1000L).toInt()
            return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
        }
    }
}
