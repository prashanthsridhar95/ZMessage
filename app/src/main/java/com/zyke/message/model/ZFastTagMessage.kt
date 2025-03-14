package com.zyke.message.model

sealed class ZFastTagMessage(
    open val vehicleNumber: String
) {
    data class TollPaid(
        override val vehicleNumber: String,
        val balance: String,
        val tollAmount: String
    ) : ZFastTagMessage(vehicleNumber)

    data class ToppedUp(
        override val vehicleNumber: String,
        val amount: String,
        val balance: String
    ): ZFastTagMessage(vehicleNumber)

    data class RechargeNote(
        override val vehicleNumber: String
    ): ZFastTagMessage(vehicleNumber)

    data class ActiveNote(
        override val vehicleNumber: String
    ): ZFastTagMessage(vehicleNumber)
}
