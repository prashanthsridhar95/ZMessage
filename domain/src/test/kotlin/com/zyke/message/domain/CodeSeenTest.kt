package com.zyke.message.domain

import com.zyke.message.domain.classification.MessageClassifier
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageContent
import com.zyke.message.domain.model.SmsMessage
import com.zyke.message.domain.sharing.CodeSharing
import com.zyke.message.domain.sharing.CodeSharingRules
import com.zyke.message.domain.sharing.LearnProblem
import com.zyke.message.domain.sharing.SeenCode
import com.zyke.message.domain.sharing.SeenCodes
import com.zyke.message.domain.sharing.SeenOutcome
import com.zyke.message.domain.sharing.SentRecord
import com.zyke.message.domain.sharing.ShareCodeReader
import com.zyke.message.domain.sharing.ShareHistory
import com.zyke.message.domain.sharing.ShareLearning
import com.zyke.message.domain.sharing.ShareServices
import com.zyke.message.domain.sharing.ShareWindow
import com.zyke.message.domain.sharing.TrustedContact
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The "Recent codes seen" log, the wider header and wording matching, and teaching a header. */
class CodeSeenTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val classifier = MessageClassifier()
    private val now = ZonedDateTime.of(2026, 11, 8, 14, 14, 0, 0, zone).toInstant().toEpochMilli()
    private val all = ShareServices.all.map { it.id }.toSet()
    private val sister = TrustedContact("c1", "Sister", "+919876543210", all)
    private val rules = CodeSharingRules(enabled = true, contacts = listOf(sister))

    private fun msg(sender: String, body: String, age: Long = 5_000L): ClassifiedMessage =
        classifier.classify(SmsMessage(1, sender, body, now - age, isRead = false))

    private fun review(m: ClassifiedMessage, r: CodeSharingRules = rules, history: ShareHistory = ShareHistory()) = CodeSharing.review(m, r, now, history, zone)
    private fun seen(outcome: SeenOutcome, service: String? = "netflix", detail: String = "", test: Boolean = false) =
        SeenCode("1", now, "VM-NFLXIN-S", service, outcome, detail, test)

    // ---- the outcome of each kind of message

    @Test fun eachOutcomeHasItsPlainReason() {
        val netflix = "482913 is your Netflix verification code. Do not share it with anyone."
        assertEquals(SeenOutcome.SENT, review(msg("VM-NFLXIN-S", netflix)).outcome)
        assertEquals(SeenOutcome.NOT_SERVICE, review(msg("VM-ABCDEF", netflix)).outcome)
        assertEquals(SeenOutcome.FINANCIAL, review(msg("AX-HDFCBK", "123456 is the OTP for txn of Rs.1,500.00 at NETFLIX. Do not share it.")).outcome)
        assertEquals(SeenOutcome.FINANCIAL, review(msg("VM-NFLXIN-S", "Your Netflix OTP is 482913 for payment of Rs 649.")).outcome)
        assertEquals(SeenOutcome.ACCOUNT_SECURITY, review(msg("VM-NFLXIN-S", "482913 is your Netflix verification code. Also your Aadhaar OTP.")).outcome)
        val nobody = rules.copy(contacts = listOf(sister.copy(services = setOf("zee5"))))
        assertEquals(SeenOutcome.NO_ONE_FOR_SERVICE, review(msg("VM-NFLXIN-S", netflix), nobody).outcome)
        assertEquals(SeenOutcome.PERSON_OFF, review(msg("VM-NFLXIN-S", netflix), rules.copy(contacts = listOf(sister.copy(enabled = false)))).outcome)
        assertEquals(SeenOutcome.BAD_NUMBER, review(msg("VM-NFLXIN-S", netflix), rules.copy(contacts = listOf(sister.copy(number = "12")))).outcome)
        assertEquals(SeenOutcome.PAUSED, review(msg("VM-NFLXIN-S", netflix), rules.copy(pausedUntil = now + 1)).outcome)
        assertEquals(SeenOutcome.OUTSIDE_HOURS, review(msg("VM-NFLXIN-S", netflix), rules.copy(window = ShareWindow(22 * 60, 8 * 60))).outcome)
        assertEquals(SeenOutcome.STALE, review(msg("VM-NFLXIN-S", netflix, age = 6 * 60_000L)).outcome)
        assertEquals(SeenOutcome.ALREADY_SENT, review(msg("VM-NFLXIN-S", netflix), history = ShareHistory(listOf(SentRecord("c1", "netflix", now - 1000, CodeSharing.codeKey("netflix", "c1", "482913"))))).outcome)
        assertEquals(SeenOutcome.RATE_LIMITED, review(msg("VM-NFLXIN-S", netflix), history = ShareHistory((1..5).map { SentRecord("c1", "netflix", now - it * 1000L) })).outcome)
        assertEquals(SeenOutcome.NOT_READ_AS_CODE, review(msg("VM-NFLXIN-S", "Your Netflix plan has a new look.")).outcome)
    }

    @Test fun everyReasonSentenceIsExact() {
        assertEquals("Sent to Sister, Brother", SeenCodes.reason(seen(SeenOutcome.SENT, detail = "Sister, Brother")))
        assertEquals("Not forwarded: sender isn't one of the six services", SeenCodes.reason(seen(SeenOutcome.NOT_SERVICE, null)))
        assertEquals("Not forwarded: looks like a payment or bank code", SeenCodes.reason(seen(SeenOutcome.FINANCIAL, null)))
        assertEquals("Not forwarded: looks like a government, insurance or phone-company code", SeenCodes.reason(seen(SeenOutcome.ACCOUNT_SECURITY, null)))
        assertEquals("Not forwarded: nobody has Netflix selected", SeenCodes.reason(seen(SeenOutcome.NO_ONE_FOR_SERVICE)))
        assertEquals("Not forwarded: everyone who has Netflix selected is switched off", SeenCodes.reason(seen(SeenOutcome.PERSON_OFF)))
        assertEquals("Not forwarded: the saved number isn't valid", SeenCodes.reason(seen(SeenOutcome.BAD_NUMBER)))
        assertEquals("Not forwarded: sharing is paused", SeenCodes.reason(seen(SeenOutcome.PAUSED)))
        assertEquals("Not forwarded: outside your hours", SeenCodes.reason(seen(SeenOutcome.OUTSIDE_HOURS)))
        assertEquals("Not forwarded: limit reached", SeenCodes.reason(seen(SeenOutcome.RATE_LIMITED)))
        assertEquals("Not forwarded: already sent", SeenCodes.reason(seen(SeenOutcome.ALREADY_SENT)))
        assertEquals("Not forwarded: too old", SeenCodes.reason(seen(SeenOutcome.STALE)))
        assertEquals("Not forwarded: SMS permission missing", SeenCodes.reason(seen(SeenOutcome.NO_PERMISSION)))
        assertEquals("Not forwarded: not read as a login code", SeenCodes.reason(seen(SeenOutcome.NOT_READ_AS_CODE)))
        assertEquals("Couldn't send: the phone refused the text", SeenCodes.reason(seen(SeenOutcome.SEND_FAILED)))
        assertEquals("Couldn't send: no signal", SeenCodes.reason(seen(SeenOutcome.SEND_FAILED, detail = "no signal")))
        assertEquals("Test: Sent to Sister", SeenCodes.reason(seen(SeenOutcome.SENT, detail = "Sister", test = true)))
        assertEquals("none", SeenCodes.serviceLabel(seen(SeenOutcome.NOT_SERVICE, null)))
        assertEquals("Netflix", SeenCodes.serviceLabel(seen(SeenOutcome.SENT)))
    }

    // ---- what is worth a line

    @Test fun onlyCodeLikeMessagesAreLogged() {
        assertFalse(review(msg("VM-ABCDEF", "Your parcel is out for delivery today.")).codeLike)
        assertFalse(review(msg("9876543210", "see you at 5")).codeLike)
        // A code from anyone is logged, so a header we do not know shows up.
        assertTrue(review(msg("VM-ABCDEF", "482913 is your OTP for Zomato.")).codeLike)
        // Not read as a code, but from a service's own header.
        assertTrue(review(msg("VM-NFLXIN-S", "Your Netflix plan has a new look.")).codeLike)
        // Even a pause or the hours do not hide it from the log.
        assertTrue(review(msg("VM-NFLXIN-S", "482913 is your Netflix verification code."), rules.copy(pausedUntil = now + 1)).codeLike)
    }

    // ---- the wording the general classifier does not know

    @Test fun plainerNetflixWordingIsReadFromAServiceHeader() {
        for (body in listOf(
            "Your Netflix code is 482913. Don't share it with anyone.",
            "Netflix: Your sign-in code is 482913",
            "Use 482913 to log in to Netflix.",
            "Netflix security code: 482913"
        )) {
            val m = msg("VM-NFLXIN-S", body)
            // The general classifier needs "OTP" or "verification code" and does not call these a code: before the fix nothing was sent.
            assertFalse(body, m.content is MessageContent.Otp)
            val r = review(m)
            assertEquals(body, 1, r.decision.shares.size)
            assertEquals(body, "Netflix code 482913 (use it soon). Sent by Zyke", r.decision.shares.single().text)
        }
        // The same wording from a header that is not a service's is not read at all.
        val other = review(msg("VM-ABCDEF", "Your Netflix code is 482913."))
        assertFalse(other.codeLike)
        assertTrue(other.decision.shares.isEmpty())
    }

    @Test fun theCodeReaderIsStrict() {
        assertEquals("482913", ShareCodeReader.read("Your Netflix code is 482913."))
        assertEquals("4829", ShareCodeReader.read("Netflix sign-in code: 4829"))
        assertNull(ShareCodeReader.read("Netflix is now 649 a month"))
        assertNull(ShareCodeReader.read("Your code is ready, see you at 1234 or 5678"))
        assertEquals("482913", ShareCodeReader.read("Netflix code is 482913, valid 10 minutes, ref 5555"))
        assertNull(ShareCodeReader.read("Netflix code 1234567890"))
        assertNull(ShareCodeReader.read("Your plan costs 1,649.00 this month code"))
        assertNull(ShareCodeReader.read("Hello from Netflix 482913"))
    }

    @Test fun hardBlocksStillComeFirstForThePlainerWording() {
        assertEquals(SeenOutcome.FINANCIAL, review(msg("VM-NFLXIN-S", "Your Netflix code is 482913. Rs 649 will be charged.")).outcome)
        assertEquals(SeenOutcome.ACCOUNT_SECURITY, review(msg("VM-NFLXIN-S", "Your Netflix code is 482913 for your Aadhaar.")).outcome)
    }

    // ---- aliases

    @Test fun theLongerAliasListMatchesByTheHeaderName() {
        val cases = listOf(
            "netflix" to listOf("NFLX", "NETFLX", "NFLXIN", "NETFLIX", "VM-NFLXIN-S", "ax-netflix-t"),
            "hotstar" to listOf("HSTAR", "HOTSTR", "HTSTAR", "JIOHST", "JHSTAR", "DISNEY", "DSNYHS", "JIOHOT", "AX-HOTSTR-S"),
            "prime" to listOf("AMAZON", "AMZN", "AMZNIN", "PRIMEV", "AMAZPR", "JK-AMAZON-S"),
            "sunnxt" to listOf("SUNNXT", "SUNNXTAPP", "CP-SUNNXT-S"),
            "sonyliv" to listOf("SONYLV", "SONYLIV", "SNYLIV", "SONYLI", "VK-SONYLV-T"),
            "zee5" to listOf("ZEE5", "ZEE5IN", "ZEEFIV", "ZEETV", "AD-ZEE5-S")
        )
        for ((id, headers) in cases) for (h in headers) {
            assertEquals("$h", listOf(id), ShareServices.byHeader(h).map { it.id })
        }
        // None of the header names is a bank or payment name, or every one would be blocked.
        for (s in ShareServices.all) for (t in s.senderTokens) assertFalse(t, ShareLearning.problem("VM-$t-S", s.id, emptyMap()) == LearnProblem.LOOKS_FINANCIAL)
    }

    @Test fun theGenericAmazonHeaderStillNeedsPrimeInTheBody() {
        val shop = review(msg("JK-AMAZON-S", "123456 is your Amazon OTP. Do not share it with anyone."))
        assertEquals(SeenOutcome.NOT_SERVICE, shop.outcome)
        assertEquals(1, review(msg("JK-AMAZON-S", "123456 is your Amazon Prime OTP. Do not share it with anyone.")).decision.shares.size)
        assertEquals(SeenOutcome.FINANCIAL, review(msg("AD-AMAZONPAY", "123456 is your Amazon Pay Prime OTP.")).outcome)
    }

    // ---- teaching a header

    @Test fun aTaughtHeaderIsAcceptedForItsServiceOnly() {
        val body = "482913 is your Netflix verification code. Do not share it with anyone."
        assertEquals(SeenOutcome.NOT_SERVICE, review(msg("VM-XYZNFL-S", body)).outcome)
        assertNull(ShareLearning.problem("VM-XYZNFL-S", "netflix", emptyMap()))
        val learned = ShareLearning.add(emptyMap(), "netflix", "XYZNFL")
        val taught = rules.copy(learned = learned)
        val r = review(msg("VM-XYZNFL-S", body), taught)
        assertEquals(listOf("netflix"), r.decision.shares.map { it.service.id })
        // The body still has to fit: a taught Netflix header with a Hotstar message is ambiguous, not forwarded.
        assertTrue(review(msg("VM-XYZNFL-S", "561234 is your Hotstar verification code."), taught).decision.shares.isEmpty())
        // Any form of the same header works, and it is now known.
        assertEquals(LearnProblem.ALREADY_KNOWN, ShareLearning.problem("AX-XYZNFL", "netflix", learned))
        // It can be taken back.
        assertEquals(emptyMap<String, Set<String>>(), ShareLearning.remove(learned, "netflix", "XYZNFL"))
    }

    @Test fun aTaughtHeaderNeverSkipsTheHardBlocks() {
        val taught = rules.copy(learned = ShareLearning.add(emptyMap(), "netflix", "XYZNFL"))
        assertEquals(SeenOutcome.FINANCIAL, review(msg("VM-XYZNFL-S", "Your Netflix OTP is 482913 for payment of Rs 649."), taught).outcome)
        assertEquals(SeenOutcome.ACCOUNT_SECURITY, review(msg("VM-XYZNFL-S", "482913 is your Netflix code and Aadhaar OTP."), taught).outcome)
        assertEquals(SeenOutcome.NOT_READ_AS_CODE, review(msg("VM-XYZNFL-S", "Your Netflix plan has a new look."), taught).outcome)
    }

    @Test fun bankAndPaymentHeadersCannotBeTaught() {
        for (h in listOf("AX-HDFCBK", "JD-ICICIB-S", "VM-PHONEPE", "AD-AMAZONPAY", "VK-SBIINB", "VM-PAYTM", "BZ-UPIIND", "AX-AXISBK", "VM-AIRTEL", "VM-UIDAI", "AD-CREDIN")) {
            assertEquals(h, LearnProblem.LOOKS_FINANCIAL, ShareLearning.problem(h, "netflix", emptyMap()))
        }
        assertEquals(LearnProblem.NOT_A_NAME, ShareLearning.problem("+919876543210", "netflix", emptyMap()))
        assertEquals(LearnProblem.NOT_A_NAME, ShareLearning.problem("VM-XYZNFL", "nonesuch", emptyMap()))
        var learned: Map<String, Set<String>> = emptyMap()
        for (i in 1..ShareLearning.MAX_PER_SERVICE) learned = ShareLearning.add(learned, "zee5", "ZZZ$i")
        assertEquals(LearnProblem.TOO_MANY, ShareLearning.problem("VM-QQQQQ", "zee5", learned))
    }

    @Test fun onlyTheNotAServiceRowsCanBeTaught() {
        assertEquals("XYZNFL", SeenCodes.teachableToken(SeenCode("1", now, "VM-XYZNFL-S", null, SeenOutcome.NOT_SERVICE)))
        assertNull(SeenCodes.teachableToken(SeenCode("1", now, "VM-XYZNFL-S", null, SeenOutcome.FINANCIAL)))
        assertNull(SeenCodes.teachableToken(SeenCode("1", now, "••••••10", null, SeenOutcome.NOT_SERVICE)))
        assertNull(SeenCodes.teachableToken(SeenCode("1", now, "VM-XYZNFL-S", null, SeenOutcome.NOT_SERVICE, test = true)))
    }

    // ---- nothing personal is kept

    @Test fun headersThatAreNumbersAreMaskedToTheLastTwoDigits() {
        assertEquals("••••••10", SeenCodes.header("+919876543210"))
        assertEquals("••••••10", SeenCodes.header("98765 43210"))
        assertEquals("VM-NFLXIN-S", SeenCodes.header("VM-NFLXIN-S"))
        assertEquals("AD-ZEE5-S", SeenCodes.header("AD-ZEE5-S"))
        assertEquals("56161", SeenCodes.header("56161"))
        assertEquals("VM-NFLXb", SeenCodes.header(" VM-NFLX<b>\n"))
    }

    @Test fun theLogHoldsNoCodeBodyOrNumber() {
        val fields = SeenCode::class.java.declaredFields.map { it.name }.filter { !it.startsWith("$") }.toSet()
        assertEquals(setOf("id", "at", "header", "serviceId", "outcome", "detail", "test"), fields)
        val body = "482913 is your Netflix verification code. Do not share it with anyone."
        val sms = msg("+919876543210", body)
        val entry = SeenCodes.entry("x", now, sms.message.sender, null, review(sms).outcome, "Sister 9876543210")
        val text = entry.toString() + SeenCodes.reason(entry)
        assertFalse(text, text.contains("482913"))
        assertFalse(text, text.contains("9876543210"))
        assertFalse(text, text.contains("verification"))
        assertEquals("Sister", entry.detail)
        // A real send's entry names the person and nothing else.
        val sent = SeenCodes.entry("y", now, "VM-NFLXIN-S", "netflix", SeenOutcome.SENT, "Sister")
        assertEquals("Sent to Sister", SeenCodes.reason(sent))
        assertFalse(sent.toString().contains("482913"))
    }

    @Test fun theListKeepsTheLatestTwentyNewestFirst() {
        var list = emptyList<SeenCode>()
        for (i in 1..25) list = SeenCodes.add(list, SeenCode("id$i", now + i, "VM-X", null, SeenOutcome.NOT_SERVICE))
        assertEquals(20, list.size)
        assertEquals("id25", list.first().id)
        assertEquals("id6", list.last().id)
    }

    // ---- a person with nothing chosen, and the test code

    @Test fun aPersonWithNoServicesIsWarnedAbout() {
        assertEquals("No services chosen — nothing will be sent", SeenCodes.noServicesWarning(sister.copy(services = emptySet())))
        assertEquals("No services chosen — nothing will be sent", SeenCodes.noServicesWarning(sister.copy(services = setOf("nonesuch"))))
        assertNull(SeenCodes.noServicesWarning(sister))
        // And nothing is sent to them.
        val none = rules.copy(contacts = listOf(sister.copy(services = emptySet())))
        assertEquals(SeenOutcome.NO_ONE_FOR_SERVICE, review(msg("VM-NFLXIN-S", "482913 is your Netflix verification code."), none).outcome)
    }

    @Test fun theTestCodeTakesTheRealPathForEveryService() {
        for (service in ShareServices.all) {
            val m = classifier.classify(CodeSharing.testMessage(service, now))
            assertTrue(service.id, m.content is MessageContent.Otp)
            val r = review(m)
            assertEquals(service.id, listOf(service.id), r.decision.shares.map { it.service.id })
            assertEquals("Zyke TEST - ${service.label} code 123456", CodeSharing.testText(service))
            assertTrue(CodeSharing.isOneSegment(CodeSharing.testText(service)))
        }
        assertEquals("Zyke TEST - Netflix code 123456", CodeSharing.testText(ShareServices.byId("netflix")!!))
        // It obeys the real rules: paused means not sent.
        val m = classifier.classify(CodeSharing.testMessage(ShareServices.byId("netflix")!!, now))
        assertEquals(SeenOutcome.PAUSED, review(m, rules.copy(pausedUntil = now + 1)).outcome)
    }
}
