package com.zyke.message.domain

import com.zyke.message.domain.classification.MessageClassifier
import com.zyke.message.domain.device.CodeSharingFacts
import com.zyke.message.domain.device.CodeSharingHealth
import com.zyke.message.domain.device.CodeSharingProblem
import com.zyke.message.domain.model.SmsMessage
import com.zyke.message.domain.sharing.CodeSharing
import com.zyke.message.domain.sharing.CodeSharingRules
import com.zyke.message.domain.sharing.ContactProblem
import com.zyke.message.domain.sharing.ContactResult
import com.zyke.message.domain.sharing.PhoneNumbers
import com.zyke.message.domain.sharing.SentRecord
import com.zyke.message.domain.sharing.ShareHistory
import com.zyke.message.domain.sharing.ShareLog
import com.zyke.message.domain.sharing.ShareLogEntry
import com.zyke.message.domain.sharing.ShareServices
import com.zyke.message.domain.sharing.ShareSkip
import com.zyke.message.domain.sharing.ShareStatus
import com.zyke.message.domain.sharing.ShareWindow
import com.zyke.message.domain.sharing.TrustedContact
import com.zyke.message.domain.sharing.TrustedContacts
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeSharingTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val classifier = MessageClassifier()
    private val now = ZonedDateTime.of(2026, 11, 8, 2, 14, 0, 0, zone).toInstant().toEpochMilli()

    private fun message(sender: String, body: String, age: Long = 5_000L) =
        classifier.classify(SmsMessage(1, sender, body, now - age, isRead = false))

    private val allServices = ShareServices.all.map { it.id }.toSet()
    private val sister = TrustedContact("c1", "Sister", "+919876543210", allServices)
    private val brother = TrustedContact("c2", "Brother", "+919812345678", allServices)
    private fun rules(vararg people: TrustedContact, enabled: Boolean = true, name: String = "") =
        CodeSharingRules(enabled = enabled, contacts = people.toList(), senderName = name)

    private fun decide(m: com.zyke.message.domain.model.ClassifiedMessage, r: CodeSharingRules, history: ShareHistory = ShareHistory(), at: Long = now) =
        CodeSharing.decide(m, r, at, history, zone)

    private val netflix get() = message("VM-NFLXIN-S", "482913 is your Netflix verification code. Do not share it with anyone.")

    // ---- the six services forward

    private val samples = listOf(
        Triple("netflix", "VM-NFLXIN-S", "482913 is your Netflix verification code. Do not share it with anyone."),
        Triple("hotstar", "AX-HSTAR", "Your Disney+ Hotstar OTP is 561234. It is valid for 10 minutes. Do not share it with anyone."),
        Triple("hotstar", "JM-JHSTAR-S", "561234 is your JioHotstar verification code. Never share this code."),
        Triple("prime", "AD-AMAZON-S", "123456 is your Prime Video OTP. Do not share it with anyone."),
        Triple("prime", "VK-AMZNIN", "Your Amazon Prime OTP is 654321. Do not share."),
        Triple("sunnxt", "BZ-SUNNXT", "Your Sun NXT OTP is 7391. Do not share it with anyone."),
        Triple("sonyliv", "VK-SONYLV-S", "884213 is your SonyLIV verification code. Do not share it."),
        Triple("zee5", "ZEE5IN", "829104 is your ZEE5 OTP to login. Valid for 5 minutes.")
    )

    @Test fun everyServiceIsForwarded() {
        for ((id, sender, body) in samples) {
            val d = decide(message(sender, body), rules(sister))
            assertEquals("$sender: ${d.skipped}", 1, d.shares.size)
            assertEquals(id, d.shares.single().service.id)
        }
        assertEquals(6, ShareServices.all.size)
        assertEquals(setOf("netflix", "hotstar", "prime", "sunnxt", "sonyliv", "zee5"), ShareServices.all.map { it.id }.toSet())
        assertEquals(setOf("netflix", "hotstar", "prime", "sunnxt", "sonyliv", "zee5"), samples.map { it.first }.toSet())
    }

    // ---- hard blocks

    @Test fun bankUpiAndCardCodesAreNeverForwardedEvenIfThePersonHasTheService() {
        val everything = rules(sister)
        listOf(
            "AX-HDFCBK" to "123456 is the OTP for txn of Rs.1,500.00 at NETFLIX on card ending 4321. Do not share it.",
            "VM-PHONEPE" to "Your OTP for UPI payment to Netflix is 123456. Do not share.",
            "JD-ICICIB" to "OTP 123456 to add Netflix as a payee. Never share your OTP with anyone.",
            "AD-AMAZONPAY" to "123456 is your Amazon Pay Prime OTP. Do not share it.",
            "VK-SBIINB" to "Your Netflix verification code is 123456",
            "AX-AXISBK" to "123456 is your OTP. Do not share. Axis Bank will never call you."
        ).forEach { (sender, body) ->
            val d = decide(message(sender, body), everything)
            assertTrue("$sender should not send", d.shares.isEmpty())
        }
    }

    @Test fun aNetflixLookingSenderWithAPaymentAmountIsNeverForwarded() {
        for (body in listOf(
            "Your Netflix OTP is 482913 for payment of Rs 649.",
            "482913 is your Netflix verification code for INR 649.00",
            "Netflix verification code 482913. Amount 649 will be charged.",
            "Your Netflix code is 482913 ₹649 transaction"
        )) {
            val d = decide(message("VM-NFLXIN-S", body), rules(sister))
            assertTrue(body, d.shares.isEmpty())
            assertEquals(body, setOf(ShareSkip.FINANCIAL), d.reasons)
        }
    }

    @Test fun financialMessagesWithoutACodeAreBlockedToo() {
        val debit = message("VM-NFLXIN-S", "Rs.649.00 debited from your card ending 4321 for NETFLIX on 08-11-26.")
        assertEquals(setOf(ShareSkip.FINANCIAL), decide(debit, rules(sister)).reasons)
    }

    @Test fun governmentInsurerAndTelecomSecurityIsNeverForwarded() {
        listOf(
            "VM-UIDAI" to "482913 is your OTP for Aadhaar authentication.",
            "VM-NFLXIN-S" to "482913 is your Netflix verification code. Also your Aadhaar OTP.",
            "AX-LICIND" to "Your OTP is 482913 for your insurance policy no 123.",
            "AX-JIOINF" to "Your OTP is 482913 to port out your Jio SIM.",
            "VM-AIRTEL" to "OTP 482913 to change your SIM. Do not share."
        ).forEach { (sender, body) ->
            val d = decide(message(sender, body), rules(sister))
            assertTrue("$sender", d.shares.isEmpty())
        }
        assertEquals(setOf(ShareSkip.ACCOUNT_SECURITY), decide(message("VM-NFLXIN-S", "482913 is your Netflix verification code. Also your Aadhaar OTP."), rules(sister)).reasons)
    }

    @Test fun aWrongSenderIsNeverForwardedEvenWhenTheWordsLookRight() {
        for (sender in listOf("VM-ABCDEF", "9876543210", "+919876543210", "ADVERT", "Mom", "VM-TXTLCL-S")) {
            val d = decide(message(sender, "482913 is your Netflix verification code. Do not share it with anyone."), rules(sister))
            assertTrue(sender, d.shares.isEmpty())
            assertEquals(sender, setOf(ShareSkip.NOT_AN_ALLOWED_SERVICE), d.reasons)
        }
    }

    @Test fun amazonShoppingCodesAreNotPrimeVideo() {
        val d = decide(message("AD-AMAZON-S", "123456 is your Amazon OTP. Do not share it with anyone."), rules(sister))
        assertEquals(setOf(ShareSkip.NOT_AN_ALLOWED_SERVICE), d.reasons)
    }

    @Test fun aMessageNamingTwoServicesIsAmbiguousAndNotForwarded() {
        val d = decide(message("VM-NFLXIN-S", "482913 is your Netflix verification code. Also try Hotstar."), rules(sister))
        assertTrue(d.shares.isEmpty())
    }

    @Test fun aServiceMessageThatIsNotACodeIsNotForwarded() {
        val d = decide(message("VM-NFLXIN-S", "Your Netflix plan has a new look. Enjoy the shows this weekend."), rules(sister))
        assertEquals(setOf(ShareSkip.NOT_A_CODE), d.reasons)
    }

    // ---- limits

    @Test fun rateLimitIsPerPersonAndPerHour() {
        val recent = (1..5).map { SentRecord("c1", "netflix", now - it * 60_000L) }
        val d = decide(netflix, rules(sister, brother), ShareHistory(recent))
        assertEquals(listOf("c2"), d.shares.map { it.contact.id })
        assertEquals(listOf(ShareSkip.RATE_LIMITED), d.skipped.map { it.reason })
        // Four in the hour still leaves one; older ones do not count.
        val four = (1..4).map { SentRecord("c1", "netflix", now - it * 60_000L) }
        assertEquals(1, decide(netflix, rules(sister), ShareHistory(four)).shares.size)
        val old = (1..9).map { SentRecord("c1", "netflix", now - 61 * 60_000L - it) }
        assertEquals(1, decide(netflix, rules(sister), ShareHistory(old)).shares.size)
    }

    @Test fun theSameCodeIsNeverSentTwiceToTheSamePerson() {
        val key = CodeSharing.codeKey("netflix", "c1", "482913")
        val d = decide(netflix, rules(sister, brother), ShareHistory(listOf(SentRecord("c1", "netflix", now - 1_000, key))))
        assertEquals(listOf("c2"), d.shares.map { it.contact.id })
        assertEquals(listOf(ShareSkip.ALREADY_SENT), d.skipped.map { it.reason })
        // A different code goes through.
        val other = message("VM-NFLXIN-S", "111222 is your Netflix verification code.")
        assertEquals(2, decide(other, rules(sister, brother), ShareHistory(listOf(SentRecord("c1", "netflix", now - 1_000, key)))).shares.size)
    }

    @Test fun oldMessagesAreNeverForwarded() {
        assertEquals(1, decide(message("VM-NFLXIN-S", "482913 is your Netflix verification code.", age = 4 * 60_000L), rules(sister)).shares.size)
        val stale = decide(message("VM-NFLXIN-S", "482913 is your Netflix verification code.", age = 6 * 60_000L), rules(sister))
        assertEquals(setOf(ShareSkip.STALE), stale.reasons)
        val dayOld = decide(message("VM-NFLXIN-S", "482913 is your Netflix verification code.", age = 26 * 3_600_000L), rules(sister))
        assertTrue(dayOld.shares.isEmpty())
        // A date in the future is not a fresh message either.
        assertTrue(decide(message("VM-NFLXIN-S", "482913 is your Netflix verification code.", age = -10 * 60_000L), rules(sister)).shares.isEmpty())
    }

    @Test fun masterSwitchOffSendsNothing() {
        val d = decide(netflix, rules(sister, enabled = false))
        assertTrue(d.shares.isEmpty())
        assertEquals(setOf(ShareSkip.OFF), d.reasons)
        assertFalse(CodeSharingRules().enabled)
        assertTrue(CodeSharingRules().contacts.isEmpty())
        assertNull(CodeSharingRules().window)
        assertEquals(0L, CodeSharingRules().pausedUntil)
    }

    @Test fun aPauseHoldsUntilItEnds() {
        val paused = rules(sister).copy(pausedUntil = CodeSharing.pausedUntil(now, 8))
        assertEquals(setOf(ShareSkip.PAUSED), decide(netflix, paused).reasons)
        assertEquals(1, decide(netflix, paused.copy(pausedUntil = now)).shares.size)
        assertEquals(listOf(1, 8, 24), CodeSharing.pauseHours)
    }

    @Test fun anOvernightWindowIsHonoured() {
        val night = rules(sister).copy(window = ShareWindow(22 * 60, 8 * 60))
        fun at(h: Int, m: Int) = ZonedDateTime.of(2026, 11, 8, h, m, 0, 0, zone).toInstant().toEpochMilli()
        fun allowed(h: Int, m: Int): Boolean {
            val t = at(h, m)
            return decide(message("VM-NFLXIN-S", "482913 is your Netflix verification code.").let { it.copy(message = it.message.copy(date = t - 1000)) }, night, at = t).shares.isNotEmpty()
        }
        assertTrue(allowed(23, 30))
        assertTrue(allowed(2, 14))
        assertTrue(allowed(22, 0))
        assertTrue(allowed(7, 59))
        assertFalse(allowed(8, 0))
        assertFalse(allowed(12, 0))
        assertFalse(allowed(21, 59))
    }

    @Test fun aDaytimeWindowAndAnEmptyWindow() {
        assertTrue(ShareWindow(9 * 60, 17 * 60).contains(12 * 60))
        assertFalse(ShareWindow(9 * 60, 17 * 60).contains(17 * 60))
        assertFalse(ShareWindow(9 * 60, 17 * 60).contains(3 * 60))
        assertTrue(ShareWindow(0, 0).contains(5 * 60))
        assertTrue(ShareWindow(600, 600).isAlways)
        assertEquals("10:00 PM to 8:00 AM", ShareWindow(22 * 60, 8 * 60).label())
        assertEquals("12:30 AM", ShareWindow.clock(30))
    }

    // ---- who gets what

    @Test fun eachPersonOnlyGetsTheirOwnServices() {
        val netflixOnly = sister.copy(services = setOf("netflix"))
        val primeOnly = brother.copy(services = setOf("prime"))
        val d = decide(netflix, rules(netflixOnly, primeOnly))
        assertEquals(listOf("c1"), d.shares.map { it.contact.id })
        val prime = decide(message("AD-AMAZON-S", "123456 is your Prime Video OTP."), rules(netflixOnly, primeOnly))
        assertEquals(listOf("c2"), prime.shares.map { it.contact.id })
        val nobody = decide(message("ZEE5IN", "829104 is your ZEE5 OTP to login."), rules(netflixOnly, primeOnly))
        assertEquals(setOf(ShareSkip.NO_ONE_FOR_SERVICE), nobody.reasons)
    }

    @Test fun twoPeopleBothGetTheCode_andAPersonSwitchedOffDoesNot() {
        assertEquals(listOf("c1", "c2"), decide(netflix, rules(sister, brother)).shares.map { it.contact.id })
        val d = decide(netflix, rules(sister, brother.copy(enabled = false)))
        assertEquals(listOf("c1"), d.shares.map { it.contact.id })
        assertEquals(listOf(ShareSkip.PERSON_OFF), d.skipped.map { it.reason })
        assertEquals(setOf(ShareSkip.BAD_NUMBER), decide(netflix, rules(sister.copy(number = "12345"))).reasons)
    }

    // ---- the text

    @Test fun theTextHoldsOnlyTheCodeAndTheService() {
        val share = decide(netflix, rules(sister)).shares.single()
        assertEquals("Netflix code 482913 (use it soon). Sent by Zyke", share.text)
        for (leaked in listOf("verification", "anyone", "Do not", "NFLX", "VM-")) assertFalse(leaked, share.text.contains(leaked))
        assertEquals("Netflix code 482913 (use it soon). Sent by Aman via Zyke", decide(netflix, rules(sister, name = "Aman")).shares.single().text)
    }

    @Test fun theTextIsOneSegmentForEveryServiceAndAnyName() {
        val names = listOf("", "Aman", "Aman 😀 Kümar", "अमन", "A".repeat(200), "  spaced   out  name ")
        for (service in ShareServices.all) for (name in names) {
            val text = CodeSharing.text(service, "12345678", name)
            assertTrue(text, text.length <= 160)
            assertTrue(text, CodeSharing.isOneSegment(text))
        }
        assertEquals("Aman Kmar", CodeSharing.cleanName("Aman \u00FC Kmar").replace("  ", " "))
        assertEquals("", CodeSharing.cleanName("अमन"))
        assertEquals(20, CodeSharing.cleanName("A".repeat(200)).length)
        assertTrue(CodeSharing.isOneSegment(CodeSharing.TEST_TEXT))
        assertFalse(CodeSharing.isOneSegment("hi 😀"))
    }

    // ---- numbers and people

    @Test fun phoneNumbersAreNormalisedAndValidated() {
        assertEquals("+919876543210", PhoneNumbers.normalise("98765 43210"))
        assertEquals("+919876543210", PhoneNumbers.normalise("+91 98765-43210"))
        assertEquals("+919876543210", PhoneNumbers.normalise("09876543210"))
        assertEquals("+919876543210", PhoneNumbers.normalise("919876543210"))
        assertEquals("+919876543210", PhoneNumbers.normalise("0091 (98765) 43210"))
        assertEquals("+14155552671", PhoneNumbers.normalise("+1 415 555 2671"))
        assertEquals("+447911123456", PhoneNumbers.normalise("00447911123456"))
        for (bad in listOf("", "12345", "5876543210", "98765432", "9999999999", "98765 43210 9", "abc", "+0123456789", "+91 1234567890", "+", "98765x43210", "++919876543210", "9876+543210", "+1234567")) {
            assertNull("'$bad'", PhoneNumbers.normalise(bad))
        }
        assertEquals("+91 98••• ••210", PhoneNumbers.mask("+919876543210"))
        assertEquals("+14•••• 671", PhoneNumbers.mask("+14155552671"))
        assertEquals("••••••", PhoneNumbers.mask("junk"))
        assertEquals("+91 98765 43210", PhoneNumbers.pretty("+919876543210"))
    }

    @Test fun aPersonIsValidatedBeforeItIsSaved() {
        val existing = listOf(sister)
        fun build(name: String, number: String, services: Set<String>, id: String = "new") = TrustedContacts.build(id, name, number, services, existing)
        val ok = build("  Dad  ", "98123 45678", setOf("netflix", "bogus")) as ContactResult.Ok
        assertEquals(TrustedContact("new", "Dad", "+919812345678", setOf("netflix")), ok.contact)
        assertEquals(ContactProblem.NAME_EMPTY, (build(" ", "9812345678", setOf("netflix")) as ContactResult.Bad).problem)
        assertEquals(ContactProblem.NUMBER_INVALID, (build("Dad", "123", setOf("netflix")) as ContactResult.Bad).problem)
        assertEquals(ContactProblem.NO_SERVICE, (build("Dad", "9812345678", setOf("bogus")) as ContactResult.Bad).problem)
        assertEquals(ContactProblem.DUPLICATE_NUMBER, (build("Dad", "+91 98765 43210", setOf("netflix")) as ContactResult.Bad).problem)
        // Editing the same person with the same number is fine.
        assertTrue(build("Sis", "9876543210", setOf("zee5"), id = "c1") is ContactResult.Ok)
        val full = (1..TrustedContacts.MAX_PEOPLE).map { TrustedContact("p$it", "P$it", "+91981234${5000 + it}", setOf("netflix")) }
        assertEquals(ContactProblem.TOO_MANY, (TrustedContacts.build("x", "X", "9000000001", setOf("netflix"), full) as ContactResult.Bad).problem)
    }

    // ---- log

    @Test fun theLogKeepsTheLatestAndNeverGoesBackwards() {
        assertEquals(ShareStatus.SENT, ShareLog.advance(ShareStatus.SENDING, ShareStatus.SENT))
        assertEquals(ShareStatus.DELIVERED, ShareLog.advance(ShareStatus.SENT, ShareStatus.DELIVERED))
        assertEquals(ShareStatus.DELIVERED, ShareLog.advance(ShareStatus.DELIVERED, ShareStatus.SENT))
        assertEquals(ShareStatus.FAILED, ShareLog.advance(ShareStatus.SENDING, ShareStatus.FAILED))
        assertEquals(ShareStatus.FAILED, ShareLog.advance(ShareStatus.FAILED, ShareStatus.SENT))
        var log = emptyList<ShareLogEntry>()
        for (i in 1..60) log = ShareLog.add(log, ShareLogEntry("e$i", "netflix", "c1", "Sister", now + i, ShareStatus.SENT), now + i)
        assertEquals(ShareLog.MAX_ENTRIES, log.size)
        assertEquals("e60", log.first().id)
        log = ShareLog.update(log, "e60", ShareStatus.DELIVERED)
        assertEquals(ShareStatus.DELIVERED, log.first().status)
        assertEquals("Sent Netflix code to Sister", ShareLog.headline(log.first().copy(status = ShareStatus.SENT)))
        assertEquals("Couldn't send to Sister", ShareLog.headline(log.first().copy(status = ShareStatus.FAILED)))
        val old = ShareLogEntry("old", "netflix", "c1", "Sister", now - 40L * 24 * 3_600_000, ShareStatus.SENT)
        assertTrue(ShareLog.add(listOf(old), log.first(), now).none { it.id == "old" })
    }

    @Test fun testTextsAreNotCountedAgainstTheLimitAndNotRepeatedAtOnce() {
        val test = ShareLogEntry("t", "", "c1", "Sister", now - 10_000, ShareStatus.SENT, test = true)
        assertTrue(ShareLog.records(listOf(test)).isEmpty())
        assertFalse(CodeSharing.canSendTest(listOf(test), "c1", now))
        assertTrue(CodeSharing.canSendTest(listOf(test), "c2", now))
        assertTrue(CodeSharing.canSendTest(listOf(test), "c1", now + 61_000))
        assertEquals("Test from Zyke: code sharing is set up", CodeSharing.TEST_TEXT)
    }

    // ---- health and summary

    @Test fun healthNamesTheFirstThingInTheWay() {
        val ok = CodeSharingFacts(true, 1, false, false, true, true, true)
        assertNull(CodeSharingHealth.problem(ok))
        assertEquals(CodeSharingProblem.SWITCHED_OFF, CodeSharingHealth.problem(ok.copy(switchedOn = false)))
        assertEquals(CodeSharingProblem.NOBODY_ADDED, CodeSharingHealth.problem(ok.copy(people = 0)))
        assertEquals(CodeSharingProblem.READING_PAUSED, CodeSharingHealth.problem(ok.copy(readingPaused = true)))
        assertEquals(CodeSharingProblem.PAUSED, CodeSharingHealth.problem(ok.copy(paused = true)))
        assertEquals(CodeSharingProblem.CANNOT_HEAR_TEXTS, CodeSharingHealth.problem(ok.copy(canHearTexts = false)))
        assertEquals(CodeSharingProblem.CANNOT_SEND_TEXTS, CodeSharingHealth.problem(ok.copy(canSendTexts = false)))
        assertEquals(CodeSharingProblem.RECEIVER_OFF, CodeSharingHealth.problem(ok.copy(receiverOn = false)))
    }

    @Test fun theSettingsRowSummarises() {
        assertEquals("Off", CodeSharing.summary(CodeSharingRules(), now))
        assertEquals("On, nobody added yet", CodeSharing.summary(CodeSharingRules(enabled = true), now))
        assertEquals("On for 1 person", CodeSharing.summary(rules(sister), now))
        assertEquals("On for 2 people", CodeSharing.summary(rules(sister, brother), now))
        assertEquals("Paused", CodeSharing.summary(rules(sister).copy(pausedUntil = now + 1), now))
    }

    @Test fun serviceMatchingIsGenerousOnHeadersButStrict() {
        for (sender in listOf("NFLXIN", "VM-NFLXIN-S", "AX-NETFLIX", "NETFLX", "TX-NFLX-T")) assertEquals(sender, "netflix", ShareServices.match(sender, "your Netflix code")?.id)
        assertEquals("sunnxt", ShareServices.match("SUNNXT", "Sun NXT code")?.id)
        assertNull(ShareServices.match("NFLXIN", "Hotstar code"))
        assertNull(ShareServices.match("AMAZON", "your Amazon code"))
        assertNull(ShareServices.match("", "Netflix"))
        assertEquals("", ShareServices.senderToken("98765 43210"))
        assertEquals("NFLXIN", ShareServices.senderToken("vm-nflxin-s"))
    }
}
