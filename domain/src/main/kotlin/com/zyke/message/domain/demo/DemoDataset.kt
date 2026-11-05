package com.zyke.message.domain.demo

import com.zyke.message.domain.calendar.CalendarEvent
import com.zyke.message.domain.goals.Goal
import com.zyke.message.domain.investments.Holding
import com.zyke.message.domain.loans.Loan
import com.zyke.message.domain.markets.Quote
import com.zyke.message.domain.markets.WatchSettings
import com.zyke.message.domain.model.MessageCategory
import com.zyke.message.domain.model.SmsMessage
import com.zyke.message.domain.model.UserCard
import com.zyke.message.domain.networth.NetWorthItem
import com.zyke.message.domain.plan.MoneyPlan
import com.zyke.message.domain.reminders.Reminder
import java.math.BigDecimal

/**
 * Everything Demo mode shows, in one value: the invented inbox and the invented setup of the invented person. Built by
 * [DemoData.build]; it only describes things, and what to do with it (load it into in-memory stores) is the app's job.
 */
class DemoDataset(
    /** Oldest first, with ids that grow with the date, the way the phone's own inbox numbers them. */
    val messages: List<SmsMessage>,
    val cards: List<UserCard>,
    val plan: MoneyPlan,
    /** Monthly limits and the one daily limit. */
    val budgets: Map<MessageCategory, BigDecimal>,
    val dailyBudgets: Map<MessageCategory, BigDecimal>,
    val goals: List<Goal>,
    val holdings: List<Holding>,
    val netWorth: List<NetWorthItem>,
    val loans: List<Loan>,
    val reminders: List<Reminder>,
    val events: List<CalendarEvent>,
    /** The category chosen for a payee, by [com.zyke.message.domain.categories.CategoryKey] (so groceries, fuel, travel... are filled in). */
    val categories: Map<String, MessageCategory>,
    /** The names given to payees, by the same key. */
    val nicknames: Map<String, String>,
    val watch: WatchSettings,
    /** Fixed prices for the market strip; Demo mode never asks the network. */
    val quotes: Map<String, Quote>
)
