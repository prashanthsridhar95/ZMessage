package com.zyke.message.domain.places

import com.zyke.message.domain.categories.PayeeKey

/**
 * A best-effort list of payees where "where" means nothing: online shops, delivery and streaming apps, app stores,
 * subscriptions and mandates. A text for one of these is not given a place unless the person said so for that payee.
 * Wrong in both directions sometimes (a Swiggy Instamart order is placed from home; a Google Play top-up from anywhere),
 * which is why the person can override it per payee.
 */
object OnlineMerchants {
    /** Words of a payee name that mark an online-only business. Matched as whole words of the folded payee key. */
    private val NAMES = listOf(
        "amazon", "amazonpay", "amzn", "flipkart", "myntra", "ajio", "nykaa", "meesho", "snapdeal", "tata cliq", "jiomart", "bigbasket", "blinkit",
        "zepto", "swiggy", "zomato", "dunzo", "netflix", "hotstar", "disney", "prime video", "spotify", "gaana", "jiosaavn", "youtube",
        "google play", "googleplay", "google one", "google cloud", "google ads", "apple", "itunes", "icloud", "microsoft", "xbox", "playstation",
        "steam", "openai", "chatgpt", "anthropic", "github", "linkedin", "zoom", "canva", "adobe", "dropbox", "notion", "uber one",
        "irctc", "redbus", "makemytrip", "goibibo", "cleartrip", "easemytrip", "ixigo", "yatra", "bookmyshow", "razorpay", "payu", "billdesk", "cashfree", "juspay", "instamojo", "ccavenue", "olacabs", "ola money", "airtel", "jio", "vodafone idea",
        "dream11", "zerodha", "groww", "upstox", "lic", "simpl", "lazypay", "cred"
    )

    /** Phrases in a text that mark a recurring, remote charge. */
    private val RECURRING = Regex("(?i)\\b(?:mandate|auto\\s?pay|autopay|e-?mandate|nach|subscription|recurring|standing instruction|emi)\\b")

    private val WORDS = NAMES.map { name -> name to Regex("(?:^| )" + Regex.escape(name) + "(?: |$)") }

    /** True when [payee] (or the text it came from) reads as an online-only payee. */
    fun isOnline(payee: String, body: String = ""): Boolean {
        if (RECURRING.containsMatchIn(body)) return true
        val key = PayeeKey.of(payee) ?: return false
        if (PayeeKey.isVpa(payee)) {
            // A merchant's UPI handle: "netflix@icici", "swiggy.rzp@axisbank", "amazonpay@apl".
            val handle = key.substringBefore('@').replace('.', ' ').replace('-', ' ').replace('_', ' ')
            return WORDS.any { (_, rx) -> rx.containsMatchIn(handle) }
        }
        return WORDS.any { (_, rx) -> rx.containsMatchIn(key) }
    }
}
