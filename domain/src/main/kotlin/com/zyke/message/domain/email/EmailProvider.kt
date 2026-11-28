package com.zyke.message.domain.email

/**
 * The only mail providers the app can talk to. The host is part of the choice: there is no field to type a server name, so the list of
 * hosts the app can ever connect to is exactly the one below (a test pins it, with the privacy page and docs). The connection is
 * always TLS from the first byte on port [PORT]: there is no plain or STARTTLS mode.
 *
 * What each provider needs is written from the providers' own help pages as the developers remember them and has NOT been run
 * against a live account (see docs/PRIVACY.md): IMAP switches, app-password menus and whether a provider still accepts an app
 * password for IMAP change without notice.
 */
enum class EmailProvider(
    val id: String,
    val label: String,
    val host: String,
    /** Two or three plain lines on making an app password, no link to open. */
    val appPasswordSteps: List<String>,
    /** A warning to show beside the choice, or empty. */
    val caution: String = ""
) {
    GMAIL(
        "gmail", "Gmail", "imap.gmail.com",
        listOf(
            "1. In your Google Account, Security, turn on 2-Step Verification.",
            "2. Search \"App passwords\" in the Google Account page and create one called Zyke.",
            "3. Paste the 16 letters here. Spaces do not matter."
        )
    ),
    OUTLOOK(
        "outlook", "Outlook", "outlook.office365.com",
        listOf(
            "1. In your Microsoft account, Security, turn on two-step verification.",
            "2. Choose App passwords and create one called Zyke.",
            "3. Paste it here."
        ),
        caution = "Microsoft has been switching off password sign-in for some Outlook and Microsoft 365 accounts. If Connect says the provider blocked it, this provider cannot be used."
    ),
    YAHOO(
        "yahoo", "Yahoo", "imap.mail.yahoo.com",
        listOf(
            "1. In Yahoo Account Security, turn on two-step verification.",
            "2. Choose Generate app password, name it Zyke.",
            "3. Paste the password here."
        )
    ),
    ICLOUD(
        "icloud", "iCloud", "imap.mail.me.com",
        listOf(
            "1. Sign in at appleid.apple.com with two-factor authentication on.",
            "2. Under Sign-In and Security choose App-Specific Passwords and make one called Zyke.",
            "3. Paste it here, with your full iCloud address."
        )
    );

    companion object {
        /** TLS from the first byte; the only port the app uses. */
        const val PORT = 993

        /** Every host the app can connect to for mail. */
        val hosts: Set<String> = entries.map { it.host }.toSet()

        fun fromId(id: String): EmailProvider? = entries.firstOrNull { it.id == id }
    }
}
