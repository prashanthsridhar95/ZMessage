package com.zyke.message.domain

import com.zyke.message.domain.calendar.CalendarBuilder
import com.zyke.message.domain.calendar.CalendarKind
import com.zyke.message.domain.classification.MessageClassifier
import com.zyke.message.domain.export.Exports
import com.zyke.message.domain.life.LifeAgenda
import com.zyke.message.domain.life.LifeKind
import com.zyke.message.domain.life.Warranties
import com.zyke.message.domain.life.WarrantyKind
import com.zyke.message.domain.model.BankName
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.Merchant
import com.zyke.message.domain.model.MerchantLogo
import com.zyke.message.domain.model.MessageCategory
import com.zyke.message.domain.model.MessageContent
import com.zyke.message.domain.model.SmsMessage
import com.zyke.message.domain.notes.PaymentNotes
import com.zyke.message.domain.notes.Tags
import com.zyke.message.domain.plan.MoneyPlan
import com.zyke.message.domain.search.GlobalSearch
import com.zyke.message.domain.search.SearchKind
import com.zyke.message.domain.search.SearchSources
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WarrantiesAndNotesTest {
    private val zone = ZoneId.of("UTC")
    private val today = LocalDate.of(2026, 10, 29)
    private val classifier = MessageClassifier()
    private var nextId = 1L

    private fun msg(body: String, on: LocalDate = today, sender: String = "AD-POPEYE") =
        classifier.classify(SmsMessage(nextId++, sender, body, on.atTime(10, 0).atZone(zone).toInstant().toEpochMilli(), true))

    // ---- warranties

    @Test fun statedDates_areUsedAsWritten() {
        val w = Warranties.of(msg("Your Croma order is delivered. Product warranty till 12 Oct 2027. Invoice sent."), zone)!!
        assertEquals(WarrantyKind.WARRANTY, w.kind)
        assertEquals(LocalDate.of(2027, 10, 12), w.endsOn)
        val r = Warranties.of(msg("Your order #A1B2C3D4 was delivered. Items are returnable by 05-Nov-26."), zone)!!
        assertEquals(WarrantyKind.RETURN, r.kind)
        assertEquals(LocalDate.of(2026, 11, 5), r.endsOn)
    }

    @Test fun statedLengths_areCountedFromTheMessageDay() {
        assertEquals(LocalDate.of(2027, 10, 29), Warranties.of(msg("Thanks for your purchase. 1 year warranty registered on your product."), zone)!!.endsOn)
        assertEquals(LocalDate.of(2026, 11, 8), Warranties.of(msg("Your order has been delivered. 10-day return available."), zone)!!.endsOn)
        assertEquals(LocalDate.of(2026, 11, 5), Warranties.of(msg("Your order was delivered. Replace within 7 days if damaged."), zone)!!.endsOn)
    }

    @Test fun vagueOrUnrelatedMessages_giveNothing() {
        assertNull(Warranties.of(msg("Easy returns on all orders. T&C apply."), zone))
        assertNull(Warranties.of(msg("Your OTP is 123456, valid for 10 minutes. Do not share. Return to app."), zone))
        assertNull(Warranties.of(msg("Your refund will be credited in 5 days. Return request approved for order 99887766."), zone))
        assertNull(Warranties.of(msg("Weekend sale! 2 year warranty on all TVs. Visit store today."), zone))
        assertNull(Warranties.of(msg("Dentist reminder: tomorrow."), zone))
    }

    @Test fun warrantiesAppearInTheCalendar_andTheAgenda() {
        val order = msg("Your Popeye order #PP123456 was delivered. Returnable by 01-11-2026.")
        val month = CalendarBuilder.month(YearMonth.of(2026, 11), listOf(order), emptyList(), emptyList(), emptyList(), MoneyPlan(), today.atStartOfDay(zone).toInstant(), zone)
        val entry = month.getValue(LocalDate.of(2026, 11, 1)).single { it.kind == CalendarKind.WARRANTY }
        assertTrue(entry.title.startsWith("Return window for"))
        val agenda = LifeAgenda.build(listOf(order), today, zone).single { it.kind == LifeKind.WARRANTY }
        assertEquals("ends in 3 days", agenda.status)
        assertTrue(agenda.title.endsWith("order"))
    }

    // ---- notes, tags, search, export

    private fun tx(id: Long, amount: String, tags: List<String> = emptyList(), note: String? = null, received: Boolean = false) = ClassifiedMessage(
        message = SmsMessage(id, "AD-BANK", "Rs.$amount paid", 1_000L + id, true),
        category = MessageCategory.FOOD,
        merchant = Merchant("Cafe", MerchantLogo.PERSON, MessageCategory.FOOD),
        content = MessageContent.Transaction(BankName.OTHERS, "Cafe", amount, "1234", isReceived = received, isCard = false),
        note = note, tags = tags
    )

    @Test fun tagsAndNotes_areCleaned() {
        val n = PaymentNotes.of("  hello  ", listOf("#Trip ", "trip", " ", "Work  Lunch", "x".repeat(40)) + (1..10).map { "t$it" })
        assertEquals("hello", n.note)
        assertEquals(PaymentNotes.MAX_TAGS, n.tags.size)
        assertEquals(listOf("trip", "work lunch", "x".repeat(24)), n.tags.take(3))
        assertEquals(500, PaymentNotes.cleanNote("a".repeat(900)).length)
    }

    @Test fun applyingNotes_copiesOnlyTheMessagesThatHaveThem() {
        val a = tx(1, "10"); val b = tx(2, "20")
        val out = listOf(a, b).map { PaymentNotes.apply(it, mapOf("msg:2" to PaymentNotes.of("split", listOf("trip")))) }
        assertTrue(out[0] === a)
        assertEquals(listOf("trip"), out[1].tags)
        assertEquals("split", out[1].note)
    }

    @Test fun tagSummaries_honourSetAside_andSuggest() {
        val list = listOf(tx(1, "100", listOf("trip")), tx(2, "50.50", listOf("trip", "work")), tx(3, "70", listOf("trip"), received = true), tx(4, "9"))
        val summary = Tags.summaries(list)
        assertEquals("trip", summary[0].tag)
        assertEquals(3, summary[0].count)
        assertEquals("150.50", summary[0].spent.toPlainString())
        assertEquals(listOf("work"), Tags.suggest("", list.flatMap { it.tags }, listOf("trip")))
    }

    @Test fun searchFindsATagOrANoteWord() {
        val sources = SearchSources(messages = listOf(tx(1, "100", listOf("holiday")), tx(2, "5", note = "birthday gift for Asha")))
        fun ids(q: String) = GlobalSearch.search(q, sources).groups.first { it.kind == SearchKind.MESSAGE }.hits.map { it.messageId }
        assertEquals(listOf(1L), ids("holiday"))
        assertEquals(listOf(2L), ids("asha"))
    }

    @Test fun csvGetsTagAndNoteColumnsAtTheEnd() {
        val csv = Exports.transactionsCsv(listOf(tx(1, "100", listOf("trip", "work"), "split, with Asha")), zone).trim().lines()
        assertTrue(csv[0].endsWith(",tags,personal_note"))
        assertTrue(csv[1].endsWith(",trip work,\"split, with Asha\""))
    }
}
