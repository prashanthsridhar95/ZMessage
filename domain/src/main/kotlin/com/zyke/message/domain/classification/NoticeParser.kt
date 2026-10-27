package com.zyke.message.domain.classification

import com.zyke.message.domain.model.NoticeKind

/** Spots informational messages that mention money but are not a payment, so they are not reported as unparsed. */
object NoticeParser {
    private val RULES: List<Pair<Regex, NoticeKind>> = listOf(
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
        Regex("(?i)\\b(?:transfer|txn) limit\\b") to NoticeKind.LIMIT_CHANGE,
        Regex("(?i)\\bdues of\\b.*?\\blegal action\\b") to NoticeKind.DUES_REMINDER,
        Regex("(?i)\\bonce in every \\d+ days to be eligible for insurance") to NoticeKind.ADVISORY,
        Regex("(?i)\\bSIM or telecom service through fraud") to NoticeKind.ADVISORY
    )

    private val AMOUNT = Regex("(?i)(?:INR\\.?|Rs\\.?|\u20b9)\\s*(\\d[\\d,]*(?:\\.\\d+)?)")

    fun detect(body: String): Pair<NoticeKind, String?>? {
        val flat = body.replace(Regex("\\s+"), " ")
        val kind = RULES.firstOrNull { (rule, _) -> rule.containsMatchIn(flat) }?.second ?: return null
        val amount = AMOUNT.find(flat)?.groupValues?.get(1)?.replace(",", "")?.trimEnd('.')
        return kind to amount
    }
}
