package com.zyke.message.domain.classification

import com.zyke.message.domain.model.Merchant
import com.zyke.message.domain.model.MerchantLogo
import com.zyke.message.domain.model.MessageCategory

/**
 * Maps free text (sender, payee, message body) to a known [Merchant].
 * Rules are evaluated top to bottom; the first match wins.
 */
class MerchantResolver {

    private class Rule(
        val keywords: List<String>,
        val logo: MerchantLogo,
        val name: String?,
        val category: MessageCategory,
        val upiAware: Boolean = false,
        val upiCategory: MessageCategory = MessageCategory.UPI_PAYMENT
    )

    private val rules = listOf(
        Rule(listOf("Zerodha", "Groww", "Upstox", "Kuvera", "Mutual Fund", "Smallcase", "Coin by"), MerchantLogo.PERSON, null, MessageCategory.INVESTMENTS),
        Rule(listOf("Recurring Deposit", "Fixed Deposit", "PPF", "Sukanya", "National Pension"), MerchantLogo.PERSON, null, MessageCategory.SAVINGS),
        Rule(listOf("LIC of India", "Insurance", "Policybazaar", "Acko"), MerchantLogo.PERSON, null, MessageCategory.INSURANCE),
        Rule(listOf("Donat", "Charit", "Foundation", "Akshaya Patra", "GiveIndia", "Ketto", "Milaap"), MerchantLogo.PERSON, null, MessageCategory.CHARITY),
        Rule(listOf("School", "College", "University", "Tuition", "Byju", "Unacademy"), MerchantLogo.PERSON, null, MessageCategory.EDUCATION),
        Rule(listOf("HDFC"), MerchantLogo.HDFC, "HDFC", MessageCategory.CREDIT_CARDS, upiAware = true),
        Rule(
            listOf("Federal Bank", "FEDBNK"), MerchantLogo.FEDERAL_BANK, "Federal Bank",
            MessageCategory.UPI_PAYMENT, upiAware = true
        ),
        Rule(
            listOf("Axis Bank", "AXIS"), MerchantLogo.AXIS_BANK, "Axis Bank",
            MessageCategory.CREDIT_CARDS, upiAware = true
        ),
        Rule(listOf("Blue dart", "BLUDRT"), MerchantLogo.BLUE_DART, "Blue dart", MessageCategory.COURIER),
        Rule(listOf("Fastag"), MerchantLogo.FASTAG, "Fastag", MessageCategory.FASTAG),
        Rule(listOf("BROADWAY"), MerchantLogo.BROADWAY_CINEMAS, "Broadway Cinemas", MessageCategory.ENTERTAINMENT),
        Rule(listOf("SWIGGY"), MerchantLogo.SWIGGY, "Swiggy", MessageCategory.FOOD),
        Rule(listOf("TATA PLAY", "TPLYSV", "TPPLAY"), MerchantLogo.TATA_PLAY, "Tata Play", MessageCategory.UTILITIES),
        Rule(listOf("Boomerang"), MerchantLogo.BOOMERANG, "Boomerang", MessageCategory.FOOD),
        Rule(listOf("Dineout"), MerchantLogo.DINEOUT, "Swiggy Dineout", MessageCategory.FOOD),
        Rule(listOf("Skyltd"), MerchantLogo.SKYLINK, "Skylink", MessageCategory.NETWORK_CARRIERS),
        Rule(listOf("FUEL"), MerchantLogo.FUEL, null, MessageCategory.FUEL),
        Rule(listOf("amazon"), MerchantLogo.AMAZON, "Amazon", MessageCategory.SHOPPING),
        Rule(listOf("Bakery"), MerchantLogo.BAKERY, null, MessageCategory.FOOD),
        Rule(listOf("Karuppatti"), MerchantLogo.TEA, null, MessageCategory.FOOD),
        Rule(listOf("Simpl", "SMPL"), MerchantLogo.SIMPL, "Simpl", MessageCategory.PAY_LATER),
        Rule(listOf("Lazypay"), MerchantLogo.LAZYPAY, "Lazypay", MessageCategory.PAY_LATER),
        Rule(listOf("Swirlyo"), MerchantLogo.SWIRLYO, "Swirlyo", MessageCategory.FOOD),
        Rule(listOf("Burgerman"), MerchantLogo.BURGERMAN, "Burgerman", MessageCategory.FOOD),
        Rule(listOf("American Express", "AMEX"), MerchantLogo.AMEX, "American Express", MessageCategory.CREDIT_CARDS),
        Rule(listOf("Lenskart", "LNSKRT", "LENSKT"), MerchantLogo.LENSKART, "Lenskart", MessageCategory.SHOPPING),
        Rule(listOf("Airtel"), MerchantLogo.AIRTEL, "Airtel", MessageCategory.NETWORK_CARRIERS),
        Rule(listOf("BSNL"), MerchantLogo.BSNL, "BSNL", MessageCategory.NETWORK_CARRIERS),
        Rule(listOf("Jio"), MerchantLogo.JIO, "Jio", MessageCategory.NETWORK_CARRIERS),
        Rule(listOf("Vi Mobile"), MerchantLogo.VI, "Vi", MessageCategory.NETWORK_CARRIERS)
    )

    /**
     * @param categoryOverride forces the category (e.g. a loyalty programme resolved
     * through its merchant logo still belongs to LOYALTY_PROGRAMMES).
     */
    fun resolve(text: String, categoryOverride: MessageCategory? = null): Merchant {
        val isUpi = UPI_PATTERN.matches(text)
        val rule = rules.firstOrNull { rule -> rule.keywords.any { text.contains(it, ignoreCase = true) } }

        if (rule != null) {
            return if (rule.upiAware && isUpi) {
                Merchant(text, MerchantLogo.UPI, categoryOverride ?: rule.upiCategory)
            } else {
                Merchant(rule.name ?: text, rule.logo, categoryOverride ?: rule.category)
            }
        }

        return if (isUpi) {
            Merchant(text, MerchantLogo.UPI, categoryOverride ?: MessageCategory.UPI_PAYMENT)
        } else {
            Merchant(text, MerchantLogo.PERSON, categoryOverride ?: MessageCategory.OTHERS)
        }
    }

    private companion object {
        val UPI_PATTERN = Regex("[*a-zA-Z0-9.\\-_]{2,49}@[a-zA-Z._]{2,49}")
    }
}
