# Unification audit

Features were built in parallel, so some concepts exist twice. This file lists every overlap that was checked by
reading the code, what was decided, and what the person sees. "Merged" means one entry point, one data store and one
UI; the old store stays readable.

## Merged

### 1. Envelopes + Spending limits -> Budgets

| | Before | After |
|---|---|---|
| Concept | "Spending limits" (Settings, a monthly and/or daily cap per category) and "Envelopes" (Insights, an amount per category per cycle, with carry-over, move money and "not yet assigned") | One concept: **Budgets**. A budget is a category's monthly amount (plus an optional daily cap) |
| Data | Prefs `budgets` / `daily_budgets` and, separately, prefs `envelopes` (per-cycle assignments) | Prefs `budgets` (monthly), `daily_budgets` (daily) and, in the same `budgets` file, the budget adjustments (carry-over choice per category, money moved per cycle). `BudgetRepository` is the only store |
| Entry | Settings > Spending limits, and Insights > Envelopes page | Settings > Budgets only. Insights shows how the budgets are going and links to Settings |
| Domain | `SpendingLimits` and `Envelopes` | `SpendingLimits` (status) and `Budgets` (carry-over, moves, not-yet-assigned) |

What the person sees: the same limits as before, now called Budgets. Each monthly budget can carry leftovers into the next
cycle, money can be moved between budgets, and the section says how much of the plan's spendable money is "not yet
assigned". The separate Envelopes card and page are gone.

Migration (idempotent, nothing deleted):
- On first start of the new version the app reads the old `envelopes` prefs once. For the most recent cycle that had
  amounts, each envelope amount becomes the category's monthly budget **only if the category has no monthly budget yet**
  (an existing limit is never overwritten). Carry-over choices and moves are copied into the budget adjustments (only
  where the new store has none).
- A `migrated_envelopes` flag in the `budgets` file makes this run once. Clearing all budgets keeps the flag, so removed
  budgets never come back.
- The `envelopes` prefs file is left untouched, so a downgrade or a re-run loses nothing.
- Old backups restore as before: the restored `envelopes` file has no flag in the restored `budgets` file, so the
  migration runs again on the next start. New backups carry the `budgets` file like every other prefs file.

Known behaviour change: envelope amounts used to be chosen per cycle; budgets are standing amounts, so the amount of the
latest cycle is used for every cycle (past carry-over chains use today's amount).

### 2. Limit suggestions shown twice -> Settings only

"Suggested for your plan" appeared in Settings > limits and again as a card in Insights. Now it is in Settings > Budgets
only; Insights links there. The unused `BudgetDialog` was deleted.

## Checked and kept (genuinely different, or already one path)

| Candidates | Decision |
|---|---|
| Goals vs plan "savings goal" vs "Afford it -> Add as a goal" | Keep. The plan's savings goal is one monthly amount to set aside; a Goal is a named target with a balance. Afford it is an entry that creates a Goal (already one data store, `GoalRepository`) |
| Reminders vs Calendar events | Keep for now, flagged below. Reminders alert and may repeat or carry an amount; events are calendar-only. The calendar page already merges both, bills, goals and salary into one view |
| Upcoming / Next due / Bills / Calendar | Keep. All read the same `Upcoming`, `UpcomingOutflow` and `BillPayments` domain code; the Next 7 days card is already removed. Each screen is a different view (one next bill, the bills tab, a month) |
| Needs review vs Review queue vs Attention strip | Keep. Attention is the top three act-now items (codes, bills due, low FASTag); the Review queue is what the app is unsure of (possible duplicates and transfers, unread messages). Home already shows one card for each and a single page for review |
| Subscriptions vs Recurring vs Regular payments | Already one: `RecurringPayments` / `Subscriptions` feed the single "Repeats monthly" section and the "Regular payment" calendar kind |
| Net worth vs Accounts totals | Keep. Accounts totals are bank balances from messages; Net worth adds the person's own assets and loans, and is reached from the balance card |
| Year in review vs Monthly summary | Keep. Different periods (a year, a month) and different pages |
| Card swipe vs card bill payment | One rule: `Counting.of(message)` (domain/ledger/Counting.kt) says whether a message is spending, income, a card refund or not counted, and every total reads it. A card swipe is spending on the day of the swipe; the bank debit that pays the card's bill is not counted again (`CardBillPayments` finds it: card-bill wording, the bill's amount, a matching "payment received" on the card, or a bill-payment service the person confirms in Needs review). The bank balance still falls by it. A credit on a card that is not a bill payment is a refund and lowers spending; it is never income |
| Mute / hide / leave out of totals | Keep. Muted sender (hide a sender's messages), hidden account (leave out of totals) and set-aside (leave a payment out) act on different objects |
| Alerts toggles | Not audited toggle by toggle; Settings > Reminders and alerts is the one entry point and none was merged |
| GlobalSearch vs feed search | Keep. Feed search filters the inbox list; GlobalSearch is the app-wide page |

## Follow-ups not done

- Calendar events and Reminders could become one "Reminder with an optional alert" with a migration of the `calendar_events`
  prefs. It touches alarms and notifications, so it needs on-device testing and was left out of this pass.
