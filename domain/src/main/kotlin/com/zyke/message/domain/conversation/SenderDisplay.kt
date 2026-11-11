package com.zyke.message.domain.conversation

import com.zyke.message.domain.accounts.SenderBank
import com.zyke.message.domain.classification.MerchantResolver
import com.zyke.message.domain.mms.RbmSender
import com.zyke.message.domain.model.BankName
import java.util.concurrent.ConcurrentHashMap

/**
 * An Indian DLT sender header taken apart: "VM-SPINNY-S" is route "VM", name "SPINNY", category 'S'. The route is the
 * operator circle, the category says what kind of traffic it is (S service-implicit, T transactional, P promotional,
 * G government or explicit). Either may be missing ("HDFCBK-S", "AX-HDFCBK"). The name may hold hyphens ("VK-AMAZON-IN-S").
 */
data class SenderHeader(val route: String?, val name: String, val category: Char?)

/** What a row shows for a sender: the [name] alone, and whether the header was a registered one ([verified], a check badge beside the name). */
data class SenderLabel(val name: String, val verified: Boolean) {
    /** What a screen reader says for the name. */
    val spoken: String get() = if (verified) "$name, verified sender" else name
}

/**
 * How a sender is named on screen. A registered header shows the NAME ALONE ("AX-HDFCBK-T" is "HDFC Bank", "VM-SPINNY-S" is
 * "Spinny", "CP-CARSEW-T" is "Carsew") with a verified mark. A phone number, a short code, a contact's name, or a name the
 * rules do not recognise as a header is shown as it is, unmarked. A name the person gave the sender always wins for the name;
 * the mark follows the header.
 *
 * "Verified" here means only that the header has the shape of a registered sender: a route, a name and (usually) a category. Nothing
 * on the phone can prove who owns it; the shape is what a spoofed number cannot have, since a registered header cannot be a phone
 * number. An RBM agent's own address (the part before @rbm.google.com) is a business Google has verified, so that is marked too; once
 * it has been turned into a plain name by [RbmSender] it can no longer be told from a contact and is not.
 */
object SenderDisplay {
    private const val CATEGORIES = "STPGstpg"
    private const val MAX_NAME = 20

    private val WITH_ROUTE_AND_CATEGORY = Regex("^([A-Za-z]{2})-([A-Za-z0-9]+(?:-[A-Za-z0-9]+)*)-([$CATEGORIES])$")
    private val WITH_CATEGORY = Regex("^([A-Za-z0-9]+(?:-[A-Za-z0-9]+)*)-([$CATEGORIES])$")
    private val WITH_ROUTE = Regex("^([A-Z]{2})-([A-Z0-9]{3,11})$")

    /** Names whose letters stand for words: written in capitals. */
    private val ACRONYMS = RbmSender.acronyms + setOf("sms", "otp", "irctc", "epfo", "gst", "uidai", "trai", "dlf", "tcs", "ola", "ipo", "mf", "amc", "pf", "tv", "cdsl", "nsdl", "ndps")

    /** Header names that are an abbreviation of the name people know. */
    private val SPELLED = mapOf("IRCTCI" to "IRCTC", "EPFOHO" to "EPFO", "UIDAIN" to "UIDAI", "CDSLEV" to "CDSL", "NSDLDP" to "NSDL")

    /** Words after a bank's name that make the header some other company of the group ("KOTAKSEC", "AXISMF"). */
    private val NOT_THE_BANK = listOf("SEC", "MF", "AMC", "INS", "LIFE", "GEN", "CAP", "FIN", "HFL", "HOME", "REAL")

    private val resolver = MerchantResolver()
    private val memo = ConcurrentHashMap<String, SenderLabel>()

    /**
     * The header [raw] is, or null when it is not one: a phone number, a short code, a contact, an address with spaces, a
     * mixed-case name ("Vm-Spinny-S" is how a person types, never how a registered header is written).
     */
    fun parseHeader(raw: String): SenderHeader? {
        val s = raw.trim()
        if (s.length < 4 || s.length > 40 || s.any { it.isWhitespace() || it == '@' || it == '+' } || s.none { it.isLetter() }) return null
        if (s != s.uppercase() && s != s.lowercase()) return null
        WITH_ROUTE_AND_CATEGORY.matchEntire(s)?.let { m ->
            val name = m.groupValues[2]
            if (validName(name)) return SenderHeader(m.groupValues[1].uppercase(), name.uppercase(), m.groupValues[3].uppercase()[0])
        }
        WITH_CATEGORY.matchEntire(s)?.let { m ->
            val name = m.groupValues[1]
            // Without a route, a name of at least three letters in the first word: "HDFCBK-S" is a header, "Al-S" is not.
            if (validName(name) && name.substringBefore('-').length >= 3 && name.count { it.isLetter() } >= 3) {
                return SenderHeader(null, name.uppercase(), m.groupValues[2].uppercase()[0])
            }
        }
        // No category ("AX-HDFCBK"): only the strict capital form, so a contact like "Mo-Ann" is never taken for one.
        if (s == s.uppercase()) {
            WITH_ROUTE.matchEntire(s)?.let { m ->
                val name = m.groupValues[2]
                if (name.count { it.isLetter() } >= 2 && validName(name)) return SenderHeader(m.groupValues[1], name, null)
            }
        }
        return null
    }

    private fun validName(name: String) = name.length in 2..MAX_NAME && name.any { it.isLetter() }

    /** True when [raw] is a registered header or an RBM agent's address. */
    fun isVerified(raw: String): Boolean = RbmSender.isRbm(raw) || parseHeader(raw) != null

    /** The name alone for [raw] ("HDFC Bank"); what a notification says. A plain number or name is returned as it is. */
    fun name(raw: String): String = of(raw).name

    /**
     * The label for [sender]. [nickname] is the name the person gave the sender (it wins for the name); [fallbackName] is a name read
     * from the message itself, used when the header does not name the sender (a phone number that says "HDFC Bank" in its text).
     */
    fun of(sender: String, nickname: String? = null, fallbackName: String? = null): SenderLabel {
        val nick = nickname?.trim().orEmpty()
        val base = baseLabel(sender)
        if (nick.isNotEmpty()) return SenderLabel(nick, base.verified)
        val fallback = fallbackName?.trim().orEmpty()
        if (!base.verified && fallback.isNotEmpty()) return SenderLabel(fallback, false)
        return base
    }

    private fun baseLabel(sender: String): SenderLabel {
        if (memo.size > MEMO_LIMIT) memo.clear()
        return memo.getOrPut(sender) { derive(sender) }
    }

    private fun derive(sender: String): SenderLabel {
        val trimmed = sender.trim()
        if (trimmed.isEmpty()) return SenderLabel(UNKNOWN, false)
        if (RbmSender.isRbm(trimmed)) return SenderLabel(RbmSender.friendly(trimmed), true)
        val header = parseHeader(trimmed) ?: return SenderLabel(trimmed, false)
        return SenderLabel(nameOf(header, trimmed), true)
    }

    private fun nameOf(header: SenderHeader, raw: String): String {
        val compact = header.name.replace("-", "")
        SPELLED[compact]?.let { return it }
        // The bank's own name, unless the header is some other company of its group ("KOTAKSEC").
        val ownBank = NOT_THE_BANK.none { compact.endsWith(it) }
        val bank = if (ownBank) SenderBank.of(raw) else null
        if (bank != null) bankName(bank)?.let { return it }
        // A sender the merchant rules know by name (Swiggy, Amazon, Airtel): their name, as the rest of the app calls them.
        if (ownBank && compact.length <= 12) {
            val spaced = header.name.replace('-', ' ')
            val merchant = resolver.resolve(spaced)
            // Only a rule that names the whole header: "JIO" is Jio, "JIOSAAVN" is not.
            if (merchant.name != spaced && compact.length <= merchant.name.count { it.isLetter() } + 2) return merchant.name
        }
        return titleCase(header.name)
    }

    private fun bankName(bank: BankName): String? = when (bank) {
        BankName.HDFC -> "HDFC Bank"
        BankName.AXIS -> "Axis Bank"
        BankName.FEDERAL_BANK -> "Federal Bank"
        BankName.AMERICAN_EXPRESS -> "American Express"
        BankName.SBI -> "SBI"
        BankName.ICICI -> "ICICI Bank"
        BankName.KOTAK -> "Kotak Bank"
        BankName.IDFC_FIRST -> "IDFC First Bank"
        BankName.YES_BANK -> "Yes Bank"
        BankName.PNB -> "PNB"
        BankName.CANARA -> "Canara Bank"
        BankName.BANK_OF_BARODA -> "Bank of Baroda"
        BankName.INDUSIND -> "IndusInd Bank"
        BankName.INDIAN_BANK -> "Indian Bank"
        BankName.KVB -> "Karur Vysya Bank"
        else -> bank.displayString.takeIf { it.isNotEmpty() }
    }

    /**
     * "CARSEW" is "Carsew", "ZEE5" is "Zee5", "MY_SHOP" and "MyShop" are "My Shop", "AMAZON-IN" is "Amazon IN": words are
     * capitalised, a camel-case join and an underscore or hyphen are word breaks, and one or two capital letters, or a known
     * acronym, stay in capitals.
     */
    fun titleCase(name: String): String {
        val words = name.split('-', '_', ' ').filter { it.isNotEmpty() }.flatMap { splitCamel(it) }
        return words.joinToString(" ") { word ->
            when {
                word.lowercase() in ACRONYMS -> word.uppercase()
                word.all { it.isUpperCase() } && word.length <= 2 -> word
                else -> word.lowercase().replaceFirstChar { it.uppercase() }
            }
        }
    }

    /** "MyShop" to "My", "Shop"; a word in one case, or with digits, is not split. */
    private fun splitCamel(word: String): List<String> {
        if (word.all { !it.isLetter() || it.isUpperCase() } || word.all { !it.isLetter() || it.isLowerCase() }) return listOf(word)
        val out = ArrayList<String>()
        var start = 0
        for (i in 1 until word.length) {
            val lowerToUpper = word[i - 1].isLowerCase() && word[i].isUpperCase()
            val acronymEnd = i + 1 < word.length && word[i - 1].isUpperCase() && word[i].isUpperCase() && word[i + 1].isLowerCase()
            if (lowerToUpper || acronymEnd) {
                out += word.substring(start, i)
                start = i
            }
        }
        out += word.substring(start)
        return out
    }

    private const val UNKNOWN = "Unknown sender"
    private const val MEMO_LIMIT = 4_000
}
