package com.zyke.message.domain.classification

import com.zyke.message.domain.model.FastTagEvent

/** Extracts a [FastTagEvent] from a FASTag SMS body. */
class FastTagMessageParser {

    fun parse(message: String): FastTagEvent? =
        tollPaid(message)
            ?: tollTransaction(message)
            ?: parkingCharge(message)
            ?: rechargeNeeded(message)
            ?: toppedUp(message)
            ?: activated(message.replace(".", ""))

    private fun tollPaid(message: String): FastTagEvent? {
        val g = TOLL_PAID.find(message)?.groupValues ?: return null
        return FastTagEvent.TollPaid(vehicleNumber = g[4], balance = g[8], tollAmount = g[1], tollName = g[5].trim())
    }

    private fun tollTransaction(message: String): FastTagEvent? {
        val g = TOLL_TXN.find(message)?.groupValues ?: return null
        return FastTagEvent.TollPaid(
            vehicleNumber = g[2],
            balance = g[4].trimEnd('.'),
            tollAmount = g[1].trimEnd('.'),
            tollName = g[3].trim()
        )
    }

    /** SBI: "FASTag Acc ending with X VRN: TN01.. debited Rs.100 Parking Charges at Y on 10-Oct-2026 ... Bal Rs.1234". */
    private fun parkingCharge(message: String): FastTagEvent? {
        val g = PARKING.find(message)?.groupValues ?: return null
        return FastTagEvent.TollPaid(
            vehicleNumber = g[1], balance = g[4].trimEnd('.'), tollAmount = g[2].trimEnd('.'), tollName = "Parking at ${g[3].trim()}"
        )
    }

    private fun toppedUp(message: String): FastTagEvent? {
        TOP_UP.find(message)?.groupValues?.let {
            return FastTagEvent.ToppedUp(vehicleNumber = it[1], amount = it[3], balance = it[5])
        }
        val g = TOP_UP_CREDITED.find(message)?.groupValues ?: return null
        return FastTagEvent.ToppedUp(vehicleNumber = g[1], amount = g[2].trimEnd('.'), balance = g[3].trimEnd('.'))
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

        /** "txn. of INR 95 using IDFC FIRST Bank FASTag on TN01AB1234 done at X on 10/10/2026 10:00. Avbl. Bal.: INR 505.5". */
        val TOLL_TXN =
            "(?is)txn\\. of INR\\s*([\\d,.]+) using .*?FASTag on (\\S+) done at (.*?) on \\d{1,2}[-/ ].*?Avbl\\. Bal\\.:\\s*INR\\s*([\\d,.]+)".toRegex()

        val PARKING =
            "(?is)VRN:\\s*(\\S+)\\s+debited Rs\\.?\\s*([\\d,.]+)\\s+Parking Charges at (.*?) on \\d{1,2}[-/ ].*?Bal Rs\\.?\\s*([\\d,.]+)".toRegex()

        val TOP_UP =
            "Recharge successful: (.*?) FASTag (.*?) Credited with Rs (\\d+) Ref: (\\d+) Avbl Bal:Rs (\\d+)".toRegex()

        /** "Recharge successful: IDFC FIRST Bank FASTag on TN01AB1234 credited with Rs.500.0 on ... Avbl Bal: Rs.620.5". */
        val TOP_UP_CREDITED =
            "(?is)Recharge successful: .*?FASTag on (\\S+) credited with Rs\\.?\\s*([\\d,.]+) on .*?Avbl Bal:\\s*Rs\\.?\\s*([\\d,.]+)".toRegex()

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
