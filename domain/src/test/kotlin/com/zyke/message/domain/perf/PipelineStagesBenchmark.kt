package com.zyke.message.domain.perf

import com.zyke.message.domain.cards.CardMatcher
import com.zyke.message.domain.categories.CategoryKey
import com.zyke.message.domain.insights.CategorySuggester
import com.zyke.message.domain.ledger.SetAsides
import com.zyke.message.domain.ledger.TransferLink
import com.zyke.message.domain.merge.Merges
import com.zyke.message.domain.model.CardType
import com.zyke.message.domain.model.MessageCategory
import com.zyke.message.domain.model.MessageContent
import com.zyke.message.domain.model.UserCard
import org.junit.Test

/** Timing printout only: each stage of the inbox pipeline over ~1600 messages, with a realistic number of user choices. */
class PipelineStagesBenchmark {
    @Test
    fun printStageTimings() {
        val msgs = SyntheticInbox.build(1600)
        val payees = msgs.mapNotNull { CategoryKey.of(it) }.distinct()
        val overrides = payees.take(60).associateWith { MessageCategory.FOOD } + msgs.take(4).associate { CategoryKey.messageKey(it.id) to MessageCategory.SHOPPING }
        val words = mapOf("swiggy" to MessageCategory.FOOD, "amazon" to MessageCategory.SHOPPING, "cred" to MessageCategory.CREDIT_CARDS)
        val nicknames = payees.take(10).associateWith { "Nick $it" }
        val cards = listOf(UserCard(id = "c1", type = CardType.CREDIT, last4 = "1234", name = "A"), UserCard(id = "c2", type = CardType.ACCOUNT, last4 = "5765", name = "B"))
        val txIds = msgs.filter { it.content is MessageContent.Transaction }.map { it.id }
        val asides = SetAsides(excluded = txIds.take(2).toSet(), transfers = txIds.drop(10).take(32).chunked(2).map { TransferLink(it[0], it[1]) }.toList(), dismissed = emptySet())
        val merges = txIds.drop(100).take(10).chunked(2).associate { it[0] to it[1] }

        val stages = linkedMapOf<String, () -> Any?>()
        stages["category"] = { msgs.map { CategoryKey.apply(it, overrides, words) } }
        stages["cards"] = { msgs.map { CardMatcher.apply(it, cards) } }
        stages["nickname"] = {
            msgs.map { m ->
                val own = nicknames[CategoryKey.messageKey(m.id)]
                val nick = own ?: CategoryKey.of(m)?.let(nicknames::get)
                if (nick != null) m.copy(merchant = m.merchant.copy(name = nick, renamed = true), nicknameOwn = own != null) else m
            }
        }
        stages["setAside"] = {
            msgs.map { m ->
                val kind = m.setAside ?: asides.kindOf(m.id) ?: return@map m
                m.copy(setAside = kind)
            }
        }
        stages["merges"] = { Merges.apply(msgs, merges) }
        stages["suggest"] = { CategorySuggester.annotate(msgs) }
        for ((name, f) in stages) {
            repeat(8) { f() }
            val t = DoubleArray(11) { val s = System.nanoTime(); f(); (System.nanoTime() - s) / 1e6 }.sorted()
            println("  PIPE %-10s %6.1f ms".format(name, t[5]))
        }
    }
}
