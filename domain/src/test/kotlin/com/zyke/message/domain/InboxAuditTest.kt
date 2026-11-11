package com.zyke.message.domain

import com.zyke.message.domain.classification.MessageClassifier
import com.zyke.message.domain.diagnostics.Disposition
import com.zyke.message.domain.diagnostics.InboxAudit
import com.zyke.message.domain.diagnostics.InboxAuditInput
import com.zyke.message.domain.diagnostics.ProviderRow
import com.zyke.message.domain.diagnostics.ProviderSnapshot
import com.zyke.message.domain.diagnostics.ProviderSource
import com.zyke.message.domain.diagnostics.providerSourceOf
import com.zyke.message.domain.feed.FeedBuilder
import com.zyke.message.domain.feed.MessageFilter
import com.zyke.message.domain.feed.SmartFilter
import com.zyke.message.domain.ledger.SamePaymentKind
import com.zyke.message.domain.ledger.SamePaymentLink
import com.zyke.message.domain.mms.MmsMessages
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.SmsMessage
import com.zyke.message.domain.model.UserMessageState
import com.zyke.message.domain.usecase.LoadProgress
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InboxAuditTest {
    private val zone = ZoneId.of("UTC")
    private val now = Instant.parse("2026-11-11T10:00:00Z")
    private val t = now.toEpochMilli()
    private val classifier = MessageClassifier()

    private fun sms(id: Long, sender: String, body: String, minutesAgo: Long = id) =
        classifier.classify(SmsMessage(id, sender, body, t - minutesAgo * 60_000, isRead = false))

    private val debit = "Sent Rs.540.00\nFrom HDFC Bank A/C *5765\nTo BURMY FOODS\nOn 26/10/26\nRef 1"

    private fun input(
        messages: List<ClassifiedMessage>,
        providerIds: List<Long> = messages.map { it.id },
        materialized: List<Long> = providerIds,
        folded: Map<Long, Long> = emptyMap(),
        merges: Map<Long, Long> = emptyMap(),
        state: UserMessageState = UserMessageState(),
        filter: MessageFilter = MessageFilter.All,
        progress: LoadProgress = LoadProgress(),
        newest: List<ProviderRow> = emptyList()
    ) = InboxAuditInput(
        ProviderSnapshot(providerIds.toLongArray(), materialized.toLongArray(), smsOtherTypes = 4, newest = newest),
        messages, folded, merges, state, filter, progress, now, zone
    )

    @Test
    fun everyMessageOnThePhoneIsInExactlyOneDispositionAndTheyAddUp() {
        val shown = sms(1, "AX-HDFCBK-T", debit)
        val promo = sms(2, "VM-SHOPX-P", "Big sale, use code SAVE20 T&C apply")
        val muted = sms(3, "+919876543210", "hello there").copy(isMuted = true, isPromotional = true)
        val archived = sms(4, "VM-SPINNY-S", "Your test drive is booked")
        val report = InboxAudit.build(input(listOf(shown, promo, muted, archived), state = UserMessageState(archivedIds = setOf(4L))))
        assertEquals(4, report.providerTotal)
        assertEquals(4, report.accountedFor)
        assertEquals(1, report.count(Disposition.ROW))
        assertEquals(1, report.count(Disposition.PROMOTIONAL))
        assertEquals(1, report.count(Disposition.MUTED))
        assertEquals(1, report.count(Disposition.ARCHIVED))
        assertEquals(0, report.count(Disposition.UNEXPLAINED))
        assertEquals(1, report.rowsInAll)
        assertEquals(3, report.notReachableInAll)
        assertEquals(4, report.smsOtherTypes)
    }

    @Test
    fun theAuditAgreesWithWhatTheAllFeedShows() {
        val msgs = (1L..12L).map { sms(it, if (it % 3 == 0L) "VM-SHOPX-P" else "AX-HDFCBK-T", if (it % 3 == 0L) "Big sale T&C apply" else debit) }
        val feed = FeedBuilder.build(msgs, UserMessageState(), MessageFilter.All, "", now, zone)
        val report = InboxAudit.build(input(msgs))
        assertEquals(feed.visibleCount, report.rowsInAll)
        assertEquals(feed.visibleCount, report.count(Disposition.ROW))
    }

    @Test
    fun relatedMessagesAreReachableInsideTheirPaymentRow() {
        val payment = sms(1, "AX-HDFCBK-T", debit)
        val follower = sms(2, "VM-RZRPAY-S", "Payment of Rs 540 to Burmy Foods successful").copy(
            samePayment = SamePaymentLink(SamePaymentKind.CERTAIN, payment.id, "HDFC debit", "ref", "ref")
        )
        val report = InboxAudit.build(input(listOf(payment, follower), newest = listOf(
            ProviderRow(2, follower.message.date, "VM-RZRPAY-S", 41, ProviderSource.SMS), ProviderRow(1, payment.message.date, "AX-HDFCBK-T", 60, ProviderSource.SMS)
        )))
        assertEquals(1, report.rowsInAll)
        assertEquals(1, report.count(Disposition.RELATED))
        assertEquals(0, report.notReachableInAll)
        val line = report.newest.first()
        assertEquals(Disposition.RELATED, line.disposition)
        assertEquals(payment.message.date, line.rowDate)
        assertEquals(InboxAudit.mask("AX-HDFCBK-T"), line.rowSender)
    }

    @Test
    fun severalCodesFromOneSenderOnOneDayAreOneRowAndAllReachable() {
        val codes = (1L..3L).map { sms(it, "VM-SPINNY-S", "48291$it is your OTP for login", minutesAgo = it) }
        val report = InboxAudit.build(input(codes))
        assertEquals(1, report.rowsInAll)
        assertEquals(1, report.count(Disposition.ROW))
        assertEquals(2, report.count(Disposition.IN_GROUP))
        assertEquals(0, report.notReachableInAll)
    }

    @Test
    fun foldedMergedAndNotYetLoadedRowsAreTold() {
        val mmsId = MmsMessages.idFor(5)
        val kept = sms(10, "AX-HDFCBK-T", debit)
        val folded = -77L
        val mergedAway = 11L
        val waiting = 12L
        val unusableMms = MmsMessages.idFor(6)
        val duplicateChat = -88L
        val report = InboxAudit.build(
            input(
                messages = listOf(kept),
                providerIds = listOf(10, mmsId, folded, mergedAway, waiting, unusableMms, duplicateChat),
                materialized = listOf(10, mmsId, folded, mergedAway, waiting),
                folded = mapOf(folded to mmsId, mmsId to mmsId).filterKeys { it == folded },
                merges = mapOf(mergedAway to 10L),
                progress = LoadProgress(monthsLoaded = 1, monthsTotal = 2, isDone = false)
            )
        )
        assertEquals(7, report.accountedFor)
        assertEquals(1, report.count(Disposition.ROW))
        assertEquals(1, report.count(Disposition.FOLDED_RICH))
        assertEquals(1, report.count(Disposition.MERGED_BY_YOU))
        assertEquals(2, report.count(Disposition.NOT_LOADED)) // the picture message and the waiting SMS: read, not in the list
        assertEquals(1, report.count(Disposition.MMS_UNUSABLE))
        assertEquals(1, report.count(Disposition.RCS_SEEN_AS_SMS))
        assertEquals(0, report.count(Disposition.UNEXPLAINED))
        assertEquals(3, report.smsInbox)
        assertEquals(2, report.mmsInbox)
        assertEquals(2, report.chatCaptured)
    }

    @Test
    fun aMessageThatIsInTheListButNotInAllAndHasNoReasonIsFlagged() {
        // Nothing hides a plain message: it is a row. An unknown reason can only come from a message the feed dropped, which would be the bug.
        val plain = sms(1, "AX-HDFCBK-T", debit)
        assertEquals(0, InboxAudit.build(input(listOf(plain))).count(Disposition.UNEXPLAINED))
    }

    @Test
    fun theNewestRowsCarryMaskedSendersLengthsAndNoText() {
        val body = "Secret body text 123456"
        val m = sms(1, "+919876543210", body)
        val report = InboxAudit.build(input(listOf(m), newest = listOf(ProviderRow(1, m.message.date, "+919876543210", body.length, ProviderSource.SMS, type = 1))))
        val line = report.newest.single()
        assertEquals(body.length, line.length)
        assertFalse("9876543210" in line.maskedSender)
        assertTrue(line.maskedSender.startsWith("+91"))
        assertTrue(line.maskedSender.endsWith("10"))
        val text = report.toText(zone)
        assertFalse("Secret" in text)
        assertFalse("9876543210" in text)
        assertTrue("inbox audit" in text.lowercase())
    }

    @Test
    fun sendersAreMaskedByKind() {
        assertEquals("AX-HD…-T", InboxAudit.mask("AX-HDFCBK-T"))
        assertEquals("(none)", InboxAudit.mask(" "))
        assertEquals("R•••", InboxAudit.mask("Raja"))
        assertFalse("123456" in InboxAudit.mask("56123456"))
    }

    @Test
    fun sourcesAreToldFromTheIdRanges() {
        assertEquals(ProviderSource.SMS, providerSourceOf(5))
        assertEquals(ProviderSource.RCS, providerSourceOf(-5))
        assertEquals(ProviderSource.MMS, providerSourceOf(MmsMessages.idFor(5)))
    }

    @Test
    fun hiddenByFiltersCountsWhatAChipLeavesOutAndNothingWhenNoFilterIsOn() {
        val otp = sms(1, "VM-SPINNY-S", "482913 is your OTP for login")
        val pay = sms(2, "AX-HDFCBK-T", debit)
        val promo = sms(3, "VM-SHOPX-P", "Big sale T&C apply")
        val msgs = listOf(otp, pay, promo)
        val all = FeedBuilder.build(msgs, UserMessageState(), MessageFilter.All, "", now, zone)
        assertEquals(0, all.hiddenByFilters)
        val otpOnly = FeedBuilder.build(msgs, UserMessageState(), MessageFilter(smart = SmartFilter.OTP), "", now, zone)
        assertEquals(1, otpOnly.visibleCount)
        assertEquals(1, otpOnly.hiddenByFilters) // the payment; the promotion is not in All to begin with
        val searching = FeedBuilder.build(msgs, UserMessageState(), MessageFilter(smart = SmartFilter.OTP), "sale", now, zone)
        assertEquals(0, searching.hiddenByFilters)
        val auditHidden = InboxAudit.build(input(msgs, filter = MessageFilter(smart = SmartFilter.OTP))).hiddenByFilters
        assertEquals(1, auditHidden)
    }

    @Test
    fun aRelatedMessageIsNotCountedAsHiddenByAFilterWhenItsPaymentIsShown() {
        val payment = sms(1, "AX-HDFCBK-T", debit)
        val follower = sms(2, "VM-RZRPAY-S", "Payment of Rs 540 to Burmy Foods successful").copy(
            samePayment = SamePaymentLink(SamePaymentKind.CERTAIN, payment.id, "HDFC debit", "ref", "ref")
        )
        val feed = FeedBuilder.build(listOf(payment, follower), UserMessageState(), MessageFilter(smart = SmartFilter.MONEY), "", now, zone)
        assertEquals(0, feed.hiddenByFilters)
    }

    @Test
    fun fastTagStatusMessagesAreRowsLikeAnyOther() {
        val active = sms(1, "VM-FASTAG", "Your FASTag for vehicle no. KA01AB1234 is now active")
        val low = sms(2, "VM-FASTAG", "Your FASTag vehicle KA01AB1234 is low on balance")
        assertTrue(active.content is com.zyke.message.domain.model.MessageContent.FastTag)
        assertTrue(low.content is com.zyke.message.domain.model.MessageContent.FastTag)
        // Before, these two never appeared in All or in Travel; a real message from the bank is a row.
        assertEquals(2, FeedBuilder.build(listOf(active, low), UserMessageState(), MessageFilter.All, "", now, zone).visibleCount)
        assertEquals(2, FeedBuilder.build(listOf(active, low), UserMessageState(), MessageFilter(smart = SmartFilter.TRAVEL), "", now, zone).visibleCount)
    }
}
