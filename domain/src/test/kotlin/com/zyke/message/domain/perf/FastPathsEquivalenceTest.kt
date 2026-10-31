package com.zyke.message.domain.perf

import com.zyke.message.domain.categories.CategoryKey
import com.zyke.message.domain.categories.Tokens
import com.zyke.message.domain.home.BillPayments
import com.zyke.message.domain.ledger.ManualEntries
import com.zyke.message.domain.ledger.ReviewQueues
import com.zyke.message.domain.ledger.SetAsides
import com.zyke.message.domain.ledger.TransferLink
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.Random
import kotlin.math.abs
import com.zyke.message.domain.util.toBigDecimalOrNull as fast

/** The quicker ways of doing things give exactly the answers of the plain ones, on randomised inputs. */
class FastPathsEquivalenceTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val now: Instant = Instant.parse("2026-10-31T06:30:00Z")

    @Test
    fun fastDecimal_readsLikeTheStandardOne() {
        val samples = listOf(
            "", " ", "0", "00", "1", "-1", "+1", "1.", ".5", "1.50", "1,500", "1e5", "1E-3", "NaN", "Infinity", "0x10", "1d", "1f",
            " 12", "12 ", "12.34.56", "١٢", "1_000", "999999999999999999999999.99", "-0.0", "5000.00", "abc", "1.5.", "--1"
        )
        val random = Random(3)
        val generated = (1..500).map { _ ->
            val chars = "0123456789.,-+eE xX"
            String(CharArray(random.nextInt(8)) { chars[random.nextInt(chars.length)] })
        }
        for (text in samples + generated) {
            assertEquals("for \"$text\"", text.toBigDecimalOrNull(), text.fast())
        }
    }

    @Test
    fun transferCandidates_matchThePlainScan() {
        for (seed in 1L..6L) {
            val messages = SyntheticInbox.build(500, seed = seed)
            val random = Random(seed)
            // Dismiss a few of the pairs the plain scan finds, to exercise that condition too.
            val first = referenceTransfers(messages, SetAsides())
            val dismissed = first.filter { random.nextInt(3) == 0 }.map { "${it.first.id}:${it.second.id}" }.toSet()
            val asides = SetAsides(dismissed = dismissed)
            val expected = referenceTransfers(messages, asides).map { it.first.id to it.second.id }
            val actual = ReviewQueues.build(messages, asides, zone, now).transfers.map { it.debit.id to it.credit.id }
            assertEquals("seed $seed", expected, actual)
        }
    }

    private fun last4(tx: MessageContent.Transaction) = tx.accountNumber.filter { it.isDigit() }.takeLast(4)

    /** The scan as it was written before credits were indexed by amount. */
    private fun referenceTransfers(messages: List<ClassifiedMessage>, asides: SetAsides): List<Pair<ClassifiedMessage, ClassifiedMessage>> {
        val live = messages.filter { !it.isPromotional && !it.isMuted && it.setAside == null }
        val txs = live.filter { it.content is MessageContent.Transaction && !ManualEntries.isManual(it) }
            .filter { (it.content as MessageContent.Transaction).let { tx -> !tx.isCardPayment && last4(tx).isNotEmpty() } }
        val debits = txs.filter { !(it.content as MessageContent.Transaction).isReceived }
        val credits = txs.filter { (it.content as MessageContent.Transaction).isReceived }
        val used = HashSet<Long>()
        return debits.sortedByDescending { it.message.date }.mapNotNull { debit ->
            val d = debit.content as MessageContent.Transaction
            val match = credits.firstOrNull { credit ->
                val c = credit.content as MessageContent.Transaction
                credit.id !in used &&
                    c.amount.toBigDecimalOrNull()?.compareTo(d.amount.toBigDecimalOrNull() ?: return@firstOrNull false) == 0 &&
                    last4(c) != last4(d) &&
                    abs(credit.message.date - debit.message.date) <= 24L * 60 * 60 * 1000 &&
                    "${debit.id}:${credit.id}" !in asides.dismissed
            } ?: return@mapNotNull null
            used += match.id
            debit to match
        }.take(20)
    }

    @Test
    fun billPayments_findsTheSameAsOnTheOldFilteredList() {
        for (seed in 1L..4L) {
            val messages = SyntheticInbox.build(600, seed = seed)
            val bills = messages.filter { it.content is MessageContent.CardBill }
            check(bills.isNotEmpty())
            for (bill in bills.take(40)) {
                val content = bill.content as MessageContent.CardBill
                // What the plain version looked at: later messages, no bills, oldest first (a stable sort).
                val old = messages.filter { it.message.date > bill.message.date && it.id != bill.id && it.content !is MessageContent.CardBill }
                    .sortedBy { it.message.date }
                val expected = BillPayments.find(bill, content, old, zone)
                val actual = BillPayments.find(bill, content, messages, zone)
                assertEquals("seed $seed bill ${bill.id}", expected, actual)
                // And asking again for another list in between does not confuse what was remembered.
                assertEquals(expected, BillPayments.find(bill, content, messages.toList(), zone))
            }
        }
    }

    @Test
    fun categoryKeyAndTokens_cachesChangeNothing() {
        val names = listOf("  Burmy  Foods ", "SWIGGY", "swiggy", "Cafe\tOne\n", "", "   ", "A  B", "UPI-ZOMATO 123", "Shop@okhdfc")
        repeat(3) {
            for (n in names) {
                assertEquals(n.trim().lowercase().replace(Regex("\\s+"), " "), CategoryKey.normalise(n))
                val key = CategoryKey.normalise(n)
                val plain = key.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 3 && !it.all(Char::isDigit) && it !in setOf(
                    "the", "and", "pvt", "ltd", "limited", "private", "india", "bank", "upi", "payment", "payments",
                    "services", "service", "store", "stores", "mr", "mrs", "ms", "shri", "smt", "co", "corp", "inc", "llp") }.distinct()
                assertEquals(plain, Tokens.of(key))
            }
        }
    }

    @Test
    fun transferIds_areTheLegsOfTheTransfers() {
        val asides = SetAsides(transfers = listOf(TransferLink(1, 2), TransferLink(null, 5), TransferLink(7, null)))
        assertEquals(setOf(1L, 2L, 5L, 7L), asides.transferIds)
        assertEquals(com.zyke.message.domain.ledger.SetAside.TRANSFER, asides.kindOf(5))
        assertNull(asides.kindOf(9))
    }
}
