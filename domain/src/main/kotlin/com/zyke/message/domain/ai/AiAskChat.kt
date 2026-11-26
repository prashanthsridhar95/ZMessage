package com.zyke.message.domain.ai

/**
 * Follow-ups in the Ask Zyke chat ("and last month?", "what about Swiggy?"). The model is shown the previous request as a small
 * structured object and answers with only the fields the new question changes; the app merges them onto the previous request
 * and checks every changed field against the same whitelists as a fresh question ([AskParser]). The model never sees or
 * writes a figure, and a reply that does not make sense as a follow-up is dropped in favour of a fresh reading of the question.
 */
object AskFollowUp {
    private const val FIELD_FOLLOW_UP = "follow_up"
    private const val MAX_SHOWN = 30
    private const val MAX_NAME = 60

    /** The previous request written the way the prompt's examples are, from whitelisted words and the person's own names. */
    fun previousJson(q: AskQuery): String {
        fun name(text: String?): String = if (text == null) "null" else "\"" + AiPrompts.clean(text.replace('"', ' '), MAX_NAME) + "\""
        return "{\"metric\":\"${q.metric.word}\",\"category\":${name(q.category?.name)},\"payee\":${name(q.payee)}," +
            "\"period\":\"${q.period.word}\",\"group_by\":\"${q.group.word}\"}"
    }

    /**
     * Reads [reply]: as a follow-up to [previous] when it says `"follow_up":true`, else as a fresh question.
     * A follow-up that changes nothing, or changes a field to something outside the lists, is rejected.
     */
    fun parse(
        reply: String, question: String, knownPayees: Collection<String>, customs: Map<String, String>, previous: AskQuery?
    ): Guarded<AskQuery> {
        val obj = MiniJson.firstObject(reply) ?: return Guarded.Rejected("The answer was not in a form the app can use")
        if (previous == null || obj.values[FIELD_FOLLOW_UP] != true) return AskParser.parse(reply, question, knownPayees, customs)
        return merge(previous, obj, question, knownPayees, customs)
    }

    private fun merge(previous: AskQuery, obj: MiniJson.Obj, question: String, known: Collection<String>, customs: Map<String, String>): Guarded<AskQuery> {
        var metric = previous.metric
        var category = previous.category
        var payee = previous.payee
        var period = previous.period
        var group = previous.group
        var changed = false
        if (obj.has("metric")) {
            metric = AskMetric.entries.firstOrNull { it.word.equals(obj.text("metric"), ignoreCase = true) }
                ?: return Guarded.Rejected("It asked for something the app cannot work out")
            changed = true
        }
        if (obj.has("period")) {
            period = AskPeriod.of(obj.text("period")) ?: return Guarded.Rejected("I could not tell which days you meant")
            changed = true
        }
        if (obj.has("group_by")) {
            group = AskGroup.entries.firstOrNull { it.word.equals(obj.text("group_by") ?: "none", ignoreCase = true) }
                ?: return Guarded.Rejected("It asked for a grouping the app does not have")
            changed = true
        }
        if (obj.has("category")) {
            val text = obj.text("category")
            category = if (text == null || isNone(text)) null else AiSuggestionGuard.category(text, customs)
                ?: return Guarded.Rejected("There is no category called “${text.take(MAX_SHOWN)}”")
            changed = true
        }
        if (obj.has("payee")) {
            val text = obj.text("payee")
            payee = if (text == null || isNone(text)) null else AskParser.matchPayee(text, question, known)
                ?: return Guarded.Rejected("I could not find a payee called “${text.take(MAX_SHOWN)}”")
            changed = true
        }
        val merged = AskQuery(metric, category, payee, period, group)
        return if (!changed || merged == previous) Guarded.Rejected("That did not change the question") else Guarded.Accepted(merged)
    }

    private fun isNone(text: String) = text.equals("null", true) || text.equals("none", true)
}

/** The first things to try in the Ask Zyke chat: each is a question the vocabulary can answer. */
object AskSuggestions {
    val starters: List<String> = listOf(
        "This month's spending", "Biggest payment this month", "Food last week", "Money received this month"
    )
}
