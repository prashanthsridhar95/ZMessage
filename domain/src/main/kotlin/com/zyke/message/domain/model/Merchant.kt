package com.zyke.message.domain.model

/** Identifies a merchant logo without depending on Android resources. */
enum class MerchantLogo(val isTinted: Boolean = false) {
    UPI(isTinted = true),
    PERSON(isTinted = true),
    HDFC,
    FEDERAL_BANK,
    AXIS_BANK,
    BLUE_DART,
    FASTAG,
    BROADWAY_CINEMAS,
    SWIGGY,
    TATA_PLAY,
    BOOMERANG,
    DINEOUT,
    SKYLINK,
    FUEL,
    AMAZON,
    BAKERY,
    TEA,
    SIMPL,
    LAZYPAY,
    SWIRLYO,
    BURGERMAN,
    AMEX,
    LENSKART,
    AIRTEL,
    BSNL,
    JIO,
    VI
}

data class Merchant(
    val name: String,
    val logo: MerchantLogo,
    val category: MessageCategory,
    /** Set when the person has chosen a picture for this sender / receiver; see the image store. */
    val imageKey: String? = null
) : java.io.Serializable
