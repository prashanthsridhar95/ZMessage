package com.zyke.message.domain.insights

import java.time.format.DateTimeFormatter
import java.util.Locale

/** How a subscription is being paid, which decides where it is stopped. */
enum class PaymentChannel(val label: String) {
    UPI_AUTOPAY("UPI AutoPay"),
    CARD_EMANDATE("Card e-mandate"),
    STANDING_INSTRUCTION("Bank standing instruction"),
    APP_STORE("Google Play or app store"),
    DIRECT("Paid to the merchant")
}

/** Works out the channel from the words of the payment's own messages. The text is only read here, never kept or shown. */
object PaymentChannels {
    private val STORE = Regex("google play|play\\.google|playstore|play store|itunes|apple\\.com/bill|app store|appstore|apple services")
    private val UPI_MANDATE = Regex("autopay|auto pay|auto-pay|upi mandate|mandate")
    private val BANK_MANDATE = Regex("standing instruction|\\bnach\\b|\\becs\\b|auto debit|auto-debit|autodebit|e-mandate|emandate")

    fun infer(texts: List<String>, isCard: Boolean): PaymentChannel {
        val text = texts.joinToString(" ").lowercase()
        return when {
            STORE.containsMatchIn(text) -> PaymentChannel.APP_STORE
            !isCard && "upi" in text && UPI_MANDATE.containsMatchIn(text) -> PaymentChannel.UPI_AUTOPAY
            isCard -> PaymentChannel.CARD_EMANDATE
            BANK_MANDATE.containsMatchIn(text) -> PaymentChannel.STANDING_INSTRUCTION
            UPI_MANDATE.containsMatchIn(text) && "upi" !in text -> PaymentChannel.STANDING_INSTRUCTION
            else -> PaymentChannel.DIRECT
        }
    }
}

/** Plain steps for stopping a subscription, chosen by [PaymentChannel]. */
data class CancelGuide(
    val channel: PaymentChannel,
    val title: String,
    val steps: List<String>,
    val tip: String,
    /** Whether the page for Google Play subscriptions is worth offering as a one-tap link. */
    val offersPlayLink: Boolean
)

object CancelGuides {
    /** Opened by the phone's browser or Play Store; the app itself never fetches it. */
    const val PLAY_SUBSCRIPTIONS_URL = "https://play.google.com/store/account/subscriptions"

    private const val COMMON_TIP = "Stopping the payment may not close your account with them. Check their app or site shows the plan ending."

    fun guide(channel: PaymentChannel, merchant: String): CancelGuide {
        val name = merchant.ifBlank { "the merchant" }
        return when (channel) {
            PaymentChannel.UPI_AUTOPAY -> CancelGuide(
                channel, "Stop a UPI AutoPay",
                listOf(
                    "Open the UPI app you set it up with (Google Pay, PhonePe, Paytm, BHIM or your bank's app).",
                    "Go to AutoPay or Mandates. It is usually under your profile or Manage.",
                    "Pick $name from the active mandates.",
                    "Choose Revoke (or Cancel) and confirm with your UPI PIN."
                ),
                COMMON_TIP, offersPlayLink = false
            )
            PaymentChannel.CARD_EMANDATE -> CancelGuide(
                channel, "Stop a card e-mandate",
                listOf(
                    "Open $name's app or website and go to Account, then Subscription, Membership or Billing.",
                    "Choose Cancel, or turn off auto-renew.",
                    "If you cannot find it, ask $name's support to cancel the recurring payment on your card.",
                    "You can also call your card issuer, or look for Recurring payments or E-mandates in its app, and ask for the mandate to be stopped."
                ),
                "Cancel with $name first. Blocking only the card can leave you with dues on their side.", offersPlayLink = false
            )
            PaymentChannel.STANDING_INSTRUCTION -> CancelGuide(
                channel, "Stop a bank standing instruction",
                listOf(
                    "Open your bank's app or net banking and look for Standing instructions, E-mandates or Auto-debit.",
                    "Find the one for $name and cancel it.",
                    "If your bank does not show it, call the bank or visit a branch with the last four digits of your account.",
                    "Also cancel the plan with $name so they do not try another way to charge you."
                ),
                COMMON_TIP, offersPlayLink = false
            )
            PaymentChannel.APP_STORE -> CancelGuide(
                channel, "Stop a Google Play or app store subscription",
                listOf(
                    "Open Google Play, tap your profile picture, then Payments & subscriptions, then Subscriptions.",
                    "Tap $name and choose Cancel subscription.",
                    "On an iPhone it is Settings, your name, then Subscriptions."
                ),
                "Deleting the app does not cancel the subscription.", offersPlayLink = true
            )
            PaymentChannel.DIRECT -> CancelGuide(
                channel, "Stop it with the merchant",
                listOf(
                    "Sign in to $name's app or website and go to Account, then Plan, Subscription or Membership.",
                    "Choose Cancel, or turn off auto-renew.",
                    "If you pay by hand each time, you can simply stop paying. If it is taken automatically, look for a mandate in your UPI app or bank.",
                    "Write to their support if you cannot find the option, and keep their reply."
                ),
                COMMON_TIP, offersPlayLink = false
            )
        }
    }

    private val DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)

    /**
     * What to paste to the merchant or the bank: the name, amount, how often, the last charge and the last four digits.
     * Never the text of a message.
     */
    fun reference(line: SubscriptionLine): String = buildString {
        append("Subscription: ").append(line.name).append('\n')
        append("Amount: ").append(PaceNudges.money(line.subscription.amount)).append(" (").append(line.cadence.label.lowercase()).append(")\n")
        append("Last charge: ").append(DAY.format(line.subscription.lastPaid)).append('\n')
        if (line.account.isNotEmpty()) append("Account ending: ").append(line.account).append('\n')
        append("Paid by: ").append(line.channel.label)
    }
}
