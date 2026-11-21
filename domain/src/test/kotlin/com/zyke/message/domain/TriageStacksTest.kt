package com.zyke.message.domain

import com.zyke.message.domain.classification.MessageClassifier
import com.zyke.message.domain.life.LifeKind
import com.zyke.message.domain.model.BankName
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.FastTagEvent
import com.zyke.message.domain.model.Merchant
import com.zyke.message.domain.model.MerchantLogo
import com.zyke.message.domain.model.MessageCategory
import com.zyke.message.domain.model.MessageContent
import com.zyke.message.domain.model.NoticeKind
import com.zyke.message.domain.model.ScheduledKind
import com.zyke.message.domain.model.SmsMessage
import com.zyke.message.domain.model.UserMessageState
import com.zyke.message.domain.triage.TriageMode
import com.zyke.message.domain.triage.TriageMove
import com.zyke.message.domain.triage.TriageGestures
import com.zyke.message.domain.triage.TriageSettings
import com.zyke.message.domain.triage.TriageStackSpec
import com.zyke.message.domain.triage.TriageStacks
import com.zyke.message.domain.triage.TriageTone
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Home's deck: which stack a message lands in, what its card says, and what may be cleared without a look. */
class TriageStacksTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val now = Instant.parse("2026-11-21T06:00:00Z")
    private val today = LocalDate.of(2026, 11, 21)
    private val classifier = MessageClassifier()
    private var nextId = 1L

    private fun minutesAgo(m: Long) = now.toEpochMilli() - m * 60_000L
    private fun daysAgo(d: Long) = minutesAgo(d * 24 * 60)

    private fun cm(
        content: MessageContent, sender: String = "AX-AXISBK-S", body: String = "body", at: Long = minutesAgo(30), read: Boolean = false,
        promo: Boolean = false, muted: Boolean = false, name: String = "Axis Bank", category: MessageCategory = MessageCategory.OTHERS
    ): ClassifiedMessage = ClassifiedMessage(
        SmsMessage(nextId++, sender, body, at, read), category, Merchant(name, MerchantLogo.UPI, category), content, isPromotional = promo, isMuted = muted
    )

    private fun debit(payee: String = "Mr S Prashanth", amount: String = "2500.00", at: Long = minutesAgo(30), card: Boolean = false, received: Boolean = false, cardPayment: Boolean = false) =
        cm(MessageContent.Transaction(BankName.HDFC, payee, amount, "5765", received, card, cardPayment), "AD-HDFCBK-S", at = at, name = payee, category = MessageCategory.UPI_PAYMENT)

    private fun deck(vararg m: ClassifiedMessage, state: UserMessageState = UserMessageState(), settings: TriageSettings = TriageSettings(), restored: Set<Long> = emptySet()) =
        TriageStacks.build(m.toList(), state, settings, now, zone, restored)

    private fun cardOf(m: ClassifiedMessage) = deck(m).stacks.flatMap { it.items + it.autoCleared }.single()

    // ---- stacks

    @Test
    fun aDebitIsAPaymentsCard_withTheAmountAndPayeeInTheHeadline() {
        val d = deck(debit())
        assertEquals(1, d.toGo)
        val card = d.stackOf(TriageStackSpec.PAYMENTS)!!.items.single()
        assertEquals("₹2,500 sent to Mr S Prashanth", card.headline)
        assertTrue(card.summary, card.summary.startsWith("HDFC •• 5765".replace(" ", "")) || card.summary.contains("5765"))
        assertFalse(card.verified)
    }

    @Test
    fun cardSpendCreditAndBillPaymentWordTheirHeadlinesDifferently() {
        assertEquals("₹499 spent at Netflix", cardOf(debit("Netflix", "499.00", card = true)).headline)
        assertEquals("₹900 received from Asha", cardOf(debit("Asha", "900.00", received = true)).headline)
        assertEquals("₹120 refunded by Zepto", cardOf(debit("Zepto", "120.00", card = true, received = true)).headline)
        assertTrue(cardOf(debit("Axis", "5176.86", card = true, received = true, cardPayment = true)).headline.startsWith("Card bill payment of ₹5,176.86"))
        assertEquals(TriageTone.GOOD, cardOf(debit("Asha", "900.00", received = true)).tone)
    }

    @Test
    fun aRealCardBillIsABillsCard_withDueDateMinimumAndTone() {
        val bill = classifier.classify(
            SmsMessage(nextId++, "AX-AXISBK", "Axis Bank Credit Card XX8065 Statement: Total due Rs.5,176.86 Min due Rs.259.00 Due by:30-11-2026", minutesAgo(60), false)
        )
        assertTrue(bill.content.toString(), bill.content is MessageContent.CardBill)
        val d = deck(bill)
        val card = d.stackOf(TriageStackSpec.BILLS)!!.items.single()
        assertEquals("Axis Card ••8065 bill ₹5,176.86 due 30 Nov", card.headline)
        assertTrue(card.summary, card.summary.contains("Minimum due ₹259"))
        assertTrue(card.tags.any { it.text == "Due 30 Nov" })
        assertTrue(card.tags.size <= 3)
    }

    @Test
    fun aBillDueSoonIsWarning_andOverdueIsDanger() {
        fun bill(due: LocalDate, id: String) = cm(MessageContent.CardBill(BankName.AXIS, id, "1000.00", "100.00", due), "AX-AXISBK")
        assertEquals(TriageTone.WARNING, cardOf(bill(today.plusDays(2), "1111")).tone)
        val late = cardOf(bill(today.minusDays(3), "2222"))
        assertEquals(TriageTone.DANGER, late.tone)
        assertTrue(late.tags.any { it.text == "Overdue" })
        assertTrue(late.summary.contains("Overdue by 3 days"))
    }

    @Test
    fun onlyTheNewestBillOfACardIsACard_andAnOldOverdueBillIsDropped() {
        val old = cm(MessageContent.CardBill(BankName.AXIS, "1111", "900.00", null, today.minusDays(40)), at = daysAgo(40))
        val newest = cm(MessageContent.CardBill(BankName.AXIS, "1111", "1000.00", null, today.plusDays(9)), at = daysAgo(1))
        val older = cm(MessageContent.CardBill(BankName.AXIS, "1111", "800.00", null, today.plusDays(1)), at = daysAgo(8))
        val d = deck(old, newest, older)
        assertEquals(listOf(newest.id), d.stackOf(TriageStackSpec.BILLS)!!.items.map { it.messageId })
    }

    @Test
    fun scheduledPaymentsAndAutopayNoticesAreBills() {
        val debitSoon = cm(MessageContent.ScheduledPayment(ScheduledKind.UPCOMING_DEBIT, BankName.HDFC, "5765", "Netflix", "649.00", today.plusDays(2), false))
        val card = cardOf(debitSoon)
        assertEquals("₹649 will be auto-debited for Netflix", card.headline)
        assertEquals(TriageTone.WARNING, card.tone)
        val failed = cm(MessageContent.Notice(NoticeKind.AUTOPAY_FAILED, "300.00"))
        val d = deck(failed)
        assertEquals(1, d.stackOf(TriageStackSpec.BILLS)!!.items.size)
        assertEquals(TriageTone.DANGER, d.stackOf(TriageStackSpec.BILLS)!!.items.single().tone)
        assertNull(deck(cm(MessageContent.ScheduledPayment(ScheduledKind.UPCOMING_DEBIT, BankName.HDFC, "5765", "Old", "10.00", today.minusDays(2), false))).stacks.firstOrNull { it.items.isNotEmpty() })
    }

    @Test
    fun lifeMessagesAreOrdersAndTravel_withTheRenewalDateInTheHeadline() {
        val renewal = cm(MessageContent.Life(LifeKind.RENEWAL, "Zoho", "Subscription expires", LocalDate.of(2026, 12, 3), null, null, false), "VM-ZOHOIN-S", name = "Zoho")
        val card = cardOf(renewal)
        assertEquals(TriageStackSpec.LIFE, card.stackId)
        assertEquals("Zoho: Subscription expires on 3 Dec", card.headline)
        assertTrue(card.tags.any { it.text == "3 Dec" })
        val trip = cm(MessageContent.Life(LifeKind.TRAVEL, "IndiGo", "Booking confirmed", today.plusDays(10), LocalTime.of(6, 30), "ABC123", false), "AX-INDIGO-S",
            body = "Your IndiGo flight 6E 201 from Chennai to Bengaluru on 01-12-2026 is confirmed. PNR ABC123")
        val tripCard = cardOf(trip)
        assertTrue(tripCard.summary, tripCard.summary.contains("Chennai → Bengaluru"))
        assertTrue(tripCard.summary, tripCard.summary.contains("6:30 AM"))
        assertTrue(tripCard.tags.any { it.text.startsWith("Ref ABC123") })
    }

    @Test
    fun aRefundNoticeIsARefundsCard_withItsStage() {
        val processed = classifier.classify(
            SmsMessage(nextId++, "AD-ZEPTOO", "Dear user, Refund of Rs 176.00 has been processed for your Zepto order NUGTJCORO38008. Refund Ref. num. 667812523611.", minutesAgo(20), false)
        )
        val initiated = classifier.classify(
            SmsMessage(nextId++, "AD-ZEPTOO", "Dear user, refund of Rs 176.00 for your Zepto order NUGTJCORO38008 has been initiated. It should reflect in your account within 5–7 business days!", minutesAgo(25), false)
        )
        val p = cardOf(processed)
        assertEquals(TriageStackSpec.REFUNDS, p.stackId)
        assertEquals("Refund of ₹176 processed", p.headline)
        assertTrue(p.tags.any { it.text == "Refund processed" })
        assertEquals("Refund of ₹176 initiated", cardOf(initiated).headline)
    }

    @Test
    fun balanceFasTagAndOtherNoticesAreAccountAlerts() {
        val low = cm(MessageContent.FastTag(FastTagEvent.RechargeNeeded("TN38DC3528")), "VM-FASTAG-S")
        val c = cardOf(low)
        assertEquals(TriageStackSpec.ALERTS, c.stackId)
        assertEquals(TriageTone.DANGER, c.tone)
        val balance = cm(MessageContent.Notice(NoticeKind.BALANCE_UPDATE, "12340.00"))
        assertEquals("Balance update · ₹12,340", cardOf(balance).headline)
        // A gateway's "payment confirmed" repeats the debit and is no card.
        assertTrue(deck(cm(MessageContent.Notice(NoticeKind.PAYMENT_CONFIRMATION, "10.00"))).stacks.all { it.items.isEmpty() })
    }

    @Test
    fun genericMessagesUseTheFirst120Characters() {
        val body = "Hi, this is a long note from a friend about the plan for the weekend and where everyone will meet so please read it carefully before replying to the group thanks"
        val c = cardOf(cm(MessageContent.Generic, "+919876543210", body = body, name = "Ravi"))
        assertEquals(TriageStackSpec.OTHER, c.stackId)
        assertTrue(c.summary.length <= 121)
        assertTrue(c.summary.endsWith("…"))
        assertTrue(body.startsWith(c.summary.removeSuffix("…").trim().take(40)))
        // A short message has a headline and no repeating summary.
        assertEquals("", cardOf(cm(MessageContent.Generic, body = "See you at 5 pm today")).summary)
    }

    @Test
    fun theSenderLabelCarriesTheVerifiedMark_andANicknameWins() {
        val c = cardOf(cm(MessageContent.Generic, "VM-SPINNY-S", body = "Your car inspection is on Sunday at ten in the morning", name = "Spinny"))
        assertEquals("Spinny", c.title)
        assertTrue(c.verified)
        val m = cm(MessageContent.Generic, "VM-SPINNY-S", body = "Your car inspection is on Sunday at ten in the morning")
        val named = m.copy(merchant = Merchant("Car guy", MerchantLogo.PERSON, MessageCategory.OTHERS, renamed = true))
        assertEquals("Car guy", cardOf(named).title)
    }

    // ---- who is a card

    @Test
    fun readArchivedOldAndFreshCodesAreNotCards_butReadAndArchivedAreCounted() {
        val unread = debit()
        val read = debit(at = minutesAgo(60)).let { it.copy(message = it.message.copy(isRead = true)) }
        val markedRead = debit(at = minutesAgo(90))
        val archived = debit(at = minutesAgo(120))
        val old = debit(at = daysAgo(45))
        val fresh = cm(MessageContent.Otp("123456", "Axis", null, null), at = minutesAgo(3))
        val d = deck(unread, read, markedRead, archived, old, fresh, state = UserMessageState(readIds = setOf(markedRead.id), archivedIds = setOf(archived.id)))
        assertEquals(1, d.toGo)
        assertEquals(3, d.readCount)
    }

    @Test
    fun aPaymentsRepeatsAreNotCards_andReadingTheCardReadsThem() {
        val lead = debit()
        val link = com.zyke.message.domain.ledger.SamePaymentLink(com.zyke.message.domain.ledger.SamePaymentKind.CERTAIN, lead.id, "HDFC debit", "ref", "test")
        val follower = cm(MessageContent.Generic, "VM-RZRPAY-S", body = "Payment of Rs 2500 received", at = minutesAgo(29)).copy(samePayment = link)
        val d = deck(lead, follower)
        val items = d.stackOf(TriageStackSpec.PAYMENTS)!!.items
        assertEquals(1, items.size)
        assertEquals(listOf(follower.id), items.single().followerIds)
        assertEquals(0, d.stackOf(TriageStackSpec.OTHER)!!.items.size)
        // Once the lead is read but a repeat is not, the card is still there; when all are read it is gone.
        assertEquals(1, deck(lead, follower, state = UserMessageState(readIds = setOf(lead.id))).toGo)
        assertEquals(0, deck(lead, follower, state = UserMessageState(readIds = setOf(lead.id, follower.id))).toGo)
    }

    @Test
    fun aMutedSendersPlainMessageIsAnOffer_butItsPaymentIsNotACard() {
        val note = cm(MessageContent.Generic, muted = true)
        assertEquals(TriageStackSpec.OFFERS, cardOf(note).stackId)
        assertTrue(deck(cm(MessageContent.Transaction(BankName.HDFC, "X", "10.00", "1", false, false), muted = true)).stacks.all { it.items.isEmpty() })
    }

    // ---- counts and modes

    @Test
    fun toGoCountsOnlyKeepStacks_quietWaitsAndAutoClearedIsSeparate() {
        val pay = debit()
        val offer = cm(MessageContent.Generic, promo = true, body = "Flat 50% off today only on everything in store")
        val code = cm(MessageContent.Otp("123456", "Axis", null, null), at = minutesAgo(40))
        val d = deck(pay, offer, code)
        assertEquals(1, d.toGo)
        assertEquals(1, d.quietCount)
        assertEquals(listOf(code.id), d.autoCleared.map { it.messageId })
        assertEquals(TriageMode.QUIET, d.stackOf(TriageStackSpec.OFFERS)!!.mode)
        assertEquals(TriageMode.AUTO_CLEAR, d.stackOf(TriageStackSpec.CODES)!!.mode)
        assertEquals(listOf(TriageStackSpec.PAYMENTS), d.tiles.map { it.id })
    }

    @Test
    fun aRestoredCardWaitsUnderQuietAndIsNotClearedAgain() {
        val code = cm(MessageContent.Otp("123456", "Axis", null, null), at = minutesAgo(40))
        val d = deck(code, restored = setOf(code.id))
        assertTrue(d.autoCleared.isEmpty())
        assertEquals(1, d.quietCount)
        assertEquals(0, d.toGo)
        assertEquals(listOf(code.id), d.cards(quiet = true).map { it.messageId })
    }

    @Test
    fun offersCanBeAutoClearedOnlyWhenThePersonSaysSo() {
        val offer = cm(MessageContent.Generic, promo = true, body = "Flat 50% off today only on everything in store")
        assertTrue(deck(offer).autoCleared.isEmpty())
        val d = deck(offer, settings = TriageSettings().withMode(TriageStackSpec.OFFERS, TriageMode.AUTO_CLEAR))
        assertEquals(1, d.autoCleared.size)
        assertEquals(0, d.quietCount)
    }

    @Test
    fun keepingExpiredCodesMakesThemCards() {
        val code = cm(MessageContent.Otp("123456", "Axis", null, null), at = minutesAgo(40))
        val d = deck(code, settings = TriageSettings().withMode(TriageStackSpec.CODES, TriageMode.KEEP))
        assertEquals(1, d.toGo)
        assertFalse(d.stackOf(TriageStackSpec.CODES)!!.items.single().headline.contains("123456"))
    }

    // ---- safety

    @Test
    fun noMoneyStackCanEverBeAutoCleared_evenFromAHandEditedSetting() {
        val tampered = TriageSettings(modes = TriageStackSpec.DEFAULT_ORDER.associateWith { TriageMode.AUTO_CLEAR })
        for (spec in TriageStackSpec.ALL) {
            if (!spec.canAutoClear) {
                assertFalse(spec.id, TriageMode.AUTO_CLEAR in spec.allowed)
                assertTrue(spec.id, tampered.modeOf(spec.id) != TriageMode.AUTO_CLEAR)
                assertEquals(spec.id, spec.defaultMode, tampered.modeOf(spec.id))
            }
        }
        assertEquals(setOf(TriageStackSpec.CODES, TriageStackSpec.OFFERS), TriageStackSpec.ALL.filter { it.canAutoClear }.map { it.id }.toSet())
        val everything = listOf(
            debit(), debit(card = true), debit(received = true),
            cm(MessageContent.CardBill(BankName.AXIS, "1", "10.00", null, today.plusDays(5))),
            cm(MessageContent.ScheduledPayment(ScheduledKind.UPCOMING_DEBIT, BankName.HDFC, "1", "N", "1.00", today.plusDays(1), false)),
            cm(MessageContent.Notice(NoticeKind.REFUND_PENDING, "176.00"), body = "refund of Rs 176.00 has been initiated"),
            cm(MessageContent.Notice(NoticeKind.BALANCE_UPDATE, "100.00")),
            cm(MessageContent.FastTag(FastTagEvent.RechargeNeeded("TN01"))),
            cm(MessageContent.Life(LifeKind.DELIVERY, "Zepto", "Out for delivery", null, null, null, false)),
            cm(MessageContent.Generic, body = "A normal message from a person that is not marketing at all")
        )
        val d = TriageStacks.build(everything, UserMessageState(), tampered, now, zone)
        assertTrue(d.autoCleared.isEmpty())
        for (m in everything) assertFalse(TriageStacks.autoClearable(m, now))
        assertEquals(everything.size, d.toGo)
    }

    @Test
    fun autoClearableIsOnlyExpiredCodesAndPlainMarketing() {
        assertTrue(TriageStacks.autoClearable(cm(MessageContent.Otp("1", "A", null, null), at = minutesAgo(30)), now))
        assertFalse(TriageStacks.autoClearable(cm(MessageContent.Otp("1", "A", null, null), at = minutesAgo(2)), now))
        assertTrue(TriageStacks.autoClearable(cm(MessageContent.Generic, promo = true), now))
        assertFalse(TriageStacks.autoClearable(cm(MessageContent.Generic), now))
        assertFalse(TriageStacks.autoClearable(cm(MessageContent.Transaction(BankName.HDFC, "x", "1", "1", false, false), promo = true), now))
    }

    // ---- settings

    @Test
    fun settingsRepairUnknownMissingAndRepeatedStacks() {
        val s = TriageSettings(order = listOf("offers", "ghost", "payments", "offers"))
        assertEquals(listOf("offers", "payments", "bills", "orders", "refunds", "alerts", "codes", "other"), s.orderedIds())
        assertEquals(TriageStackSpec.DEFAULT_ORDER.toSet(), s.orderedIds().toSet())
    }

    @Test
    fun movingAndModesAreStoredAsDifferencesFromTheDefaults() {
        val s = TriageSettings().moved("refunds", -2)
        assertEquals(listOf("payments", "refunds", "bills"), s.orderedIds().take(3))
        assertEquals(s, s.moved("payments", -1))
        assertEquals(TriageMode.KEEP, TriageSettings().withMode("payments", TriageMode.AUTO_CLEAR).modeOf("payments"))
        val quiet = TriageSettings().withMode("payments", TriageMode.QUIET)
        assertEquals(TriageMode.QUIET, quiet.modeOf("payments"))
        assertEquals(emptyMap<String, TriageMode>(), quiet.withMode("payments", TriageMode.KEEP).modes)
        assertTrue(TriageSettings().isDefault)
        assertEquals(TriageSettings(), TriageSettings(order = listOf("payments", "ghost"), modes = mapOf("payments" to TriageMode.KEEP)).normalized())
        assertEquals(mapOf("offers" to TriageMode.AUTO_CLEAR), TriageSettings().withMode("offers", TriageMode.AUTO_CLEAR).normalized().modes)
        assertFalse(quiet.isDefault)
    }

    @Test
    fun stacksFollowThePersonsOrder() {
        val d = deck(debit(), cm(MessageContent.Notice(NoticeKind.BALANCE_UPDATE, "1.00")), settings = TriageSettings().moved("alerts", -4))
        assertEquals(listOf("alerts", "payments"), d.tiles.map { it.id })
    }

    // ---- gestures

    @Test
    fun gesturesCommitPastAThirdOrOnAFlick_andVerticalMovesNeedTwoCards() {
        val w = 300f
        val h = 400f
        assertEquals(TriageMove.SWEEP, TriageGestures.decide(-100f, 5f, w, h, 0f, 0f, 3))
        assertEquals(TriageMove.KEEP, TriageGestures.decide(100f, 5f, w, h, 0f, 0f, 3))
        assertEquals(TriageMove.NONE, TriageGestures.decide(80f, 0f, w, h, 0f, 0f, 3))
        assertEquals(TriageMove.KEEP, TriageGestures.decide(40f, 0f, w, h, 1500f, 0f, 3))
        assertEquals(TriageMove.NONE, TriageGestures.decide(40f, 0f, w, h, -1500f, 0f, 3))
        assertEquals(TriageMove.NEXT, TriageGestures.decide(5f, 100f, w, h, 0f, 0f, 3))
        assertEquals(TriageMove.PREVIOUS, TriageGestures.decide(5f, -100f, w, h, 0f, 0f, 3))
        assertEquals(TriageMove.NONE, TriageGestures.decide(5f, 100f, w, h, 0f, 0f, 1))
        assertEquals(TriageMove.SWEEP, TriageGestures.decide(-100f, 0f, w, h, 0f, 0f, 1))
        assertEquals(TriageMove.NONE, TriageGestures.decide(-100f, 0f, w, h, 0f, 0f, 0))
        assertEquals(TriageMove.NONE, TriageGestures.decide(Float.NaN, 0f, w, h, 0f, 0f, 3))
    }

    @Test
    fun theCursorWrapsAndStaysInPlaceWhenACardLeaves() {
        assertEquals(0, TriageGestures.next(2, 3))
        assertEquals(2, TriageGestures.previous(0, 3))
        assertEquals(0, TriageGestures.next(0, 1))
        assertEquals(1, TriageGestures.afterRemoval(1, 3))
        assertEquals(1, TriageGestures.afterRemoval(2, 2))
        assertEquals(0, TriageGestures.afterRemoval(0, 0))
        assertEquals("3 of 8", TriageGestures.counter(2, 8))
        assertEquals("", TriageGestures.counter(0, 0))
    }

    @Test
    fun hidingACardTakesItOffAtOnce_andCountsFollow() {
        val a = debit()
        val b = debit(at = minutesAgo(40))
        val d = deck(a, b)
        val after = d.without(setOf(a.id))
        assertEquals(1, after.toGo)
        assertEquals(d.readCount + 1, after.readCount)
        assertEquals(listOf(b.id), after.stackOf(TriageStackSpec.PAYMENTS)!!.items.map { it.messageId })
        assertTrue(d === d.without(emptySet()))
        assertTrue(d === d.without(setOf(9999L)))
        assertEquals(setOf(a.id, b.id), d.shownIds)
    }
}

/** The demo has something unread for every stack, so the deck is full the first time it is seen. */
class TriageDemoTest {
    @Test
    fun everyStackHasAUnreadCardInTheDemo() {
        val zone = ZoneId.of("Asia/Kolkata")
        val now = Instant.parse("2026-11-21T06:00:00Z")
        val data = com.zyke.message.domain.demo.DemoData.build(now.toEpochMilli(), zone)
        val classifier = MessageClassifier()
        val messages = data.messages.map { classifier.classify(it) }
        val deck = TriageStacks.build(messages, UserMessageState(), TriageSettings(), now, zone)
        for (spec in TriageStackSpec.ALL) {
            val stack = deck.stackOf(spec.id)!!
            val has = stack.items.size + stack.autoCleared.size
            assertTrue("${spec.title} has no demo card (${deck.stacks.map { it.id + ":" + it.items.size }})", has > 0)
        }
        assertTrue(deck.toGo >= 6)
        assertTrue(deck.autoCleared.isNotEmpty())
    }
}
