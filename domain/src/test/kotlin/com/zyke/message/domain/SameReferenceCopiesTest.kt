package com.zyke.message.domain

import com.zyke.message.domain.accounts.Accounts
import com.zyke.message.domain.classification.MessageClassifier
import com.zyke.message.domain.diagnostics.Disposition
import com.zyke.message.domain.diagnostics.InboxAudit
import com.zyke.message.domain.diagnostics.InboxAuditInput
import com.zyke.message.domain.diagnostics.ProviderSnapshot
import com.zyke.message.domain.feed.FeedBuilder
import com.zyke.message.domain.feed.FeedItem
import com.zyke.message.domain.feed.MessageFilter
import com.zyke.message.domain.insights.AccountBalance
import com.zyke.message.domain.insights.BalanceReader
import com.zyke.message.domain.ledger.Counted
import com.zyke.message.domain.ledger.Counting
import com.zyke.message.domain.ledger.PaymentGroups
import com.zyke.message.domain.ledger.SamePayment
import com.zyke.message.domain.ledger.SamePaymentDecisions
import com.zyke.message.domain.ledger.SamePaymentKind
import com.zyke.message.domain.mms.InboxCopies
import com.zyke.message.domain.mms.MmsMessages
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.SmsMessage
import com.zyke.message.domain.model.UserMessageState
import com.zyke.message.domain.usecase.LoadProgress
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The same HDFC alert twice (identical or near-identical text, one transaction reference): one payment. Without a shared
 * reference, identical alerts stay separate payments and every one counts.
 */
class SameReferenceCopiesTest {
    private val zone = ZoneId.of("UTC")
    private val classifier = MessageClassifier()
    private val now = Instant.parse("2026-11-11T20:00:00Z")
    private var nextId = 1L

    private fun at(hour: Int, minute: Int, second: Int = 0, day: Int = 11) =
        LocalDate.of(2026, 11, day).atTime(hour, minute, second).atZone(zone).toInstant().toEpochMilli()

    private fun msg(sender: String, body: String, date: Long, id: Long = nextId++) =
        classifier.classify(SmsMessage(id, sender, body, date, false))

    private fun sent(amount: String, to: String, ref: String?, date: Long, sender: String = "AX-HDFCBK-T", acct: String = "5765", id: Long = nextId++) =
        msg(sender, "Sent Rs.$amount From HDFC Bank A/c $acct To $to On 11/11/26" + (ref?.let { " Ref $it" } ?: "") + " Not You? Call 18002586161", date, id)

    private fun credited(amount: String, vpa: String, ref: String?, date: Long, sender: String = "AX-HDFCBK-T", id: Long = nextId++) =
        msg(sender, "Rs.$amount credited to HDFC Bank A/c XX5765 on 11-11-26 from VPA $vpa" + (ref?.let { " (UPI Ref No $it)" } ?: ""), date, id)

    private fun link(list: List<ClassifiedMessage>, decisions: SamePaymentDecisions = SamePaymentDecisions()) =
        SamePayment.apply(list.sortedByDescending { it.message.date }, decisions, zone)

    private fun spent(list: List<ClassifiedMessage>) = list.fold(BigDecimal.ZERO) { a, m -> a + (Counting.spend(m) ?: BigDecimal.ZERO) }
    private fun income(list: List<ClassifiedMessage>) = list.fold(BigDecimal.ZERO) { a, m -> a + (Counting.income(m) ?: BigDecimal.ZERO) }
    private fun assertMoney(expected: Long, actual: BigDecimal) = assertEquals("expected $expected got $actual", 0, BigDecimal(expected).compareTo(actual))
    private fun List<ClassifiedMessage>.byId(id: Long) = single { it.id == id }

    // ---- the reference extractor

    @Test
    fun theReferenceIsReadFromHdfcWording() {
        assertEquals(setOf("612345678901"), SamePayment.transactionRefs("Sent Rs.150.00 From HDFC Bank A/c 5765 To APOLLO On 11/11/26 Ref 612345678901 Not You? Call 1800"))
        assertEquals(setOf("612345678902"), SamePayment.transactionRefs("Rs.10000.00 credited to HDFC Bank A/c XX5765 on 11-11-26 from VPA 40-5@okhdfcbank (UPI Ref No 612345678902)"))
        assertEquals(setOf("612345678903"), SamePayment.transactionRefs("Rs 5 debited. UPI Ref No. 612345678903."))
        assertEquals(setOf("612345678904"), SamePayment.transactionRefs("Paid. Ref No: 612345678904"))
        assertEquals(setOf("612345678905"), SamePayment.transactionRefs("Paid via UPI Ref. No. 612345678905 ok"))
        assertEquals(setOf("612345678906"), SamePayment.transactionRefs("RRN:612345678906 done"))
        assertEquals(setOf("612345678907"), SamePayment.transactionRefs("UPI:612345678907"))
        // Not a reference: a phone number after "Call", a short number, an amount.
        assertTrue(SamePayment.transactionRefs("Not You? Call 18002586161").isEmpty())
        assertTrue(SamePayment.transactionRefs("Rs.150.00 debited, Ref 1234").isEmpty())
        assertTrue(SamePayment.transactionRefs("Rs.150.00 debited from A/c 5765").isEmpty())
    }

    // ---- the three pairs from the screenshot

    @Test
    fun threeIdenticalPairsWithTheirReferenceAreThreePayments_countedOnce() {
        val a1 = sent("150.00", "APOLLO PHARMACY", "612345678901", at(21, 17, 5))
        val a2 = sent("150.00", "APOLLO PHARMACY", "612345678901", at(21, 17, 6), sender = "HDFCBK")
        val b1 = credited("10000.00", "40-5@okhdfcbank", "612345678902", at(20, 52, 10))
        val b2 = credited("10000.00", "40-5@okhdfcbank", "612345678902", at(20, 52, 11), sender = "VM-HDFCBK-S")
        val c1 = sent("3500.00", "Ashwin Balaji S", "612345678903", at(19, 6, 0))
        val c2 = sent("3500.00", "Ashwin Balaji S", "612345678903", at(19, 6, 1))
        val raw = listOf(a1, a2, b1, b2, c1, c2)
        val all = link(raw)

        for ((first, second) in listOf(a1 to a2, b1 to b2, c1 to c2)) {
            assertNull(all.byId(first.id).samePayment)
            val l = all.byId(second.id).samePayment!!
            assertEquals(SamePaymentKind.CERTAIN, l.kind)
            assertEquals(first.id, l.primaryId)
            assertEquals(SamePayment.LAYER_COPY, l.layer)
            assertTrue(Counting.of(all.byId(second.id)) is Counted.NotCounted)
            assertEquals("Not counted: same payment as the ${l.primaryLabel}, which is counted", (Counting.of(all.byId(second.id)) as Counted.NotCounted).reason)
        }
        assertMoney(3650, spent(all))
        assertMoney(10000, income(all))
        assertMoney(7300, spent(raw))

        // One row each, "+1 copy".
        val feed = FeedBuilder.build(all.sortedByDescending { it.message.date }, UserMessageState(), MessageFilter.All, "", now, zone)
        val rows = feed.sections.flatMap { it.items }
        assertEquals(3, rows.size)
        for (row in rows) {
            row as FeedItem.Single
            assertEquals(1, row.related.size)
            assertEquals("+1 copy", PaymentGroups.relatedCaption(row.related))
        }
    }

    @Test
    fun balanceMovesOnceForACopy_andAccountTotalsCountOnce() {
        val reading = AccountBalance("5765", "HDFC", BigDecimal(10_000), LocalDate.of(2026, 11, 10), 0L, at(9, 0, day = 10))
        val d1 = sent("150.00", "APOLLO PHARMACY", "612345678901", at(21, 17, 5))
        val d2 = sent("150.00", "APOLLO PHARMACY", "612345678901", at(21, 17, 6), sender = "HDFCBK")
        val c1 = credited("10000.00", "40-5@okhdfcbank", "612345678902", at(20, 52, 10))
        val c2 = credited("10000.00", "40-5@okhdfcbank", "612345678902", at(20, 52, 11), sender = "VM-HDFCBK-S")
        val raw = listOf(d1, d2, c1, c2).sortedByDescending { it.message.date }
        // Unlinked, the debit and the credit are each counted twice.
        assertMoney(19700, BalanceReader.movement(raw, reading).net)
        val all = link(raw)
        assertMoney(9850, BalanceReader.movement(all, reading).net)
        assertEquals(2, BalanceReader.movement(all, reading).count)

        val account = Accounts.build(all, emptyList(), now, zone).accounts.single()
        assertMoney(150, account.spent)
        assertMoney(10000, account.received)
    }

    // ---- what must stay separate

    @Test
    fun fiveIdenticalAlertsWithNoReference_stayFive() {
        val five = (0 until 5).map { sent("500.00", "SHOP", null, at(10, it, 0)) }
        val all = link(five)
        assertTrue(all.all { it.samePayment == null })
        assertMoney(2500, spent(all))
        val sameSecondish = (0 until 5).map { sent("500.00", "SHOP", null, at(11, 0, 0) + it) }
        assertTrue(link(sameSecondish).all { it.samePayment == null })
    }

    @Test
    fun fiveIdenticalAlertsWithFiveDifferentReferences_stayFive() {
        val five = (0 until 5).map { sent("500.00", "SHOP", "61234567890$it", at(10, it, 0)) }
        val all = link(five)
        assertTrue(all.all { it.samePayment == null })
        assertMoney(2500, spent(all))
    }

    @Test
    fun sameReferenceButAnotherAmountOrAccountOrDirection_isNotOnePayment() {
        val base = sent("150.00", "SHOP", "612345678901", at(10, 0))
        val otherAmount = sent("160.00", "SHOP", "612345678901", at(10, 1))
        val otherAccount = sent("150.00", "SHOP", "612345678901", at(10, 2), acct = "1111")
        val credit = credited("150.00", "shop@upi", "612345678901", at(10, 3))
        val all = link(listOf(base, otherAmount, otherAccount, credit))
        assertTrue(all.all { it.samePayment == null })
        assertMoney(460, spent(all))
        assertMoney(150, income(all))
    }

    @Test
    fun twoIdenticalAlertsAMinuteApartWithoutAReference_stayTwo() {
        val x = sent("150.00", "APOLLO PHARMACY", null, at(21, 17, 0))
        val y = sent("150.00", "APOLLO PHARMACY", null, at(21, 18, 0))
        val all = link(listOf(x, y))
        assertTrue(all.all { it.samePayment == null })
        assertMoney(300, spent(all))
    }

    @Test
    fun aReferenceOnOneCopyOnly_isNotEnough() {
        val x = sent("150.00", "APOLLO", "612345678901", at(21, 17, 0))
        val y = sent("150.00", "APOLLO", null, at(21, 17, 1))
        assertTrue(link(listOf(x, y)).all { it.samePayment == null })
    }

    @Test
    fun theWindowIs48Hours() {
        val x = sent("150.00", "APOLLO", "612345678901", at(10, 0, day = 9))
        val near = sent("150.00", "APOLLO", "612345678901", at(9, 0, day = 11))
        val far = sent("150.00", "APOLLO", "612345678901", at(10, 1, day = 11), id = 99)
        assertEquals(SamePaymentKind.CERTAIN, link(listOf(x, near)).byId(near.id).samePayment?.kind)
        assertNull(link(listOf(x, far)).byId(far.id).samePayment)
    }

    // ---- which copy stays, wording, the person's word

    @Test
    fun threeCopiesAreOnePayment_andTheOneStatingTheBalanceStays() {
        val a = sent("150.00", "APOLLO", "612345678901", at(21, 17, 0))
        val b = msg("AX-HDFCBK-T", "Sent Rs.150.00 From HDFC Bank A/c 5765 To APOLLO On 11/11/26 Ref 612345678901 Avl Bal Rs 9,850.00", at(21, 17, 30))
        val c = sent("150.00", "APOLLO", "612345678901", at(21, 18, 0))
        val all = link(listOf(a, b, c))
        assertNull(all.byId(b.id).samePayment)
        assertEquals(b.id, all.byId(a.id).samePayment?.primaryId)
        assertEquals(b.id, all.byId(c.id).samePayment?.primaryId)
        // The copy words differ from the statement's, so they are "related", not word for word copies.
        assertEquals("ref", all.byId(a.id).samePayment?.layer)
        assertEquals("ref", all.byId(c.id).samePayment?.layer)
        assertMoney(150, spent(all))
        val groups = PaymentGroups.of(all)
        assertEquals(2, groups.followersOf(b.id).size)
        assertEquals("+2 related", PaymentGroups.relatedCaption(groups.followersOf(b.id)))
    }

    @Test
    fun aCutOffCopyWithTheSameReferenceIsTheSamePayment() {
        val full = sent("150.00", "APOLLO PHARMACY", "612345678901", at(21, 17, 0))
        val cut = msg("HDFC Bank", "Sent Rs.150.00 From HDFC Bank A/c 5765 To APOLLO PHARMACY On 11/11/26 Ref 612345678901", at(21, 17, 3))
        val all = link(listOf(full, cut))
        assertEquals(full.id, all.byId(cut.id).samePayment?.primaryId)
        assertMoney(150, spent(all))
    }

    @Test
    fun theSmsStaysOverAChatCopyAndAPictureMessageCopy() {
        val body = "Sent Rs.150.00 From HDFC Bank A/c 5765 To APOLLO On 11/11/26 Ref 612345678901 Not You? Call 18002586161"
        val chat = msg("AX-HDFCBK-T", body, at(21, 17, 0), id = -50)
        val picture = msg("AX-HDFCBK-T", body, at(21, 17, 1), id = MmsMessages.idFor(7))
        val sms = msg("AX-HDFCBK-T", body, at(21, 17, 20), id = 400)
        val all = link(listOf(chat, picture, sms))
        assertNull(all.byId(sms.id).samePayment)
        assertEquals(sms.id, all.byId(chat.id).samePayment?.primaryId)
        assertEquals(sms.id, all.byId(picture.id).samePayment?.primaryId)
        assertMoney(150, spent(all))
    }

    @Test
    fun notTheSamePayment_onTheCopy_keepsBothAndUndoes() {
        val a = sent("150.00", "APOLLO", "612345678901", at(21, 17, 0))
        val b = sent("150.00", "APOLLO", "612345678901", at(21, 17, 1))
        assertEquals(a.id, link(listOf(a, b)).byId(b.id).samePayment?.primaryId)
        val said = SamePaymentDecisions(no = setOf(b.id))
        val all = link(listOf(a, b), said)
        assertTrue(all.all { it.samePayment == null })
        assertMoney(300, spent(all))
    }

    @Test
    fun aSenderRuleNeverHidesACopyThatSharesAReference() {
        val a = sent("150.00", "APOLLO PHARMACY", "612345678901", at(21, 17, 0))
        val b = sent("150.00", "APOLLO PHARMACY", "612345678901", at(21, 17, 1))
        val key = com.zyke.message.domain.categories.CategoryKey.of(b)!!
        val all = link(listOf(a, b), SamePaymentDecisions(sendersNo = setOf(key)))
        assertEquals(SamePaymentKind.CERTAIN, all.byId(b.id).samePayment?.kind)
    }

    // ---- the same text delivered twice before anything is read

    private fun raw(id: Long, sender: String, body: String, date: Long, rcs: Boolean = false, mms: Boolean = false) =
        SmsMessage(id, sender, body, date, false, isRcs = rcs, isMms = mms)

    private val hdfc = "Sent Rs.150.00 From HDFC Bank A/c 5765 To APOLLO On 11/11/26 Ref 612345678901 Not You? Call 18002586161"

    @Test
    fun aChatNotificationCopyFoldsIntoTheSms_neverTheReverse() {
        val sms = raw(10, "AX-HDFCBK-T", hdfc, at(21, 17, 0))
        val chat = raw(-5, "HDFCBK", hdfc.uppercase(), at(21, 22, 0), rcs = true)
        val result = InboxCopies.apply(listOf(chat, sms))
        assertEquals(listOf(10L), result.messages.map { it.id })
        assertEquals(mapOf(-5L to 10L), result.folded)
        // A text cut off by the notification is still a copy.
        val cut = raw(-6, "AX-HDFCBK-T", hdfc.take(70), at(21, 17, 5), rcs = true)
        assertEquals(mapOf(-6L to 10L), InboxCopies.apply(listOf(sms, cut)).folded)
    }

    @Test
    fun aPictureMessageCopyFoldsIntoTheSms() {
        val sms = raw(10, "AX-HDFCBK-T", hdfc, at(21, 17, 0))
        val mms = raw(MmsMessages.idFor(3), "AX-HDFCBK-T", hdfc, at(21, 17, 2), mms = true)
        assertEquals(mapOf(mms.id to 10L), InboxCopies.apply(listOf(sms, mms)).folded)
    }

    @Test
    fun aCopyNeedsTheSameSenderTheSameTextAndTenMinutes() {
        val sms = raw(10, "AX-HDFCBK-T", hdfc, at(21, 17, 0))
        assertTrue(InboxCopies.apply(listOf(sms, raw(-5, "VM-AXISBK-S", hdfc, at(21, 17, 5), rcs = true))).folded.isEmpty())
        assertTrue(InboxCopies.apply(listOf(sms, raw(-5, "AX-HDFCBK-T", hdfc.replace("150", "250"), at(21, 17, 5), rcs = true))).folded.isEmpty())
        assertTrue(InboxCopies.apply(listOf(sms, raw(-5, "AX-HDFCBK-T", hdfc, at(21, 29, 0), rcs = true))).folded.isEmpty())
        // One SMS absorbs one copy only: a second SMS of the same text keeps its own.
        val twin = raw(11, "AX-HDFCBK-T", hdfc, at(21, 17, 30))
        val chat = raw(-5, "AX-HDFCBK-T", hdfc, at(21, 17, 31), rcs = true)
        val result = InboxCopies.apply(listOf(sms, twin, chat))
        assertEquals(mapOf(-5L to 11L), result.folded)
        assertEquals(setOf(10L, 11L), result.messages.map { it.id }.toSet())
    }

    @Test
    fun aProviderDoubleInsertOfTheSameMillisecondFolds() {
        val t = at(21, 17, 5) + 417
        val first = raw(10, "AX-HDFCBK-T", hdfc, t)
        val second = raw(11, "AX-HDFCBK-T", hdfc, t)
        val result = InboxCopies.apply(listOf(second, first))
        assertEquals(mapOf(11L to 10L), result.folded)
        assertEquals(listOf(10L), result.messages.map { it.id })
        // Idempotent.
        assertTrue(InboxCopies.apply(result.messages).folded.isEmpty())
    }

    @Test
    fun aProviderDoubleInsertWithAReferenceAndASecondApartFolds_withoutOneItDoesNot() {
        val a = raw(10, "AX-HDFCBK-T", hdfc, at(21, 17, 5) + 100)
        val b = raw(11, "AX-HDFCBK-T", hdfc, at(21, 17, 5) + 900)
        assertEquals(mapOf(11L to 10L), InboxCopies.apply(listOf(a, b)).folded)
        val plain = "Rs.500.00 debited from A/c XX5765 at SHOP on 11-11-26"
        val c = raw(12, "AX-HDFCBK-T", plain, at(21, 17, 5) + 100)
        val d = raw(13, "AX-HDFCBK-T", plain, at(21, 17, 5) + 900)
        assertTrue(InboxCopies.apply(listOf(c, d)).folded.isEmpty())
        // The same address and text, different milliseconds, no reference: two alerts.
        assertTrue(InboxCopies.apply(listOf(raw(14, "AX-HDFCBK-T", plain, at(21, 17, 5) + 1), raw(15, "AX-HDFCBK-T", plain, at(21, 17, 5) + 2))).folded.isEmpty())
    }

    @Test
    fun aDateWithNoMillisecondsCannotShowAnIdenticalInstant() {
        val plain = "Rs.500.00 debited from A/c XX5765 at SHOP on 11-11-26"
        val a = raw(10, "AX-HDFCBK-T", plain, at(21, 17, 5))
        val b = raw(11, "AX-HDFCBK-T", plain, at(21, 17, 5))
        assertTrue(InboxCopies.apply(listOf(a, b)).folded.isEmpty())
    }

    @Test
    fun twoRealIdenticalAlertsAMinuteApart_stayTwoMessages() {
        val a = raw(10, "AX-HDFCBK-T", hdfc, at(21, 17, 5) + 417)
        val b = raw(11, "AX-HDFCBK-T", hdfc, at(21, 18, 5) + 417)
        val result = InboxCopies.apply(listOf(a, b))
        assertTrue(result.folded.isEmpty())
        assertEquals(2, result.messages.size)
        val plain = "Rs.500.00 debited from A/c XX5765 at SHOP on 11-11-26"
        assertTrue(InboxCopies.apply(listOf(raw(12, "AX-HDFCBK-T", plain, at(21, 17, 5) + 417), raw(13, "AX-HDFCBK-T", plain, at(21, 18, 5) + 417))).folded.isEmpty())
    }

    @Test
    fun anUpiLiteNotificationAndAPlainTextAreNotFolded() {
        val sms = raw(10, "AX-HDFCBK-T", hdfc, at(21, 17, 0))
        val short = raw(-9, "AX-HDFCBK-T", "ok", at(21, 17, 1), rcs = true)
        assertTrue(InboxCopies.apply(listOf(sms, short)).folded.isEmpty())
    }

    // ---- the inbox audit says which copy it is

    @Test
    fun theAuditListsAlikeRowsWithTheirSourceAndSenderHeader_noText() {
        val a = sent("150.00", "APOLLO PHARMACY", "612345678901", at(21, 17, 5), id = 5)
        val b = sent("150.00", "APOLLO PHARMACY", "612345678901", at(21, 17, 6), sender = "HDFCBK", id = MmsMessages.idFor(2))
        val c = sent("150.00", "APOLLO PHARMACY", null, at(21, 17, 30), sender = "HDFCBK", id = -77)
        val all = link(listOf(a, b, c))
        val report = InboxAudit.build(
            InboxAuditInput(
                ProviderSnapshot(all.map { it.id }.toLongArray(), all.map { it.id }.toLongArray(), 0, emptyList()),
                all, emptyMap(), emptyMap(), UserMessageState(), MessageFilter.All, LoadProgress(), now, zone
            )
        )
        val group = report.alike.single()
        assertEquals(3, group.members.size)
        assertEquals(listOf("sms", "mms", "chat"), group.members.map { it.source.label })
        assertTrue(group.members[1].sameTextAsFirst)
        assertFalse(group.members[2].sameTextAsFirst)
        assertTrue(group.members[1].sharesRefWithFirst)
        assertEquals(0, group.members[2].refCount)
        assertEquals("certain repeat (copy)", group.members[1].decision)
        assertEquals(Disposition.RELATED, group.members[1].disposition)
        val text = report.toText(zone)
        assertTrue(text, text.contains("Payment rows that look alike"))
        assertTrue(text.contains("mms (mms provider id)"))
        assertTrue(text.contains("chat (notification-captured chat id)"))
        assertTrue(text.contains("same reference as the first"))
        assertFalse(text.contains("APOLLO"))
        assertFalse(text.contains("612345678901"))
    }
}
