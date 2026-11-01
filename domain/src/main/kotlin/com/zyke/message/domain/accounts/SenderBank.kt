package com.zyke.message.domain.accounts

import com.zyke.message.domain.model.BankName

/**
 * The bank a message's SENDER header belongs to ("AX-HDFCBK-S", "VM-ICICIT", "JD-SBIINB-S"). The header is the only
 * evidence of who wrote a message that cannot be fooled by the text: "credited to HDFC Bank A/c" in an ICICI message
 * names the payee's bank, not the bank the account is with.
 */
object SenderBank {
    private val PREFIX = Regex("^[A-Za-z]{2}-")
    private val SUFFIX = Regex("-[A-Za-z0-9]{1,2}$")

    /** Header fragments, checked in order; the first that the cleaned header contains decides. */
    private val HINTS: List<Pair<String, BankName>> = listOf(
        "HDFC" to BankName.HDFC, "ICICI" to BankName.ICICI, "SBI" to BankName.SBI, "AXIS" to BankName.AXIS,
        "KOTAK" to BankName.KOTAK, "KMBL" to BankName.KOTAK, "IDFC" to BankName.IDFC_FIRST,
        "YESBNK" to BankName.YES_BANK, "YESBK" to BankName.YES_BANK, "YESBAN" to BankName.YES_BANK,
        "INDUS" to BankName.INDUSIND, "FEDBNK" to BankName.FEDERAL_BANK, "FEDERAL" to BankName.FEDERAL_BANK,
        "PNB" to BankName.PNB, "CANBNK" to BankName.CANARA, "CANARA" to BankName.CANARA,
        "BOBTXN" to BankName.BANK_OF_BARODA, "BOBCRD" to BankName.BANK_OF_BARODA, "BARODA" to BankName.BANK_OF_BARODA,
        "INDBNK" to BankName.INDIAN_BANK, "INDIANB" to BankName.INDIAN_BANK,
        "KVB" to BankName.KVB, "KARUR" to BankName.KVB,
        "AMEX" to BankName.AMERICAN_EXPRESS
    )

    private val memo = java.util.concurrent.ConcurrentHashMap<String, Any>()
    private val NONE = Any()

    /** The bank this sender header is, or null when it is not a bank we know (a wallet, a shop, a person). */
    fun of(sender: String): BankName? {
        if (sender.isEmpty()) return null
        val hit = memo.getOrPut(sender) { derive(sender) ?: NONE }
        return hit as? BankName
    }

    private fun derive(sender: String): BankName? {
        val header = sender.trim().uppercase().replace(PREFIX, "").replace(SUFFIX, "")
        if (header.isEmpty() || header.any { it.isDigit() } && header.all { it.isDigit() || it == '+' }) return null
        return HINTS.firstOrNull { (fragment, _) -> header.contains(fragment) }?.second
    }
}
