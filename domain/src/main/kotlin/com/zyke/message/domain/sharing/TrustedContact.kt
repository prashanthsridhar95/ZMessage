package com.zyke.message.domain.sharing

/** A person the user trusts with codes: a name to show, a validated number, and the services they may be sent. */
data class TrustedContact(
    val id: String,
    val name: String,
    /** Normalised by [PhoneNumbers.normalise]: "+91" and ten digits, or "+" and an international number. */
    val number: String,
    /** [ShareService.id]s. */
    val services: Set<String>,
    /** The per-person switch: off keeps the person and their choices but sends nothing. */
    val enabled: Boolean = true
)

/** Reading and showing phone numbers. Pure: no Android contact classes. */
object PhoneNumbers {
    /**
     * "+91XXXXXXXXXX" for an Indian mobile number typed any usual way (10 digits starting 6 to 9, with 0, 91 or +91 in
     * front, spaces, dashes or brackets), "+<digits>" for an international number (8 to 15 digits, not starting 0), or null.
     */
    fun normalise(raw: String): String? {
        val text = raw.trim()
        if (text.isEmpty() || text.any { !(it in '0'..'9' || it in " -().+") }) return null
        if (text.count { it == '+' } > 1 || (text.contains('+') && !text.startsWith("+"))) return null
        var digits = text.filter { it in '0'..'9' }
        var international = text.startsWith("+")
        if (!international && digits.startsWith("00")) {
            digits = digits.drop(2)
            international = true
        }
        return when {
            international -> international(digits)
            digits.length == 10 -> indianMobile(digits)
            digits.length == 11 && digits.startsWith("0") -> indianMobile(digits.drop(1))
            digits.length == 12 && digits.startsWith("91") -> indianMobile(digits.drop(2))
            else -> null
        }
    }

    private fun indianMobile(ten: String): String? {
        if (ten.length != 10 || ten[0] !in '6'..'9' || ten.all { it == ten[0] }) return null
        return "+91$ten"
    }

    private fun international(digits: String): String? {
        if (digits.length !in 8..15 || digits[0] == '0') return null
        if (digits.startsWith("91")) return if (digits.length == 12) indianMobile(digits.drop(2)) else null
        return "+$digits"
    }

    /** A number with its middle hidden for a list: "+91 98••• ••210". Anything not normalised is hidden entirely. */
    fun mask(number: String): String = when {
        number.startsWith("+91") && number.length == 13 -> "+91 ${number.substring(3, 5)}••• ••${number.takeLast(3)}"
        number.startsWith("+") && number.length >= 9 -> "${number.take(3)}•••• ${number.takeLast(3)}"
        else -> "••••••"
    }

    /** A normalised number as it is easiest to read: "+91 98765 43210". */
    fun pretty(number: String): String =
        if (number.startsWith("+91") && number.length == 13) "+91 ${number.substring(3, 8)} ${number.substring(8)}" else number
}

/** Why a person cannot be saved yet. */
enum class ContactProblem { NAME_EMPTY, NUMBER_INVALID, NO_SERVICE, DUPLICATE_NUMBER, TOO_MANY }

sealed interface ContactResult {
    data class Ok(val contact: TrustedContact) : ContactResult
    data class Bad(val problem: ContactProblem) : ContactResult
}

object TrustedContacts {
    const val MAX_PEOPLE = 10
    private const val MAX_NAME = 40

    fun cleanName(raw: String): String = raw.trim().replace(Regex("\\s+"), " ").take(MAX_NAME).trim()

    /** Checks a form and builds the person. [existing] is everyone saved now (including this person when editing). */
    fun build(id: String, rawName: String, rawNumber: String, services: Set<String>, existing: List<TrustedContact>, enabled: Boolean = true): ContactResult {
        val name = cleanName(rawName)
        if (name.isEmpty()) return ContactResult.Bad(ContactProblem.NAME_EMPTY)
        val number = PhoneNumbers.normalise(rawNumber) ?: return ContactResult.Bad(ContactProblem.NUMBER_INVALID)
        val known = services.filter { ShareServices.byId(it) != null }.toSet()
        if (known.isEmpty()) return ContactResult.Bad(ContactProblem.NO_SERVICE)
        if (existing.any { it.id != id && it.number == number }) return ContactResult.Bad(ContactProblem.DUPLICATE_NUMBER)
        if (existing.none { it.id == id } && existing.size >= MAX_PEOPLE) return ContactResult.Bad(ContactProblem.TOO_MANY)
        return ContactResult.Ok(TrustedContact(id, name, number, known, enabled))
    }

    fun explain(problem: ContactProblem): String = when (problem) {
        ContactProblem.NAME_EMPTY -> "Add a name"
        ContactProblem.NUMBER_INVALID -> "Enter a mobile number: 10 digits, or with + and the country code"
        ContactProblem.NO_SERVICE -> "Choose at least one service"
        ContactProblem.DUPLICATE_NUMBER -> "This number is already on the list"
        ContactProblem.TOO_MANY -> "You can share with up to $MAX_PEOPLE people"
    }
}
