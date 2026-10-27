package com.zyke.message.domain.model

enum class MessageCategory(val displayString: String) {
    ENTERTAINMENT("Entertainment"),
    FOOD("Food"),
    UPI_PAYMENT("UPI Payment"),
    FUEL("Fuel Spends"),
    SHOPPING("Shopping"),
    UTILITIES("Utilities"),
    CREDIT_CARDS("Credit cards"),
    PAY_LATER("Pay later Services"),
    LOYALTY_PROGRAMMES("Loyalty Programmes"),
    COURIER("Courier Services"),
    OTP("OTP"),
    FASTAG("Fastag"),
    NETWORK_CARRIERS("Network Carrier"),
    TRAVEL("Travel"),
    GROCERIES("Groceries"),
    HEALTH("Health"),
    INVESTMENTS("Investments"),
    SAVINGS("Savings"),
    INSURANCE("Insurance"),
    CHARITY("Charity"),
    EDUCATION("Education"),
    FAMILY("Family & gifts"),
    OTHERS("Others");

    companion object {
        /** What a person can file a sender or receiver under; OTP and "Others" are not their call. */
        val assignable: List<MessageCategory> = entries.filter { it != OTP && it != OTHERS }
    }
}
