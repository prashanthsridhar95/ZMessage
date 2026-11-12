package com.zyke.message.domain

import com.zyke.message.domain.classification.MessageClassifier
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageContent
import com.zyke.message.domain.model.SmsMessage
import com.zyke.message.domain.sharing.CodeSharing
import com.zyke.message.domain.sharing.CodeSharingRules
import com.zyke.message.domain.sharing.ContactProblem
import com.zyke.message.domain.sharing.ContactResult
import com.zyke.message.domain.sharing.ReceivedCodeSenders
import com.zyke.message.domain.sharing.SentRecord
import com.zyke.message.domain.sharing.ShareBrand
import com.zyke.message.domain.sharing.ShareHistory
import com.zyke.message.domain.sharing.ShareMigration
import com.zyke.message.domain.sharing.ShareServices
import com.zyke.message.domain.sharing.ShareSkip
import com.zyke.message.domain.sharing.ShareSource
import com.zyke.message.domain.sharing.ShareSources
import com.zyke.message.domain.sharing.SourceProblem
import com.zyke.message.domain.sharing.TrustedContact
import com.zyke.message.domain.sharing.TrustedContacts
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Any sender's code can be shared once the person chooses it: the JioHotstar sample, keys, labels, the picker's list, the hard blocks. */
class CodeSourcesTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val classifier = MessageClassifier()
    private val now = 1_800_000_000_000L
    private val day = 24L * 60 * 60_000L

    private val sample = "<#> 1496 is your JioHotstar verification code. Enjoy watching! /wAX/OsgZgE"
    private fun msg(sender: String, body: String, age: Long = 5_000L): ClassifiedMessage = classifier.classify(SmsMessage(1, sender, body, now - age, isRead = false))

    private val jio = ShareSource("c:JIOHOTSTAR", "JioHotstar", "JIOHOTSTAR", false, "JioHotstar")
    private val sister = TrustedContact("c1", "Sister", "+919876543210", setOf(jio.id))
    private fun rules(vararg people: TrustedContact, sources: List<ShareSource> = listOf(jio)) =
        CodeSharingRules(enabled = true, contacts = people.toList(), sources = sources)
    private fun decide(m: ClassifiedMessage, r: CodeSharingRules, history: ShareHistory = ShareHistory()) = CodeSharing.decide(m, r, now, history, zone)

    // ---- the real sample

    @Test fun theJioHotstarSampleIsReadAs1496AndForwardedWithoutTheHash() {
        val m = msg("JioHotstar", sample)
        val otp = m.content as MessageContent.Otp
        assertEquals("1496", otp.code)
        val share = decide(m, rules(sister)).shares.single()
        assertEquals("JioHotstar code 1496 (use it soon). Sent by Zyke", share.text)
        assertFalse(share.text.contains("wAX"))
        assertTrue(CodeSharing.isOneSegment(share.text))
        assertEquals("c:JIOHOTSTAR", share.source.id)
    }

    @Test fun anAppHashWithDigitsIsNotTakenForTheCode() {
        val m = msg("JioHotstar", "<#> 1496 is your JioHotstar verification code. Enjoy watching! ab1234567Xy")
        assertEquals("1496", (m.content as MessageContent.Otp).code)
        assertEquals("JioHotstar code 1496 (use it soon). Sent by Zyke", decide(m, rules(sister)).shares.single().text)
        for (digits in listOf("1496", "12345678")) assertEquals(digits, (msg("JioHotstar", "<#> $digits is your JioHotstar verification code. /wAX/OsgZgE").content as MessageContent.Otp).code)
    }

    @Test fun anyHeaderFormOfTheSenderMatchesItsOwnSource() {
        for (sender in listOf("VM-JIOHOTSTAR-S", "jd-jiohotstar-t", "JioHotstar", "JIOHOTSTAR", "AX-JioHotstar")) {
            assertEquals(sender, 1, decide(msg(sender, sample), rules(sister)).shares.size)
        }
        // Another header of the same service is another source until the person chooses it.
        val d = decide(msg("VM-JIOHST-S", sample), rules(sister))
        assertTrue(d.shares.isEmpty())
        assertEquals(setOf(ShareSkip.NO_ONE_FOR_SERVICE), d.reasons)
    }

    @Test fun aCustomSourceNeedsNoBrandInTheBodyButNeedsALoginCode() {
        val swiggy = ShareSource("c:SWIGGY", "Swiggy", "SWIGGY", false)
        val r = rules(TrustedContact("c1", "A", "+919876543210", setOf(swiggy.id)), sources = listOf(swiggy))
        assertEquals("Swiggy code 4821 (use it soon). Sent by Zyke", decide(msg("VM-SWIGGY-S", "<#> 4821 is your verification code. /wAX/OsgZgE"), r).shares.single().text)
        // Plainer wording is read by the strict reader because the sender is chosen.
        assertEquals(1, decide(msg("VM-SWIGGY-S", "Your login code is 482913. Don't share it."), r).shares.size)
        // Not a code at all.
        assertEquals(setOf(ShareSkip.NOT_A_CODE), decide(msg("VM-SWIGGY-S", "Hello, 4821 people are waiting for you."), r).reasons)
        assertEquals(setOf(ShareSkip.NOT_A_CODE), decide(msg("VM-SWIGGY-S", "Big sale this weekend!"), r).reasons)
    }

    // ---- keys and labels

    @Test fun senderKeysAreNormalised() {
        assertEquals("JIOHOT", ShareSources.key("VM-JIOHOT-S"))
        assertEquals("JIOHST", ShareSources.key("JD-JIOHST-T"))
        assertEquals("JIOHOTSTAR", ShareSources.key("JioHotstar"))
        assertEquals("JIOHOTSTAR", ShareSources.key("vm-JioHotstar-s"))
        assertEquals("AMAZONIN", ShareSources.key("VK-AMAZON-IN-S"))
        assertEquals("#919876543210", ShareSources.key("+919876543210"))
        assertEquals("", ShareSources.key(""))
        assertEquals("", ShareSources.key("Hi"))
    }

    @Test fun theServiceNameIsReadFromTheBody() {
        assertEquals("JioHotstar", ShareBrand.fromBody(sample))
        assertEquals("Google", ShareBrand.fromBody("G-123456 is your Google verification code."))
        assertEquals("Swiggy", ShareBrand.fromBody("Your Swiggy OTP is 123456"))
        assertEquals("Spotify", ShareBrand.fromBody("Spotify code: 123456"))
        assertEquals("Uber", ShareBrand.fromBody("Your Uber code is 4821"))
        assertEquals("Zomato", ShareBrand.fromBody("123456 is your Zomato login OTP"))
        assertNull(ShareBrand.fromBody("123456 is your verification code"))
        assertNull(ShareBrand.fromBody("Your one-time password is 123456"))
        assertNull(ShareBrand.fromBody("use this code 123456"))
    }

    @Test fun labelsAreAsciiAndShort() {
        assertEquals("JioHotstar", ShareSources.cleanLabel("  JioHotstar é😀 "))
        assertTrue(ShareSources.cleanLabel("A very long service name indeed").length in 15..20)
        val src = ShareSources.custom("VM-ZOMATO-S", "Zomato ü Gold Premium Membership")!!
        assertTrue(CodeSharing.isOneSegment(CodeSharing.text(src, "12345678", "A".repeat(100))))
    }

    // ---- hard blocks

    @Test fun bankPaymentAndSimilarSendersCannotBeSources() {
        for (h in listOf("AX-HDFCBK-T", "JD-ICICIB-S", "VM-SBIN", "VM-PAYTM", "BZ-UPIIND", "VM-PHONEPE", "AD-AMAZONPAY", "VM-AIRTEL", "VM-UIDAI", "AX-AXISBK", "VK-ZERODHA", "VM-BINANCE", "AD-CREDIN", "JD-LICIND-S", "VM-IRDAI")) {
            assertNull(h, ShareSources.custom(h, ""))
            assertEquals(h, SourceProblem.BLOCKED, ShareSources.problem(ShareSources.key(h), "Whatever"))
        }
        assertEquals(SourceProblem.BLOCKED, ShareSources.problem("XYZ", "HDFC Bank"))
        assertEquals(SourceProblem.BLOCKED, ShareSources.problem("XYZ", "Money Wallet"))
        assertEquals(SourceProblem.NOT_A_NAME, ShareSources.problem("", "X"))
        // Plenty of ordinary services are fine, including ones that share letters with the block list.
        for (h in listOf("VM-JIOHOTSTAR-S", "AX-SWIGGY-S", "VM-ZOMATO-T", "UBER", "GOOGLE", "MSFT", "SPOTIFY", "JD-PUBLIC-S", "VM-ROBLOX-S")) assertNotNull(h, ShareSources.custom(h, ""))
        assertEquals("Jiohotstar", ShareSources.custom("VM-JIOHOTSTAR-S", "")!!.label)
    }

    @Test fun paymentWordedMessagesAreNeverForwardedWhateverTheSource() {
        for (body in listOf(
            "<#> 1496 is your JioHotstar verification code for payment of Rs 299. /wAX/OsgZgE",
            "1496 is your JioHotstar OTP. Your UPI mandate of INR 299 is pending.",
            "1496 is your JioHotstar code. Card ending 4455 will be debited.",
            "1496 is your JioHotstar code for Aadhaar verification."
        )) assertTrue(body, decide(msg("JioHotstar", body), rules(sister)).shares.isEmpty())
        // And a stored source whose key turns out to be a bank is still stopped by the header.
        val bank = ShareSource("c:HDFCBK", "Alerts", "HDFCBK", false)
        val r = rules(TrustedContact("c1", "A", "+919876543210", setOf(bank.id)), sources = listOf(bank))
        assertEquals(setOf(ShareSkip.FINANCIAL), decide(msg("AX-HDFCBK-T", "123456 is your OTP"), r).reasons)
    }

    // ---- the rules around it still apply

    @Test fun staleDuplicateAndRateLimitStillApply() {
        assertEquals(setOf(ShareSkip.STALE), decide(msg("JioHotstar", sample, age = 10 * 60_000L), rules(sister)).reasons)
        val first = decide(msg("JioHotstar", sample), rules(sister)).shares.single()
        val history = ShareHistory(listOf(SentRecord("c1", first.source.id, now - 1000, first.codeKey)))
        assertEquals(setOf(ShareSkip.ALREADY_SENT), decide(msg("JioHotstar", sample), rules(sister), history).reasons)
        val busy = ShareHistory((1..5).map { SentRecord("c1", jio.id, now - it * 1000L, null) })
        assertEquals(setOf(ShareSkip.RATE_LIMITED), decide(msg("JioHotstar", sample), rules(sister), busy).reasons)
        assertEquals(setOf(ShareSkip.OFF), CodeSharing.decide(msg("JioHotstar", sample), rules(sister).copy(enabled = false), now, ShareHistory(), zone).reasons)
    }

    @Test fun onlyPeopleWhoChoseTheSourceGetIt() {
        val brother = TrustedContact("c2", "Brother", "+919812345678", setOf("netflix"))
        val d = decide(msg("JioHotstar", sample), rules(sister, brother))
        assertEquals(listOf("c1"), d.shares.map { it.contact.id })
    }

    @Test fun aSenderNobodyChoseIsLoggedByName() {
        val review = CodeSharing.review(msg("JioHotstar", sample), CodeSharingRules(enabled = true, contacts = listOf(sister.copy(services = setOf("netflix")))), now, ShareHistory(), zone)
        assertEquals("JioHotstar", review.label)
        assertTrue(review.codeLike)
        assertEquals(ShareSkip.NO_ONE_FOR_SERVICE, review.decision.skipped.single().reason)
    }

    @Test fun aBuiltInAndACustomSourceForTheSameHeaderSendOnce() {
        val both = sister.copy(services = setOf("hotstar", "c:JIOHOT"))
        val src = ShareSource("c:JIOHOT", "JioHotstar", "JIOHOT", false)
        val d = decide(msg("VM-JIOHOT-S", sample), rules(both, sources = listOf(src)))
        assertEquals(1, d.shares.size)
        assertEquals("JioHotstar code 1496 (use it soon). Sent by Zyke", d.shares.single().text)
    }

    // ---- people and backward compatibility

    @Test fun savedPeopleWithTheOldServiceIdsStillWork() {
        val old = TrustedContact("c1", "Sister", "+919876543210", setOf("netflix", "hotstar"))
        val r = CodeSharingRules(enabled = true, contacts = listOf(old))
        assertEquals(1, decide(msg("VM-NFLXIN-S", "482913 is your Netflix verification code."), r).shares.size)
        assertEquals("Hotstar code 561234 (use it soon). Sent by Zyke", decide(msg("JM-JHSTAR-S", "561234 is your JioHotstar verification code."), r).shares.single().text)
    }

    @Test fun aPersonCanBeSavedWithCustomSourcesAndUnknownIdsAreDropped() {
        val built = TrustedContacts.build("n", "Sister", "98765 43210", setOf("c:JIOHOTSTAR", "netflix", "c:GONE", "nonesuch"), emptyList(), sources = listOf(jio))
        assertEquals(setOf("c:JIOHOTSTAR", "netflix"), (built as ContactResult.Ok).contact.services)
        assertEquals(ContactProblem.NO_SERVICE, (TrustedContacts.build("n", "S", "98765 43210", setOf("c:JIOHOTSTAR"), emptyList()) as ContactResult.Bad).problem)
    }

    @Test fun removingASourceDetachesItFromEveryone() {
        val r = rules(sister, sister.copy(id = "c2", number = "+919812345678", services = setOf("netflix", jio.id)))
        val after = ShareSources.remove(r, jio.id)
        assertTrue(after.sources.isEmpty())
        assertEquals(listOf(emptySet<String>(), setOf("netflix")), after.contacts.map { it.services })
    }

    @Test fun renamingKeepsTheIdAndTheChoices() {
        val renamed = ShareSources.rename(listOf(jio), jio.id, "  Hotstar é TV ")
        assertEquals("c:JIOHOTSTAR", renamed.single().id)
        assertEquals("Hotstar TV", renamed.single().label)
        assertEquals("JioHotstar", ShareSources.rename(listOf(jio), jio.id, "   ").single().label)
        assertEquals("NETFLIX", ShareSources.rename(listOf(jio), "netflix", "NETFLIX").let { "NETFLIX" })
    }

    @Test fun learnedHeadersBecomeCustomSourcesForThePeopleWhoHadTheService() {
        val a = TrustedContact("c1", "A", "+919876543210", setOf("netflix", "zee5"))
        val b = TrustedContact("c2", "B", "+919812345678", setOf("zee5"))
        val migrated = ShareMigration.fromLearned(CodeSharingRules(contacts = listOf(a, b)), mapOf("netflix" to setOf("XYZNFL"), "zee5" to setOf("ZEEX"), "hotstar" to setOf("HDFCBK")))
        assertEquals(listOf("c:XYZNFL", "c:ZEEX"), migrated.sources.map { it.id })
        assertEquals(listOf("Netflix", "ZEE5"), migrated.sources.map { it.label })
        assertEquals(setOf("netflix", "zee5", "c:XYZNFL", "c:ZEEX"), migrated.contacts[0].services)
        assertEquals(setOf("zee5", "c:ZEEX"), migrated.contacts[1].services)
        // And the migrated header now forwards.
        val r = migrated.copy(enabled = true)
        assertEquals("Netflix code 482913 (use it soon). Sent by Zyke", decide(msg("VM-XYZNFL-S", "482913 is your Netflix verification code."), r).shares.single().text)
    }

    // ---- the picker's list

    private fun otp(sender: String, code: String, ageDays: Double, brand: String = "Brand") =
        msg(sender, "$code is your $brand verification code.", age = (ageDays * day).toLong())

    @Test fun theListIsDedupedCountedAndNewestFirst() {
        val list = listOf(
            otp("VM-JIOHOT-S", "1111", 10.0, "JioHotstar"), otp("JD-JIOHOT-T", "2222", 2.0, "JioHotstar"), otp("VM-JIOHOT-S", "3333", 30.0, "JioHotstar"),
            otp("AX-SWIGGY-S", "4444", 1.0, "Swiggy"), otp("UBER", "5555", 40.0, "Uber")
        )
        val out = ReceivedCodeSenders.build(list, now)
        assertEquals(listOf("SWIGGY", "JIOHOT", "UBER"), out.map { it.key })
        val jioRow = out[1]
        assertEquals(3, jioRow.count)
        assertEquals(now - (2 * day), jioRow.lastAt)
        assertEquals("JioHotstar", jioRow.brand)
        assertEquals("JioHotstar", jioRow.label)
        assertEquals("3 codes · last 2 days ago", ReceivedCodeSenders.summary(jioRow, now))
        assertEquals("1 code · last yesterday", ReceivedCodeSenders.summary(out[0], now))
        assertTrue(out[1].verified)
        assertEquals("c:JIOHOT", jioRow.sourceId)
    }

    @Test fun onlyTheLastNinetyDaysAndOnlyCodesCount() {
        val list = listOf(
            otp("AX-SWIGGY-S", "4444", 89.0, "Swiggy"), otp("VM-OLD-S", "1111", 91.0, "Old"),
            msg("VM-SHOP-S", "Big sale on now, up to 50% off", age = day), msg("Mom", "Dinner at 8?", age = day)
        )
        assertEquals(listOf("SWIGGY"), ReceivedCodeSenders.build(list, now).map { it.key })
        assertTrue(ReceivedCodeSenders.build(emptyList(), now).isEmpty())
    }

    @Test fun phoneNumbersAreMaskedAndNeverFirst_andBlockedSendersGoLastGreyed() {
        val list = listOf(
            otp("+919876543210", "1111", 0.0, "Friend"), otp("AX-HDFCBK-T", "2222", 0.1, "Bank"), otp("AX-SWIGGY-S", "3333", 20.0, "Swiggy"),
            msg("AX-ICICIB-S", "4444 is your OTP for transaction of Rs 500 at Amazon", age = 0)
        )
        val out = ReceivedCodeSenders.build(list, now)
        assertEquals(listOf("SWIGGY"), out.filter { !it.blocked && !it.isPhone }.map { it.key })
        assertEquals(listOf(false, false, true, true), out.map { it.blocked })
        assertTrue(out[1].isPhone)
        assertEquals("••••••10", out[1].header)
        assertFalse(out.any { it.header.contains("9876543210") })
        assertNull(ReceivedCodeSenders.sourceFor(out[2], emptyList()))
        assertNull(ReceivedCodeSenders.sourceFor(out[3], emptyList()))
        val picked = ReceivedCodeSenders.sourceFor(out[0], emptyList())!!
        assertEquals("c:SWIGGY", picked.id)
        assertEquals("Swiggy", picked.label)
    }

    @Test fun theListNeverHoldsACodeOrABody() {
        val out = ReceivedCodeSenders.build(listOf(otp("VM-JIOHOT-S", "482913", 1.0, "JioHotstar"), msg("JioHotstar", sample, age = day)), now)
        val text = out.toString()
        assertFalse(text, text.contains("482913"))
        assertFalse(text, text.contains("1496"))
        assertFalse(text, text.contains("verification"))
        assertFalse(text, text.contains("wAX"))
        val fields = com.zyke.message.domain.sharing.ReceivedSender::class.java.declaredFields.map { it.name }.filter { !it.startsWith("$") }.toSet()
        assertEquals(setOf("key", "sourceId", "header", "displayName", "verified", "brand", "label", "count", "lastAt", "isPhone", "blocked"), fields)
    }

    @Test fun searchFiltersByNameServiceOrHeader() {
        val out = ReceivedCodeSenders.build(listOf(otp("VM-JIOHOT-S", "1111", 1.0, "JioHotstar"), otp("AX-SWIGGY-S", "2222", 2.0, "Swiggy")), now)
        assertEquals(listOf("JIOHOT"), ReceivedCodeSenders.filter(out, "hotstar").map { it.key })
        assertEquals(listOf("SWIGGY"), ReceivedCodeSenders.filter(out, "ax-sw").map { it.key })
        assertEquals(2, ReceivedCodeSenders.filter(out, "  ").size)
        assertTrue(ReceivedCodeSenders.filter(out, "zzz").isEmpty())
    }

    @Test fun theSixBuiltInsAreStillThere() {
        assertEquals(setOf("netflix", "hotstar", "prime", "sunnxt", "sonyliv", "zee5"), ShareSources.builtIns.map { it.id }.toSet())
        assertTrue(ShareSources.builtIns.all { it.builtIn })
        assertEquals(6, ShareServices.all.size)
    }
}
