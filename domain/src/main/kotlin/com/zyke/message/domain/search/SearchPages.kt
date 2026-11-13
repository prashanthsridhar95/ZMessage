package com.zyke.message.domain.search

/** A place in the app a search can jump to. [key] is what the app maps to a screen. */
data class SearchPageEntry(val key: String, val title: String, val subtitle: String, val keywords: List<String> = emptyList())

object SearchPages {
    const val ACCOUNTS = "accounts"
    const val BILLS = "bills"
    const val INSIGHTS = "insights"
    const val MESSAGES = "messages"
    const val SETTINGS = "settings"
    const val CALENDAR = "calendar"
    const val GOALS = "goals"
    const val REMINDERS = "reminders"
    const val SUBSCRIPTIONS = "subscriptions"
    const val INVESTMENTS = "investments"

    val all = listOf(
        SearchPageEntry(ACCOUNTS, "Accounts", "Bank accounts and cards", listOf("bank", "card", "balance", "overview", "billed", "unbilled", "card dues", "net worth")),
        SearchPageEntry(
            INVESTMENTS, "Investments", "Deposits, funds, stocks and gold, and how they have grown",
            listOf("fd", "fixed deposit", "rd", "recurring deposit", "mutual fund", "sip", "stocks", "gold", "maturity", "returns", "receipt")
        ),
        SearchPageEntry(BILLS, "Bills", "What is due and what you paid", listOf("due", "paid", "emi")),
        SearchPageEntry(INSIGHTS, "Insights", "Spending, income and trends", listOf("spend", "report", "chart", "budget")),
        SearchPageEntry(MESSAGES, "Messages", "Every message, sorted", listOf("inbox", "sms", "otp")),
        SearchPageEntry(SETTINGS, "Settings", "Look, privacy, backup and alerts", listOf("theme", "dark", "privacy", "hide amounts", "backup", "restore", "notifications", "language")),
        SearchPageEntry(CALENDAR, "Calendar", "Your month at a glance", listOf("agenda", "events")),
        SearchPageEntry(GOALS, "Goals", "What you are saving for", listOf("saving", "plan", "target")),
        SearchPageEntry(REMINDERS, "Reminders", "Things to pay or renew", listOf("due", "renewal")),
        SearchPageEntry(
            SUBSCRIPTIONS, "Subscriptions", "What repeats, what it costs a year, and how to stop it",
            listOf("subscription", "cancel", "netflix", "autopay", "mandate", "recurring", "yearly cost")
        )
    )

    /** The tiles shown when nothing is typed. */
    val shortcuts: List<SearchPageEntry> = listOf(ACCOUNTS, BILLS, INSIGHTS, SETTINGS, CALENDAR).map { key -> all.first { it.key == key } }

    internal fun hits(needle: String): List<SearchHit> =
        all.mapNotNull { page ->
            val rank = listOfNotNull(
                GlobalSearch.rank(needle, page.title),
                page.keywords.mapNotNull { GlobalSearch.rank(needle, it) }.minOrNull()?.plus(KEYWORD_PENALTY)
            ).minOrNull() ?: return@mapNotNull null
            SearchHit(SearchKind.PAGE, page.key, page.title, page.subtitle, rank = rank)
        }

    private const val KEYWORD_PENALTY = 1
}
