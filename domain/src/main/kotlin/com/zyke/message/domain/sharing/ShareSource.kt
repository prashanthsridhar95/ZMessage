package com.zyke.message.domain.sharing

import com.zyke.message.domain.conversation.SenderDisplay
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageContent

/**
 * Where a code may come from: one of the six built-in services (several sender aliases, an id such as "netflix") or a CUSTOM
 * source the person chose from the codes they received (matched by the sender's normalised name, id "c:" + [key]).
 * A custom source holds sender names only: never a code, a message or a number of the person's.
 */
data class ShareSource(
    val id: String,
    /** What the chip and the forwarded text call it. Plain ASCII, at most 20 characters. */
    val label: String,
    /** [ShareSources.key] of the sender; for a built-in, its first alias. */
    val key: String,
    val builtIn: Boolean,
    /** The raw header the person saw ("VM-JIOHOT-S"), for the test code and the page; blank for a phone number. */
    val header: String = ""
) {
    val isPhone: Boolean get() = key.startsWith(ShareSources.PHONE_MARK)
}

/** Why a sender cannot be a source. */
enum class SourceProblem { NOT_A_NAME, BLOCKED, TOO_MANY }

object ShareSources {
    const val ID_PREFIX = "c:"
    const val PHONE_MARK = "#"
    const val MAX_CUSTOM = 40
    private const val MAX_LABEL = 20

    fun idOf(key: String): String = ID_PREFIX + key

    fun of(service: ShareService): ShareSource = ShareSource(service.id, service.label, service.senderTokens.first(), true)

    val builtIns: List<ShareSource> get() = ShareServices.all.map(::of)

    /** A source by id: a built-in, or one of the person's [custom] ones. */
    fun find(id: String, custom: List<ShareSource>): ShareSource? =
        ShareServices.byId(id)?.let(::of) ?: custom.firstOrNull { it.id == id }

    /** True for an id that is a built-in or one of [custom]. */
    fun exists(id: String, custom: List<ShareSource>): Boolean = find(id, custom) != null

    /**
     * The sender with the route letters ("VM-" in front, "-S" behind) removed, upper-case, letters and digits only: "VM-JIOHOT-S"
     * is "JIOHOT", "JioHotstar" is "JIOHOTSTAR". A phone number is "#" and its digits; anything else unusable is "".
     */
    fun key(sender: String): String {
        val token = ShareServices.senderToken(sender)
        if (token.isNotEmpty()) return token
        val digits = sender.filter { it in '0'..'9' }
        val letters = sender.count { it.isLetter() }
        return if (digits.length >= 6 && letters == 0) PHONE_MARK + digits else ""
    }

    /** The label as the other person sees it: plain ASCII letters, digits and a few marks, at most 20, so the text stays one SMS. */
    fun cleanLabel(raw: String): String =
        raw.filter { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == ' ' || it == '.' || it == '\'' || it == '-' || it == '+' || it == '&' }
            .trim().replace(Regex("\\s+"), " ").take(MAX_LABEL).trim()

    /** Null when this sender may be a source; a bank, payment, wallet, broker, government, insurer or phone-company sender may not. */
    fun problem(key: String, label: String, existing: List<ShareSource> = emptyList()): SourceProblem? {
        if (key.isEmpty()) return SourceProblem.NOT_A_NAME
        if (isBlocked(key, label)) return SourceProblem.BLOCKED
        if (existing.none { it.key == key } && existing.count { !it.builtIn } >= MAX_CUSTOM) return SourceProblem.TOO_MANY
        return null
    }

    fun isBlocked(key: String, label: String): Boolean = SharingBlocks.financialSender(key) || SharingBlocks.financialLabel(label)

    fun explain(problem: SourceProblem): String = when (problem) {
        SourceProblem.NOT_A_NAME -> "This sender has no usable name"
        SourceProblem.BLOCKED -> BLOCKED_REASON
        SourceProblem.TOO_MANY -> "You chose as many senders as allowed"
    }

    const val BLOCKED_REASON = "Bank and payment codes can't be shared"

    /** A custom source for [sender], labelled [label] (else the sender's name); null when [problem] says no. */
    fun custom(sender: String, label: String, existing: List<ShareSource> = emptyList()): ShareSource? {
        val key = key(sender)
        val clean = cleanLabel(label).ifEmpty { cleanLabel(SenderDisplay.name(sender)) }.ifEmpty { cleanLabel(key) }.ifEmpty { "Sender" }
        if (problem(key, clean, existing) != null) return null
        existing.firstOrNull { it.key == key }?.let { return it }
        val header = if (key.startsWith(PHONE_MARK)) "" else SeenCodes.header(sender)
        return ShareSource(idOf(key), clean, key, false, header)
    }

    /** Renames a custom source; the id (and so every person's choice) stays. */
    fun rename(sources: List<ShareSource>, id: String, label: String): List<ShareSource> {
        val clean = cleanLabel(label)
        if (clean.isEmpty()) return sources
        return sources.map { if (it.id == id && !it.builtIn) it.copy(label = clean) else it }
    }

    /** Adds [source] unless one with its key is there. */
    fun add(sources: List<ShareSource>, source: ShareSource): List<ShareSource> =
        if (source.builtIn || sources.any { it.id == source.id }) sources else sources + source

    /** Removes a custom source and detaches it from everyone. */
    fun remove(rules: CodeSharingRules, id: String): CodeSharingRules = rules.copy(
        sources = rules.sources.filter { it.id != id },
        contacts = rules.contacts.map { it.copy(services = it.services - id) }
    )

    /** Every source that can be shown: the six, then the person's, by label. */
    fun all(custom: List<ShareSource>): List<ShareSource> = builtIns + custom.sortedBy { it.label.lowercase() }

    /** The sources a message from [sender] with [body] belongs to: the built-in service it is (if any) and the custom ones by key. */
    fun matching(sender: String, body: String, custom: List<ShareSource>): List<ShareSource> {
        val key = key(sender)
        val builtIn = ShareServices.match(sender, body)?.let(::of)
        // The person's own source first, so its name ("JioHotstar") is the one in the text.
        return (if (key.isEmpty()) emptyList() else custom.filter { it.key == key }) + listOfNotNull(builtIn)
    }
}

/** Reads the service's name out of a code message: "1496 is your JioHotstar verification code" gives "JioHotstar". */
object ShareBrand {
    private val YOUR = Regex("(?i)\\byour\\s+(.{2,30}?)\\s+(?:(?:verification|login|log-in|sign-in|security|one-time|confirmation|authentication|access)\\s+)*(?:code|otp|passcode)\\b")
    private val NAMED = Regex("\\b([A-Z][A-Za-z0-9+&.]{1,19})\\s+(?:(?:[Vv]erification|[Ll]ogin|[Ss]ecurity)\\s+)?(?:[Cc]ode|OTP)\\b")
    private val NOT_A_BRAND = setOf(
        "your", "the", "this", "a", "an", "otp", "one", "login", "verification", "security", "new", "account", "use", "is", "sms", "mobile", "secret",
        "password", "pin", "one-time", "temporary", "delivery", "my", "our", "unique", "secure", "access", "single", "use"
    )

    fun fromBody(body: String): String? {
        val first = YOUR.find(body)?.groupValues?.get(1)
        val raw = first ?: NAMED.find(body)?.groupValues?.get(1)
        val brand = raw?.let(ShareSources::cleanLabel).orEmpty()
        if (brand.length < 2 || !brand.first().isUpperCase()) return null
        if (brand.split(' ').any { it.lowercase() in NOT_A_BRAND }) return null
        if (brand.all { it.isDigit() || it == ' ' }) return null
        return brand
    }
}

/** One sender that texted a code lately: what the picker shows. It never holds a code or a message body. */
data class ReceivedSender(
    val key: String,
    /** The id its source has (or would have): "c:" + key. */
    val sourceId: String,
    /** The sender header as shown (a phone number masked to its last two digits). */
    val header: String,
    /** [SenderDisplay]'s name for it. */
    val displayName: String,
    val verified: Boolean,
    /** The service named in the codes, e.g. "JioHotstar", or null. */
    val brand: String?,
    /** What a source for it would be called. */
    val label: String,
    val count: Int,
    val lastAt: Long,
    val isPhone: Boolean,
    val blocked: Boolean
) {
    /** The detected name when it adds something to [displayName]. */
    val brandIfDifferent: String? get() = brand?.takeIf { it.filter(Char::isLetterOrDigit).lowercase() != displayName.filter(Char::isLetterOrDigit).lowercase() }
}

object ReceivedCodeSenders {
    const val WINDOW_DAYS = 90
    private const val DAY_MS = 24L * 60 * 60_000L
    private const val SKEW_MS = 60_000L

    /**
     * The senders of code-like messages (OTP content) in the last 90 days, de-duplicated by source key. Order: usable senders
     * by most recent first, then phone numbers, then blocked ones. [customSources] are only used to keep a chosen name.
     */
    fun build(messages: List<ClassifiedMessage>, now: Long, customSources: List<ShareSource> = emptyList()): List<ReceivedSender> {
        val from = now - WINDOW_DAYS * DAY_MS
        val groups = LinkedHashMap<String, MutableList<ClassifiedMessage>>()
        for (m in messages) {
            if (m.content !is MessageContent.Otp) continue
            val at = m.message.date
            if (at < from || at > now + SKEW_MS) continue
            val key = ShareSources.key(m.message.sender)
            if (key.isEmpty()) continue
            groups.getOrPut(key) { ArrayList() } += m
        }
        val out = groups.map { (key, list) ->
            val newest = list.maxByOrNull { it.message.date }!!
            val sender = newest.message.sender
            val label = SenderDisplay.of(sender)
            val brand = list.sortedByDescending { it.message.date }.firstNotNullOfOrNull { ShareBrand.fromBody(it.message.body) }
            val chosen = customSources.firstOrNull { it.key == key }
            val phone = key.startsWith(ShareSources.PHONE_MARK)
            val suggested = chosen?.label
                ?: ShareSources.cleanLabel(brand ?: (if (phone) "Phone number" else label.name)).ifEmpty { "Sender" }
            val blocked = ShareSources.isBlocked(key, suggested) || ShareSources.isBlocked(key, brand.orEmpty()) ||
                list.all { SharingBlocks.financial(it.message.sender, it.message.body) || SharingBlocks.accountSecurity(it.message.sender, it.message.body) || paymentOtp(it) }
            ReceivedSender(
                key = key,
                sourceId = ShareSources.idOf(key),
                header = SeenCodes.header(sender),
                displayName = if (phone) "Phone number" else label.name,
                verified = label.verified,
                brand = brand,
                label = suggested,
                count = list.size,
                lastAt = newest.message.date,
                isPhone = phone,
                blocked = blocked
            )
        }
        return out.sortedWith(compareBy<ReceivedSender> { if (it.blocked) 2 else if (it.isPhone) 1 else 0 }.thenByDescending { it.lastAt }.thenBy { it.key })
    }

    private fun paymentOtp(m: ClassifiedMessage): Boolean {
        val c = m.content
        return c is MessageContent.Otp && (c.paymentAmount != null || c.cardNumber != null)
    }

    /** The source to create for a pick. Null when blocked. */
    fun sourceFor(sender: ReceivedSender, existing: List<ShareSource>): ShareSource? {
        if (sender.blocked) return null
        existing.firstOrNull { it.key == sender.key }?.let { return it }
        val header = if (sender.isPhone) "" else sender.header
        if (ShareSources.problem(sender.key, sender.label, existing) != null) return null
        return ShareSource(ShareSources.idOf(sender.key), sender.label, sender.key, false, header)
    }

    /** "3 codes · last 2 days ago" */
    fun summary(sender: ReceivedSender, now: Long): String {
        val days = ((now - sender.lastAt).coerceAtLeast(0L) / DAY_MS).toInt()
        val ago = when {
            days <= 0 -> "today"
            days == 1 -> "yesterday"
            else -> "$days days ago"
        }
        return "${sender.count} ${if (sender.count == 1) "code" else "codes"} · last $ago"
    }

    /** Filters by what the person typed: the name, the detected service or the header. */
    fun filter(list: List<ReceivedSender>, query: String): List<ReceivedSender> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return list
        return list.filter { listOfNotNull(it.displayName, it.brand, it.label, it.header).any { f -> f.lowercase().contains(q) } }
    }
}

/** Learned headers of the earlier version ("Treat X as Netflix") become custom sources. */
object ShareMigration {
    /**
     * [learned] was service id to sender tokens. Each token becomes a custom source labelled with the service, attached to
     * the people who had that service. Tokens that are blocked or unusable are dropped.
     */
    fun fromLearned(rules: CodeSharingRules, learned: Map<String, Set<String>>): CodeSharingRules {
        var out = rules
        for ((serviceId, tokens) in learned.toSortedMap()) {
            val service = ShareServices.byId(serviceId) ?: continue
            for (token in tokens.sorted()) {
                val source = ShareSources.custom(token, service.label, out.sources) ?: continue
                out = out.copy(
                    sources = ShareSources.add(out.sources, source),
                    contacts = out.contacts.map { if (serviceId in it.services) it.copy(services = it.services + source.id) else it }
                )
            }
        }
        return out
    }
}
