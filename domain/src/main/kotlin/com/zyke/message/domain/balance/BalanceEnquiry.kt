package com.zyke.message.domain.balance

import com.zyke.message.domain.model.BankName

/** What an enquiry is for: a bank account's balance, or a provident fund's. */
enum class EnquiryKind { BANK_ACCOUNT, PROVIDENT_FUND }

/**
 * One "text this number this word" balance enquiry, as shipped.
 *
 * UNVERIFIED: these presets were copied from an AI-generated list of banks' balance-enquiry numbers. They have not been checked
 * against any bank's website, card or app, and numbers change. The app says so wherever it offers one, and the person must
 * confirm each against their bank before sending. Anything here can be changed per bank in the sheet ("Change").
 */
data class EnquiryPreset(
    /** Stable id of the bank (or "EPFO"): the key the person's changes are saved under. */
    val key: String,
    val bank: BankName?,
    val name: String,
    val number: String,
    /** The words to send; `{last4}` stands for the account's last four digits and `{uan}` for the 12-digit UAN. */
    val template: String,
    val kind: EnquiryKind = EnquiryKind.BANK_ACCOUNT,
    /** Other numbers and wordings the same list gave for this bank; offered under "Change", never sent unless chosen. */
    val otherNumbers: List<String> = emptyList(),
    val otherTexts: List<String> = emptyList(),
    /** Why Zyke will not send this one itself (it needs a secret); null when it can. */
    val blockedReason: String? = null
)

/** What the person typed for a bank that replaces or adds to the presets. */
data class EnquiryEdit(val number: String, val template: String, val name: String = "")

/** The person's changes, by bank key. Part of a backup (it holds no account number and no UAN). */
data class EnquirySetup(val edits: Map<String, EnquiryEdit> = emptyMap())

/** A preset or a custom bank with the person's changes applied. */
data class EnquiryService(
    val key: String,
    val name: String,
    val number: String,
    val template: String,
    val kind: EnquiryKind,
    val isPreset: Boolean,
    val isEdited: Boolean,
    val otherNumbers: List<String>,
    val otherTexts: List<String>,
    val blockedReason: String?
)

/** What can be done for one account or fund right now. */
sealed interface EnquiryPlan {
    /** No preset for this bank and nothing set up: explain, and offer to set it up by hand. */
    data class Unsupported(val bankLabel: String) : EnquiryPlan
    /** A bank whose enquiry needs a secret (an MPIN); Zyke never stores or sends one. */
    data class Blocked(val service: EnquiryService, val reason: String) : EnquiryPlan
    data class NeedsLast4(val service: EnquiryService) : EnquiryPlan
    data class NeedsUan(val service: EnquiryService) : EnquiryPlan
    /** Exactly this number and this text will be sent, after a tap on Send. */
    data class Ready(val service: EnquiryService, val number: String, val text: String) : EnquiryPlan
}

object BalanceEnquiry {
    const val EPFO_KEY = "EPFO"
    /** The one request key for the provident fund (accounts use their own key). */
    const val PF_REQUEST_KEY = "pf"
    const val MAX_TEMPLATE = 60
    const val UAN_LENGTH = 12

    const val NOTE_RATES = "Your bank may charge standard SMS rates."
    const val NOTE_UNVERIFIED = "This number comes from a published list. Check it against your bank before you send."
    const val MPIN_REASON = "Needs your MPIN, so Zyke can't send this for you"
    const val SIM_NOTE = "Sent from the SIM your phone uses for texts. If your bank knows a different SIM, change that in your phone's settings, or send the text from your messages app."

    val presets: List<EnquiryPreset> = listOf(
        EnquiryPreset("HDFC", BankName.HDFC, "HDFC Bank", "7308080808", "Balance", otherTexts = listOf("BAL")),
        EnquiryPreset("FEDERAL_BANK", BankName.FEDERAL_BANK, "Federal Bank", "9895088888", "BAL SB{last4}", otherNumbers = listOf("5676762")),
        EnquiryPreset("INDIAN_BANK", BankName.INDIAN_BANK, "Indian Bank", "", "", blockedReason = MPIN_REASON),
        EnquiryPreset(EPFO_KEY, null, "EPFO provident fund", "7738299899", "EPFOHO {uan} ENG", kind = EnquiryKind.PROVIDENT_FUND)
    )

    /** The key a bank's setup is saved under: the known bank's id, else the name as shown (lower case) for any other. */
    fun bankKey(bank: BankName, label: String): String =
        if (bank != BankName.OTHERS) bank.name else "other:" + label.trim().lowercase().take(40)

    fun presetFor(key: String): EnquiryPreset? = presets.firstOrNull { it.key == key }

    /** The service for this bank, with the person's changes applied; null when the bank has no preset and nothing was set up. */
    fun service(key: String, displayName: String, setup: EnquirySetup): EnquiryService? {
        val preset = presetFor(key)
        val edit = setup.edits[key]
        if (preset == null && edit == null) return null
        return EnquiryService(
            key = key,
            name = preset?.name ?: edit?.name?.ifBlank { null } ?: displayName,
            number = edit?.number ?: preset?.number.orEmpty(),
            template = edit?.template ?: preset?.template.orEmpty(),
            kind = preset?.kind ?: EnquiryKind.BANK_ACCOUNT,
            isPreset = preset != null,
            isEdited = edit != null,
            otherNumbers = preset?.otherNumbers.orEmpty(),
            otherTexts = preset?.otherTexts.orEmpty(),
            // Setting the number and words by hand is the person's choice and they were checked not to hold a secret.
            blockedReason = if (edit != null) null else preset?.blockedReason
        )
    }

    /** What to do for a bank account of [bank] (shown as [bankLabel]) ending [last4]. */
    fun planForAccount(bank: BankName, bankLabel: String, last4: String, setup: EnquirySetup): EnquiryPlan {
        val service = service(bankKey(bank, bankLabel), bankLabel.ifBlank { bank.displayString }, setup)
            ?: return EnquiryPlan.Unsupported(bankLabel.ifBlank { bank.displayString })
        return plan(service, last4, "")
    }

    /** What to do for the provident fund, given the UAN the person saved ("" when none). */
    fun planForProvidentFund(setup: EnquirySetup, uan: String): EnquiryPlan =
        plan(service(EPFO_KEY, "EPFO provident fund", setup)!!, "", uan)

    fun plan(service: EnquiryService, last4: String, uan: String): EnquiryPlan {
        service.blockedReason?.let { return EnquiryPlan.Blocked(service, it) }
        val wantsLast4 = service.template.contains(LAST4)
        val wantsUan = service.template.contains(UAN)
        if (wantsLast4 && !(last4.length == 4 && last4.all { it.isDigit() })) return EnquiryPlan.NeedsLast4(service)
        if (wantsUan && !Uan.isValid(uan)) return EnquiryPlan.NeedsUan(service)
        val number = normalizeNumber(service.number) ?: return EnquiryPlan.Unsupported(service.name)
        if (templateProblem(service.template) != null) return EnquiryPlan.Unsupported(service.name)
        return EnquiryPlan.Ready(service, number, render(service.template, last4, uan))
    }

    fun render(template: String, last4: String, uan: String): String =
        template.replace(LAST4, last4).replace(UAN, uan).trim().replace(Regex("\\s+"), " ")

    /** The digits of a number as it will be dialled (a leading + kept), or null when it cannot be a phone number or short code. */
    fun normalizeNumber(raw: String): String? {
        val cleaned = raw.trim().replace(Regex("[\\s\\-()]"), "")
        val plus = cleaned.startsWith("+")
        val digits = if (plus) cleaned.drop(1) else cleaned
        if (digits.isEmpty() || !digits.all { it.isDigit() } || digits.length !in 5..13) return null
        return if (plus) "+$digits" else digits
    }

    fun numberProblem(raw: String): String? =
        if (normalizeNumber(raw) == null) "Enter a phone number or short code, digits only (5 to 13)" else null

    private val SECRET_WORDS = Regex("(?i)\\b(?:m?pin|password|passcode|passwd|otp|cvv|cvc|secret)\\b|\\{(?!last4\\}|uan\\})")
    private val ALLOWED = Regex("[A-Za-z0-9 .,_{}-]+")

    /**
     * Why these words cannot be sent, or null when they can. The text may use only letters, digits, spaces and `{last4}` / `{uan}`;
     * it may never carry a PIN, password or OTP, and any run of three or more digits is refused (type `{last4}` instead of the digits),
     * so a secret cannot be saved here by accident. Zyke does not store or send an MPIN.
     */
    fun templateProblem(raw: String): String? {
        val text = raw.trim()
        if (text.isEmpty()) return "Enter the words to send"
        if (text.length > MAX_TEMPLATE) return "Keep it under $MAX_TEMPLATE characters"
        if (!ALLOWED.matches(text)) return "Use letters, digits and spaces only"
        if (SECRET_WORDS.containsMatchIn(text)) return "Zyke never sends a PIN, password or OTP. Use {last4} for the account's last digits"
        if (Regex("\\d{3,}").containsMatchIn(text.replace(LAST4, " ").replace(UAN, " "))) {
            return "Type {last4} instead of the account digits. Zyke never sends a PIN"
        }
        return null
    }

    /** Whether [raw] equals the preset or what the person last typed, ignoring case and spaces, so a no-op edit is not saved. */
    fun sameAs(a: String, b: String) = a.trim().replace(Regex("\\s+"), " ").equals(b.trim().replace(Regex("\\s+"), " "), ignoreCase = true)

    private const val LAST4 = "{last4}"
    private const val UAN = "{uan}"
}

/** The Universal Account Number: 12 digits. Kept only in private preferences on this phone; shown masked. */
object Uan {
    fun clean(raw: String): String = raw.filter { it.isDigit() }.take(BalanceEnquiry.UAN_LENGTH)
    fun isValid(raw: String): Boolean = raw.length == BalanceEnquiry.UAN_LENGTH && raw.all { it.isDigit() }

    /** "••••••••6789": the last four digits only. */
    fun mask(uan: String): String = if (uan.length < 4) "" else "•".repeat((uan.length - 4).coerceAtLeast(0)) + uan.takeLast(4)
}
