package com.zyke.message.domain.cards

import com.zyke.message.domain.model.BankName

/** The company that runs the card's payment network. */
enum class CardNetwork(val label: String) {
    VISA("Visa"), MASTERCARD("Mastercard"), RUPAY("RuPay"), AMEX("Amex"), DINERS("Diners Club"), OTHER("Other")
}

/**
 * A card colour: a dark gradient (ARGB, top-left to bottom-right) that white text stays readable on. Every stop keeps
 * a contrast of at least 4.5:1 with white; a test checks it.
 */
data class CardTheme(val id: String, val label: String, val colors: List<Long>)

object CardThemes {
    val all: List<CardTheme> = listOf(
        CardTheme("midnight", "Midnight", listOf(0xFF2B3A67, 0xFF111936)),
        CardTheme("ocean", "Ocean", listOf(0xFF0B5A9E, 0xFF0A2F5C)),
        CardTheme("emerald", "Emerald", listOf(0xFF0E6B52, 0xFF08382C)),
        CardTheme("crimson", "Crimson", listOf(0xFFA3162B, 0xFF4A0A17)),
        CardTheme("violet", "Violet", listOf(0xFF5B3BA8, 0xFF2A1659)),
        CardTheme("ember", "Ember", listOf(0xFFB04A0B, 0xFF5C2204)),
        CardTheme("graphite", "Graphite", listOf(0xFF3E4450, 0xFF16191F)),
        CardTheme("teal", "Teal", listOf(0xFF0F6B73, 0xFF083A40))
    )

    fun byId(id: String): CardTheme? = all.firstOrNull { it.id == id }

    /** The chosen theme, else the one that suits the bank. */
    fun resolve(themeId: String, bank: String): CardTheme = byId(themeId) ?: auto(bank)

    /** A theme from the bank's own brand colours; unknown banks get a stable pick from the text. */
    fun auto(bank: String): CardTheme {
        val known = CardCatalog.bank(bank)
        val id = when (known) {
            BankName.HDFC -> "ocean"
            BankName.ICICI -> "ember"
            BankName.SBI -> "midnight"
            BankName.AXIS -> "crimson"
            BankName.KOTAK -> "crimson"
            BankName.AMERICAN_EXPRESS -> "teal"
            BankName.IDFC_FIRST -> "violet"
            BankName.YES_BANK -> "ocean"
            BankName.INDUSIND -> "crimson"
            BankName.FEDERAL_BANK -> "midnight"
            BankName.PNB, BankName.CANARA, BankName.BANK_OF_BARODA, BankName.INDIAN_BANK, BankName.KVB -> "graphite"
            else -> null
        }
        if (id != null) return byId(id)!!
        val text = bank.trim().lowercase()
        if (text.isEmpty()) return byId("graphite")!!
        return all[Math.floorMod(text.hashCode(), all.size)]
    }
}

/** What the app knows about Indian banks and their cards, so the form can suggest instead of asking for everything. */
object CardCatalog {
    /** Banks the form offers, as the person would say them. */
    val banks: List<String> = listOf(
        "HDFC Bank", "ICICI Bank", "State Bank of India", "Axis Bank", "Kotak Mahindra Bank", "IDFC FIRST Bank", "Yes Bank", "IndusInd Bank",
        "RBL Bank", "Federal Bank", "Bank of Baroda", "Punjab National Bank", "Canara Bank", "AU Small Finance Bank", "American Express",
        "Standard Chartered", "HSBC", "Citibank", "Union Bank of India", "Bank of India", "Indian Bank", "Karur Vysya Bank"
    )

    private val aliases: List<Pair<String, BankName>> = listOf(
        "hdfc" to BankName.HDFC, "icici" to BankName.ICICI, "state bank" to BankName.SBI, "sbi" to BankName.SBI,
        "axis" to BankName.AXIS, "kotak" to BankName.KOTAK, "idfc" to BankName.IDFC_FIRST, "yes bank" to BankName.YES_BANK,
        "indusind" to BankName.INDUSIND, "federal" to BankName.FEDERAL_BANK, "baroda" to BankName.BANK_OF_BARODA,
        "punjab national" to BankName.PNB, "pnb" to BankName.PNB, "canara" to BankName.CANARA, "indian bank" to BankName.INDIAN_BANK, "karur vysya" to BankName.KVB, "kvb" to BankName.KVB,
        "american express" to BankName.AMERICAN_EXPRESS, "amex" to BankName.AMERICAN_EXPRESS
    )

    /** The known bank a typed name refers to ("HDFC Bank", "hdfc", "SBI card"), or null. */
    fun bank(text: String): BankName? {
        val t = text.trim().lowercase()
        if (t.isEmpty()) return null
        return aliases.firstOrNull { (key, _) -> t.contains(key) }?.second
    }

    private val variants: Map<BankName, List<String>> = mapOf(
        BankName.HDFC to listOf("Regalia", "Regalia Gold", "Millennia", "Swiggy HDFC", "Diners Club Black", "Infinia", "MoneyBack+", "Tata Neu Infinity", "Pixel", "Freedom", "IndianOil"),
        BankName.ICICI to listOf("Amazon Pay ICICI", "Coral", "Rubyx", "Sapphiro", "Emeralde", "HPCL Super Saver", "MakeMyTrip", "Platinum Chip"),
        BankName.AXIS to listOf("Flipkart Axis", "Ace", "Magnus", "Neo", "Select", "Atlas", "My Zone", "Airtel Axis", "Vistara"),
        BankName.SBI to listOf("SimplyCLICK", "SimplySAVE", "Cashback", "Elite", "BPCL Octane", "IRCTC", "Prime"),
        BankName.KOTAK to listOf("Myntra Kotak", "League Platinum", "Zen Signature", "811 #DreamDifferent", "Royale Signature"),
        BankName.IDFC_FIRST to listOf("Wealth", "Millennia", "Select", "Classic", "Ashva", "Power"),
        BankName.YES_BANK to listOf("Marquee", "Prosperity Rewards Plus", "Elite", "Paisabazaar PaisaSave"),
        BankName.INDUSIND to listOf("Legend", "Platinum Aura Edge", "EazyDiner", "Tiger"),
        BankName.AMERICAN_EXPRESS to listOf("Platinum Travel", "Membership Rewards", "SmartEarn", "Platinum Reserve", "Gold Charge"),
        BankName.FEDERAL_BANK to listOf("Scapia", "Signet", "Celesta", "Imperio"),
        BankName.PNB to listOf("Platinum", "Rupay Select"),
        BankName.BANK_OF_BARODA to listOf("Easy", "Select", "Premier")
    )

    private val generic = listOf("Platinum", "Signature", "Rewards", "Cashback", "Travel", "Classic", "Titanium")

    /** Card names to suggest for a bank, those that contain what has been typed first. Free text is always allowed. */
    fun variantSuggestions(bank: String, typed: String = "", limit: Int = 8): List<String> {
        val pool = (bank(bank)?.let { variants[it] }.orEmpty() + generic).distinct()
        val q = typed.trim()
        if (q.isEmpty()) return pool.take(limit)
        return pool.filter { it.contains(q, ignoreCase = true) && !it.equals(q, ignoreCase = true) }.take(limit)
    }

    /** Banks that contain what has been typed, for the searchable picker; blank gives them all. */
    fun bankSuggestions(typed: String): List<String> {
        val q = typed.trim()
        return if (q.isEmpty()) banks else banks.filter { it.contains(q, ignoreCase = true) }
    }

    /** The network a card most likely runs on, from its bank and name; null when it cannot be told. */
    fun guessNetwork(bank: String, variant: String): CardNetwork? {
        val v = variant.lowercase()
        return when {
            "rupay" in v -> CardNetwork.RUPAY
            "diners" in v -> CardNetwork.DINERS
            "amex" in v || bank(bank) == BankName.AMERICAN_EXPRESS -> CardNetwork.AMEX
            "mastercard" in v -> CardNetwork.MASTERCARD
            "visa" in v -> CardNetwork.VISA
            else -> null
        }
    }
}
