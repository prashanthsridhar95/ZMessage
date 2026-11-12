package com.zyke.message.domain.sharing

import com.zyke.message.domain.ledger.Counted
import com.zyke.message.domain.ledger.Counting
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageContent
import com.zyke.message.domain.conversation.SenderDisplay
import java.time.Instant
import java.time.ZoneId

/** A daily span in which codes may be passed on; [startMinute] after [endMinute] means it runs past midnight (22:00 to 08:00). */
data class ShareWindow(val startMinute: Int, val endMinute: Int) {
    /** Start equal to end is the whole day, so a half-set window never blocks everything by accident. */
    val isAlways: Boolean get() = startMinute == endMinute

    fun contains(minuteOfDay: Int): Boolean = when {
        isAlways -> true
        startMinute < endMinute -> minuteOfDay in startMinute until endMinute
        else -> minuteOfDay >= startMinute || minuteOfDay < endMinute
    }

    companion object {
        /** "10:00 PM" for minute 1320. */
        fun clock(minute: Int): String {
            val m = ((minute % 1440) + 1440) % 1440
            val hour = m / 60
            val h12 = if (hour % 12 == 0) 12 else hour % 12
            return "$h12:${(m % 60).toString().padStart(2, '0')} ${if (hour < 12) "AM" else "PM"}"
        }
    }

    fun label(): String = "${clock(startMinute)} to ${clock(endMinute)}"
}

/** Everything the person set. All of it starts off: no master switch, no people, no hours, no pause. */
data class CodeSharingRules(
    /** The master switch. */
    val enabled: Boolean = false,
    val contacts: List<TrustedContact> = emptyList(),
    /** Nothing is sent before this moment (epoch ms); 0 is not paused. */
    val pausedUntil: Long = 0L,
    /** Null is any time of day. */
    val window: ShareWindow? = null,
    /** The most codes sent to one person in an hour. */
    val maxPerHour: Int = CodeSharing.DEFAULT_MAX_PER_HOUR,
    /** Shown to the other person as "Sent by …"; blank is "Zyke". */
    val senderName: String = "",
    /** A code older than this when it is read is never passed on. */
    val freshForMs: Long = CodeSharing.FRESH_MS,
    /** The senders the person chose from the codes they received (the six built-in services are always there). */
    val sources: List<ShareSource> = emptyList()
) {
    val activePeople: Int get() = contacts.count { it.enabled }
}

/** One thing sent (or tried) to one person, for the rate limit and the duplicate check. */
data class SentRecord(
    val contactId: String,
    val serviceId: String,
    val at: Long,
    /** Held in memory only, never saved: see [CodeSharing.codeKey]. Null for what was read back from the saved log. */
    val codeKey: String? = null
)

data class ShareHistory(val records: List<SentRecord> = emptyList())

/** What the message turned out to be, when it is not passed on. */
enum class ShareSkip {
    OFF, PAUSED, OUTSIDE_HOURS, NOT_A_CODE, FINANCIAL, ACCOUNT_SECURITY, STALE,
    NOT_AN_ALLOWED_SERVICE, NO_ONE_FOR_SERVICE, PERSON_OFF, BAD_NUMBER, RATE_LIMITED, ALREADY_SENT
}

data class Share(val contact: TrustedContact, val source: ShareSource, val text: String, val codeKey: String)

data class Skipped(val contactId: String?, val reason: ShareSkip)

/** Who gets what, or why not. [shares] is empty when nothing is to be sent. */
data class ShareDecision(val shares: List<Share>, val skipped: List<Skipped>) {
    val reasons: Set<ShareSkip> get() = skipped.map { it.reason }.toSet()
}

/**
 * Passing a login code on to people the user trusts. Pure rules, no Android: [decide] is given a message that has just
 * arrived, the person's settings and what was sent lately, and answers with the texts to send.
 *
 * What it will NEVER do, whatever the settings say (checked before anything else is looked at):
 *  - pass on anything about money: a payment or bank, UPI, card or wallet message, any amount ("Rs", "INR", the rupee sign),
 *    a "transaction" code, or a code that is counted in the person's spending;
 *  - pass on account security from a government body, an insurer or a phone company;
 *  - pass on a message whose sender the person has not chosen (one of the six services, or a sender picked from their received codes).
 * It sends only the code and the service name, never the original message. It does not know where the message came from:
 * the caller must call it only for a message that has just arrived, and [freshForMs] is a second line of defence for old ones.
 */
object CodeSharing {
    const val DEFAULT_MAX_PER_HOUR = 5
    const val FRESH_MS = 5 * 60_000L
    private const val CLOCK_SKEW_MS = 60_000L
    private const val HOUR_MS = 60L * 60_000L
    const val TEST_TEXT = "Test from Zyke: code sharing is set up"

    /** The made-up code of "Send a test code": it is only ever the made-up one. */
    const val TEST_CODE = "123456"
    private const val MIN_CODE = 4
    private const val MAX_CODE = 8
    private const val MAX_NAME = 20

    fun decide(
        message: ClassifiedMessage,
        rules: CodeSharingRules,
        now: Long,
        history: ShareHistory = ShareHistory(),
        zone: ZoneId = ZoneId.systemDefault()
    ): ShareDecision = review(message, rules, now, history, zone).decision

    /**
     * [decide] plus what the "Recent codes seen" log needs: whether the message looked like a code at all, the service it was
     * taken for, and one plain outcome. The checks and their order are exactly [decide]'s.
     */
    fun review(
        message: ClassifiedMessage,
        rules: CodeSharingRules,
        now: Long,
        history: ShareHistory = ShareHistory(),
        zone: ZoneId = ZoneId.systemDefault()
    ): ShareReview {
        val sms = message.message
        val content = message.content
        val byHeader = ShareServices.byHeader(sms.sender)
        val key = ShareSources.key(sms.sender)
        val chosenByKey = if (key.isEmpty()) emptyList() else rules.sources.filter { it.key == key }
        // Worth a line in the log: read as a login code, or from a sender that is (by name) one of the sources.
        val codeLike = content is MessageContent.Otp || byHeader.isNotEmpty() || chosenByKey.isNotEmpty()
        val headerSource: ShareSource? = chosenByKey.firstOrNull() ?: byHeader.singleOrNull()?.let(ShareSources::of)
        // What a row in the log calls an unchosen sender: the service named in the code, else the sender's name.
        val name = chosenByKey.firstOrNull()?.label ?: ShareBrand.fromBody(sms.body)?.let(ShareSources::cleanLabel)?.takeIf { it.isNotEmpty() }
            ?: headerSource?.label ?: ShareSources.cleanLabel(SenderDisplay.name(sms.sender))

        fun stop(reason: ShareSkip, source: ShareSource? = headerSource) =
            ShareReview(ShareDecision(emptyList(), listOf(Skipped(null, reason))), codeLike, source, SeenCodes.outcomeOf(reason, codeLike), null, name)

        if (!rules.enabled) return stop(ShareSkip.OFF)
        if (now < rules.pausedUntil) return stop(ShareSkip.PAUSED)
        rules.window?.let { window ->
            val time = Instant.ofEpochMilli(now).atZone(zone)
            if (!window.contains(time.hour * 60 + time.minute)) return stop(ShareSkip.OUTSIDE_HOURS)
        }

        // The hard blocks come first and read the whole message, not only what was classified.
        val counted = Counting.of(message)
        if (content is MessageContent.Transaction || content is MessageContent.CardBill || content is MessageContent.ScheduledPayment ||
            counted !is Counted.NotCounted
        ) return stop(ShareSkip.FINANCIAL)
        if (SharingBlocks.financial(sms.sender, sms.body)) return stop(ShareSkip.FINANCIAL)
        if (SharingBlocks.accountSecurity(sms.sender, sms.body)) return stop(ShareSkip.ACCOUNT_SECURITY)

        // The classifier only knows wording such as "OTP" and "verification code". A chosen source's header lets a plainer wording
        // ("Your Netflix code is 123456") be read too, by the stricter reader.
        val code: String
        if (content is MessageContent.Otp) {
            if (content.paymentAmount != null || content.cardNumber != null) return stop(ShareSkip.FINANCIAL)
            code = content.code
        } else {
            if (headerSource == null) return stop(ShareSkip.NOT_A_CODE)
            code = ShareCodeReader.read(sms.body) ?: return stop(ShareSkip.NOT_A_CODE)
        }
        if (code.length !in MIN_CODE..MAX_CODE || code.any { it !in '0'..'9' }) return stop(ShareSkip.NOT_A_CODE)

        val age = now - sms.date
        if (age > rules.freshForMs || age < -CLOCK_SKEW_MS) return stop(ShareSkip.STALE)

        val sources = ShareSources.matching(sms.sender, sms.body, rules.sources)
        if (sources.isEmpty()) {
            // A built-in header that is ambiguous or lacks its brand is not this service; any other sender simply is not chosen yet.
            return if (byHeader.isNotEmpty()) stop(ShareSkip.NOT_AN_ALLOWED_SERVICE) else stop(ShareSkip.NO_ONE_FOR_SERVICE, null)
        }
        val ids = sources.map { it.id }.toSet()
        val candidates = rules.contacts.filter { c -> c.services.any { it in ids } }
        if (candidates.isEmpty()) return stop(ShareSkip.NO_ONE_FOR_SERVICE, sources.first())

        val shares = ArrayList<Share>()
        val skipped = ArrayList<Skipped>()
        for (contact in candidates) {
            val service = sources.first { it.id in contact.services }
            val text = text(service, code, rules.senderName)
            val key = codeKey(service.id, contact.id, code)
            when {
                !contact.enabled -> skipped += Skipped(contact.id, ShareSkip.PERSON_OFF)
                PhoneNumbers.normalise(contact.number) == null -> skipped += Skipped(contact.id, ShareSkip.BAD_NUMBER)
                history.records.any { it.codeKey == key } -> skipped += Skipped(contact.id, ShareSkip.ALREADY_SENT)
                history.records.count { it.contactId == contact.id && it.at > now - HOUR_MS && it.at <= now + CLOCK_SKEW_MS } >= rules.maxPerHour ->
                    skipped += Skipped(contact.id, ShareSkip.RATE_LIMITED)
                else -> shares += Share(contact, service, text, key)
            }
        }
        val outcome = if (shares.isNotEmpty()) SeenOutcome.SENT else SeenCodes.outcomeOf(skipped.first().reason, true)
        return ShareReview(ShareDecision(shares, skipped), true, sources.first(), outcome, code, sources.first().label)
    }

    /** What a test code sends: clearly a test, and only the made-up code. Plain ASCII so it stays one SMS. */
    fun testText(service: ShareSource): String = "Zyke TEST - ${service.label} code $TEST_CODE"

    /** The made-up incoming message for "Send a test code": a Netflix-style code from the service's first header, read like any other. */
    fun testMessage(service: ShareSource, now: Long): com.zyke.message.domain.model.SmsMessage =
        com.zyke.message.domain.model.SmsMessage(
            0L, "VM-${service.key}-S", "$TEST_CODE is your ${service.label} verification code. This is a Zyke test.", now, isRead = false
        )

    /**
     * What the duplicate check compares. It contains the code, so it lives in memory only and is never written to the
     * saved log, a file or the developer log.
     */
    fun codeKey(serviceId: String, contactId: String, code: String): String = "$serviceId|$contactId|$code"

    /** "Netflix code 482913 (use it soon). Sent by Aman via Zyke": the code and the service, nothing of the original text. */
    fun text(service: ShareSource, code: String, senderName: String): String {
        val who = cleanName(senderName)
        val by = if (who.isEmpty()) "Zyke" else "$who via Zyke"
        return "${service.label} code $code (use it soon). Sent by $by"
    }

    /** The name as the other person sees it: plain letters, digits and a few marks, so the text stays one 160-character SMS. */
    fun cleanName(raw: String): String =
        raw.filter { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == ' ' || it == '.' || it == '\'' || it == '-' }
            .trim().replace(Regex("\\s+"), " ").take(MAX_NAME).trim()

    /** True when every character is in the basic GSM alphabet's plain ASCII part, so one SMS holds 160 of them. */
    fun isOneSegment(text: String): Boolean = text.length <= 160 && text.all { it.code in 32..126 && it != '`' && it != '~' && it != '^' && it != '\\' && it != '|' && it != '{' && it != '}' && it != '[' && it != ']' }

    /** The pause presets: hours of quiet from now. */
    val pauseHours: List<Int> = listOf(1, 8, 24)

    fun pausedUntil(now: Long, hours: Int): Long = now + hours * HOUR_MS

    fun isPaused(rules: CodeSharingRules, now: Long): Boolean = now < rules.pausedUntil

    /** Whether a test text to this person may go now: not twice within a minute. */
    fun canSendTest(log: List<ShareLogEntry>, contactId: String, now: Long): Boolean =
        log.none { it.test && it.contactId == contactId && now - it.at in 0 until 60_000L }

    /** One line for the Settings row: "Off", "On for 2 people", "Paused for 3 more hours". */
    fun summary(rules: CodeSharingRules, now: Long): String = when {
        !rules.enabled -> "Off"
        rules.activePeople == 0 -> "On, nobody added yet"
        isPaused(rules, now) -> "Paused"
        else -> "On for ${rules.activePeople} ${if (rules.activePeople == 1) "person" else "people"}"
    }
}

/** The words and senders that always stop a message from being passed on. */
internal object SharingBlocks {
    private val FINANCIAL_BODY = Regex(
        "(?i)(?:\\b(?:rs|inr)\\b\\.?\\s*[0-9]|[₹]|\\bamount\\b|\\bupi\\b|\\bbank\\b|\\ba/c\\b|\\bacct?\\b|bank account|account\\s+(?:no|number|ending)|\\bending\\s+(?:with\\s+)?[0-9x*]" +
            "|\\bdebit(?:ed)?\\b|\\bcredit(?:ed)?\\b|\\bcards?\\b|\\bcvv\\b|\\bwallet\\b|\\bpay(?:ment|ments|ing)?\\b|\\bpaid\\b|\\btransactions?\\b|\\btxn\\b|\\btransfer\\b" +
            "|\\bemi\\b|\\bloan\\b|\\bbill(?:s|ing)?\\b|\\brecharge\\b|\\bpurchase\\b|\\b(?:your|my)\\s+order\\b|\\border\\s+(?:no|id|number|#)|\\brefund\\b|\\binvoice\\b|\\bmandate\\b|\\bauto-?pay\\b|\\bcharged?\\b" +
            "|\\bbhim\\b|\\bneft\\b|\\bimps\\b|\\brtgs\\b|\\bifsc\\b|\\bbalance\\b|\\bcashback\\b|\\bmpin\\b|\\bnet\\s?banking\\b)"
    )

    private val ACCOUNT_BODY = Regex(
        "(?i)\\b(?:aadhaar|aadhar|uidai|digilocker|pan\\s+card|income\\s?tax|itr|gst|epfo|uan|umang|passport|voter\\s+id|driving\\s+licen[cs]e|insurance|insurer|irdai" +
            "|policy\\s+(?:no|number)|sim\\s+(?:card|swap|change)|e-?sim|porting|\\bmnp\\b|\\bupc\\b|prepaid|postpaid|airtel|vodafone|bsnl|mtnl|my\\s?jio|jio\\s?(?:sim|number|fiber|care))\\b"
    )

    /** Headers that belong to a bank, a payment app or an authority; matched against [ShareServices.senderToken]. */
    private val FINANCIAL_SENDER = Regex(
        "(?:HDFC|ICICI|SBI|AXIS|KOTAK|PNB|CANARA|IDFC|YESB|INDUS|RBL|BOB|UNION|PAYTM|PHONEPE|PHNPE|GPAY|GOOGLEPAY|BHIM|AMAZONPAY|AMZNPAY|AMZPAY|CRED|SLICE|SIMPL|LAZYPAY|NPCI|UPI|BANK|BNK|CARD|WALLET|PAY$|FINSERV|LOAN|INSUR|^LIC|LICIN|IRCTC|UIDAI|AADHA|EPFO|INCOMETAX|DIGILOC|AIRTEL|VODAFONE|BSNL|MTNL" +
            // More banks, cards, wallets, brokers, exchanges, authorities, insurers and phone companies.
            "|BARODA|FEDERAL|FEDBNK|AUBANK|AUSFB|BANDHAN|IDBI|CITI|HSBC|STANCHART|AMEX|AMERICANEX|MOBIKWIK|FREECHARGE|BHARATPE|RAZORPAY|PINELABS|ZERODHA|ZRDHA|GROWW|UPSTOX|ANGELONE|ANGELBRK|ICICIDIRECT|MOTILAL|5PAISA|BINANCE|COINDCX|WAZIRX|COINSWITCH|CDSL|NSDL|NSEIND|BSEIND|SEBI|BROK|DEMAT|MUTUAL|FINANCE|CREDIT|UJJIVAN|EQUITAS|JANASF|BAJAJFIN|BAJFIN|TATACAP|MYGOV|GOVT|GOVIN|UMANG|PASSPORT|IRDAI|SBILIFE|MAXLIFE|JIOPAY|JIOCNF|JIOFBR|JIOCARE|^JIO$|^JIOIN$|VODAIDEA|^VI$|^IDEA$" +
            "|^(?:BOI|BOM|UCO|IOB|SIB|DCB|TMB|CSB|SCB|KVB|NSE|BSE|NIC)[A-Z0-9]*)"
    )

    fun financial(sender: String, body: String): Boolean =
        FINANCIAL_BODY.containsMatchIn(body) || financialSender(ShareServices.senderToken(sender))

    /** [token] is a [ShareServices.senderToken]. */
    fun financialSender(token: String): Boolean = token.isNotEmpty() && FINANCIAL_SENDER.containsMatchIn(token)

    fun accountSecurity(sender: String, body: String): Boolean = ACCOUNT_BODY.containsMatchIn(body)

    private val FINANCIAL_LABEL = Regex(
        "(?i)\\b(?:bank|banking|finance|financial|payments?|upi|wallet|cards?|credit|debit|loans?|insurance|insurer|broker|broking|securities|exchange|demat|mutual|government|govt|telecom|airtel|vodafone|bsnl|mtnl)\\b"
    )

    /** A source's friendly name that says it is a bank, payment, broker, government, insurer or phone-company sender. */
    fun financialLabel(label: String): Boolean = FINANCIAL_LABEL.containsMatchIn(label)
}
