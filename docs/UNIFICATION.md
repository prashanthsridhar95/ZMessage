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

### 3. Accounts tab: one visual language for Accounts, Cards and Investments

`HeaderRegion` (a dark, edge-to-edge region with large bottom corners; inverse surface in light themes, a raised surface in dark),
`StackedDeck` (cards peek out behind the front one, swipe, page dots; a plain pager in Lite mode or with animations off),
`ActionCircleRow` (up to four round 48 dp actions with labels under them; a 2 x 2 grid at font scale 1.8 and up),
`SpendCard` ("Spent this month": big amount, limit or "Set a limit", thin bar, one note line, `CategoryBar` and category rows) live
in `presentation/common` and are the only components the three sub-tabs use. Cards: deck, Pay bill / Details / Limit / Mark paid,
spend card for the card's cycle, bill panel, recent spends. Accounts: total and a deck of bank accounts, Check balance (progress in
the circle) / Details / Set balance, spend card with "Came in", recent activity. Investments: total and growth, Add / Check PF /
Show closed, then the existing holdings. Nothing was removed: Edit, Leave out, Balance check settings and Check by SMS are in the
three-dot menu beside the name of the card or account in front. Pure logic (limit math, cycle days, category shares, segments,
deck dots) is `CardSpend` / `CardLimits` / `DeckModel` in `:domain`, tested in `CardSpendTest`.

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
| One payment, several texts | One rule again: `Counting.of(message)`. A payment often sends a bank debit, a gateway's confirmation (Razorpay, PayU, BillDesk, Cashfree), the merchant's "payment received" and an app's "paid". `SamePayment` (domain/ledger/SamePayment.kt) links them without deleting or hiding anything: the bank debit (a message with an account number) is the payment and counts; a linked text is `NotCounted` ("same payment as the HDFC debit"), stays in Messages marked with that, and drops out of every total, subscription, bill history and refund match because they all read `Counting.of` (bills history, subscription channel and refund matching also skip linked texts). Links are made only on evidence: same amount plus a shared reference number (UPI ref, txn id, RRN) within 30 minutes links by itself; same merchant or only same amount within 10 / 3 minutes, and two texts with no bank debit, are a "Same payment?" question in Needs review (both stay counted until answered). Matching is one to one (a debit takes one text per sender; two equal debits keep their own texts) and a shared reference beats nearness in time. The person's answer (here, on the message, or remembered for a sender) always wins and is kept in the `same_payments` prefs file, which backups carry. Bank balances and the five-identical-alerts case are untouched. **One payment, one row**: the messages `SamePayment` is certain about are the debit's *followers*, and `PaymentGroups` (domain/ledger/PaymentGroups.kt) shows the debit as one row ("+2 related" in its caption, the time of the debit, unread if any member is unread) in Messages (All, Money, Bills, search, the sender thread, Home's recent activity, an account's Activity) while its detail sheet lists them under "Related messages" (sender, time, a two-line preview, tap to open, "Not the same payment" to unlink, undoable). Nothing is removed from the data; a "Same payment?" question stays two rows until answered; a follower the person archived or pinned stays a row of its own; a group holds at most 6 followers (the nearest in time, the rest stay rows). Two new kinds of evidence: a merchant's *booking or order confirmation* ("Booking confirmed", "Order placed": IRCTC, an airline, a bus, a hotel, a shop) follows a debit of the same amount within 15 minutes when it also names the same merchant or shares a reference; with no amount it needs a shared reference, or the debit's payee naming the same merchant within 10 minutes; a confirmation that fits two debits about equally is not linked; and *two bank messages about one transaction* (an account alert and a UPI or card alert) only with a shared reference number within 30 minutes (links by itself) or, without one, the same account and amount within 2 minutes (a "Same payment?" question), with clearly different wording templates, never a debit with its credit, never two different reference numbers; and **the same transaction reference is positive evidence even for identical bodies**: two bank messages of one direction with the same account (last four), amount and 9 to 16 digit reference ("Ref 6...", "UPI Ref No ...", RRN, UTR) within 48 hours are one transaction, the one stating the balance (else the SMS row, else the earlier) is the payment, the other is a certain repeat shown inside its row as "+1 copy" and counted nowhere (identical alerts with no reference, or with different references, are separate payments and all count; five identical alerts are five payments, and a pair of them is only the Needs review "Duplicates" question, answered per pair). The same text delivered twice before reading (a chat-notification or picture-message copy of an SMS with the same sender and text within 10 minutes, or two provider rows with an identical millisecond date and body) is folded into the SMS (`InboxCopies`); the one stating the balance (else the earlier) is the payment and the other stops counting, and `BalanceReader.movement` skips it, so balances neither double count nor drop the debit. Exports keep every message and add a `same_payment` column ("main, 2 related", "repeat of the HDFC debit") |
| Refunds | One rule: `RefundTracker.build` (domain/insights/Refunds.kt). A refund goes INITIATED, then PROCESSED, then ARRIVED. The merchant's notices ("refund of Rs 176.00 for your Zepto order NUGTJCORO38008 has been initiated", then "Refund of Rs 176.00 has been processed ... Refund Ref. num. 667812523611") are NOT money movements (`Counting.of` is `NotCounted` for them) and they are ONE refund however they arrive: grouped by merchant, amount and the same order number (alphanumeric, 9 to 14 characters, after "order") or refund reference (9 to 16 digits after "Ref. num."); without ids, a different stage of the same merchant and amount within 3 days (or the same stage the same day) joins; different order numbers stay two refunds. The row keeps the earliest start day and the latest stage and opens the newest text; the detail sheet's Type says "Refund initiated" / "Refund processed" (`RefundTracker.typeLabel`). Arrival: a bank credit of exactly the same amount (to the paisa), from the start day to 10 days after (after the processed day when later), used once, oldest refund first, that names the merchant or its company (Zepto, Kiranakart, Swiggy, Bundl ...) or says refund / reversal; and, once PROCESSED only, an otherwise unexplained credit of exactly that amount (under Rs 20,000, no salary / interest wording) when it is the only candidate or there are as many equal refunds as credits. The match only LINKS the credit to the refund; the credit still counts as `Counting` says (income for a bank credit, a card refund for a card), it is not taken off spending. The person can mark a refund received (Bills, with Undo; `received_refunds` prefs, in `PrefsBackup`, in memory in Demo) when no credit can be matched; a found credit wins. Rows: "Initiated 8 Nov · expected in 5–7 days", "Processed 8 Nov · should reach you soon", "Arrived 12 Nov" (kept 7 days) |
| Sender name vs raw header | One rule: `SenderDisplay` (domain/conversation) decides the name and the verified check everywhere a sender is shown (Messages rows, search, conversations, detail sheet, Home code card, attention strip, Review queue). `SenderKey` still decides what two headers are "the same sender" for muting, nicknames and threads; the two share the header shape on purpose |
| Payee vs sender rules | Already one chain, not two stores. A rule for a person or shop (the payee a payment names) and a rule for a sender are the same stores (`category_overrides`, `nicknames`, `custom_categories`, the image store) under different keys: `CategoryKey.of` is the payee for a transaction and the sender only when a message names nobody. A parallel `payee_rules` store was considered and rejected: it would have made two rules for one payee. The detail sheet used to label these rows after the bank ("Same as HDFC Bank") when the payee had no logo; it now names the payee, shows where each value comes from, and asks whether a choice is for all payments or only this one. Settings > Your rules lists them as Payee rules (with search) and Senders |
| Mute / hide / leave out of totals | Keep. Muted sender (hide a sender's messages), hidden account (leave out of totals) and set-aside (leave a payment out) act on different objects |
| Code alerts vs Share codes with trusted people | Keep. Code alerts (Copy-code and Pay buttons) tell the person on this phone; Share codes sends the code on by SMS to other people. They share one receiver (`SmsReceiver`, `LiveAlerts.refreshReceiver` keeps it on if either switch is on) and one classifier pass, but have separate switches, stores (`settings` vs `code_sharing`) and health checks (`CodeAlertHealth` vs `CodeSharingHealth`), and neither needs the other |
| Check balance by SMS vs Set balance / Fix balance | Keep. "Set balance" is the person typing a figure; "Check balance" texts the bank and the reply is read by the same `BalanceReader`, so both land in one balance (the newer reading wins). One sender (`CodeSharer.sendPlain`), one status receiver (`CodeShareStatusReceiver`) and one backup rule shared with Share codes; separate stores (`balance_check`, `balance_check_device` vs `code_sharing`) |
| Alerts toggles | Not audited toggle by toggle; Settings > Reminders and alerts is the one entry point and none was merged |
| GlobalSearch vs feed search | Keep. Feed search filters the inbox list; GlobalSearch is the app-wide page |

### Flight card and the trip entries

Trip messages already show in the agenda and the Journey scene (`LifeAgenda`, `JourneyUi`), read by `LifeParser`. The flight card
reads the same texts with `FlightParser` because it needs far more (flight number, route, times, terminal, gate) than
`LifeParser` keeps, and it shows only on the day of travel. `LifeParser` is unchanged, so classification and the agenda behave
as before; the two readers do not disagree on what a trip is (the flight card needs a known airline code, a flight number and a
flight word).

## Follow-ups not done

- Calendar events and Reminders could become one "Reminder with an optional alert" with a migration of the `calendar_events`
  prefs. It touches alarms and notifications, so it needs on-device testing and was left out of this pass.
