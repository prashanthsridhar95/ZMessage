package com.zyke.message.domain.ai

import com.zyke.message.domain.model.BankName
import com.zyke.message.domain.model.BankTransaction
import com.zyke.message.domain.model.BankTransactionType

/**
 * Second chance for money messages the rules do not understand: asks the on-device model for the amount,
 * payee and direction, then checks every answer against the message itself. A model can be fluent and wrong,
 * so nothing it says is trusted unless the text backs it up.
 */
class ModelTransactionParser(private val model: OnDeviceModel) {
    private val cache = HashMap<String, BankTransaction?>()

    val isAvailable: Boolean get() = model.isAvailable

    suspend fun parse(body: String): BankTransaction? {
        if (!model.isAvailable) return null
        if (cache.containsKey(body)) return cache[body]
        val reply = runCatching { model.generate(prompt(body)) }.getOrNull()
        val parsed = reply?.let { validate(it, body) }
        if (cache.size > MAX_CACHE) cache.clear()
        cache[body] = parsed
        return parsed
    }

    internal fun prompt(body: String) = """
        Read this bank or payment SMS. Reply with one JSON object and nothing else:
        {"amount":"<number as written in the message>","direction":"debit" or "credit" or "none","payee":"<who was paid or who paid, as written>"}
        Use direction "none" if no money was paid or received (offers, reminders, balances, OTPs).
        SMS: $body
    """.trimIndent()

    internal fun validate(reply: String, body: String): BankTransaction? {
        val amount = field(reply, "amount")?.replace(",", "")?.trim()?.takeIf { it.matches(NUMBER) } ?: return null
        val direction = field(reply, "direction")?.lowercase()?.trim()
        if (direction != "debit" && direction != "credit") return null
        // The amount must be written in the message, with or without separators or a trailing ".00".
        val digits = body.replace(",", "")
        val plain = amount.removeSuffix(".00").removeSuffix(".0")
        if (!Regex("(?<![\\d.])${Regex.escape(plain)}(?:\\.0+)?(?![\\d])").containsMatchIn(digits)) return null

        val payee = field(reply, "payee")?.trim().orEmpty()
        // The payee must also come from the message, or it is dropped rather than invented.
        val safePayee = if (payee.isNotEmpty() && body.contains(payee, ignoreCase = true) && payee.length <= MAX_PAYEE) payee else ""
        return BankTransaction(
            bankName = BankName.OTHERS,
            accountNumber = "",
            payee = safePayee,
            amount = amount,
            type = if (direction == "credit") BankTransactionType.RECEIVED else BankTransactionType.SENT
        )
    }

    private fun field(json: String, name: String): String? =
        Regex("\"$name\"\\s*:\\s*\"?([^\",}]*)\"?").find(json)?.groupValues?.get(1)

    private companion object {
        val NUMBER = Regex("\\d+(?:\\.\\d{1,2})?")
        const val MAX_CACHE = 2000
        const val MAX_PAYEE = 60
    }
}
