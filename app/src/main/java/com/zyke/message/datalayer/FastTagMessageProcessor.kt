package com.zyke.message.datalayer

import com.zyke.message.model.ZFastTagMessage
import java.util.regex.Matcher
import java.util.regex.Pattern

object FastTagMessageProcessor {

    fun processMessage(message: String): ZFastTagMessage? {
        return paidNote(message) ?: ((rechargeNote(message) ?: topUpNote(message)) ?: activeNote(message.replace(".","")))
    }

    private fun topUpNote(message: String): ZFastTagMessage?
    {
        val pattern = Pattern.compile("Recharge successful: (.*?) FASTag (.*?) Credited with Rs (\\d+) Ref: (\\d+) Avbl Bal:Rs (\\d+)")

        val matcher: Matcher = pattern.matcher(if(message.endsWith(".")) message else "${message}.")

        return if(matcher.find()) {
            ZFastTagMessage.ToppedUp(
                matcher.group(1),
                matcher.group(3),
                matcher.group(5)
            )
        }
        else
        {
            null
        }
    }

    private fun activeNote(message: String): ZFastTagMessage?
    {
        val pattern = Pattern.compile("vehicle no\\. (.*?) is now active")

        val matcher: Matcher = pattern.matcher(message.replace("\n",""))

        return if(matcher.find())
        {
            ZFastTagMessage.ActiveNote(
                matcher!!.group(1)
            )
        }
        else
        {
            val patterns = listOf(  // List of patterns for flexibility
                Pattern.compile("vehicle no\\s+(.*?)(\\s+is now active|$)"),
                Pattern.compile("vehicle number\\s+(.*?)(\\s+is now active|$)")
            )

            for (patterns in patterns) {
                val matcher2: Matcher = patterns.matcher(message)
                if (matcher2.find()) {
                    return ZFastTagMessage.ActiveNote(matcher2.group(1).trim())
                }
            }

            return null
        }
    }

    private fun rechargeNote(message: String): ZFastTagMessage?
    {
        val pattern = Pattern.compile("vehicle (\\w+) is low on balance")

        val matcher: Matcher = pattern.matcher(message)

        return if(matcher.find())
        {
            ZFastTagMessage.RechargeNote(
                matcher.group(1)
            )
        }
        else
        {
            val pattern = Pattern.compile("Vehicle no\\. (.*?) is low")

            val matcher = pattern.matcher(message)

            if (matcher.find()) {
                ZFastTagMessage.RechargeNote(
                    matcher.group(1)
                )
            }
            else
            {
                val pattern = Pattern.compile("Vehicle no\\. (.*?) will run out of balance soon")

                val matcher = pattern.matcher(message)

                if (matcher.find()) {
                    ZFastTagMessage.RechargeNote(
                        matcher.group(1)
                    )
                }
                else
                {
                    val pattern = Pattern.compile("vehicle (.*?) will run out of balance soon")

                    val matcher = pattern.matcher(message)

                    if (matcher.find()) {
                        ZFastTagMessage.RechargeNote(
                            matcher.group(1)
                        )
                    }
                    else
                    {
                        val pattern = Pattern.compile("vehicle (.*?) has low balance")

                        val matcher = pattern.matcher(message)

                        if (matcher.find()) {
                            ZFastTagMessage.RechargeNote(
                                matcher.group(1)
                            )
                        }
                        else
                        {
                            null
                        }
                    }
                }
            }
        }
    }

    private fun paidNote(message: String): ZFastTagMessage?
    {
        val pattern = Pattern.compile(
            "INR (\\d+) toll paid from (.*?) Tag (.*?) for vehicle no. (.*?) at (.*?) Toll on (.*?) (\\d{2}:\\d{2}). Avbl. Bal.: INR(\\d+)"
        )

        val matcher: Matcher = pattern.matcher(message)

        if (matcher.find()) {
            return ZFastTagMessage.TollPaid(
                matcher.group(4),
                matcher.group(8),
                matcher.group(1)
            )
        }
        else
            return null
    }
}