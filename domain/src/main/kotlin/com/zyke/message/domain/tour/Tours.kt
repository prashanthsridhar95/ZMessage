package com.zyke.message.domain.tour

import com.zyke.message.domain.tour.TourAnchor as A
import com.zyke.message.domain.tour.TourGroup as G
import com.zyke.message.domain.tour.TourPage as P
import com.zyke.message.domain.tour.TourPreviewKind as V
import com.zyke.message.domain.tour.TourTab as T

/**
 * The tour, in order. All the words live here, in one place, so they can be edited without touching any screen.
 * Style: plain English, present tense, what it is, where it is, what to do with it. A body is one or two short sentences
 * (at most [MAX_BODY]); a [TourStep.tip] adds a gesture or a shortcut. Every feature the app has is named in some step's
 * `covers`, and `TourToursTest` fails when one is missing.
 */
object Tours {
    const val MAX_BODY = 220
    const val MAX_TITLE = 36
    const val MAX_TIP = 160

    fun full(): List<TourStep> = FULL

    /** Every feature key explained by some step. */
    fun coverage(): Set<String> = FULL.flatMapTo(HashSet()) { it.covers }

    /** The chapters, in the order they first appear. */
    fun groups(steps: List<TourStep> = FULL): List<G> = steps.map { it.group }.distinct()

    fun stepsIn(group: G, steps: List<TourStep> = FULL): List<TourStep> = steps.filter { it.group == group }

    private val FULL: List<TourStep> = listOf(
        // ---- Welcome
        TourStep(
            "welcome", G.WELCOME, "Welcome to Zyke Message",
            "Zyke turns the bank and payment texts already on your phone into a clear picture of your money. There is no account and no sign-up.",
            tab = T.HOME,
            tip = "Your messages never leave this phone. Nothing is uploaded, and there is no tracking.",
            covers = setOf("privacy.local_first", "privacy.no_account", "privacy.messages_stay")
        ),
        TourStep(
            "sms", G.WELCOME, "Why it reads your messages",
            "Zyke reads your SMS only to find spends, bills and codes. It never sends, changes or deletes a message. Allow SMS access when Android asks.",
            tab = T.HOME,
            tip = "Pause reading or delete everything any time from Settings.",
            covers = setOf("sms.reading", "sms.permission", "privacy.messages_stay")
        ),
        TourStep(
            "bar", G.WELCOME, "Five tabs, one bar",
            "The bottom bar has Home, Accounts, Insights, Bills and Messages. The tour moves between them for you.",
            tab = T.HOME, anchor = A.NAV_BAR, covers = setOf("nav.tabs")
        ),
        TourStep(
            "eye", G.WELCOME, "Hide your amounts",
            "The eye in the top bar hides every amount on every screen. Tap a hidden amount, or the eye, to show them again after your screen lock.",
            tab = T.HOME, anchor = A.TOOLBAR_EYE,
            covers = setOf("settings.privacy_hide", "settings.authentication")
        ),

        // ---- Home
        TourStep(
            "home.balance", G.HOME, "Your total balance",
            "The big number adds up the bank accounts Zyke found in your messages. Tap it to open Accounts. A ticker under it follows any shares you choose in Settings.",
            tab = T.HOME, anchor = A.HOME_BALANCE,
            covers = setOf("home.total_balance", "home.market_strip", "settings.market_watch", "settings.market_any")
        ),
        TourStep(
            "home.budget", G.HOME, "Today's budget",
            "A safe amount to spend today, worked out from your salary and bills. Stay under it to build a streak. Tap Adjust to set your own.",
            tab = T.HOME, anchor = A.HOME_BUDGET, fallbackAnchor = A.HOME_BALANCE, needsData = true, preview = V.TODAY_BUDGET,
            covers = setOf("home.todays_budget", "home.streak", "home.adjust_budget")
        ),
        TourStep(
            "home.codes", G.HOME, "Codes and alerts",
            "A fresh OTP appears here with a Copy button until it expires. Bills and other things that need your attention sit in the same card.",
            tab = T.HOME, anchor = A.HOME_CODES, fallbackAnchor = A.HOME_BALANCE, needsData = true, preview = V.CODE,
            covers = setOf("home.codes_card", "home.copy_code", "home.attention_strip")
        ),
        TourStep(
            "home.review", G.HOME, "Needs review",
            "Possible duplicates, transfers between your own accounts and messages Zyke could not read are collected here. A minute of review keeps totals true.",
            tab = T.HOME, anchor = A.HOME_REVIEW, fallbackAnchor = A.HOME_BALANCE, needsData = true, preview = V.NEEDS_REVIEW,
            covers = setOf("home.needs_review", "messages.review", "messages.duplicates", "messages.transfers")
        ),
        TourStep(
            "home.plans", G.HOME, "What is coming up",
            "Next due and Plans list your bills and trips. Use the three dots on a row to Snooze, Dismiss, Mark as paid, or Pay bill when the message has a link.",
            tab = T.HOME, anchor = A.HOME_NEXT_DUE, fallbackAnchor = A.HOME_BALANCE, needsData = true, preview = V.PLAN_ROW,
            covers = setOf("home.plans", "home.snooze_dismiss", "home.mark_paid", "home.pay_bill")
        ),
        TourStep(
            "home.shortcuts", G.HOME, "Four shortcuts",
            "Calendar, Goals, Reminders and Can I afford it? Each opens a page of its own. Can I afford it? checks a purchase against what you have left.",
            tab = T.HOME, anchor = A.HOME_SHORTCUTS,
            covers = setOf("home.shortcut_calendar", "home.shortcut_goals", "home.shortcut_reminders", "home.shortcut_afford")
        ),
        TourStep(
            "home.calendar", G.HOME, "Calendar",
            "Bills, reminders, trips and warranty end dates on one calendar. Tap a day to see it, and add your own events.",
            tab = T.HOME, page = P.CALENDAR, covers = setOf("calendar", "calendar.warranties")
        ),
        TourStep(
            "home.goals", G.HOME, "Goals and reminders",
            "Set a goal such as a trip or a phone and add money towards it. Reminders can repeat monthly and show up in Bills.",
            tab = T.HOME, page = P.GOALS, tip = "Reminders is the shortcut next to Goals.",
            covers = setOf("goals", "reminders")
        ),
        TourStep(
            "home.add", G.HOME, "Add an entry",
            "+ Entry records cash or anything that came in no message. You can also use the Quick add widget, the quick-settings tile, or share text to Zyke.",
            tab = T.HOME, anchor = A.HOME_ADD,
            covers = setOf("add.entry_button", "add.widget", "add.quick_tile", "add.share_to_app")
        ),

        // ---- Accounts
        TourStep(
            "accounts.tabs", G.ACCOUNTS, "Accounts, Cards, Investments",
            "Switch between bank accounts, credit cards and investments. The big number above follows the tab: your bank total, what is billed on cards, or what you have invested.",
            tab = T.ACCOUNTS, anchor = A.ACCOUNTS_TABS, covers = setOf("accounts.tabs", "accounts.hero_number")
        ),
        TourStep(
            "accounts.deck", G.ACCOUNTS, "Your cards and accounts",
            "Credit cards form a deck you swipe through. Tap any account to fix its name, bank or type, or to leave it out of your totals.",
            tab = T.ACCOUNTS, anchor = A.ACCOUNTS_DECK, fallbackAnchor = A.ACCOUNTS_TABS, needsData = true, preview = V.ACCOUNT_CARD,
            covers = setOf("accounts.card_deck", "accounts.edit_account", "accounts.leave_out")
        ),
        TourStep(
            "accounts.networth", G.ACCOUNTS, "Net worth",
            "Pull down at the top of Accounts to reveal your net worth: what you own minus what you owe. Tap the card for the full page.",
            tab = T.ACCOUNTS, anchor = A.ACCOUNTS_TABS, needsData = true, preview = V.NET_WORTH,
            tip = "It stays hidden until you ask for it.", actionLabel = "Try it: pull down",
            covers = setOf("accounts.net_worth")
        ),

        // ---- Insights
        TourStep(
            "insights.month", G.INSIGHTS, "This month against last",
            "Spent and received this month, with last month beside it. Use the arrows to move back through earlier months.",
            tab = T.INSIGHTS, anchor = A.INSIGHTS_MONTH, fallbackAnchor = A.NAV_INSIGHTS, needsData = true, preview = V.MONTH_COMPARE,
            covers = setOf("insights.month_vs_last")
        ),
        TourStep(
            "insights.heading", G.INSIGHTS, "Where it is heading",
            "At your pace, Zyke estimates where the month ends. The salary strip shows how many days your salary has to last.",
            tab = T.INSIGHTS, anchor = A.INSIGHTS_FORECAST, fallbackAnchor = A.NAV_INSIGHTS, needsData = true, preview = V.HEADING,
            covers = setOf("insights.heading_salary")
        ),
        TourStep(
            "insights.report", G.INSIGHTS, "Report card, budgets, your year",
            "A simple grade for the month, bars for the budgets you set, and once there is history, a shareable Your year in review.",
            tab = T.INSIGHTS, anchor = A.INSIGHTS_REPORT_CARD, fallbackAnchor = A.NAV_INSIGHTS, needsData = true, preview = V.REPORT_CARD,
            covers = setOf("insights.report_card", "insights.budgets", "insights.year_in_review")
        ),
        TourStep(
            "insights.categories", G.INSIGHTS, "Spending by category",
            "See where the money goes: food, travel, shopping and more. Tap a category to see its payments.",
            tab = T.INSIGHTS, anchor = A.INSIGHTS_CATEGORIES, fallbackAnchor = A.NAV_INSIGHTS, needsData = true, preview = V.CATEGORY_BARS,
            covers = setOf("insights.categories")
        ),
        TourStep(
            "insights.subscriptions", G.INSIGHTS, "Subscriptions",
            "See what repeats, what it costs a year, and get the cancel helper: where to go and what to say to stop each one.",
            tab = T.INSIGHTS, anchor = A.INSIGHTS_SUBSCRIPTIONS, covers = setOf("insights.subscriptions", "insights.cancel_helper")
        ),
        TourStep(
            "insights.filter", G.INSIGHTS, "Filter and flip",
            "Filter by period, category, or source such as one card. The switch near the top flips between money spent and money received.",
            tab = T.INSIGHTS, anchor = A.TOOLBAR_FILTER_INSIGHTS, fallbackAnchor = A.NAV_INSIGHTS,
            covers = setOf("insights.filters", "insights.direction")
        ),

        // ---- Bills
        TourStep(
            "bills.due", G.BILLS, "Bills that are due",
            "Everything due in the next 30 days, soonest first. Mark a bill as paid, snooze it, or pay it from the link in its message.",
            tab = T.BILLS, anchor = A.BILLS_DUE, fallbackAnchor = A.NAV_BILLS, needsData = true, preview = V.BILL_ROW,
            covers = setOf("bills.due_list", "bills.mark_paid", "bills.snooze")
        ),
        TourStep(
            "bills.repeats", G.BILLS, "Repeats monthly",
            "Rent, EMIs and subscriptions that come back every month are spotted by themselves. Confirm the real ones and ignore the rest.",
            tab = T.BILLS, anchor = A.BILLS_REPEATS, fallbackAnchor = A.BILLS_FRIENDS, needsData = true, preview = V.REPEATS,
            covers = setOf("bills.repeats")
        ),
        TourStep(
            "bills.friends", G.BILLS, "Split with friends",
            "Split any payment from its detail, then see who owes you. Friends keeps a running balance for each person and lets you settle up.",
            tab = T.BILLS, anchor = A.BILLS_FRIENDS, needsData = true, preview = V.FRIEND_SPLIT,
            covers = setOf("bills.split", "bills.friends")
        ),

        // ---- Messages
        TourStep(
            "messages.feed", G.MESSAGES, "Your messages feed",
            "Every money message, grouped by day and by kind. Pin one to keep it on top, or archive it to tidy up.",
            tab = T.MESSAGES, anchor = A.MESSAGES_FEED, fallbackAnchor = A.NAV_MESSAGES, needsData = true, preview = V.MESSAGE_ROW,
            covers = setOf("messages.feed")
        ),
        TourStep(
            "messages.search", G.MESSAGES, "Search and filters",
            "Search the feed by word or amount. Filters narrow it by kind, bank, card or dates.",
            tab = T.MESSAGES, anchor = A.MESSAGES_SEARCH, fallbackAnchor = A.NAV_MESSAGES, covers = setOf("messages.search_filters")
        ),
        TourStep(
            "messages.detail", G.MESSAGES, "Open any message",
            "Tap a message to rename the merchant, pick a category (or make your own), and add a picture for it.",
            tab = T.MESSAGES, anchor = A.NAV_MESSAGES, needsData = true, preview = V.MESSAGE_DETAIL,
            covers = setOf("messages.detail", "messages.rename", "messages.category", "messages.custom_categories", "messages.picture")
        ),
        TourStep(
            "messages.detail2", G.MESSAGES, "Notes, tags and totals",
            "Add notes and tags, leave a payment out of totals or mark it a transfer, and link it to a goal.",
            tab = T.MESSAGES, anchor = A.NAV_MESSAGES, needsData = true, preview = V.MESSAGE_DETAIL,
            covers = setOf("messages.notes_tags", "messages.leave_out", "messages.goal_link")
        ),
        TourStep(
            "messages.teach", G.MESSAGES, "Teach the parser",
            "If a message is read wrongly, use Teach on it, or in Needs review, to show Zyke the right amount and merchant. It remembers that wording.",
            tab = T.MESSAGES, anchor = A.NAV_MESSAGES, covers = setOf("messages.teach_parser")
        ),

        // ---- Search
        TourStep(
            "search.open", G.SEARCH, "Search everything",
            "The magnifier searches messages, accounts, goals, reminders and events. What you searched for stays on this phone only.",
            tab = T.HOME, anchor = A.TOOLBAR_SEARCH, covers = setOf("search.global", "search.recent")
        ),
        TourStep(
            "search.page", G.SEARCH, "Jump anywhere",
            "Type a page name such as goals or privacy to jump straight there. Use the filters to look only at one kind of result.",
            tab = T.HOME, page = P.SEARCH, anchor = A.SEARCH_FIELD, covers = setOf("search.jump_to", "search.filters")
        ),

        // ---- Settings & privacy
        TourStep(
            "settings.open", G.SETTINGS, "Settings",
            "Everything is set here. Each area shows how it is set now, and a card on top lists what is left to set up.",
            tab = T.HOME, page = P.SETTINGS_HUB, anchor = A.SETTINGS_HUB_MONEY, covers = setOf("settings.hub")
        ),
        TourStep(
            "settings.money", G.SETTINGS, "Plan, salary and budgets",
            "Add your salary, or let Zyke spot it from your messages. Then set a monthly budget for any category, and one limit across your credit cards.",
            tab = T.HOME, page = P.SETTINGS_MONEY, anchor = A.SETTINGS_GROUP_BODY,
            covers = setOf("settings.plan_salary", "settings.salary_autodetect", "settings.budgets")
        ),
        TourStep(
            "settings.cards", G.SETTINGS, "Cards, categories and rules",
            "Name your cards and accounts, add statement days, and teach Zyke your own categories and rules. Everything you taught it is listed under Your rules.",
            tab = T.HOME, page = P.SETTINGS_CARDS, anchor = A.SETTINGS_GROUP_BODY,
            covers = setOf("settings.cards_accounts", "settings.categories_rules")
        ),
        TourStep(
            "settings.alerts", G.SETTINGS, "Reminders and alerts",
            "Choose a morning digest, a weekly summary, heads-ups before bills, and live alerts with a Copy button when an OTP arrives.",
            tab = T.HOME, page = P.SETTINGS_ALERTS, anchor = A.SETTINGS_GROUP_BODY,
            covers = setOf("settings.notifications", "settings.digest", "settings.weekly_summary", "settings.heads_up", "settings.otp_alerts")
        ),
        TourStep(
            "settings.privacy", G.SETTINGS, "Privacy and security",
            "Hide amounts and ask for your screen lock to show them, block screenshots, and turn on App lock so Zyke asks to unlock every time.",
            tab = T.HOME, page = P.SETTINGS_PRIVACY, anchor = A.SETTINGS_GROUP_BODY,
            covers = setOf("settings.privacy_hide", "settings.authentication", "settings.block_screenshots", "settings.app_lock")
        ),
        TourStep(
            "settings.appearance", G.SETTINGS, "Look and feel",
            "Pick light, dark or True black, an accent colour or your own, and a matching app icon colour.",
            tab = T.HOME, page = P.SETTINGS_APPEARANCE, anchor = A.SETTINGS_GROUP_BODY,
            covers = setOf("settings.theme", "settings.true_black", "settings.accent", "settings.app_icon")
        ),

        // ---- Backup
        TourStep(
            "backup.where", G.BACKUP, "Back up your setup",
            "Under Your data and about, back up your plan, goals and settings to this phone, your Google Drive, or a folder you pick, on a schedule.",
            tab = T.HOME, page = P.SETTINGS_ABOUT, anchor = A.SETTINGS_BACKUP,
            tip = "Messages are not backed up. Zyke reads them again on a new phone.",
            covers = setOf("backup.this_phone", "backup.drive", "backup.folder", "backup.schedule")
        ),
        TourStep(
            "backup.restore", G.BACKUP, "Restore a setup",
            "On a new phone, the Welcome screen restores from this phone, Google Drive or a file. You can also restore a file here any time. Your PIN protects it.",
            tab = T.HOME, page = P.SETTINGS_ABOUT, anchor = A.SETTINGS_BACKUP, covers = setOf("backup.restore_file")
        ),
        TourStep(
            "backup.logs", G.BACKUP, "Logs and the parsing report",
            "If something looks wrong, share logs from Developer options, or generate a Parsing report to see which messages were not understood.",
            tab = T.HOME, page = P.SETTINGS_ABOUT,
            tip = "Long-press the app icon on your launcher for Share logs.",
            covers = setOf("settings.share_logs", "settings.developer", "settings.parsing_report")
        ),

        // ---- Widgets & Watch
        TourStep(
            "extras.widgets", G.EXTRAS, "Widgets and quick tile",
            "Add Quick add and Your day widgets from your launcher. Cash presets save a spend in one tap, and the quick-settings tile opens Add entry.",
            tab = T.HOME, covers = setOf("widgets")
        ),
        TourStep(
            "extras.watch", G.EXTRAS, "Wear OS watch",
            "On a paired Wear OS watch, see today's budget and what is due next. The phone sends it a small summary and nothing else.",
            tab = T.HOME, covers = setOf("wear")
        ),
        TourStep(
            "extras.done", G.EXTRAS, "You are all set",
            "That is Zyke Message. Take this tour again any time from Settings, Help, and jump straight to any chapter.",
            tab = T.HOME, page = P.SETTINGS_HELP, anchor = A.SETTINGS_GROUP_BODY, covers = setOf("tour.settings_entry")
        )
    )
}
