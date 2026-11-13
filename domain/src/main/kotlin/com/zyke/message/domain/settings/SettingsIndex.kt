package com.zyke.message.domain.settings

import com.zyke.message.domain.search.TextHighlight

/** The groups Settings is divided into, in the order the first screen lists them. */
enum class SettingsGroupId(val title: String) {
    APPEARANCE("Appearance and feel"),
    ALERTS("Notifications and alerts"),
    MONEY("Money and budgets"),
    ACCOUNTS("Accounts and cards"),
    MESSAGES("Messages and senders"),
    TRAVEL("Travel"),
    BACKUP("Backup and data"),
    PRIVACY("Privacy and security"),
    HELP("Help and about"),
    DEVELOPER("Developer")
}

/**
 * The pages a group row can open. The names match the app's page enum one to one (a test in the app checks it), so a page
 * is addressed by [name] everywhere: a search result, the tour, a deep link.
 */
enum class SettingsPageId(val title: String) {
    MONEY("Money plan"),
    LIMITS("Budgets"),
    CARDS("Cards and accounts"),
    MESSAGES("Messages and pictures"),
    RULES("Your rules"),
    ALERTS("Reminders and alerts"),
    MARKETS("Market watch"),
    FLIGHTS("Flights and trains"),
    PRIVACY("Privacy and security"),
    APPEARANCE("Appearance"),
    HELP("Help and tour"),
    DEMO("Demo mode"),
    ABOUT("Your data and about"),
    LEGAL("Privacy and terms"),
    LICENCES("Open-source licences"),
    DEVELOPER("Developer options")
}

/**
 * One thing that can be set (or read, or done) in Settings: what it is called, one line saying what it does, the words
 * people might look for it by, the group it is listed under and the page that holds it.
 */
data class SettingsEntry(
    val id: String,
    val title: String,
    val summary: String,
    val keywords: List<String>,
    val group: SettingsGroupId,
    val page: SettingsPageId,
    /** Only offered while developer options are on. */
    val developerOnly: Boolean = false,
    /** Not shown in demo mode (it would act on the real phone, or on nothing). */
    val hiddenInDemo: Boolean = false
)

/** A search hit: the entry and where the typed words fall in its title and summary (for bold). */
data class SettingsMatch(
    val entry: SettingsEntry,
    val rank: Int,
    val titleRanges: List<IntRange>,
    val summaryRanges: List<IntRange>
)

/** Which pages each group lists on the first screen, in order. Every page is listed exactly once. */
object SettingsLayout {
    val groups: List<Pair<SettingsGroupId, List<SettingsPageId>>> = listOf(
        SettingsGroupId.APPEARANCE to listOf(SettingsPageId.APPEARANCE),
        SettingsGroupId.ALERTS to listOf(SettingsPageId.ALERTS),
        SettingsGroupId.MONEY to listOf(SettingsPageId.MONEY, SettingsPageId.LIMITS, SettingsPageId.MARKETS),
        SettingsGroupId.ACCOUNTS to listOf(SettingsPageId.CARDS),
        SettingsGroupId.MESSAGES to listOf(SettingsPageId.MESSAGES, SettingsPageId.RULES),
        SettingsGroupId.TRAVEL to listOf(SettingsPageId.FLIGHTS),
        SettingsGroupId.BACKUP to listOf(SettingsPageId.ABOUT),
        SettingsGroupId.PRIVACY to listOf(SettingsPageId.PRIVACY),
        SettingsGroupId.HELP to listOf(SettingsPageId.HELP, SettingsPageId.DEMO, SettingsPageId.LEGAL, SettingsPageId.LICENCES),
        SettingsGroupId.DEVELOPER to listOf(SettingsPageId.DEVELOPER)
    )

    /** The groups to list now: Developer only while developer options are on. */
    fun visible(developer: Boolean): List<Pair<SettingsGroupId, List<SettingsPageId>>> =
        groups.filter { developer || it.first != SettingsGroupId.DEVELOPER }
}

/**
 * Every setting the app has, as data. It feeds the search field in Settings, the Settings results of the global search
 * and the coverage test that fails when a setting disappears in a rearrangement. Add a line here whenever a setting is
 * added; [SettingsIndexTest] keeps the list honest.
 */
object SettingsIndex {
    private fun e(
        id: String, title: String, summary: String, group: SettingsGroupId, page: SettingsPageId, vararg keywords: String,
        developerOnly: Boolean = false, hiddenInDemo: Boolean = false
    ) = SettingsEntry(id, title, summary, keywords.toList(), group, page, developerOnly, hiddenInDemo)

    val all: List<SettingsEntry> = listOf(
        // Appearance and feel
        e("appearance.theme", "Theme", "Automatic, light, dark or true black", SettingsGroupId.APPEARANCE, SettingsPageId.APPEARANCE, "dark", "light", "black", "oled", "night", "mode"),
        e("appearance.colour", "Accent colour", "The colour the app is drawn in", SettingsGroupId.APPEARANCE, SettingsPageId.APPEARANCE, "color", "accent", "wallpaper", "custom", "palette"),
        e("appearance.icon", "App icon", "The colour of the icon on your home screen", SettingsGroupId.APPEARANCE, SettingsPageId.APPEARANCE, "launcher", "logo", hiddenInDemo = true),
        e("appearance.haptics", "Haptic feedback", "A light tick on switches, a firmer one on deletes", SettingsGroupId.APPEARANCE, SettingsPageId.APPEARANCE, "vibration", "vibrate", "buzz", "touch"),
        e("appearance.performance", "Performance", "Smooth animations, or lite mode for a slow phone", SettingsGroupId.APPEARANCE, SettingsPageId.APPEARANCE, "lite", "animations", "motion", "battery", "slow"),
        // Notifications and alerts
        e("alerts.status", "Code alert status", "Whether a one-time code would be announced", SettingsGroupId.ALERTS, SettingsPageId.ALERTS, "otp", "test", "notification", "code"),
        e("alerts.morning", "Morning digest", "A short summary at about 8 AM", SettingsGroupId.ALERTS, SettingsPageId.ALERTS, "summary", "daily", "notification"),
        e("alerts.buttons", "Copy-code and Pay buttons", "A button the moment a code or card bill arrives", SettingsGroupId.ALERTS, SettingsPageId.ALERTS, "otp", "copy", "pay", "live alerts", "notification"),
        e("alerts.weekly", "Weekly summary", "Sunday at about 9 AM", SettingsGroupId.ALERTS, SettingsPageId.ALERTS, "week", "report", "notification"),
        e("alerts.headsup", "Heads-up alerts", "A quiet note for an unusual payment or a low balance", SettingsGroupId.ALERTS, SettingsPageId.ALERTS, "unusual", "warning", "notification"),
        e("alerts.floor", "Low balance floor", "Warn when an account drops below this", SettingsGroupId.ALERTS, SettingsPageId.ALERTS, "minimum", "balance", "warn", "low"),
        e("alerts.share", "Share codes with trusted people", "Pass one-time codes on to people you trust", SettingsGroupId.ALERTS, SettingsPageId.ALERTS, "otp", "forward", "family", "receiver", "send", "sms"),
        e("alerts.widget", "Your day widget", "On your home screen, from the widget list", SettingsGroupId.ALERTS, SettingsPageId.ALERTS, "widget", "home screen"),
        // Money and budgets
        e("money.salary", "Salary and pay day", "What comes in each month", SettingsGroupId.MONEY, SettingsPageId.MONEY, "income", "pay day", "payday", "earnings"),
        e("money.savings", "Savings goal", "How much to put aside each month", SettingsGroupId.MONEY, SettingsPageId.MONEY, "save", "saving", "goal"),
        e("money.fixed", "Fixed monthly items", "Rent, EMIs and other regular costs", SettingsGroupId.MONEY, SettingsPageId.MONEY, "rent", "emi", "plan", "bills", "recurring"),
        e("money.cycle", "Month starts", "The day your budget month begins", SettingsGroupId.MONEY, SettingsPageId.MONEY, "cycle", "salary day", "month", "period"),
        e("budgets.categories", "Budgets", "Keep a category within what you choose", SettingsGroupId.MONEY, SettingsPageId.LIMITS, "limit", "daily", "monthly", "carry over", "category", "spending"),
        e("budgets.suggestions", "Budget suggestions", "Limits worked out from your spending", SettingsGroupId.MONEY, SettingsPageId.LIMITS, "suggest", "automatic"),
        e("budgets.move", "Move money between budgets", "For this month, with Undo", SettingsGroupId.MONEY, SettingsPageId.LIMITS, "transfer", "shift"),
        e("budgets.card", "Credit card limit", "One limit across all your cards", SettingsGroupId.MONEY, SettingsPageId.LIMITS, "credit", "card", "spend limit"),
        e("markets.watch", "Market watch", "Stocks you follow, on Home", SettingsGroupId.MONEY, SettingsPageId.MARKETS, "stocks", "shares", "nse", "bse", "watchlist", "ticker", "strip"),
        e("markets.close", "Market close summary", "A note when the market closes", SettingsGroupId.MONEY, SettingsPageId.MARKETS, "evening", "notification", "stocks"),
        // Accounts and cards
        e("cards.list", "Cards and accounts", "Found in your messages, or added by you", SettingsGroupId.ACCOUNTS, SettingsPageId.CARDS, "bank", "last four", "statement day", "credit card", "debit"),
        e("cards.add", "Add a card or account", "By its last four digits", SettingsGroupId.ACCOUNTS, SettingsPageId.CARDS, "new", "manual", "create"),
        // Messages and senders
        e("messages.logos", "Brand logos", "Fetch a logo once for well-known brands", SettingsGroupId.MESSAGES, SettingsPageId.MESSAGES, "pictures", "images", "internet", "photos", "fetch"),
        e("messages.tags", "Tags", "Labels you add to payments", SettingsGroupId.MESSAGES, SettingsPageId.MESSAGES, "label", "note", "trip", "work"),
        e("messages.rcs", "RCS chats and Google Pay top-ups", "Needs notification access", SettingsGroupId.MESSAGES, SettingsPageId.MESSAGES, "notification access", "rcs", "upi lite", "listener", "google messages", "samsung"),
        e("messages.health", "Parsing health", "How many money messages are understood", SettingsGroupId.MESSAGES, SettingsPageId.MESSAGES, "diagnostics", "report", "unrecognised", "share report", "accuracy"),
        e("rules.categories", "Your categories", "Make your own, then pick them when filing a payment", SettingsGroupId.MESSAGES, SettingsPageId.RULES, "custom", "category"),
        e("rules.payees", "Payee rules", "Category, name and picture for a person or shop you pay", SettingsGroupId.MESSAGES, SettingsPageId.RULES, "payee", "person", "shop", "merchant", "upi", "nickname", "picture", "category"),
        e("rules.taught", "What you taught the app", "Sender names, word rules and single payments", SettingsGroupId.MESSAGES, SettingsPageId.RULES, "rules", "sender", "receiver", "rename", "learn", "teach"),
        // Travel
        e("travel.flights", "Live flight status", "Follow the flights found in your messages", SettingsGroupId.TRAVEL, SettingsPageId.FLIGHTS, "airline", "plane", "pnr", "departure", "gate"),
        e("travel.trains", "Live train status", "Follow the trains found in your messages", SettingsGroupId.TRAVEL, SettingsPageId.FLIGHTS, "railway", "irctc", "pnr", "platform", "delay"),
        e("travel.checks", "Live status check", "Test the flight and train look-ups", SettingsGroupId.TRAVEL, SettingsPageId.FLIGHTS, "diagnostics", "test", developerOnly = true),
        // Backup and data
        e("data.auto", "Automatic backup", "Android backup, Google Drive or a folder", SettingsGroupId.BACKUP, SettingsPageId.ABOUT, "auto", "drive", "schedule", "wifi", "charging", "encrypt", "passphrase", "pin", hiddenInDemo = true),
        e("data.backup", "Backup and restore", "A file of your setup, protected by a passphrase", SettingsGroupId.BACKUP, SettingsPageId.ABOUT, "restore", "passphrase", "new phone", "file", "import", hiddenInDemo = true),
        e("data.export.transactions", "Export transactions", "A spreadsheet of what was read", SettingsGroupId.BACKUP, SettingsPageId.ABOUT, "csv", "excel", "spreadsheet", "download", "share"),
        e("data.export.setup", "Export my setup", "Plan, limits, cards, goals and reminders", SettingsGroupId.BACKUP, SettingsPageId.ABOUT, "file", "share", "copy"),
        e("data.reset", "Reset marks and budgets", "Clears read, pinned and archived marks", SettingsGroupId.BACKUP, SettingsPageId.ABOUT, "clear", "reset", "archive", "pinned", "tips"),
        e("data.delete", "Delete all my data", "Removes everything this app has saved", SettingsGroupId.BACKUP, SettingsPageId.ABOUT, "wipe", "erase", "remove", "uninstall", "clear", hiddenInDemo = true),
        // Privacy and security
        e("privacy.hide", "Hide amounts until tapped", "Money shows as dots, everywhere", SettingsGroupId.PRIVACY, SettingsPageId.PRIVACY, "dots", "mask", "private", "money", "hidden"),
        e("privacy.auth", "Ask to unlock amounts", "Fingerprint, face or screen lock before amounts show", SettingsGroupId.PRIVACY, SettingsPageId.PRIVACY, "authentication", "fingerprint", "face", "pin", "biometric", "unlock"),
        e("privacy.reveal", "Hide amounts again after", "How long amounts stay shown once unlocked", SettingsGroupId.PRIVACY, SettingsPageId.PRIVACY, "timeout", "duration", "seconds", "minute"),
        e("privacy.screenshots", "Block screenshots", "Also hides the recent-apps view", SettingsGroupId.PRIVACY, SettingsPageId.PRIVACY, "recent apps", "screen capture", "secure", "screenshot"),
        e("privacy.lock", "Lock the app", "Asks for your screen lock on return", SettingsGroupId.PRIVACY, SettingsPageId.PRIVACY, "pin", "fingerprint", "biometric", "lock screen", "password"),
        e("privacy.pause", "Pause reading messages", "Stops reading until you carry on", SettingsGroupId.PRIVACY, SettingsPageId.PRIVACY, "stop", "sms", "listening", "off"),
        // Help and about
        e("help.tour", "App tour", "A short walkthrough, chapter by chapter", SettingsGroupId.HELP, SettingsPageId.HELP, "walkthrough", "guide", "tutorial", "chapters", "help", "learn"),
        e("help.demo", "Demo mode", "See the app with sample data", SettingsGroupId.HELP, SettingsPageId.DEMO, "sample", "example", "try", "fake"),
        e("help.legal", "Privacy and terms", "What is sent, permissions and terms", SettingsGroupId.HELP, SettingsPageId.LEGAL, "legal", "policy", "terms", "permissions", "data"),
        e("help.licences", "Open-source licences", "Libraries, font and icons we use", SettingsGroupId.HELP, SettingsPageId.LICENCES, "licenses", "open source", "third party", "font", "credits"),
        e("about.version", "Version", "Tap seven times for developer options", SettingsGroupId.HELP, SettingsPageId.ABOUT, "build", "about", "developer", "number"),
        e("about.logs", "Share logs", "A small file of start-up steps, never message text", SettingsGroupId.HELP, SettingsPageId.ABOUT, "crash", "bug", "report", "support", "diagnostics", hiddenInDemo = true),
        // Developer
        e("dev.log", "Write a log file", "Counts and ids only, never message text", SettingsGroupId.DEVELOPER, SettingsPageId.DEVELOPER, "debug", "logging", developerOnly = true),
        e("dev.checks", "Checks", "Log a snapshot, test rename and category", SettingsGroupId.DEVELOPER, SettingsPageId.DEVELOPER, "test", "snapshot", developerOnly = true),
        e("dev.audit", "Inbox audit", "How the messages were sorted", SettingsGroupId.DEVELOPER, SettingsPageId.DEVELOPER, "classification", "diagnostics", developerOnly = true),
        e("dev.simulate", "Simulate a small phone", "Try a narrow screen or large text", SettingsGroupId.DEVELOPER, SettingsPageId.DEVELOPER, "screen size", "density", "font scale", developerOnly = true),
        e("dev.off", "Turn developer options off", "Stops the log and hides this page", SettingsGroupId.DEVELOPER, SettingsPageId.DEVELOPER, "disable", developerOnly = true)
    )

    /** What may be offered now: developer entries only in developer mode, and not those hidden in demo mode while it is on. */
    fun available(developer: Boolean, demo: Boolean): List<SettingsEntry> =
        all.filter { (developer || !it.developerOnly) && !(demo && it.hiddenInDemo) }

    /** The entries listed under [group], in order. */
    fun inGroup(group: SettingsGroupId, developer: Boolean, demo: Boolean): List<SettingsEntry> =
        available(developer, demo).filter { it.group == group }

    /**
     * The entries that match [query]: every typed word must appear in the title, the summary or a keyword (ignoring
     * case). Best first (a title that starts with the word, a word inside the title, a keyword, the summary), then in
     * the order of the list. A blank query matches nothing.
     */
    fun search(query: String, developer: Boolean = false, demo: Boolean = false): List<SettingsMatch> {
        val words = TextHighlight.words(query)
        if (words.isEmpty()) return emptyList()
        val found = ArrayList<Pair<Int, SettingsMatch>>()
        available(developer, demo).forEachIndexed { index, entry ->
            var worst = 0
            for (word in words) {
                val rank = rankOf(entry, word) ?: return@forEachIndexed
                if (rank > worst) worst = rank
            }
            found += index to SettingsMatch(
                entry, worst,
                TextHighlight.ranges(entry.title, words),
                TextHighlight.ranges(entry.summary, words)
            )
        }
        return found.sortedWith(compareBy({ it.second.rank }, { it.first })).map { it.second }
    }

    private fun rankOf(entry: SettingsEntry, word: String): Int? {
        val title = entry.title
        val inTitle = title.indexOf(word, ignoreCase = true)
        return when {
            title.startsWith(word, ignoreCase = true) -> 0
            inTitle > 0 && !title[inTitle - 1].isLetterOrDigit() -> 0
            inTitle >= 0 -> 1
            entry.keywords.any { it.contains(word, ignoreCase = true) } -> 2
            entry.summary.contains(word, ignoreCase = true) -> 3
            else -> null
        }
    }
}
