package com.zyke.message.domain.backup

/**
 * What protects an automatic backup: a 6-digit PIN (easy to remember, weak against a determined attacker) or,
 * as the advanced option, a passphrase of at least [BackupCrypto.MIN_PASSPHRASE_LENGTH] characters.
 *
 * Honest limit, shown to the person: a 6-digit PIN has only 1,000,000 possibilities. The key is stretched with
 * PBKDF2 (600,000 rounds), which makes every guess slow, but someone who gets hold of the backup file could still
 * try all of them offline on fast hardware. It keeps the file private from casual access; a longer passphrase is
 * far stronger.
 */
object BackupSecret {
    const val PIN_LENGTH = 6

    enum class Kind { PIN, PASSPHRASE }

    /** What [secret] is, or null when it is neither a 6-digit PIN nor a long-enough passphrase. */
    fun kindOf(secret: CharArray): Kind? = when {
        secret.size == PIN_LENGTH && secret.all { it in '0'..'9' } -> Kind.PIN
        secret.size >= BackupCrypto.MIN_PASSPHRASE_LENGTH -> Kind.PASSPHRASE
        else -> null
    }

    /** A PIN anyone would try first: one digit repeated, or counting up or down. Allowed, but the person is warned. */
    fun isObviousPin(pin: CharArray): Boolean {
        if (kindOf(pin) != Kind.PIN) return false
        if (pin.all { it == pin[0] }) return true
        val steps = (1 until pin.size).map { pin[it] - pin[it - 1] }
        return steps.all { it == 1 } || steps.all { it == -1 }
    }
}
