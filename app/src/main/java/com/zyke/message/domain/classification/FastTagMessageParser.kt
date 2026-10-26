package com.zyke.message.domain.classification

import com.zyke.message.domain.model.FastTagEvent

/** Extracts a [FastTagEvent] from a FASTag SMS body. */
class FastTagMessageParser {

    fun parse(message: String): FastTagEvent? =
        tollPaid(message)
            ?: rechargeNeeded(message)
            ?: toppedUp(message)
            ?: activated(message.replace(".", ""))

    private fun tollPaid(message: String): FastTagEvent? {
        val g = TOLL_PAID.find(message)?.groupValues ?: return null
        return FastTagEvent.TollPaid(vehicleNumber = g[4], balance = g[8], tollAmount = g[1])
    }

    private fun toppedUp(message: String): FastTagEvent? {
        val g = TOP_UP.find(message)?.groupValues ?: return null
        return FastTagEvent.ToppedUp(vehicleNumber = g[1], amount = g[3], balance = g[5])
    }

    private fun rechargeNeeded(message: String): FastTagEvent? {
        val vehicle = LOW_BALANCE_PATTERNS.firstNotNullOfOrNull { it.find(message) }
            ?.groupValues?.get(1) ?: return null
        return FastTagEvent.RechargeNeeded(vehicle)
    }

    /** Expects [message] with periods already stripped (vehicle numbers may contain them). */
    private fun activated(message: String): FastTagEvent? {
        val vehicle = ACTIVE_PATTERNS.firstNotNullOfOrNull { it.find(message) }
            ?.groupValues?.get(1)?.trim() ?: return null
        return FastTagEvent.Activated(vehicle)
    }

    private companion object {
        val TOLL_PAID =
            "INR (\\d+) toll paid from (.*?) Tag (.*?) for vehicle no. (.*?) at (.*?) Toll on (.*?) (\\d{2}:\\d{2}). Avbl. Bal.: INR(\\d+)".toRegex()

        val TOP_UP =
            "Recharge successful: (.*?) FASTag (.*?) Credited with Rs (\\d+) Ref: (\\d+) Avbl Bal:Rs (\\d+)".toRegex()

        val LOW_BALANCE_PATTERNS = listOf(
            "vehicle (\\w+) is low on balance",
            "Vehicle no\\. (.*?) is low",
            "Vehicle no\\. (.*?) will run out of balance soon",
            "vehicle (.*?) will run out of balance soon",
            "vehicle (.*?) has low balance"
        ).map { it.toRegex() }

        val ACTIVE_PATTERNS = listOf(
            "vehicle no\\s+(.*?)(\\s+is now active|$)",
            "vehicle number\\s+(.*?)(\\s+is now active|$)"
        ).map { it.toRegex() }
    }
}
