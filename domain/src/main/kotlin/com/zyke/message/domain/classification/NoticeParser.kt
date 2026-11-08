package com.zyke.message.domain.classification

import com.zyke.message.domain.model.NoticeKind

/** Spots informational messages that mention money but are not a payment, so they are not reported as unparsed. */
object NoticeParser {
    private val STATED_BALANCE = Regex("(?i)\\bis the (?:available |current |ledger )?balance in (?:your )?a/?c\\b")

    /**
     * "TXN DECLINED: Rs.1299 on ... Reason: Online usage disabled", "Debit Card Txn Declined! For Rs. 500 ...". Anchored at the
     * start so a shop that happens to be called "Declined" in a real debit is never read as one.
     */
    private val DECLINED_PAYMENT = Regex("(?i)^\\W{0,3}(?:(?:debit|credit|atm|prepaid) card )?(?:txn|transaction|payment) declined\\b")
    private val REASON = Regex("(?i)\\breason:\\s*([^.\\n]{2,80})")

    /** Advisories that quote penalties in rupees: the amount is a fine, not money moving. */
    private val AMOUNTLESS_ADVISORY = Regex("(?i)\\bAdvisory as per the Telecommunications Act\\b")

    /** A payment that was refused: never a transaction, whatever else the message says. */
    fun isDeclinedPayment(body: String) = DECLINED_PAYMENT.containsMatchIn(body.take(MAX_HEAD).replace(SPACES_NBSP, " "))

    /** Why a notice happened, when it says (a declined payment's reason). */
    fun detail(kind: NoticeKind, body: String): String? =
        if (kind == NoticeKind.PAYMENT_DECLINED) REASON.find(body.replace(SPACES_NBSP, " "))?.groupValues?.get(1)?.trim()?.trimEnd('.', ',') else null

    private const val MAX_HEAD = 400

    /**
     * A bank's reply to a balance enquiry ("Bal in A/C XX1234 is Rs 5,000.00", "Available balance in A/c XX1234 is Rs. 12,345.67 as on ...",
     * "Avl Bal Rs.99 in A/c XX1234"): it states a balance for an account and moves no money. Any word of a payment, a card, a limit or a
     * due amount makes it something else, so a debit alert that ends in "Avl Bal" is never read as one.
     */
    private val REPLY_BALANCE = Regex("(?i)\\b(?:avl\\.?|avbl\\.?|available|current|ledger)?\\s*bal(?:ance)?\\b.{0,80}?(?:INR\\.?|Rs\\.?|\u20b9)\\s*-?\\s*\\d")
    private val REPLY_ACCOUNT = Regex("(?i)\\b(?:a/?c|acct?|account)\\b")
    private val REPLY_NOT = Regex(
        "(?i)\\b(?:debited|credited|debit|credit|spent|withdrawn|withdrawal|paid|payment|pay|received|transferred|transfer|sent|purchase|txn|transaction|upi|neft|imps|rtgs|" +
            "ref|emi|due|bill|limit|outstanding|reversed|refund|cheque|chq|cashback|otp|card|wallet|mandate|autopay|offer|loan|charged|charges|charge|alert|maintain|minimum|fee|fees|gst|penalty|low|fall|below)\\b"
    )

    /** Whether [body] is a bank's reply to a balance enquiry; see [REPLY_BALANCE]. */
    fun isBalanceReply(body: String): Boolean {
        val flat = body.replace(SPACES_NBSP, " ")
        return flat.length <= MAX_REPLY && REPLY_BALANCE.containsMatchIn(flat) && REPLY_ACCOUNT.containsMatchIn(flat) && !REPLY_NOT.containsMatchIn(flat)
    }

    private const val MAX_REPLY = 320

    /** EPFO's answer to "EPFOHO UAN ENG": "Dear member, your PF balance ... is Rs ...". Needs the member or EPFO wording beside it. */
    private val PF_REPLY = Regex("(?i)(?=.*\\b(?:dear member|EPFO?|UAN)\\b)(?=.*\\b(?:EPF|PF|provident fund)\\s+(?:a/?c\\s+)?balance\\b)")

    private val RULES: List<Pair<Regex, NoticeKind>> = listOf(
        Regex("(?i)\\b(?:EPFO?|PF|provident fund)\\b.*\\bpassbook\\b|\\bpassbook\\b.*\\b(?:EPFO?|PF|provident fund|UAN|member id|contribution|due month|wage month)\\b") to NoticeKind.PF_UPDATE,
        PF_REPLY to NoticeKind.PF_UPDATE,
        Regex("(?i)\\bavailable balance for a/?c\\b") to NoticeKind.BALANCE_UPDATE,
        // KVB: "INR 99,999.99 is the Balance in a/c ****1234 as of 14-AUG-2026 10:32:15"
        STATED_BALANCE to NoticeKind.BALANCE_UPDATE,
        Regex("(?i)\\b(?:term|fixed|recurring) deposit\\b.{0,80}?\\b(?:has been |is |was )?closed\\b") to NoticeKind.DEPOSIT_CLOSED,
        Regex("(?i)\\b(?:term|fixed|recurring) deposit\\b.{0,80}?\\b(?:has |have )?matured\\b") to NoticeKind.DEPOSIT_MATURED,
        DECLINED_PAYMENT to NoticeKind.PAYMENT_DECLINED,
        Regex("(?i)\\byou have declined a mandate\\b") to NoticeKind.MANDATE_DECLINED,
        Regex("(?i)^(?!.*\\b(?:debited|credited|a/c|account|avl bal)\\b)(?=.*\\btransaction (?:no|id|ref)\\.?:? ?\\w+ for (?:INR\\.?|Rs\\.?|\u20b9) ?[\\d,.]+).{0,160}?\\b(?:has )?(?:succeeded|been successful)\\b") to NoticeKind.PAYMENT_CONFIRMATION,
        Regex("(?i)\\btransaction of\\b.*\\bconverted into EMI\\b") to NoticeKind.EMI_CONVERTED,
        Regex("(?i)\\bTrade Confirmation\\b|\\byour traded value for\\b") to NoticeKind.TRADE_CONFIRMATION,
        Regex("(?i)\\b(?:was|were) declined\\b") to NoticeKind.PAYMENT_DECLINED,
        Regex("(?i)\\bAvg\\.? bal(?:ance)?\\.? maintained\\b") to NoticeKind.ADVISORY,
        Regex("(?i)\\bManage (?:INR|Rs\\.?) ?[\\d,.]+ spend at\\b.*\\bEMIs?\\b") to NoticeKind.ADVISORY,
        Regex("(?i)\\bInvoice generated for\\b") to NoticeKind.ADVISORY,
        Regex("(?i)\\bAutopay Failed") to NoticeKind.AUTOPAY_FAILED,
        Regex("(?i)\\brefund\\b.*\\b(?:initiated|processed|approved|will be credited|being processed|in process|raised|issued)\\b") to NoticeKind.REFUND_PENDING,
        Regex("(?i)\\bRedeem your .*?(?:Miles|Points|Reward)") to NoticeKind.REWARD_REDEMPTION,
        Regex("(?i)\\bIPO\\b.*?\\ballotted") to NoticeKind.IPO_ALLOTMENT,
        Regex("(?i)\\bTotal TDS by\\b") to NoticeKind.TAX_DEDUCTED,
        Regex("(?i)\\breported your Fund bal") to NoticeKind.BROKER_BALANCE,
        Regex("(?i)\\bapproved against case ID") to NoticeKind.INSURANCE_CLAIM,
        Regex("(?i)\\bTo pay (?:INR|Rs\\.?)\\s*[\\d,.]+.*?\\bplease click") to NoticeKind.PAYMENT_REQUEST,
        Regex("(?i)\\bannual pass issued on FASTag") to NoticeKind.FASTAG_PASS,
        Regex("(?i)\\bAutoPay \\(E-mandate\\) Modified") to NoticeKind.MANDATE_MODIFIED,
        Regex("(?i)\\binstructed to unblock\\b") to NoticeKind.FUNDS_UNBLOCKED,
        Regex("(?i)\\b(?:transfer|txn)\\.?\\s+limit\\b") to NoticeKind.LIMIT_CHANGE,
        Regex("(?i)\\bdues of\\b.*?\\blegal action\\b") to NoticeKind.DUES_REMINDER,
        Regex("(?i)\\bonce in every \\d+ days to be eligible for insurance") to NoticeKind.ADVISORY,
        Regex("(?i)\\bSIM or telecom service through fraud") to NoticeKind.ADVISORY
    )

    private val AMOUNT = Regex("(?i)(?:INR\\.?|Rs\\.?|\u20b9)\\s*(\\d[\\d,]*(?:\\.\\d+)?)")

    /** An EPF / PF passbook message, which says what went in and often the balance. */
    private val SPACES_NBSP = Regex("[\\s\\u00a0]+")

    fun isProvidentFund(body: String): Boolean {
        val flat = body.replace(SPACES_NBSP, " ")
        return RULES.first().first.containsMatchIn(flat) || PF_REPLY.containsMatchIn(flat)
    }

    fun detect(body: String): Pair<NoticeKind, String?>? {
        val flat = body.replace(SPACES_NBSP, " ")
        if (AMOUNTLESS_ADVISORY.containsMatchIn(flat)) return NoticeKind.ADVISORY to null
        val kind = RULES.firstOrNull { (rule, _) -> rule.containsMatchIn(flat) }?.second
            ?: if (isBalanceReply(flat)) NoticeKind.BALANCE_UPDATE else return null
        val amount = AMOUNT.find(flat)?.groupValues?.get(1)?.replace(",", "")?.trimEnd('.')
        return kind to amount
    }
}
