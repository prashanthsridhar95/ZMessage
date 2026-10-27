package com.zyke.message.domain.classification

/**
 * Websites of well-known senders and merchants, so a logo can be looked up for them. This list is the only
 * thing ever used to ask a network for a picture: names from messages are matched against it locally and
 * only the listed domain is sent, never message text, amounts or numbers.
 */
object KnownEntityDomains {
    private val DOMAINS: List<Pair<String, String>> = listOf(
        "swiggy" to "swiggy.com", "zomato" to "zomato.com", "zepto" to "zeptonow.com", "blinkit" to "blinkit.com",
        "bigbasket" to "bigbasket.com", "dominos" to "dominos.co.in", "domino's" to "dominos.co.in",
        "mcdonald" to "mcdonalds.com", "starbucks" to "starbucks.in", "kfc" to "kfc.co.in", "burger king" to "burgerking.in",
        "amazon" to "amazon.in", "flipkart" to "flipkart.com", "myntra" to "myntra.com", "ajio" to "ajio.com",
        "nykaa" to "nykaa.com", "lenskart" to "lenskart.com", "croma" to "croma.com", "reliance" to "relianceretail.com",
        "netflix" to "netflix.com", "spotify" to "spotify.com", "youtube" to "youtube.com", "hotstar" to "hotstar.com",
        "google play" to "play.google.com", "google" to "google.com", "apple" to "apple.com", "microsoft" to "microsoft.com",
        "anthropic" to "anthropic.com", "openai" to "openai.com", "zoom" to "zoom.us",
        "uber" to "uber.com", "ola" to "olacabs.com", "rapido" to "rapido.bike", "irctc" to "irctc.co.in",
        "makemytrip" to "makemytrip.com", "goibibo" to "goibibo.com", "indigo" to "goindigo.in", "air india" to "airindia.com",
        "redbus" to "redbus.in", "fastag" to "npci.org.in",
        "phonepe" to "phonepe.com", "paytm" to "paytm.com", "gpay" to "pay.google.com", "bhim" to "bhimupi.org.in",
        "hdfc" to "hdfcbank.com", "icici" to "icicibank.com", "axis" to "axisbank.com", "kotak" to "kotak.com",
        "sbi" to "sbi.co.in", "state bank" to "sbi.co.in", "indusind" to "indusind.com", "yes bank" to "yesbank.in",
        "idfc" to "idfcfirstbank.com", "federal bank" to "federalbank.co.in", "indian bank" to "indianbank.in",
        "canara" to "canarabank.com", "bank of baroda" to "bankofbaroda.in", "punjab national" to "pnbindia.in",
        "american express" to "americanexpress.com", "amex" to "americanexpress.com",
        "airtel" to "airtel.in", "jio" to "jio.com", "bsnl" to "bsnl.co.in", "vodafone" to "myvi.in", "tata play" to "tataplay.com",
        "tangedco" to "tnebnet.org", "bescom" to "bescom.karnataka.gov.in", "lic" to "licindia.in",
        "mahindra manulife" to "mahindramanulife.com", "bajaj finserv" to "bajajfinserv.in", "zerodha" to "zerodha.com",
        "groww" to "groww.in", "kfintech" to "kfintech.com", "nse" to "nseindia.com", "bse" to "bseindia.com"
    )

    /** The website for [name], or null when it is not a name we know. */
    fun domainFor(name: String): String? {
        val text = " ${name.lowercase().replace(Regex("[^a-z0-9' ]+"), " ").replace(Regex("\\s+"), " ").trim()} "
        return DOMAINS.firstOrNull { (keyword, _) -> " $keyword " in text || (keyword.length > 4 && keyword in text) }?.second
    }
}
