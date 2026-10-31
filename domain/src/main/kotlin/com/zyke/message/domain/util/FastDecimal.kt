package com.zyke.message.domain.util

import java.math.BigDecimal

/**
 * Same answer as the standard `String.toBigDecimalOrNull()`, without its pre-check by a large regular expression
 * (which ran for every amount of every message on every rebuild): [BigDecimal] already rejects what it cannot read.
 * Files import this to take precedence over the standard one.
 */
fun String.toBigDecimalOrNull(): BigDecimal? {
    if (isEmpty()) return null
    // The standard one reads ASCII digits only; BigDecimal would also take other scripts' digits.
    for (c in this) if (c.code > 127) return null
    return try {
        BigDecimal(this)
    } catch (_: NumberFormatException) {
        null
    }
}
