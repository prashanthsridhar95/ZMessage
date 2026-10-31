package com.zyke.message.domain.perf

import com.zyke.message.domain.accounts.Accounts
import com.zyke.message.domain.budget.DailyBudgets
import com.zyke.message.domain.cycle.CycleRule
import com.zyke.message.domain.dashboard.DashboardBuilder
import com.zyke.message.domain.dashboard.DashboardQuery
import com.zyke.message.domain.dashboard.SpendingLimits
import com.zyke.message.domain.feed.AttentionBuilder
import com.zyke.message.domain.home.UpcomingBuilder
import com.zyke.message.domain.insights.BalanceReader
import com.zyke.message.domain.insights.LowBalance
import com.zyke.message.domain.insights.PaceNudges
import com.zyke.message.domain.insights.RefundTracker
import com.zyke.message.domain.insights.SpendAlerts
import com.zyke.message.domain.insights.Subscriptions
import com.zyke.message.domain.ledger.ReviewQueues
import com.zyke.message.domain.ledger.SetAsides
import com.zyke.message.domain.life.LifeAgenda
import com.zyke.message.domain.model.MessageContent
import com.zyke.message.domain.plan.MoneyPlan
import com.zyke.message.domain.plan.PlanInsightsBuilder
import com.zyke.message.domain.plan.QuickAdds
import com.zyke.message.domain.pf.ProvidentFunds
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Timing printout only (no assertions beyond a very generous bound): how long each Home part takes on ~1600 messages. */
class HomePartsBenchmark {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val today = LocalDate.of(2026, 10, 31)
    private val now: Instant = today.atTime(12, 0).atZone(zone).toInstant()

    @Test
    fun printPartTimings() {
        val msgs = SyntheticInbox.build(1600)
        val plan = MoneyPlan()
        val asides = SetAsides()
        val dismissed = emptySet<String>()
        val parts = linkedMapOf<String, () -> Any?>()
        val live = msgs
        val cycle = CycleRule.resolve(plan, msgs, now, zone)
        parts["cycle"] = { CycleRule.resolve(plan, msgs, now, zone) }
        parts["dashboard"] = { DashboardBuilder.build(msgs, DashboardQuery(), now, zone, emptyMap(), cycle) }
        parts["limits"] = { SpendingLimits.build(live, emptyMap(), emptyMap(), now, zone, cycle) }
        parts["balances"] = { BalanceReader.latest(live, zone) }
        parts["insights"] = { PlanInsightsBuilder.build(plan, live, now, zone, cycle) }
        parts["accounts"] = { Accounts.build(live, emptyList(), now, zone, cycle, emptySet(), emptySet()) }
        parts["quickAdds"] = { QuickAdds.find(live, plan, emptyList(), dismissed, now, zone, emptyMap()) }
        parts["nudges"] = { LowBalance.warnings(live, BalanceReader.latest(live, zone), today) }
        parts["attention"] = { AttentionBuilder.build(live.filter { !it.isPromotional }, dismissed, now, zone, emptyMap()) }
        parts["upcoming"] = { UpcomingBuilder.build(msgs, today, includePaidToday = true, zone = zone, markedPaid = dismissed) }
        parts["review"] = { ReviewQueues.build(live, asides, zone, now, dismissed) }
        parts["pf"] = { ProvidentFunds.find(live, zone) }
        parts["refunds"] = { RefundTracker.build(live, now, zone) }
        parts["subs"] = { Subscriptions.find(live, now, zone) }
        parts["alerts"] = { SpendAlerts.find(live, now.toEpochMilli()) }
        parts["agenda"] = { LifeAgenda.build(live.filter { !it.isPromotional }, today, zone) }
        parts["live"] = { live.filter { it.id !in emptySet<Long>() } }
        parts["feed"] = { com.zyke.message.domain.feed.FeedBuilder.build(msgs, com.zyke.message.domain.model.UserMessageState(), com.zyke.message.domain.feed.MessageFilter.All, "", now, zone) }
        parts["feedSearch"] = { com.zyke.message.domain.feed.FeedBuilder.build(msgs, com.zyke.message.domain.model.UserMessageState(), com.zyke.message.domain.feed.MessageFilter.All, "swiggy", now, zone) }
        parts["conversations"] = { com.zyke.message.domain.conversation.ConversationBuilder().conversations(msgs, com.zyke.message.domain.model.UserMessageState(), "") }
        parts["unread"] = { live.count { !it.message.isRead } }

        val medians = linkedMapOf<String, Double>()
        for ((name, f) in parts) {
            repeat(8) { f() }
            val times = DoubleArray(11) { val t = System.nanoTime(); f(); (System.nanoTime() - t) / 1e6 }.sorted()
            medians[name] = times[5]
        }
        println("== HOME PARTS (${msgs.size} msgs, ${msgs.count { it.content is MessageContent.Transaction }} tx) ==")
        medians.entries.sortedByDescending { it.value }.forEach { println("  %-10s %7.1f ms".format(it.key, it.value)) }
        println("  TOTAL      %7.1f ms (one after another)".format(medians.values.sum()))
        val pool = java.util.concurrent.Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())
        try {
            fun wall(): Double {
                val t = System.nanoTime()
                parts.values.map { f -> pool.submit { f() } }.forEach { it.get() }
                return (System.nanoTime() - t) / 1e6
            }
            repeat(8) { wall() }
            val walls = DoubleArray(11) { wall() }.sorted()
            println("  WALL       %7.1f ms (side by side on %d cores)".format(walls[5], Runtime.getRuntime().availableProcessors()))
        } finally {
            pool.shutdown()
        }
    }
}
