package com.zyke.message.domain.model

sealed class FastTagEvent {
    abstract val vehicleNumber: String

    data class TollPaid(
        override val vehicleNumber: String,
        val balance: String,
        val tollAmount: String,
        /** The toll plaza crossed; empty when the message doesn't name one. */
        val tollName: String = ""
    ) : FastTagEvent()

    data class ToppedUp(
        override val vehicleNumber: String,
        val amount: String,
        val balance: String
    ) : FastTagEvent()

    data class RechargeNeeded(override val vehicleNumber: String) : FastTagEvent()

    data class Activated(override val vehicleNumber: String) : FastTagEvent()
}
