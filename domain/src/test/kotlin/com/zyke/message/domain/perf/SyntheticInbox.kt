package com.zyke.message.domain.perf

import com.zyke.message.domain.classification.MessageClassifier
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.SmsMessage
import java.time.LocalDate
import java.time.ZoneId
import java.util.Random

/** A believable year of Indian bank SMS (about [count] messages), for timing and equivalence tests. */
object SyntheticInbox {
    private val merchants = listOf("SWIGGY", "ZOMATO", "BURMY FOODS", "CAFE ONE", "BIGBASKET", "AMAZON PAY", "UBER INDIA", "IRCTC", "BOOKMYSHOW", "DMART", "APOLLO PHARMACY", "RELIANCE FRESH", "OLA", "MYNTRA", "NETFLIX", "SPOTIFY")
    private val upi = listOf("shop@okhdfc", "cafe@ybl", "ravi@okaxis", "grocer@paytm", "friend.name@oksbi", "milk@upi")

    fun build(count: Int = 1600, today: LocalDate = LocalDate.of(2026, 10, 31), seed: Long = 7, zone: ZoneId = ZoneId.of("Asia/Kolkata")): List<ClassifiedMessage> {
        val r = Random(seed)
        val classifier = MessageClassifier()
        val out = ArrayList<SmsMessage>()
        var id = 1L
        fun add(body: String, sender: String, day: LocalDate, minute: Int = r.nextInt(1400)) {
            val date = day.atStartOfDay(zone).plusMinutes(minute.toLong()).toInstant().toEpochMilli()
            out += SmsMessage(id++, sender, body, date, r.nextInt(4) != 0)
        }
        val cards = listOf("1234", "5678", "9012")
        var i = 0
        while (out.size < count) {
            i++
            val day = today.minusDays(r.nextInt(365).toLong())
            val amount = (50 + r.nextInt(4000)) + 0.0
            val amt = "%.2f".format(amount)
            val d = "%02d-%02d-%02d".format(day.dayOfMonth, day.monthValue, day.year % 100)
            when (r.nextInt(14)) {
                0, 1, 2 -> add("Sent Rs.$amt\nFrom HDFC Bank A/C *5765\nTo ${merchants[r.nextInt(merchants.size)]}\nOn ${d.replace('-', '/')}\nRef ${100000 + i}", "AD-HDFCBK", day)
                3, 4 -> add("Rs.$amt debited from A/c XX5765 on $d to VPA ${upi[r.nextInt(upi.size)]} (UPI ${200000 + i}). Avl Bal Rs ${10000 + r.nextInt(90000)}.50", "VM-HDFCBK", day)
                5 -> add("Credit Alert! Rs.$amt credited to HDFC Bank A/c XX5765 on $d from VPA ${upi[r.nextInt(upi.size)]} (UPI ${300000 + i})", "VM-HDFCBK", day)
                6 -> add("Spent Rs. $amt on your ICICI Bank Card XX${cards[r.nextInt(3)]} on $d at ${merchants[r.nextInt(merchants.size)]}. Avl Limit Rs 50,000", "VK-ICICIB", day)
                7 -> add("Txn Rs.$amt\nOn HDFC Bank Card 4253\nAt ${merchants[r.nextInt(merchants.size)]}\nby UPI ${400000 + i}\nOn ${"%02d-%02d".format(day.dayOfMonth, day.monthValue)}\nNot You?\nCall 18002586161", "AD-HDFCBK", day)
                8 -> {
                    // A transfer between the person's own accounts: a debit and a credit for the same amount.
                    add("Rs.$amt debited from A/c XX5765 on $d to VPA self@okhdfc (UPI ${500000 + i})", "VM-HDFCBK", day, 600)
                    add("Credit Alert! Rs.$amt credited to Axis Bank A/c XX4321 on $d from VPA self@okhdfc (UPI ${500000 + i})", "AX-AXISBK", day, 601)
                }
                9 -> add("HDFC Bank Credit Card XX${cards[r.nextInt(3)]} Statement: Total due amt: Rs.${amt} Min due amt: Rs.${(amount / 20).toInt()}.00 Due by:${"%02d-%02d-%04d".format((day.dayOfMonth % 27) + 1, day.plusMonths(1).monthValue, day.plusMonths(1).year)} pay https://hdfcbk.io/$i", "AD-HDFCBK", day)
                10 -> add("Rs $amt debited from A/c XX5765 on $d towards credit card payment. Avl Bal Rs 80,000.00", "VM-HDFCBK", day)
                11 -> add("Your OTP is ${100000 + r.nextInt(899999)}. Do not share it with anyone.", "AX-OTPSMS", day)
                12 -> add("Get 50% off on your next order! Use code SAVE$i. T&C apply. Shop now at https://promo.example/$i", "VM-PROMOS", day)
                else -> add("Rs.15000.00 credited to A/c XX5765 on $d by NEFT ACME CORP SALARY. Avl Bal Rs 45,000.00", "VM-HDFCBK", day)
            }
        }
        // Distinct ids, newest first, like the app's inbox.
        return out.sortedByDescending { it.date }.map { classifier.classify(it) }
    }
}
