package com.zyke.message.domain.sharing

/**
 * A streaming service whose login code may be passed on to a trusted person.
 *
 * Matching needs the SENDER HEADER to be one of the service's ([senderTokens], compared with the operator prefix and
 * suffix removed, so "VM-NFLXIN-S" is "NFLXIN"). The body only confirms: a body that names a different service makes the
 * message ambiguous, and a service whose header is shared with something broader ([senderNeedsBrand], Amazon) also needs
 * its own name in the body. A message that merely says "Netflix" from some other sender is never a match.
 */
class ShareService(
    val id: String,
    /** What the chip and the forwarded text call it. Plain ASCII, so the text stays one SMS. */
    val label: String,
    /** Upper-case letters and digits only; a sender token that CONTAINS one of these belongs to this service. */
    val senderTokens: List<String>,
    /** Wording in the message that names the service. */
    val brandWords: List<Regex>,
    /** The header is also used for things that are not this service (Amazon shopping), so the body must name it too. */
    val senderNeedsBrand: Boolean = false,
    /** A short note for the chip's accessibility text and the add sheet, e.g. "Hotstar, Disney+ Hotstar, JioHotstar". */
    val alsoKnownAs: String = label
)

object ShareServices {
    /** The services that can be shared. Add one here (and a test) to extend the list. */
    val all: List<ShareService> = listOf(
        ShareService(
            id = "netflix", label = "Netflix",
            // UNVERIFIED GUESSES: no real Netflix header was ever seen. The "Recent codes seen" log on the sharing page exists to
            // discover the real ones (and "Treat ... as ..." lets the person teach one).
            senderTokens = listOf("NETFLIX", "NETFLX", "NFLXIN", "NFLX"),
            brandWords = listOf(Regex("(?i)netflix"))
        ),
        ShareService(
            id = "hotstar", label = "Hotstar",
            // Unverified guesses (see Netflix above).
            senderTokens = listOf("HOTSTAR", "HOTSTR", "HSTAR", "HTSTAR", "JIOHOTSTAR", "JIOHOT", "JIOHST", "JHSTAR", "DISNEYHS", "DISNEY", "DSNYHS"),
            brandWords = listOf(Regex("(?i)hot\\s?star")),
            alsoKnownAs = "Hotstar, Disney+ Hotstar, JioHotstar"
        ),
        ShareService(
            id = "prime", label = "Prime Video",
            // Unverified guesses. The generic Amazon headers (AMAZON, AMZN, AMZNIN) also carry shopping codes, hence senderNeedsBrand.
            senderTokens = listOf("AMAZON", "AMZN", "AMZNIN", "PRIMEV", "PRMVID", "AMAZPR"),
            brandWords = listOf(Regex("(?i)prime\\s?video|amazon\\s+prime|\\bprime\\b")),
            // Amazon's header also sends shopping and Amazon Pay codes: only a code that says Prime counts.
            senderNeedsBrand = true,
            alsoKnownAs = "Amazon Prime, Prime Video"
        ),
        ShareService(
            id = "sunnxt", label = "Sun NXT",
            // Unverified guesses.
            senderTokens = listOf("SUNNXT", "SUNNXTAPP"),
            brandWords = listOf(Regex("(?i)sun\\s?-?nxt"))
        ),
        ShareService(
            id = "sonyliv", label = "Sony LIV",
            // Unverified guesses.
            senderTokens = listOf("SONYLIV", "SONYLV", "SNYLIV", "SONYLI"),
            brandWords = listOf(Regex("(?i)sony\\s?-?liv"))
        ),
        ShareService(
            id = "zee5", label = "ZEE5",
            // Unverified guesses.
            senderTokens = listOf("ZEE5", "ZEE5IN", "ZEEFIV", "ZEETV"),
            brandWords = listOf(Regex("(?i)zee\\s?-?5"))
        )
    )

    fun byId(id: String): ShareService? = all.firstOrNull { it.id == id }

    /**
     * The sender with the operator's route letters ("VM-" in front, "-S" behind) taken off, upper-cased, letters and digits
     * only. A plain phone number gives "" (a code from a person's number is never a service's).
     */
    fun senderToken(sender: String): String {
        var s = sender.trim().uppercase()
        s = s.replace(Regex("^[A-Z0-9]{2}-(?=[A-Z0-9])"), "").replace(Regex("-[A-Z]$"), "")
        s = s.filter { it in 'A'..'Z' || it in '0'..'9' }
        return if (s.length >= 3 && s.any { it in 'A'..'Z' }) s else ""
    }

    /**
     * The services whose header this is, by name only (the body is not looked at): the built-in aliases, or a header the
     * person taught ([learned]: service id to exact sender tokens).
     */
    fun byHeader(sender: String, learned: Map<String, Set<String>> = emptyMap()): List<ShareService> {
        val token = senderToken(sender)
        if (token.isEmpty()) return emptyList()
        return all.filter { s -> s.senderTokens.any { token.contains(it) } || token in learned[s.id].orEmpty() }
    }

    /** The one service this message is from, or null (no header match, an ambiguous one, or a body naming another service). */
    fun match(sender: String, body: String, learned: Map<String, Set<String>> = emptyMap()): ShareService? {
        val service = byHeader(sender, learned).singleOrNull() ?: return null
        val named = all.filter { s -> s.brandWords.any { it.containsMatchIn(body) } }
        if (service.senderNeedsBrand && service !in named) return null
        if (named.any { it !== service }) return null
        return service
    }
}

/** Why a header cannot be taught as a service's. */
enum class LearnProblem { NOT_A_NAME, LOOKS_FINANCIAL, ALREADY_KNOWN, TOO_MANY }

/**
 * "Treat this sender as Netflix": the person teaches the app a header it did not know. It only widens the header gate; the hard
 * blocks (money, banks, government, insurers, phone companies) and the code wording are checked first, as for any message.
 */
object ShareLearning {
    const val MAX_PER_SERVICE = 8

    /** Null when [sender]'s name may be taught as [serviceId]'s. */
    fun problem(sender: String, serviceId: String, learned: Map<String, Set<String>>): LearnProblem? {
        val token = ShareServices.senderToken(sender)
        if (token.isEmpty() || ShareServices.byId(serviceId) == null) return LearnProblem.NOT_A_NAME
        if (SharingBlocks.financialSender(token)) return LearnProblem.LOOKS_FINANCIAL
        if (ShareServices.byHeader(sender, learned).isNotEmpty()) return LearnProblem.ALREADY_KNOWN
        if (learned[serviceId].orEmpty().size >= MAX_PER_SERVICE) return LearnProblem.TOO_MANY
        return null
    }

    fun explain(problem: LearnProblem): String = when (problem) {
        LearnProblem.NOT_A_NAME -> "This sender is a phone number or too short to be a service's name"
        LearnProblem.LOOKS_FINANCIAL -> "This looks like a bank or payment sender, which is never forwarded"
        LearnProblem.ALREADY_KNOWN -> "Zyke already knows this sender"
        LearnProblem.TOO_MANY -> "You taught as many senders for this service as allowed"
    }

    fun add(learned: Map<String, Set<String>>, serviceId: String, token: String): Map<String, Set<String>> =
        learned + (serviceId to (learned[serviceId].orEmpty() + token))

    fun remove(learned: Map<String, Set<String>>, serviceId: String, token: String): Map<String, Set<String>> {
        val left = learned[serviceId].orEmpty() - token
        return if (left.isEmpty()) learned - serviceId else learned + (serviceId to left)
    }
}
