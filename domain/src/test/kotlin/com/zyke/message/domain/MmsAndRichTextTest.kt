package com.zyke.message.domain

import com.zyke.message.domain.classification.MessageClassifier
import com.zyke.message.domain.insights.BalanceReader
import com.zyke.message.domain.model.SmsMessage
import com.zyke.message.domain.mms.CapturedChat
import com.zyke.message.domain.mms.MmsAddress
import com.zyke.message.domain.mms.MmsMessages
import com.zyke.message.domain.mms.MmsPart
import com.zyke.message.domain.mms.NotificationEntry
import com.zyke.message.domain.mms.NotificationFields
import com.zyke.message.domain.mms.RichNotificationText
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MmsAndRichTextTest {
    // ---- MMS parts and ids
    @Test fun textPartsAreJoinedInOrderAndOthersLeftOut() {
        val parts = listOf(
            MmsPart(3, "text/plain", " Second ", seq = 2),
            MmsPart(1, "application/smil", "<smil/>", seq = 0),
            MmsPart(2, "image/jpeg", null, seq = 1),
            MmsPart(4, "text/plain", "First", seq = 1),
            MmsPart(5, "text/plain", "   ", seq = 3),
            MmsPart(6, "text/x-vCard", "BEGIN:VCARD", seq = 4)
        )
        assertEquals("First\nSecond", MmsMessages.text(parts))
    }

    @Test fun contentTypeParametersAreIgnored() {
        assertEquals("hi", MmsMessages.text(listOf(MmsPart(1, "text/plain; charset=utf-8", "hi"))))
        assertEquals(listOf(7L), MmsMessages.imagePartIds(listOf(MmsPart(7, "IMAGE/PNG"))))
    }

    @Test fun textWithoutItsTextIsListedForReading() {
        val parts = listOf(MmsPart(1, "text/plain", null), MmsPart(2, "text/plain", "x"), MmsPart(3, "image/png", null))
        assertEquals(listOf(1L), MmsMessages.textPartsToRead(parts).map { it.id })
    }

    @Test fun imagesAreBoundedAndOrdered() {
        val parts = (1L..10L).map { MmsPart(it, "image/jpeg", seq = (11 - it).toInt()) }
        val ids = MmsMessages.imagePartIds(parts)
        assertEquals(MmsMessages.MAX_IMAGES, ids.size)
        assertEquals(listOf(10L, 9L, 8L, 7L), ids)
        assertTrue(MmsMessages.hasImage(parts))
        assertFalse(MmsMessages.hasImage(listOf(MmsPart(1, "text/plain", "a"))))
    }

    @Test fun senderIsTheFromAddress() {
        val addresses = listOf(MmsAddress("+919800000000", 151), MmsAddress("HDFCBK", 137))
        assertEquals("HDFCBK", MmsMessages.sender(addresses))
        assertEquals("", MmsMessages.sender(listOf(MmsAddress("insert-address-token", 137), MmsAddress("+91", 151))))
        assertEquals("", MmsMessages.sender(emptyList()))
    }

    @Test fun dateIsInMillisWhateverTheProviderGave() {
        assertEquals(1_700_000_000_000L, MmsMessages.dateMillis(1_700_000_000L))
        assertEquals(1_700_000_000_000L, MmsMessages.dateMillis(1_700_000_000_000L))
    }

    @Test fun mmsIdsNeverMeetSmsOrChatIds() {
        val ids = listOf(0L, 1L, 5_000_000L, 2_000_000_000L).map { MmsMessages.idFor(it) }
        assertTrue(ids.all { MmsMessages.isMmsId(it) && it < -2_147_483_649L })
        assertEquals(listOf(0L, 1L, 5_000_000L, 2_000_000_000L), ids.map { MmsMessages.rowIdOf(it) })
        assertFalse(MmsMessages.isMmsId(42L))
        assertFalse(MmsMessages.isMmsId(-1L - Int.MAX_VALUE.toLong() - 1))
        assertNull(MmsMessages.rowIdOf(42L))
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test fun pictureSampleSizeIsAPowerOfTwoThatKeepsTheSize() {
        assertEquals(1, MmsMessages.sampleSize(800, 600, 1024))
        assertEquals(1, MmsMessages.sampleSize(0, 0, 1024))
        assertEquals(2, MmsMessages.sampleSize(4000, 3000, 1024))
        assertEquals(4, MmsMessages.sampleSize(4000, 3000, 512))
        assertTrue(MmsMessages.maxSide(true) < MmsMessages.maxSide(false))
    }

    @Test fun theWaitingHintsSayWhatToTurnOn() {
        assertTrue(com.zyke.message.domain.balance.BalanceRequests.RICH_REPLY_HINT.contains("notification access"))
        assertTrue(com.zyke.message.domain.balance.BalanceRequests.RICH_NOTE.contains("notification access"))
    }

    @Test fun usableNeedsASenderAndSomethingToShow() {
        assertTrue(MmsMessages.isUsable("A", "t", false))
        assertTrue(MmsMessages.isUsable("A", "", true))
        assertFalse(MmsMessages.isUsable("", "t", false))
        assertFalse(MmsMessages.isUsable("A", "", false))
    }

    @Test fun anMmsBodyIsReadLikeAnSmsBody() {
        val body = MmsMessages.text(listOf(MmsPart(1, "image/jpeg"), MmsPart(2, "text/plain", "Your OTP is 481516. Do not share it.")))
        val message = MessageClassifier().classify(SmsMessage(MmsMessages.idFor(9), "VM-HDFCBK", body, 1_700_000_000_000L, false, isMms = true))
        assertTrue(message.content is com.zyke.message.domain.model.MessageContent.Otp)
        val bal = MessageClassifier().classify(
            SmsMessage(MmsMessages.idFor(10), "HDFCBK", "Bal in A/C XX1234 is Rs 5,000.00", 1_700_000_000_000L, false, isMms = true)
        )
        assertEquals(1, BalanceReader.latest(listOf(bal), ZoneId.of("UTC")).size)
    }

    // ---- notification text
    private val card = "Account Balance\nHi! Thanks for reaching out to HDFC Bank!\n\nYour account balance for account ending with 5765 is ₹8,509.06"

    @Test fun expandedTextBeatsACutShortText() {
        val f = NotificationFields(title = "HDFC Bank", text = "Account Balance", bigText = card)
        assertEquals(listOf(CapturedChat("HDFC Bank", card, 99L)), RichNotificationText.capture(f, 99L))
    }

    @Test fun inboxLinesAreJoined() {
        val f = NotificationFields(title = "HDFC Bank", text = "Account Balance", textLines = card.split("\n"))
        assertEquals(card.replace("\n\n", "\n"), RichNotificationText.capture(f, 1L).single().body)
    }

    @Test fun aCardTitleGivenApartIsPutInFront() {
        val f = NotificationFields(title = "Account Balance", conversationTitle = "HDFC Bank", text = "Hi! Thanks for reaching out to HDFC Bank!\nYour balance is Rs 1")
        val got = RichNotificationText.capture(f, 5L).single()
        assertEquals("HDFC Bank", got.sender)
        assertEquals("Account Balance\nHi! Thanks for reaching out to HDFC Bank!\nYour balance is Rs 1", got.body)
    }

    @Test fun aTitleAlreadyInTheTextIsNotRepeated() {
        val f = NotificationFields(title = "Account Balance", conversationTitle = "HDFC Bank", bigText = card)
        assertEquals(card, RichNotificationText.capture(f, 5L).single().body)
    }

    @Test fun messagingEntriesBecomeMessagesWithTheirOwnTimes() {
        val f = NotificationFields(
            title = "HDFC Bank", text = "latest",
            entries = listOf(NotificationEntry("older", 10L), NotificationEntry("latest", 20L), NotificationEntry("  ", 30L))
        )
        val got = RichNotificationText.capture(f, 99L)
        assertEquals(listOf("older" to 10L, "latest" to 20L), got.map { it.body to it.at })
    }

    @Test fun anEntryWithoutATimeUsesThePostTime() {
        val f = NotificationFields(title = "A", entries = listOf(NotificationEntry("x")))
        assertEquals(77L, RichNotificationText.capture(f, 77L).single().at)
    }

    @Test fun theNewestEntryTakesTheFullerExpandedTextOnlyWhenItHoldsIt() {
        val f1 = NotificationFields(title = "HDFC Bank", bigText = card, entries = listOf(NotificationEntry("Account Balance", 3L)))
        assertEquals(card, RichNotificationText.capture(f1, 9L).single().body)
        val f2 = NotificationFields(title = "HDFC Bank", bigText = "something else entirely, much longer than the entry", entries = listOf(NotificationEntry("short", 3L)))
        assertEquals("short", RichNotificationText.capture(f2, 9L).single().body)
    }

    @Test fun noSenderOrNoTextMeansNothing() {
        assertTrue(RichNotificationText.capture(NotificationFields(text = "x"), 1L).isEmpty())
        assertTrue(RichNotificationText.capture(NotificationFields(title = "A"), 1L).isEmpty())
        assertTrue(RichNotificationText.capture(NotificationFields(title = "A", text = "  "), 1L).isEmpty())
    }

    @Test fun theCapturedCardIsReadAsABalance() {
        val f = NotificationFields(title = "HDFC Bank", bigText = card + "\n\nExplore our WhatsApp Banking for 220+ transactions all at your fingertips!")
        val got = RichNotificationText.capture(f, 1_700_000_000_000L).single()
        val message = MessageClassifier().classify(SmsMessage(1, got.sender, got.body, got.at, false, isRcs = true))
        val balance = BalanceReader.latest(listOf(message), ZoneId.of("UTC")).single()
        assertEquals("5765", balance.account)
    }
}
