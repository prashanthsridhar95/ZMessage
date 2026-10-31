package com.zyke.message.domain

import com.zyke.message.domain.classification.MessageClassifier
import com.zyke.message.domain.feed.Cleanup
import com.zyke.message.domain.feed.QueryInterpreter
import com.zyke.message.domain.feed.SmartFilter
import com.zyke.message.domain.life.LifeAgenda
import com.zyke.message.domain.life.LifeKind
import com.zyke.message.domain.life.LifeParser
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageContent
import com.zyke.message.domain.model.SmsMessage
import com.zyke.message.domain.model.UserMessageState
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LifeTest {
    private val zone = ZoneId.systemDefault()
    private val today = LocalDate.of(2026, 10, 27)
    private val classifier = MessageClassifier()
    private var nextId = 1L

    private fun at(date: LocalDate) = date.atStartOfDay(zone).toInstant().toEpochMilli() + 3_600_000

    private fun classify(body: String, sender: String = "AD-INFO", on: LocalDate = today): ClassifiedMessage =
        classifier.classify(SmsMessage(nextId++, sender, body, at(on), true))

    private fun life(body: String, sender: String = "AD-INFO") =
        classify(body, sender).content as? MessageContent.Life ?: error("not a life message: $body")

    // ---- deliveries

    @Test
    fun deliveryStatuses_areRead() {
        assertEquals("On the way", life("Your Amazon order #402-1234567-1234567 has been shipped and is on its way.").status)
        assertEquals("Out for delivery", life("Your order is out for delivery and will reach you today. Track: https://x.in").status)
        val done = life("Your order ABC123456 has been delivered. Thanks for shopping!")
        assertEquals("Delivered", done.status)
        assertTrue(done.isDone)
        assertEquals(LifeKind.DELIVERY, done.kind)
    }

    // ---- travel

    @Test
    fun trainAndFlightBookings_carryTheirDateAndReference() {
        val train = life("PNR: 4512345678. Your train ticket is confirmed. DOJ: 12-Nov-2026. Boarding at CHENNAI CENTRAL. -IRCTC")
        assertEquals(LifeKind.TRAVEL, train.kind)
        assertEquals("4512345678", train.reference)
        assertEquals(LocalDate.of(2026, 11, 12), train.date)

        val flight = life("Your flight booking is confirmed. Booking ID: ABCD12. Departure 05/12/2026 at 6:45 PM from MAA.")
        assertEquals(LocalDate.of(2026, 12, 5), flight.date)
        assertEquals(LocalTime.of(18, 45), flight.time)
    }

    // ---- appointments

    @Test
    fun appointmentsAreRead_withDayAndTime() {
        val a = life("Your appointment with Dr Rao is confirmed for 3 Nov at 4:30 PM at Apollo Clinic.")
        assertEquals(LifeKind.APPOINTMENT, a.kind)
        assertEquals(LocalDate.of(2026, 11, 3), a.date)
        assertEquals(LocalTime.of(16, 30), a.time)
    }

    // ---- renewals

    @Test
    fun renewals_andExpiries_areRead() {
        val insurance = life("Your car insurance policy expires on 15-Dec-2026. Renew now to avoid a lapse.")
        assertEquals(LifeKind.RENEWAL, insurance.kind)
        assertEquals(LocalDate.of(2026, 12, 15), insurance.date)

        val plan = life("Your Airtel plan validity expires on 02/11/2026. Recharge now.", sender = "AD-AIRTEL")
        assertEquals(LocalDate.of(2026, 11, 2), plan.date)
    }

    @Test
    fun ordinaryMessages_areNotMistakenForLife() {
        assertNull(LifeParser.parse("See you at the meeting at 5", today))
        assertTrue(classify("Happy Diwali from all of us!").content == MessageContent.Generic)
    }

    // ---- agenda

    @Test
    fun agenda_listsSoonestFirst_andOneLinePerOrder() {
        val messages = listOf(
            classify("Your order ABC123456 is out for delivery today."),
            classify("Your order ABC123456 has been shipped."),
            classify("Your appointment is confirmed for 3 Nov at 4:30 PM at Apollo Clinic."),
            classify("Your car insurance policy expires on 30-Oct-2026."),
            classify("Your policy expires on 15-Mar-2027.")
        ).reversed()

        val agenda = LifeAgenda.build(messages, today, zone)
        assertEquals(listOf(LifeKind.RENEWAL, LifeKind.APPOINTMENT, LifeKind.DELIVERY), agenda.map { it.kind })
        assertEquals(1, agenda.count { it.kind == LifeKind.DELIVERY })
    }

    // ---- search and clean-up

    @Test
    fun searchWords_reachTheNewTabs() {
        assertEquals(SmartFilter.REMINDERS, QueryInterpreter.interpret("renewals").filter.smart)
        assertEquals(SmartFilter.TRAVEL, QueryInterpreter.interpret("my orders").filter.smart)
    }

    @Test
    fun cleanup_offersOldPromosAndCodes_neverPinnedOnes() {
        val now = Instant.ofEpochMilli(at(today))
        val oldPromo = classify("Flat Rs 200 off on your next order. T&C apply", sender = "AD-SHOPXY-P", on = today.minusDays(45))
        val freshPromo = classify("Flat Rs 200 off on your next order. T&C apply", sender = "AD-SHOPXY-P", on = today.minusDays(2))
        val oldOtp = classify("123456 is your OTP for login. Do not share.", on = today.minusDays(3))
        val all = listOf(oldPromo, freshPromo, oldOtp)

        assertEquals(listOf(oldPromo.id), Cleanup.staleIds(all, UserMessageState(), now, SmartFilter.PROMOS))
        assertEquals(listOf(oldOtp.id), Cleanup.staleIds(all, UserMessageState(), now, SmartFilter.OTP))
        assertTrue(Cleanup.staleIds(listOf(oldPromo), UserMessageState(pinnedIds = setOf(oldPromo.id)), now, SmartFilter.PROMOS).isEmpty())
        assertFalse(Cleanup.staleIds(listOf(oldPromo), UserMessageState(), now, SmartFilter.ALL).isNotEmpty())
    }

    @Test
    fun anOutForDeliveryNoteIsDroppedAfterADay_andALaterDeliveredClosesIt() {
        val old = classify("Your order is out for delivery and will reach you today. Track: https://x.in", on = today.minusDays(3))
        assertEquals(0, LifeAgenda.build(listOf(old), today, zone).size)

        val fresh = classify("Your order is out for delivery and will reach you today. Track: https://x.in", on = today)
        assertEquals(1, LifeAgenda.build(listOf(fresh), today, zone).size)

        // Shipped three days ago is still on its way.
        val shipped = classify("Your order has been shipped and will arrive soon. Track: https://x.in", on = today.minusDays(3))
        assertEquals(1, LifeAgenda.build(listOf(shipped), today, zone).size)
    }
}
