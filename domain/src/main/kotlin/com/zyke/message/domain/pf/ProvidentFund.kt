package com.zyke.message.domain.pf

import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageContent
import com.zyke.message.domain.model.NoticeKind
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** One passbook message: what went in this time, and the balance when it says so. */
data class PfUpdate(val date: LocalDate, val added: BigDecimal?, val balance: BigDecimal?, val messageId: Long)

/** The provident fund as the passbook messages describe it, newest first. */
data class ProvidentFund(
    /** The latest balance any update stated; null when none has. */
    val balance: BigDecimal?,
    val balanceAsOf: LocalDate?,
    val updates: List<PfUpdate>
) {
    val latest: PfUpdate get() = updates.first()
}

object ProvidentFunds {
    private const val AMOUNT = "(?:INR\\.?|Rs\\.?|\u20b9)\\s*([\\d,]+(?:\\.\\d+)?)"
    private val BALANCE = Regex("(?i)(?:balance|corpus)\\b[^\u20b9]{0,60}?\\bis\\s*$AMOUNT|(?:total\\s+)?(?:balance|corpus)\\b[^\\d\u20b9]{0,40}?$AMOUNT")
    private val ADDED = Regex("(?i)(?:updated with|contribution of|credited|deposit(?:ed)? of|amount of)\\b[^\\d\u20b9]{0,25}?$AMOUNT")
    private val ANY = Regex("(?i)$AMOUNT")

    fun find(messages: List<ClassifiedMessage>, zone: ZoneId): ProvidentFund? {
        val updates = messages.mapNotNull { message ->
            val notice = message.content as? MessageContent.Notice ?: return@mapNotNull null
            if (notice.kind != NoticeKind.PF_UPDATE) return@mapNotNull null
            val body = message.message.body.replace(Regex("\\s+"), " ")
            val balance = number(BALANCE, body)
            val added = number(ADDED, body)
                // A message with one amount and no balance word is the amount the passbook was updated with.
                ?: if (balance == null) number(ANY, body) else null
            if (balance == null && added == null) return@mapNotNull null
            PfUpdate(Instant.ofEpochMilli(message.message.date).atZone(zone).toLocalDate(), added, balance, message.id)
        }.sortedWith(compareByDescending<PfUpdate> { it.date }.thenByDescending { it.messageId })

        if (updates.isEmpty()) return null
        val stated = updates.firstOrNull { it.balance != null }
        return ProvidentFund(stated?.balance, stated?.date, updates)
    }

    private fun number(regex: Regex, text: String): BigDecimal? =
        regex.find(text)?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }?.replace(",", "")?.trimEnd('.')?.toBigDecimalOrNull()
}
