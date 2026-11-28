package com.zyke.message.domain.email

import com.zyke.message.domain.model.BankName

/** How sure the developers are that an address really sends the bank's alerts. */
enum class SenderBasis(val label: String) {
    /** The person who asked for this feature named it (an address seen in their own mailbox). */
    REPORTED("Reported by the app's owner"),

    /** Written from memory of the bank's emails. NOT checked against a real mailbox: if it is wrong, nothing from that bank is read. */
    UNVERIFIED("Unverified, from memory")
}

/** One bank's alert address: the exact address mail must come from, and who it is. */
data class AlertSender(
    /** The whole address, lower case. Matched exactly: a different address, a sub-domain or an address that only contains it never matches. */
    val address: String,
    /** A short id kept with each stored alert. */
    val id: String,
    /** What the message list calls the sender. */
    val name: String,
    val bank: BankName,
    val basis: SenderBasis
)

/**
 * THE LIST. Only mail from these exact addresses is ever searched for, fetched or read; every other mail in the mailbox is never asked
 * for. Edit this one list to add a bank. It is data, not logic: the app's checks (exact address, the provider's own sender verdict,
 * and the mail having to be worded like the bank's own alert before it counts) work the same for every entry.
 *
 * NOT VERIFIED AGAINST REAL MAIL. The HDFC addresses are the ones the owner reported; the others are from memory and may be wrong or
 * out of date (banks change their sending addresses, and some alerts come from a different address per product). A wrong entry
 * reads nothing and does no harm. Statements ("smartstatement", "emailstatements") are left out on purpose: they carry a PDF and no
 * payment, and are not needed. SBI and American Express are not listed because no address was known well enough; send a sample
 * header to add them.
 */
object EmailAlertSenders {
    val all: List<AlertSender> = listOf(
        AlertSender("alerts@hdfcbank.bank.in", "hdfc", "HDFC Bank", BankName.HDFC, SenderBasis.REPORTED),
        AlertSender("alerts@hdfcbank.net", "hdfc", "HDFC Bank", BankName.HDFC, SenderBasis.REPORTED),
        AlertSender("alerts@icicibank.com", "icici", "ICICI Bank", BankName.ICICI, SenderBasis.UNVERIFIED),
        AlertSender("credit_cards@icicibank.com", "icici", "ICICI Bank", BankName.ICICI, SenderBasis.UNVERIFIED),
        AlertSender("alerts@axisbank.com", "axis", "Axis Bank", BankName.AXIS, SenderBasis.UNVERIFIED),
        AlertSender("bankalerts@kotak.com", "kotak", "Kotak Bank", BankName.KOTAK, SenderBasis.UNVERIFIED)
    )

    /** The addresses, for the server's search and the read-only list in Settings. */
    val addresses: List<String> get() = all.map { it.address }

    /** The entry for a From address, by exact match only; null for anything else. */
    fun find(fromAddress: String): AlertSender? {
        val a = fromAddress.trim().lowercase()
        return all.firstOrNull { it.address == a }
    }

    fun isAllowed(fromAddress: String): Boolean = find(fromAddress) != null
}
