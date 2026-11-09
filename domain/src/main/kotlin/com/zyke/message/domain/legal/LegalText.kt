package com.zyke.message.domain.legal

/** One paragraph of a section: an optional bold lead-in and the text under it. */
data class LegalItem(val heading: String?, val body: String)

/** A titled part of the Privacy and terms page: a short introduction and the items under it. */
data class LegalSection(val id: String, val title: String, val intro: String, val items: List<LegalItem> = emptyList())

/**
 * The words of the Privacy and terms page, as plain data so they can be tested: every manifest permission is explained,
 * every network host is named, and nothing here depends on Android.
 *
 * This text is a plain-language description of what the app does and the rules for using it. It is not legal advice and has
 * not been reviewed by a lawyer; docs/LEGAL_NOTES.md says what still has to be decided before a public release.
 */
object LegalText {
    const val TITLE = "Privacy and terms"

    /** The Android permissions the manifest asks for (the part after `android.permission.`), each explained in [sections]. */
    val permissions: List<String> = listOf(
        "READ_SMS", "RECEIVE_SMS", "SEND_SMS", "INTERNET", "POST_NOTIFICATIONS", "RECEIVE_BOOT_COMPLETED",
        "FOREGROUND_SERVICE", "FOREGROUND_SERVICE_DATA_SYNC", "POST_PROMOTED_NOTIFICATIONS"
    )

    const val HOST_GOOGLE = "www.google.com"
    const val HOST_DUCKDUCKGO = "icons.duckduckgo.com"
    const val HOST_YAHOO_PRICES = "query1.finance.yahoo.com"
    const val HOST_YAHOO_SEARCH = "query2.finance.yahoo.com"
    const val HOST_FLIGHTRADAR = "api.flightradar24.com"
    const val HOST_GOOGLE_APIS = "www.googleapis.com"

    /**
     * Every fixed internet address the app can ask. A test compares this with the hosts written in the code, so the page cannot
     * drift from it. (A brand's own website is also asked for its icon, from a fixed list of well-known brands in the app.)
     */
    val networkHosts: Set<String> = setOf(
        HOST_GOOGLE, HOST_DUCKDUCKGO, HOST_YAHOO_PRICES, HOST_YAHOO_SEARCH, HOST_FLIGHTRADAR, HOST_GOOGLE_APIS
    )

    private val onPhone = LegalSection(
        id = "on-phone",
        title = "What stays on your phone",
        intro = "${LegalInfo.APP_NAME} has no account, no advertising, no analytics and no crash reporter. It reads your texts on the " +
            "phone and works out payments, bills and codes there.",
        items = listOf(
            LegalItem("Your messages", "Message text is read from the phone's SMS store and kept in memory. A copy is also kept in a database on the phone where every entry is encrypted with a key that never leaves the phone's secure hardware. Message text is never sent to us or to anyone else."),
            LegalItem("What you enter", "Budgets, cards (last four digits only), plans, goals, reminders, notes, names and settings are saved in the app's private storage."),
            LegalItem("Cookies", "The app sets no cookies and has no cookie banner. One exception: if you choose \"From the web\" to find a picture for a sender, a Google Images page opens inside the app and Google's page may use its own cookies, as it would in a browser."),
            LegalItem("Logs", "A small log of start-up steps and crash details (screen and function names, never message text, amounts or names) stays on the phone. It leaves only if you choose Share logs and pick an app.")
        )
    )

    private val leaves = LegalSection(
        id = "leaves",
        title = "What leaves the phone, and when",
        intro = "Only the features below use the internet or send a text. Each is described with exactly what is sent and to whom. " +
            "None of them ever sends message text, amounts or account numbers.",
        items = listOf(
            LegalItem(
                "Brand logos (on by default, can be turned off in Settings)",
                "To show a bank's or airline's logo, the app asks a public icon service for the logo of a well-known website, so the website name (for example hdfcbank.com) is sent. It asks the brand's own website, then $HOST_GOOGLE, then $HOST_DUCKDUCKGO. Your phone's address is visible to those servers, as with any web request. The logos belong to their owners and are shown only to identify the sender."
            ),
            LegalItem(
                "Picture for a sender (only when you tap \"From the web\")",
                "A Google Images search for the sender's name opens inside the app on $HOST_GOOGLE, as if you had typed the name into Google. The picture you tap is downloaded once."
            ),
            LegalItem(
                "Market prices (off until you switch them on)",
                "The tickers in your watch list, and the letters you type into the add-stock box while you type, go to Yahoo Finance ($HOST_YAHOO_PRICES for prices and $HOST_YAHOO_SEARCH for name suggestions). What you type is not stored."
            ),
            LegalItem(
                "Live flight status (off until you switch it on)",
                "An airline code and flight number, such as 6E2345, go to Flightradar24 ($HOST_FLIGHTRADAR). The day, the booking reference, your name and message text are never sent. While a flight is about to leave or in the air, the app asks again every 30 seconds."
            ),
            LegalItem(
                "Backup to your own Google Drive (off until you switch it on and sign in)",
                "One file, encrypted on the phone with a key made from your PIN or passphrase, is uploaded to the private app folder of your own Google Drive ($HOST_GOOGLE_APIS, scope drive.appdata, which cannot see anything else in your Drive). Google receives the encrypted file and your sign-in; we receive nothing. The file holds your setup, never messages. Android's own Auto Backup may also keep that one encrypted file in your Google account."
            ),
            LegalItem(
                "Texts the app sends, only when you confirm",
                "Sharing codes: if you switch on \"Share codes\" and add people, a login code from an allowed service (for example a streaming service) is texted from your SIM to those people, with the service name and nothing else. Bank, payment, government and other sensitive codes are never sent. Check balance by SMS: when you tap Send, after a screen that shows the exact number and words, one text goes from your SIM to your bank's enquiry number. These are ordinary texts, not internet requests; your mobile plan may charge for them."
            ),
            LegalItem(
                "Contact picker",
                "When you add a person for code sharing you can choose one from your contacts. The app uses the phone's own picker and receives only the one name and number you pick; it has no permission to read your contacts."
            ),
            LegalItem(
                "Files and reports you share",
                "Exports, backups and the parsing report go nowhere unless you pick an app in the share sheet. Check a file before you send it."
            )
        )
    )

    private val permissionsSection = LegalSection(
        id = "permissions",
        title = "Permissions and why",
        intro = "Android asks you for the sensitive ones when they are needed. You can take any of them back in the phone's settings.",
        items = listOf(
            LegalItem("READ_SMS", "Reads the texts on the phone so they can be organised into payments, bills and codes. The app never writes to or deletes your messages."),
            LegalItem("RECEIVE_SMS", "Hears about a new text the moment it arrives, for live alerts and code sharing. Off until you choose those features. The text is read in memory and dropped."),
            LegalItem("SEND_SMS", "Sends a text from your SIM, only for \"Share codes\" and \"Check balance by SMS\", asked for when you first use one. Because this permission can cost money and be misused, Google Play restricts it, so this app is installed directly rather than from Google Play."),
            LegalItem("INTERNET", "Used only by the features listed under \"What leaves the phone\", to the hosts named there."),
            LegalItem("POST_NOTIFICATIONS", "Shows reminders, code buttons, the morning summary and the flight notification. Optional."),
            LegalItem("RECEIVE_BOOT_COMPLETED", "Puts your reminder and morning-summary alarms back after the phone restarts."),
            LegalItem("FOREGROUND_SERVICE and FOREGROUND_SERVICE_DATA_SYNC", "Keep two jobs going with a visible notification: reading a very large inbox month by month, and (only with live flight status on) following a flight that is about to leave or in the air."),
            LegalItem("POST_PROMOTED_NOTIFICATIONS", "Lets the flight notification appear as a Live Update on Android 16 and later. It gives no access to anything."),
            LegalItem("Notification access (a system setting, not a normal permission)", "If you allow it, the app reads incoming chat (RCS) notifications from your messaging app so those messages can be organised too. It is off until you allow it in the phone's settings and keeps up to 2000 messages on the phone.")
        )
    )

    private val control = LegalSection(
        id = "control",
        title = "Your control",
        intro = "You decide what the app does.",
        items = listOf(
            LegalItem("Hide amounts", "Shows every amount as dots, in the app, in notifications and in the widget, until you confirm with your screen lock."),
            LegalItem("App lock and screenshots", "Ask for your fingerprint, face or screen lock when you return to the app, and block screenshots and the recent-apps preview."),
            LegalItem("Switch features off", "Logos, market prices, flight status, live alerts, code sharing and backups can each be switched off in Settings. Reading messages can be paused."),
            LegalItem("Backups", "Backups are off until you switch them on. Switching Drive backup off does not delete files already in your Drive; remove them in Google Drive settings, Manage apps."),
            LegalItem("Delete all my data", "Settings, Your data and about, Delete all my data removes everything the app has saved on the phone, after you type a word to confirm. It cannot remove a copy that Android or Google already holds, or a file you shared."),
            LegalItem("Demo mode", "Shows the app with sample data. It reads none of your messages and sends no requests for logos, prices or flights.")
        )
    )

    private val terms = LegalSection(
        id = "terms",
        title = "Terms of use",
        intro = "By installing or using ${LegalInfo.APP_NAME} you agree to these terms. They are written in plain language.",
        items = listOf(
            LegalItem("As is", "${LegalInfo.APP_NAME} is provided \"as is\", without a promise that it is correct, complete, always available or fit for a particular purpose."),
            LegalItem("Not advice", "The app is not financial, investment, legal or tax advice. Totals, budgets, forecasts and suggestions are aids, not recommendations."),
            LegalItem("Read from texts, so it can be wrong", "Amounts, balances, dates, categories, bills and flights are worked out from your SMS by rules. A text can be missing, late, forged or worded in a way the app does not understand. Check anything that matters against your bank or provider before you rely on it."),
            LegalItem("Features that send texts", "Sharing codes and checking a balance by SMS send real texts from your SIM. You use them at your own risk, and carrier charges apply."),
            LegalItem("Share codes only with people you trust", "A login code lets someone sign in to an account. Only add people you trust and only for accounts you have the right to share with them. You are responsible for what you share, and for following the rules of the services concerned."),
            LegalItem("Third-party services", "Logos, prices, flight status, Google sign-in and Google Drive come from other companies, and their own terms and privacy policies apply when you use those features. Their data can be late, wrong or stop without notice. Brand names and logos belong to their owners."),
            LegalItem("Your responsibility", "Keep your phone, screen lock, backup PIN and passphrase safe. A forgotten backup PIN or passphrase cannot be recovered by anyone."),
            LegalItem("Limit of liability", "To the extent the law allows, the publisher is not liable for any loss or damage that comes from using or not being able to use the app, including money lost, a missed payment, a wrong figure, an unwanted text or its cost. Nothing here limits any right you have by law that cannot be limited."),
            LegalItem("Changes", "These terms and this page may change. The date and version at the end say which text is current; using the app after a change means you accept it.")
        )
    )

    private val age = LegalSection(
        id = "age",
        title = "Minimum age",
        intro = "${LegalInfo.APP_NAME} is for people ${LegalInfo.MINIMUM_AGE} and over. No data about children is collected on purpose, and the app asks for no age or date of birth."
    )

    private val refunds = LegalSection(
        id = "refunds",
        title = "Refunds",
        intro = "Zyke is free and has no purchases; there is nothing to refund."
    )

    private val contact: LegalSection
        get() = LegalSection(
            id = "contact",
            title = "Contact and business details",
            intro = "Published by ${LegalInfo.OPERATOR_NAME}.",
            items = buildList {
                if (LegalInfo.OPERATOR_ADDRESS.isNotBlank()) add(LegalItem("Address", LegalInfo.OPERATOR_ADDRESS))
                add(LegalItem("Email", LegalInfo.CONTACT_EMAIL))
                add(LegalItem("Privacy questions and requests", "Because the app keeps your data on your phone, the publisher holds none of it and has nothing to look up or delete on a server. Email this address with any question, or to report a security problem; please do not include message text."))
                add(LegalItem("This text", "Version ${LegalInfo.TEXT_VERSION}, last updated ${LegalInfo.LAST_UPDATED}."))
            }
        )

    /** The sections in the order the page shows them. */
    val sections: List<LegalSection>
        get() = listOf(onPhone, leaves, permissionsSection, control, terms, age, refunds, contact)

    val lastUpdatedLine: String get() = "Version ${LegalInfo.TEXT_VERSION}, last updated ${LegalInfo.LAST_UPDATED}"

    /** All the words of the page as one string, for tests and for anything that needs to search it. */
    fun fullText(): String = buildString {
        append(TITLE).append('\n').append(lastUpdatedLine).append('\n')
        sections.forEach { section ->
            append(section.title).append('\n').append(section.intro).append('\n')
            section.items.forEach { item -> item.heading?.let { append(it).append('\n') }; append(item.body).append('\n') }
        }
    }
}
