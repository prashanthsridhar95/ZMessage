package com.zyke.message.domain

import com.zyke.message.domain.model.SmsMessage
import com.zyke.message.domain.mms.MmsMessages
import com.zyke.message.domain.mms.MmsPart
import com.zyke.message.domain.mms.MmsPartInfo
import com.zyke.message.domain.mms.MmsStructureDump
import com.zyke.message.domain.mms.RbmCardText
import com.zyke.message.domain.mms.RbmSender
import com.zyke.message.domain.mms.RichMerge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RichMergeTest {
    private val t0 = 1_700_000_000_000L
    private val agent = "hdfc_bank_gkjmtq7y_agent@rbm.google.com"
    private val card = "Account Balance\nHi! Thanks for reaching out to HDFC Bank!\nYour account balance for account ending with 5765 is ₹8,509.06\nExplore our WhatsApp Banking"

    private fun mms(row: Long, sender: String, at: Long, body: String = MmsMessages.PICTURE_ONLY_TEXT) =
        SmsMessage(MmsMessages.idFor(row), sender, body, at, isRead = false, isMms = true)

    private fun note(id: Long, sender: String, at: Long, body: String, read: Boolean = false) =
        SmsMessage(id, sender, body, at, isRead = read, isRcs = true)

    // ---- sender names
    @Test fun rbmAddressesBecomeFriendlyNames() {
        assertEquals("HDFC Bank", RbmSender.friendly(agent))
        assertEquals("HDFC Bank", RbmSender.friendly("hdfc_bank_gjkmtq7y_agent@rbm.google.com"))
        assertEquals("SBI Card", RbmSender.friendly("sbi_card_ab12cd34_agent@rbm.google.com"))
        assertEquals("Amazon Pay", RbmSender.friendly("amazon_pay_xyz789_agent@rbm.google.com"))
        assertEquals("ICICI Bank", RbmSender.friendly("icici_bank_qwrtpsdf_agent@rbm.google.com"))
        assertEquals("Brand Name", RbmSender.friendly("brand-name_xxxx12_agent@rbm.goog"))
        assertEquals("HDFC Bank", RbmSender.friendly("HDFC_BANK_GKJMTQ7Y_AGENT@RBM.GOOGLE.COM"))
        assertEquals("UPI Help", RbmSender.friendly("upi_help_agent@rbm.google.com"))
    }

    @Test fun aRealWordIsNotTakenForTheId() {
        assertEquals("Standard Chartered", RbmSender.friendly("standard_chartered_agent@rbm.google.com"))
        assertEquals("HDFC", RbmSender.friendly("hdfc_agent@rbm.google.com"))
    }

    @Test fun otherSendersAreUntouched() {
        assertEquals("HDFC Bank", RbmSender.friendly("HDFC Bank"))
        assertEquals("+919800000000", RbmSender.friendly("+919800000000"))
        assertEquals("a@gmail.com", RbmSender.friendly("a@gmail.com"))
        assertFalse(RbmSender.isRbm("VK-HDFCBK"))
        assertTrue(RbmSender.isRbm(agent))
    }

    // ---- the reported scenario
    @Test fun threeItemsOfOneCardBecomeOne() {
        val input = listOf(
            note(-5, "HDFC Bank", t0 + 20_000, card),
            note(-6, "HDFC Bank", t0 + 25_000, "Image"),
            mms(7, agent, t0)
        )
        val r = RichMerge.apply(input)
        assertEquals(1, r.messages.size)
        val m = r.messages.single()
        assertEquals(MmsMessages.idFor(7), m.id)
        assertEquals("HDFC Bank", m.sender)
        assertEquals(card, m.body)
        assertTrue(m.isMms && m.isRcs)
        assertEquals(t0, m.date)
        assertEquals(mapOf(-5L to m.id, -6L to m.id), r.folded)
    }

    @Test fun mergingAgainChangesNothing() {
        val input = listOf(note(-5, "HDFC Bank", t0 + 20_000, card), note(-6, "HDFC Bank", t0 + 25_000, "Image"), mms(7, agent, t0))
        val once = RichMerge.apply(input).messages
        val twice = RichMerge.apply(once)
        assertEquals(once, twice.messages)
        assertTrue(twice.folded.isEmpty())
        // Reading the same inbox again (notifications still stored) gives the same single row.
        assertEquals(once, RichMerge.apply(input.reversed()).messages)
    }

    @Test fun mmsWithTextAndTheSameNotificationTextIsOneMessage() {
        val r = RichMerge.apply(listOf(mms(1, agent, t0, card), note(-2, "HDFC Bank", t0 + 5_000, card)))
        assertEquals(1, r.messages.size)
        assertEquals(card, r.messages.single().body)
    }

    @Test fun theNotificationMayCarryTheCardTitleTheMmsLacks() {
        val r = RichMerge.apply(listOf(mms(1, agent, t0, "Your balance is 5"), note(-2, "HDFC Bank", t0, "Account Balance\nYour balance is 5")))
        assertEquals("Account Balance\nYour balance is 5", r.messages.single().body)
    }

    @Test fun anUnrelatedNotificationTextDoesNotReplaceTheMmsText() {
        val r = RichMerge.apply(listOf(mms(1, agent, t0, "Hello from MMS"), note(-2, "HDFC Bank", t0, "Something else entirely, and longer")))
        assertEquals("Hello from MMS", r.messages.single().body)
    }

    @Test fun differentBanksAtTheSameMinuteStaySeparate() {
        val r = RichMerge.apply(
            listOf(mms(1, agent, t0), note(-2, "ICICI Bank", t0, "Balance is 5"), note(-3, "HDFC Bank", t0, card))
        )
        assertEquals(2, r.messages.size)
        assertTrue(r.messages.any { it.sender == "ICICI Bank" && it.body == "Balance is 5" })
        assertEquals(card, r.messages.single { it.sender == "HDFC Bank" }.body)
    }

    @Test fun twoHdfcMessagesTenMinutesApartStaySeparate() {
        val r = RichMerge.apply(
            listOf(mms(1, agent, t0, "first"), mms(2, agent, t0 + 600_000, "second"), note(-3, "HDFC Bank", t0 + 600_000, "second note"))
        )
        assertEquals(2, r.messages.size)
        assertEquals("first", r.messages.single { it.id == MmsMessages.idFor(1) }.body)
        assertEquals("second note", r.messages.single { it.id == MmsMessages.idFor(2) }.body)
    }

    @Test fun aNotificationGoesToTheNearestMms() {
        val r = RichMerge.apply(listOf(mms(1, agent, t0), mms(2, agent, t0 + 150_000), note(-3, "HDFC Bank", t0 + 140_000, card)))
        assertEquals(2, r.messages.size)
        assertEquals(card, r.messages.single { it.id == MmsMessages.idFor(2) }.body)
        assertEquals(MmsMessages.PICTURE_ONLY_TEXT, r.messages.single { it.id == MmsMessages.idFor(1) }.body)
    }

    @Test fun aPlainMmsKeepsItsTagAndAContactIsNotTouched() {
        val plain = mms(1, "+919800000000", t0, "see this")
        val r = RichMerge.apply(listOf(plain, note(-2, "Mom", t0, "see this")))
        assertEquals(2, r.messages.size)
        assertEquals(plain, r.messages.first { it.isMms })
        assertFalse(r.messages.first { it.isMms }.isRcs)
    }

    @Test fun aLoneRbmMmsGetsTheFriendlyNameAndTheRichFlag() {
        val m = RichMerge.apply(listOf(mms(1, agent, t0))).messages.single()
        assertEquals("HDFC Bank", m.sender)
        assertTrue(m.isMms && m.isRcs)
    }

    // ---- placeholders
    @Test fun placeholderIsRecognised() {
        for (p in listOf("Image", "photo", "VIDEO", "Sticker", "GIF", "Audio", "Contact", "Location", "📷 Photo", "Photo.")) assertTrue(p, RichMerge.isPlaceholder(p))
        assertFalse(RichMerge.isPlaceholder("Photo of the bill is attached"))
        assertFalse(RichMerge.isPlaceholder(""))
        assertFalse(RichMerge.isPlaceholder("😀"))
    }

    @Test fun aPlaceholderFoldsIntoANotificationWhenThereIsNoMms() {
        val r = RichMerge.apply(listOf(note(-1, "HDFC Bank", t0, card), note(-2, "HDFC Bank", t0 + 10_000, "Image")))
        assertEquals(listOf(-1L), r.messages.map { it.id })
        assertEquals(mapOf(-2L to -1L), r.folded)
    }

    @Test fun aLonePlaceholderIsLabelledPicture() {
        val r = RichMerge.apply(listOf(note(-1, "Mom", t0, "Photo")))
        assertEquals("Picture", r.messages.single().body)
        assertTrue(r.folded.isEmpty())
        assertEquals(r.messages, RichMerge.apply(r.messages).messages)
    }

    @Test fun mmsWithoutTextTakesTheNotificationText() {
        val r = RichMerge.apply(listOf(mms(1, agent, t0), note(-2, "HDFC Bank", t0 + 1_000, card)))
        assertEquals(card, r.messages.single().body)
    }

    @Test fun nothingToMergeReturnsTheSameList() {
        val sms = listOf(SmsMessage(1, "VK-HDFCBK", "Rs 5 debited", t0, false))
        assertTrue(RichMerge.apply(sms).messages === sms)
    }

    // ---- extraction
    @Test fun textColumnIsReadForAnyTextTypeExceptCardsAndLayout() {
        val parts = listOf(
            MmsPart(1, "application/smil", "<smil><body>layout</body></smil>", seq = 0),
            MmsPart(2, "text/x-vCard", "BEGIN:VCARD", seq = 1),
            MmsPart(3, "text/x-whatever; charset=utf-8", "hello", seq = 2),
            MmsPart(4, "application/octet-stream", "stored in text column", seq = 3),
            MmsPart(5, "image/png", null, seq = 4)
        )
        assertEquals("hello\nstored in text column", MmsMessages.text(parts))
    }

    // Not captured from a phone: a card payload constructed from the public RBM message shape; the stored layout is unverified.
    private val cardJson = """{"message":{"generalPurposeCard":{"content":{"title":"Account Balance","description":"Your account balance for account ending with 5765 is ₹8,509.06","media":{"height":"TALL","contentInfo":{"fileUrl":"https://x/y.png"}},"suggestions":[{"action":{"text":"Explore WhatsApp Banking","openUrlAction":{"url":"https://x"}}}]}}}}"""

    @Test fun jsonCardTextIsCollectedInOrder() {
        val strings = RbmCardText.strings(cardJson)
        assertEquals(listOf("Account Balance", "Your account balance for account ending with 5765 is ₹8,509.06", "Explore WhatsApp Banking"), strings)
        val parts = listOf(MmsPart(1, "application/vnd.gsma.botmessage.v1.0+json", cardJson), MmsPart(2, "image/png"))
        assertTrue(MmsMessages.text(parts).startsWith("Account Balance\nYour account balance"))
        assertTrue(MmsMessages.text(listOf(MmsPart(1, "application/json", cardJson))).contains("8,509.06"))
        // Unknown type, JSON in the text column.
        assertTrue(MmsMessages.text(listOf(MmsPart(1, "application/x-rbm", cardJson))).contains("Account Balance"))
    }

    @Test fun jsonDataIsListedForReadingWhenTheRowHasNoText() {
        val parts = listOf(MmsPart(1, "application/json", null), MmsPart(2, "text/plain", null), MmsPart(3, "application/smil", null), MmsPart(4, "image/png", null), MmsPart(5, "application/octet-stream", null))
        assertEquals(listOf(1L, 2L), MmsMessages.textPartsToRead(parts).map { it.id })
    }

    @Test fun malformedOrHugeJsonNeverThrows() {
        assertTrue(RbmCardText.strings("{not json").isEmpty())
        assertTrue(RbmCardText.strings("").isEmpty())
        assertTrue(RbmCardText.strings("[".repeat(5000)).isEmpty())
        val big = "[" + (1..2000).joinToString(",") { "{\"text\":\"" + "x".repeat(50) + it + "\"}" } + "]"
        assertTrue(RbmCardText.strings(big).sumOf { it.length } <= RbmCardText.MAX_CHARS)
        assertEquals(listOf("a", "b"), RbmCardText.strings("""{"title":"a","x":1,"y":[true,null],"title":"b"}"""))
    }

    @Test fun charsetNames() {
        assertEquals("UTF-8", MmsMessages.charsetName(0))
        assertEquals("UTF-8", MmsMessages.charsetName(106))
        assertEquals("ISO-8859-1", MmsMessages.charsetName(4))
    }

    // ---- developer dump
    @Test fun dumpListsStructureWithoutIds() {
        val text = MmsStructureDump.build(
            "HDFC Bank",
            listOf(MmsPartInfo("application/json", 120, false, false, true, "x".repeat(500)), MmsPartInfo("image/png", -1, false, false, true, "")),
            mapOf(137 to 1, 151 to 2)
        )
        assertTrue(text.startsWith("Developer structure dump, may contain the message text"))
        assertTrue(text.contains("type=application/json size=120 bytes text=no data=yes"))
        assertTrue(text.contains("size=not read"))
        assertTrue(text.contains("137 x1, 151 x2"))
        assertFalse(text.contains("x".repeat(301)))
    }
}
