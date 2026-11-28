# Design system

How Zyke Message looks and moves, and what to check before adding a screen. The code lives in
`presentation/theme/Tokens.kt`, `presentation/motion/` and `presentation/common/`; `MotionPolicyTest` keeps the motion
rules from eroding. Phase A (this foundation) applied it to the shared components and to Home; the other screens follow
in waves (see the end of this page).

## Principles

- **Calm.** Few things on a card, one hero per screen, quiet secondary cards. Nothing moves unless the movement says
  something.
- **Fast.** Short durations, no waiting for an animation to finish before a tap works, no loops.
- **Honest.** Amounts are never animated while hidden, a placeholder never pretends to be data, an empty screen says why it
  is empty, and a counting number always ends on the exact figure.

## Motion

Movement explains a change: something entered or left, a value changed, a control was touched. It is never decoration.

1. **One-shot only.** No `rememberInfiniteTransition`, `infiniteRepeatable`, shimmer or pulse. (The one inherited loop, `movingBorder`, is no longer used on Home; it remains in the shared card and the Messages attention strip, is a faint static outline when reduced and is allow-listed in
   `LowEndPolicyTest`. It is not to be copied.)
2. **One switch.** Every spec comes from `rememberMotion()` (`motion/Motion.kt`). It returns instant specs and no-op
   transitions when the person turned system animations off or lite mode is on. A raw `tween(`, `spring(` or `snap(` in a
   shared component is a test failure.
3. **Tokens, not numbers.**

| Token | Value | Use |
|---|---|---|
| `INSTANT_MS` | 90 | a touch acknowledged; the old content fading out under a crossfade |
| `QUICK_MS` | 160 | a switch, a press release, an item leaving |
| `STANDARD_MS` | 240 | an item entering, a card opening, a page or sheet moving, an icon filling |
| `EMPHASIZED_MS` | 360 | a bar growing to its value |
| `TAB_MS` | 200 | switching tab (shared axis, 24 dp at most) |
| `COUNT_MS` | 400 | a money figure counting |
| `Standard` / `Accelerate` / `Emphasized` | cubic beziers | arriving / leaving / the biggest arrivals |
| `snappy()` / `gentle()` | springs, no bounce | touch feedback / things finding their place |

4. **Read animated values where they are cheap.** Inside `graphicsLayer {}` or `drawBehind {}` (a frame repaints, nothing
   recomposes), or through a `derivedStateOf` that quantises (a counting figure is rebuilt 16 times, not every frame).

### The pieces (`presentation/motion/`)

| Piece | What it does |
|---|---|
| `pressFeedback(source)` | a tappable card dips to 0.98 and 0.92 opacity while held; ripple and semantics untouched; no recomposition; nothing when reduced |
| `rememberEnterGate()` + `enterOnce(gate, index)` | the first eight items of a screen fade in and rise 12 dp, 40 ms apart, **once** (never on scroll, recomposition, rotation or returning to the tab). Create the gate above any loading state |
| `AnimatedAmount(value)` | a rupee figure that counts to its new value in 400 ms; `fromZero` for a hero's first showing; static while amounts are hidden or motion is reduced; with `fit = true` it is sized for its final figure so it does not breathe; always a `PrivateAmount` |
| `CrossfadeContent(state)` | swaps loading, content and error with a short crossfade and a size transition |
| `AppearVisibility(visible)` | a card or row opens (fade + grow) and closes |
| `AnimatedColumnItems(items, itemKey)` | a column whose rows open when added and fade closed when removed (the attention strip: dismiss, mark paid, Undo) |
| `LazyItemScope.itemMotion()` | `animateItem` with tokens, for keyed lazy-list items that come and go |
| `ProgressReveal(fraction)` / `rememberProgressReveal` | a bar grows from empty to its value once |
| `SkeletonBlock`, `fadeInOnce()` | still placeholders that fade in once |
| `rememberHaptics()` | `tick()` for a switch, choice or confirmed save; `reject()` / `longPress()` for the firmer buzz. Respects Settings, Appearance, Haptic feedback. Compose 1.7 has only two feedback types, so there are only two strengths |

Screen transitions (all through the tokens, instant when reduced): a tab slides 24 dp on the shared horizontal axis while
it fades (200 ms); a page opens by sliding a third of the width while fading in and closes the same way back; a sheet slides
up with a scrim that fades in step with it.

### What is not animated, and why

- Amounts while hidden: the animation would hint at the figure.
- Anything on scroll: lists never re-play their entrance; row content never animates in view.
- The long lists (Messages, Insights tables): staggering past the eighth item only makes people wait.
- Text size, colours of text, and the layout itself: only opacity, a short translation and a scale change; never size of text.
- Error states and permission gates: they appear at once (a crossfade only).
- Looping or "attention" effects: none. Urgency is said in words and marked with a dot, not with motion.

## Tokens (`theme/Tokens.kt`)

| Spacing | dp | Use |
|---|---|---|
| `xs` | 4 | between a title and its caption; inside a segmented control |
| `sm` | 8 | between related controls (buttons in a row, choice cards) |
| `md` | 12 | **between cards**; vertical padding of a row |
| `lg` | 16 | **screen gutters**; sheet footer |
| `xl` | 20 | inner padding of a card and a sheet |
| `xxl` | 24 | bottom of a page |
| `xxxl` | 32 | between large groups |

| Radius | dp | Use |
|---|---|---|
| `chip` | 12 | chips, tags, the pieces of a segmented control |
| `control` | 16 | choice cards, snackbars, fields |
| `card` | 24 | the ordinary card |
| `hero` | 28 | the one hero card of a screen |
| `sheet` | 28 | top corners of a sheet |

| Tonal level | Look | Use |
|---|---|---|
| `Quiet` | 5 % tint of the text colour (9 % on true black, which also gets a 1 dp edge) | most cards |
| `Raised` | 9 % tint | a card on a card, a selected row |
| `Hero` | 12 % tint of the accent | the one card a screen is about |

The app is flat: no shadows, blur or render effects (`LowEndPolicyTest`). Depth comes from the tonal level.

| Icon size | dp | Use |
|---|---|---|
| `sm` | 16 | beside a caption |
| `md` | 20 | beside body text |
| `lg` | 24 | the standard icon |
| `tile` | 28 | a shortcut tile |
| `feature` / `disc` | 32 / 64 | an empty state |

| Type role (`TypeRole`) | Style | Use |
|---|---|---|
| `hero` | displayMedium, bold | the one big figure of a card |
| `headline` | headlineMedium, bold | the headline of a welcome or permission page (`OnboardingHero`) |
| `title` | titleMedium | a card or section title |
| `section` | labelMedium, medium, 0.8 sp tracking | the spaced label above a card (`CardHeader`) |
| `body` | bodyLarge | rows and running text |
| `caption` | bodySmall | the quiet line under a title |

One font family, Plus Jakarta Sans, from the theme; a screen never names a font.

## Component catalogue

| Need | Use |
|---|---|
| A card | `FintechCard(level = Tonal.Quiet / Hero, onClick = ...)`; a clickable card gets press feedback |
| A card's header | `CardHeader("Today's budget", trailing = { ... })` |
| One of a few things | `ChoiceCards`, `SegmentedChoice`, `PillSwitch` |
| Side-by-side pairs that must stack on a narrow phone | `ResponsiveRow` |
| A big figure on one line | `AnimatedAmount(fit = true)` or `PrivateAmount(fit = true)`, never a bare `Text` |
| A row with actions | `PlanRow` (actions wrap as equal-width buttons, never squeezed) |
| An empty screen | `EmptyState(icon, title, body, action)` |
| An error | `ErrorState(title, hint, onRetry)` (same tile as the empty state) |
| Loading with no skeleton of its own | `LoadingState()` inside a `CrossfadeContent` |
| A long explanation | `LearnMore(text)` under a one-line summary, never a caption of three lines or more |
| Something that cannot be undone | `ConfirmDialog(title, body naming what is lost, confirmLabel)`: equal-width Cancel and Confirm, `haptics.reject()` on confirm |
| A welcome or permission page | `OnboardingHero(icon, headline, promise, primary, secondary)`; `PermissionGate` for the SMS ask |
| A row that opens a page | `SettingsRow(icon, title, summary)` inside a `FintechCard`; a switch is `ToggleRow(title, summary, learnMore)` |
| Entering data | `FormSheet` / `AdaptiveSheet` (a page when tall); a bare `AppBottomSheet` only for compact pickers |
| A page | `AppPage` |
| A message to the person | `LocalAppSnackbar` (amounts masked while hidden), with Undo for anything reversible |

## Before you add a screen

1. Which card is the **one hero**? Give it `Tonal.Hero`; every other card stays `Quiet`.
2. Gaps are `Spacing.md` between cards and `Spacing.lg` at the sides; no one-off dp values for spacing or corners.
3. Every card starts with a `CardHeader`. Row titles are `RowTitle`, captions `Caption`.
4. Amounts go through `PrivateAmount` / `AnimatedAmount` (and `Text` from `presentation.common`, never Material's).
5. Lists have a `key` on every `items(...)`; items that come and go use `itemMotion()`; the first eight enter with
   `enterOnce(gate, index)` where `gate` is created above the loading state.
6. Loading is a still skeleton, then a `CrossfadeContent` swap; empty is an `EmptyState` that says why; errors say what to do.
7. Bars use `ProgressReveal`; counting figures use `AnimatedAmount`; nothing else animates unless it explains a change.
8. Ask `rememberMotion()` for every spec. Run `MotionPolicyTest`.
9. Check at 320 dp wide, font 1.3, dark, light and true black, and with Lite mode (docs/LOWEND.md).
10. Touch targets 48 dp; every icon labelled or decorative (`AccessibilityPolicyTest`).
11. Add the screen's tour anchor if the tour should point at it (`TourPolicyTest`).

## Messages (wave B1)

The Messages tab is the longest list in the app, so it is the calmest: one row anatomy, motion only where it explains a change.

**The row** (`MessageRow`, every card type): gutters `Spacing.lg`, vertical padding `Spacing.md`, a 48 dp tile (`RowTile`), a title and a
caption of one line each (an ellipsis, never a second line; large text may add one, as `scaledLines` does everywhere), and at the end
the amount (`RowAmount`: one line, shrinks to fit its half of the row) or a status. The unread dot sits on the tile's corner. The
"+N earlier" line under a group starts where the row's text starts (`RowTextInset`). Day headers are the section label role
(`TypeRole.section`, capitals, muted) on the page colour so rows pass under them while they stick. The skeleton uses the same gutters,
tile and line heights, so the list that replaces it does not move.

**What it was paid with.** A payment's caption starts with one small monochrome icon (14 dp, 1.5x less than the font scale, theme muted
colour via `ColorFilter.tint`, never a literal colour) directly before the masked number: `[icon] •• 8065 · 10:20 PM`
(`PaymentInstrumentCaption`: icon and text share one row, the text ellipsises, the icon never wraps alone). The icon is the funding
source: credit card, debit card (card with a chip), bank account (UPI, IMPS, NEFT and net banking all pay from one), wallet (UPI Lite
balance, prepaid wallets) or FASTag; an unknown instrument draws nothing. It is decoration: the row's spoken summary says "Paid ₹804 to
Amazon from credit card ending 8065". Used by the Messages row, Home's recent activity and the detail sheet's Card / Account row (whose
label then reads "Credit card", "Bank account"...). Not shown where the caption has no number (Accounts activity, Review, Search rows).

**What animates**

| Where | What | Token |
|---|---|---|
| a row held | dips to 0.98 and 0.92 opacity (`pressFeedback`; the one-time code keeps its long press) | snappy |
| first showing of the inbox | the first eight rows fade in and rise, 40 ms apart, once (gate created in `MessagesScreen`, above loading) | STANDARD_MS |
| a row archived, restored, or arriving | fades; the rows below close the gap (`itemMotion()`) | QUICK / STANDARD |
| loading, list, empty, senders | crossfade (`CrossfadeContent`); the permission gate and the error appear at once | STANDARD |
| the attention strip, the "hidden by filters" line | item motion; the strip's own rows use `AnimatedColumnItems` | STANDARD |
| the clean-up bar, the active-filter bar | open and close (`AppearVisibility`) | STANDARD |
| a group's earlier messages | open under the newest and close (`AppearVisibility`) | STANDARD |
| the type tabs | the line slides to the chosen tab; a light tick when the choice changes | TAB_MS (200) |
| Timeline / Senders | the pill and icon change colour; a light tick | QUICK_MS |
| the detail sheet | a "Pinned" or "Archived" line opens under the title | STANDARD |

**What does not animate:** anything on scroll; rows past the eighth on first load; amounts (they are `PrivateAmount`, static while
hidden); the swipe's own reveal (it follows the finger: direct manipulation, not an animation); the empty and error states.

**Swipe** (`SwipeActions`, `RowSwipe` in the domain, tested): right marks the row read (or unread again), left archives (or
restores). Seven tenths of the width must be crossed; a light tick marks the line and the colour behind the row firms up there;
letting go earlier does nothing. The icon and name come from the theme's container colours. An Undo snackbar follows (read, unread
and archive). Under reduced motion the row returns at once instead of springing back. TalkBack gets the same actions, plus Pin or
Unpin, as custom actions on the row. Pin has no swipe; it is in the detail sheet.

- *Anchor only:* on a payment row that holds its repeats ("+2 related") and on the newest row of a group of codes, archive and
  restore apply to that one message. Marking read covers the row as a whole (the anchor and its repeats, like opening it); unread
  takes back only the anchor's own mark.
- A message already read in the phone's own messaging app has no right swipe: that mark is not ours to take back.
- No swiping while searching (archived matches are listed too, so the direction would be ambiguous). Long-press-and-drag to merge
  payments is unchanged: the hold has to come first.

**Detail sheet:** sections are `CardHeader` + `FintechCard` with `Spacing.lg` between them; the related-messages card has pressed rows
of at least 48 dp; deleting a hand-made entry asks first and the confirming tap gives the firmer buzz (`haptics.reject()`).

**Empty:** the shared `EmptyState`: a friendly line, why, and one action when there is one ("Clear search", "Show everything").

## Refresh balances before my digest (Settings, Reminders and alerts)

One switch row under "Morning digest", off by default, same row shape (title, caption, switch at the end, 48 dp) but its caption is the
consent and may take up to eight lines, so it is not the two-line `ToggleRow`: "About 5 minutes before the digest, Zyke texts your
bank's balance number from your SIM, once a day at most, only for banks you already confirmed under Check balance. These are real texts
and may cost money on some plans." The title wraps to two lines at 320 dp. With the digest off the switch is disabled and the caption says
to turn the digest on first. Turning it on asks for the permission to send texts (the Check balance one) and stays off if refused.
Under it, "LAST REFRESH · Thu 26 Nov" and one two-line entry per account: the name with its last four digits, then "Asked 8:55 AM ·
reply 8:56 AM", "Asked 8:55 AM · no reply", "Asked … · waiting for the reply", "Asked … · could not send" or "Skipped · already checked
today" (also shown as "Before the digest: …" in the bank's Balance check settings). The digest's expanded text ends with "Balances refreshed
for 1 of 2 accounts" when texts went out for it. Mock checked at 360 and 320 dp, light and dark, before building.

## Accounts (Overview, Accounts, Cards, Investments)

The Accounts tab has four sub-tabs, each with one hero: the **Overview** (the total balance, alone), the **bank total** with its
deck of accounts, the **deck of cards**, and the **investments total**. Overview is the first and the one Accounts opens on. The heroes are header regions (`HeaderRegion`, a dark region in both themes with `Radius.hero`
bottom corners); everything under them is `FintechCard` on `Tonal.Quiet` with `CardHeader` labels. `AccountsPolicyTest` keeps
this page true.

- **Overview** (`OverviewTab.kt`). The hero is the total balance (the same figure as Home), alone in its header region. Under it,
  "Credit card dues": **Billed** (what is still owed on the latest statements, net of payments) and **Unbilled** (spent since each
  card's statement date) side by side as equal tiles (`ResponsiveRow`: stacked on a narrow phone or at large text), then **Total
  card dues** (billed plus unbilled) full width. A tile opens the per-card sheet (soonest bill first). A card whose statement day is
  not known counts its month's spending as unbilled and the tiles say "Approx.". Then one "At a glance" card ("Left after card
  dues" = balance minus billed minus unbilled; "Next bill due" = card, date, amount); that is the end of the page: the tab bar is the only way to the other sub-tabs (there are no link chips under the
  content). Without
  a card the dues are one `EmptyState`. The rules are `CardDues` / `AccountsOverviewModel` in the domain, tested; the figures are
  `AnimatedAmount`, count once (`heroGates[0]`), and the cards rise in turn once (`overviewGate`).
- **Net worth is not on Overview.** It is revealed only by **pulling down at the top of Overview** (`NetWorthPull`; no Material
  pull-to-refresh). The list is drawn down with rubber-band resistance (`PullReveal.stretch`, threshold 96 dp of stretch, a light tick when
  it arms, hysteresis so it does not flicker), a quiet "Pull down to see net worth" / "Release to see net worth" line is uncovered above
  it, and letting go while armed opens the panel (`AppearVisibility`, Motion tokens) above the hero with the figure, a breakdown that
  adds up to it (`NetWorthBreakdown`) and "Open net worth" (the only way to the Net worth page). It closes with **Hide**, on leaving
  the tab, when the header scrolls away, when amounts are hidden again and when the app leaves the screen; it is never saved across
  rotation. With amounts hidden every way in asks for the screen lock first (`requestNetWorth` -> `AmountReveal.requestReveal`). The
  first time there is a "Pull down to see net worth" line under the header, gone after the first reveal (stored as the attention
  dismissal `tip-networth-pull`). A pull cannot be done with TalkBack and the stretch is not wanted with reduced motion or Lite: the
  header has a "Show net worth" custom action, and those two get a plain "Show net worth" button; it passes the same lock check.
- **Figures.** The bank total, the investments total, the balance on a deck tile, a holding's value and an investment's page
  figure are `AnimatedAmount`. A sub-tab's hero counts up from zero **once**, the first time that sub-tab is shown
  (`heroGates` in `AccountsPage`, one `EnterGate` per sub-tab, spent by `heroEntering()`); a later visit is still, and a new
  balance (a check by text, a message) counts to its figure. Hidden amounts never animate.
- **The deck** (`StackedDeck`). Drag the front card; past 30 percent of the width or a 1,200 px/s flick in the drag's
  direction it flies off (240 ms, `Motion.Emphasized`) and a light haptic tick fires; otherwise it springs back (`gentle()`),
  silently. The rule is `DeckModel.commits` in the domain and is tested. The page dots widen and brighten in 160 ms. The name
  and the "Credit card - 5580" line under the deck crossfade with the front card; the overflow menu stays put.
- **Sub-tab switch.** The header is one lazy item whose content is a `CrossfadeContent` keyed by the sub-tab (its height eases);
  the body slides 24 dp along the shared axis while it fades in (`Motion.TAB_MS`). The underline indicator is Material's own
  slide. While the first read is going the header and body are still skeleton blocks (`HeaderSkeleton`, `BodySkeleton`),
  then they crossfade to the real thing.
- **Spent this month** (`SpendCard`). `CardHeader` label, the limit bar is a `ProgressReveal`, the segmented category bar is
  revealed left to right once (`revealOnce()`), and the category rows rise in turn (`enterOnce`, first eight).
- **Touch.** Every action circle dips while held and gives a light tick on a tap (a disabled one does neither); chips, tiles
  and rows that are not cards use `Modifier.tappable` (`common/TouchAndReveal.kt`); cards use `FintechCard(onClick)`; a card
  face dips too.
- **Empty and loading.** "No cards yet - they appear when your bank texts you about a card" with an "Add a card" action, "No
  accounts yet", and Investments' "No investments yet" are the shared `EmptyState`. Investments' three first-add tiles sit under it.
- **Investments.** Holdings, closed rows, the allocation card and the matured cards use `itemMotion()` (an add, a delete with
  Undo, a filter). A gain is green, a loss red, flat neutral, from `AppColors` and always with a sign; the allocation bar is
  revealed once and its chips dip and tick.
- **Balance check.** The "Check balance" button, the progress with "Checking..." and "Updated just now" swap in one slot
  (`CrossfadeContent`, never shorter than 48 dp), and in an action circle the icon and the working ring swap inside the fixed circle.
- **Sheets.** Edit account, Billing cycle, Monthly limit, Card details and the balance-check sheets use `Spacing` for their
  rhythm and `ResponsiveRow` for button pairs; a confirmed save gives the light tick, and removing a limit, leaving an account
  out, removing an account and deleting an investment give the firmer buzz (`haptics.reject()`).
- **Not animated.** The provident fund tiles' stat rows, the contents of sheets, and anything on scroll.

## Insights (the dashboard tab)

Wave B3 applied the system to `presentation/dashboard/**` and the year in review. The tab's own pieces live in
`dashboard/components/InsightKit.kt`; the arithmetic is in `domain/chart/` (`ChartMath`, `ChartSpeech`, tested).

| Piece | What it does |
|---|---|
| `InsightCard(title, summary, spoken, level)` | the Insights card: `FintechCard` + `CardHeader`. On the page it is a tile (header, chevron, one-line summary) that opens its detail in a sheet; `startOpen` shows the content. The settings `SectionCard` is not used on this tab |
| `Chevron()` | the one chevron, at the end of anything that opens |
| `ChartBox(aspect, min, max)` | a chart's height follows the width it is given (`ChartMath.chartHeightDp`, a little taller at large text), never a fixed dp |
| `rememberScrubState()` / `Modifier.chartScrub(...)` / `ScrubTooltip` / `ScrubReadout` (`common/ChartScrub.kt`) | the one scrub for every time-series chart (see Scrubbing below) |
| `LegendChips` | the colour key: equal chips (swatch + name) in a `FlowRow`, no scrolling row |
| `SlidingSegments` | the Spent / Received and the Overview / By day / Categories switch: the indicator slides (spring from the tokens, drawn while painting) and ticks once; 48 dp segments, capped at font scale 1.3 (`ChromeScale`) |
| `InsightsSkeleton` | the still skeleton shown while the numbers are worked out (one "Loading insights" for a screen reader) |
| `CrossfadeKeyed` (motion) | `CrossfadeContent` that fades only when a key changes (the Spent / Received side, the year); the old content keeps showing the state it was drawn for |

Rules of the tab:

- **One hero.** This period's money out and in (`IncomeExpenseCard`) is `Tonal.Hero`; every other card is `Quiet`. Gaps between
  cards are `Spacing.md`; inside a card the vertical gaps are `Spacing.sm` to `Spacing.md`, never below 8 dp.
- **Entrance.** The first eight cards use `cardMotion(gate, index)` (= `itemMotion()` + `enterOnce`); the gate lives above the
  loading state, so the entrance plays once. The hero's totals and the month summary's figure count up from zero on that first
  showing only (`AnimatedAmount(fromZero = ...)`, decided by `rememberIsEntering`).
- **Charts grow once.** Bars (`TrendChart`), lines (`IncomeExpenseCard`, `ForecastChart`, `ByDay`, `LineTrend`) and meters
  (`ProgressReveal`) approach their values from zero when first shown; the progress is read inside `Canvas` / `drawBehind`
  lambdas, so nothing recomposes per frame. Touch a chart anywhere and slide to read it (see Scrubbing).
- **Scrubbing (Zerodha-style).** Every time-series chart (spending trend, income vs spent, forecast, running total by day, the
  statement and cycle lines, the year in review's months) and the stock sheet's price chart read the same way. A finger down
  anywhere selects the nearest point at once: a crosshair line and a dot on the series (theme tokens: `onSurfaceVariant` at 40 %,
  the dot ringed in the surface colour), a small flat tonal tooltip (value in title-small, date as a caption, never wider than the
  chart, above the dot or below it when there is no room) and the readout above the chart (value, date, and the change from the
  point before with a triangle and a percentage; on the stock sheet the change from the previous close or the start of the period).
  Sliding moves the point, snapped to the nearest, with the light tick when it changes (60 ms apart at most). Vertical drags go
  to the page: the gesture waits for the touch slop and a mostly vertical drag is handed to the scroll (the selection clears); a
  sideways drag consumes. A second finger is left to pinch-to-zoom (the income and spent chart). The index lives in a `ScrubState`
  read only while drawing, while laying out the tooltip and inside `ScrubReadout`, so the chart body never recomposes.
  When the finger lifts the point stays for 1.5 s (`ChartMath.SCRUB_HOLD_MS`) so the figure can be read, fades in 240 ms and the
  readout returns to the latest value. Chosen over "stay until the next touch" because a lingering historical figure in the header
  reads as the current one. Instant under reduced motion. A point picked with TalkBack's "Previous / Next <point>" actions does stay
  until the next touch, and is spoken (`stateDescription`, masked while amounts are hidden). Hidden amounts: tooltips and readouts
  use the money-aware `Text`, so they show dots and still move; the stock sheet shows public prices. Flat in every mode: no shadow.
  `MotionPolicyTest.everyTimeSeriesChartScrubsThroughTheOneSharedModifier` keeps the charts on the shared modifier. Paired bars
  (this month and last month on a category row) are not scrubbed: both amounts are always printed, and the row is a tap target.
- **Percentages add up.** Lists of shares use `ChartMath.percentages` (largest remainder), so a legend never says 99 % or 101 %.
- **Every chart is spoken.** A chart carries a `contentDescription`, built with `ChartSpeech` ("Spending by category: Food
  ₹8,200, 31%; ...") and passed through `privateSpeech`, so hidden amounts are dots for a screen reader too. Touch charts also
  offer "Next" / "Previous" actions.
- **Hide amounts.** Figures are `AnimatedAmount` / `PrivateAmount` / the money-aware `Text`; chart text is drawn by those, never
  by hand on a canvas.
- **Period and side switches** fade through `CrossfadeContent` / `CrossfadeKeyed` (200 ms-class, with a size transition); the
  selection survives the swap. `MotionPolicyTest` scans every file under `dashboard/` (no loops, no raw `tween`/`spring`/`snap`,
  no raw `Crossfade`/`AnimatedContent`, no fixed chart height of 100 dp or more, a text alternative for every chart).
- **320 dp, large text.** Totals sit in a `FlowRow` (side by side, or one above the other), tabs wrap to two lines, legends wrap
  as whole chips, charts scale with width.

## Bills and Calendar (wave B4)

The Bills tab (`home/BillsPage.kt`, `BillsKit.kt`), the pages beside it (Goals, Reminders, Marked as paid) and the calendars
(`CalendarUi.kt`, the budget calendar in `BudgetViews.kt`). The arithmetic is in the domain and tested: `BillsMath` (due
captions and tones, ordering, this month's progress, part payments) and `CalendarPaging` (page direction, swipe threshold,
slide distance, day dots, the spoken day).

**The Bills tab.** One hero, `BillsHero` (`Tonal.Hero`): this month's money still to pay (counts up from zero on the first
showing), a `ProgressReveal` bar for how much of the month is paid, and "Paid so far" with "2 of 5 paid". "This month" means
due by the end of this month (overdue included); money already put towards a part-paid bill moves the bar without making the
bill "paid". Under it, every card is `BillsSection` (`FintechCard` + `CardHeader`) with `Spacing.md` between them; the first
cards enter once (`enterOnce`, gate from `HomeScreen`). With nothing due the hero gives way to the shared `EmptyState`
("No bills due — you're clear").

**One row anatomy** (`UpcomingRow`, refund, owed and repeating rows): `PlanRow` with a 48 dp logo tile (`MerchantChip`,
`RowTile`), the title, a caption, the amount (`RowAmount`, fitted, never wrapped), the status chip, and the actions on their
own line. The due caption says the distance in words and takes its colour from a theme token (`BillsMath.tone`: overdue is
danger, today and soon are warning, later is muted), so colour is never the only signal. Rows dip on press (`rowTap` =
`pressFeedback` + `clickable` with a role and click label).

| State | Chip | Caption |
|---|---|---|
| overdue | "Overdue" (danger tone) | "Overdue by 2 days" |
| due soon | none | "Due today" / "Due tomorrow" / "Due in 3 days" (warning) |
| later | none | "Due 25 Nov" (muted) |
| part paid | "Part paid", a bar for the share and "Part paid ₹250 · ₹750 left" | as above |
| paid | "Paid" with a check mark | "Bill · paid today" |

**Actions** (`ActionButtons`): equal widths always. Up to three sit side by side, more are two to a line, and a narrow phone or
large text (`ResponsiveRow`) stacks them one under the other; a last button alone keeps the others' width. The revealed
Open / Done / Snooze / Dismiss line uses the same buttons and opens with `AppearVisibility`.

**Mark as paid.** The tap gives a light tick and the row reads as paid at once: the chip crossfades (`CrossfadeKeyed`) to
"Paid" and its check mark fades and scales in once (`AnimatedVisibility` with the tokens). After one `STANDARD_MS` beat (none
when motion is reduced) the bill is recorded; the row leaves the Due list and a row opens in the Paid group
(`AnimatedColumnItems` in both), the hero's figure counts to its new value and its bar eases forward. Undo in the snackbar
reverses all of it with the same motion. The Paid group is a collapsible header (the chevron turns half a circle once,
`QUICK_MS`) whose open state is remembered; each of its rows can be restored.

**Groups** under "Repeats monthly" (`GroupHeaderRow`, `GroupMembers`): the header dips on press, the chevron turns once, the
members open through `AppearVisibility`; the open state is remembered per group.

**Calendar.** The arrows, a sideways drag on the grid and the Today button all page the same way and give one tick. The grid
is a `SlideSwap` keyed by month: it slides at most 24 dp (30 % of its width on a very small one) in the direction of time (next
month comes from the right) while fading, `TAB_MS`; the title crossfades; the day detail crossfades by day; entries of the day
open and close one by one. Instant under reduced motion or Lite. A swipe must travel 18 % of the width (48 dp at least) and is a
horizontal drag only, so scrolling the page is unaffected. A leaving page keeps drawing its own month (entries are looked up
per month, not read from the current one).

**Day cells** (`DayCell`): at least 48 dp, dip on press, a fill that fades in and out when chosen (`QUICK_MS`, read while
drawing), a ring for today, at most three dots (`CalendarPaging.dots`) and a "+" for more. Each is one button with a spoken
description, "Tuesday 12 November, today, 2 bills due, 1 other item", and the selected state. The budget calendar's cells do the
same with "within budget, spent ₹…"; spend is shown as a mark under the date, not as a tonal ramp.

**Forms.** Goals, reminders and events open as `FormSheet`; deleting any of them asks first (`ConfirmDelete`) and the confirming
tap gives the firmer buzz (`haptics.reject()`), then the snackbar still offers Undo.

`MotionPolicyTest` scans these files (no loops, no raw specs, no raw swaps outside the motion package, one-off spacing numbers
banned) and checks the rules above.

## Home (the calm layout)

Home answers one question: "am I okay, and is there one thing I should do?" It was fifteen blocks; it is now six, with one hero and one
adaptive card. The pure rules are in `:domain` (`home/HomeFocus.kt`, `home/HomeLayout.kt`, tested in `HomeFocusTest`); `HomeCalmPolicyTest`
keeps the shape from eroding.

| Block (keyed item) | What it is |
|---|---|
| Header | logo, search, the eye, settings (unchanged) |
| `balance` | **The one hero** (`Tonal.Hero`, `TypeRole.hero`, counts up once): total balance and one quiet line (how many accounts). Tap opens Accounts. Nothing else inside it |
| `focus` (TODAY) | **One** card, chosen by `HomeFocus.pick`: a trip today (the flight or train card as it is, it is time-critical), else a fresh code with Copy (every fresh code is in its pager; it leaves when it expires), else a bill due today or overdue (Pay bill, Mark as paid with Undo), else the next bill due within 3 days, else "You're all clear" (or the one pacing nudge, in its old words). Several things competing show the top one and a quiet "and N more" link that opens the deck (or Bills when the deck is empty). Cards never stack |
| `today` | Today's budget as one slim line: "₹3,800 left today", a 4 dp bar, "Adjust". Tap opens the streak calendar. Optional (on) |
| `deck` | "YOUR DECK", "N to go", "Swipe all", at most four tiles, "All stacks" when there are more, one quiet line "Quiet 3 · Auto cleared 5" (each opens its page), and one quiet row "N things to review" (only when there are some; it stays even if the deck is switched off, the review page opens from nowhere else). Optional (on) |
| `activity` | the last three payments (the row anatomy of Messages) and "See all" to Messages. Optional (on) |
| `footer` | "Customize Home" |

Rhythm and polish (`home/HomePolish.kt`, Home-only; `FintechCard` and `CardHeader` are untouched for the other tabs): 20 dp gutters, 16 dp between
blocks (24 before the deck and Recent activity), 20 dp inside a card, 8 dp inside a group, tokens only. Home's cards (`HomeCard`) are a step of
the theme's surface containers (`surfaceContainer`), not a tint of the text colour, so dark mode gets its depth from tone and not from a border
(true black keeps FintechCard's hairline). One hero (`HomeBalanceHero`): a soft diagonal wash of the accent, the figure large in the text colour
(`displayMedium`, tabular figures, counts up once), a "TOTAL BALANCE" label with a count pill and a chevron, and "Updated today" (the newest
`balanceAsOf`). Every block label is the one small-capitals style (`HomeLabel`); statuses and counts are one pill (`HomePill`). The accent is for the
one primary action, links and the hero wash; money in is the success colour, money out and titles are the text colour, a bill's status takes the
warning or danger colour. Today's bill card: label, status pill, tile, name and amount, one filled primary ("Pay bill"; "Mark as paid" steps down to a
text button, or is the primary when there is no link). Today's budget is a slim card with a figure, "left of Rs 5,000" and an 8 dp rounded bar
(`HomeProgress`). Deck tiles are flat (the paper edge is gone), 16 dp inside, name in `titleMedium`. Recent activity is one grouped card, 40 dp
tiles, hairlines inset to the text. At 320 dp or font scale 1.5 and up (`homeStacked()`) the count pill, the amount, "Adjust" and "Swipe all" move
under their text. No borders or shadows added. Headings use the type roles. The first eight blocks enter once, in turn (`enterOnce`); tiles and
rows dip while held. Hidden amounts stay hidden (the figures are `AnimatedAmount` or `RowAmount`). Demo mode shows the same layout with made-up
content. The toolbar's logo is 26 dp (shared by every tab).

**Quick actions** (`home/HomeActions.kt`, rules in `:domain` `home/QuickActions.kt`, `QuickActionsTest`). The old brown "+ Entry" pill sat on top of Recent activity and covered amounts; it is replaced by a stack at the bottom right, 16 dp from the edge and above the bottom bar (Home sits inside the shell's Scaffold, so the bar's inset is already kept):
- **Add entry**: a 56 dp rounded square (`AppShapes.control`), `primaryContainer` / `onPrimaryContainer` from the theme, flat (zero elevation, no shadow: LowEndPolicyTest), a plus (`ic_tile_add`) and, only while the list is at the top, the words "Add entry" (the label opens and closes through `rememberMotion()`, instant under reduced motion or Lite). Spoken label "Add entry". It opens the same entry sheet as before; the sheet is unchanged. It is the one `TourAnchor.HOME_ADD`.
- **Ask Zyke**: above it, 12 dp apart, a 48 dp round button in `secondaryContainer` with a sparkle (`ic_ask_ai_24`, the Material "auto awesome" glyph), centred over the plus, spoken as "Ask Zyke". Shown when the on-device AI master switch and "Ask your money" are on and the phone can do it (`rememberAskHomeVisible()`, the gate Search uses), and in Demo, where the page holds labelled, made-up examples; otherwise Home shows the plus alone and stays calm. It opens the Ask Zyke page (see Search, Ask Zyke).
- **Moving.** Both hide together when the list moves up the screen (a finger travel of 8 px or more, `QuickActions.shown`), come back on the way down and are always there at the top. The list reserves 88 dp under its last item (148 dp with Ask Zyke) so the stack never rests on an amount at the end. At 320 dp both fit the 16 dp gutter; the words of Add entry are one line, never wrapped.
Not verified on a device: the exact feel of the hide and return, and the stack over a 3-button navigation bar.

**Customize Home** (Settings, Appearance and feel, Home; also the footer): a switch for Today's budget row, Recent activity, Your deck,
Market strip, Goals and reminders, Shortcut tiles and the Quick adds prompt, and "Reset to calm layout". Defaults: budget, activity and
deck on, the rest off. Stored as `home_layout` (`HomeLayoutRepository`, `HomeLayoutRepositoryImpl`; in backups, in memory in the demo).
The Market strip needs Market watch on in Settings as well; its full list opens from Settings, Market watch, "Open market watch".
Existing users get the calm layout on update and can turn the old blocks back on.

## Market watch

Two pieces, both public quotes (Hide amounts does not apply to them).

**The ticker on Home** (the Market strip, off by default, and only with Market watch on in Settings): Home's very first item, above the
balance hero and the tour banner. One slim 40 dp line, `NAME price ▲ move (pct)` items scrolling past; the arrow carries the direction and
only the move is green or red (theme tokens), figures tabular. A tap opens the Market watch page. No chevron, no expand or collapse, no
rows on Home. The scroll is the system marquee, drawn once, behind `rememberReduceMotion()` (`if (still)`): under reduced motion or Lite
it is static and shows the first items that fit whole (`WholeItemsRow`), so nothing is ever cut mid-name at 320 dp. Loading and offline
are one quiet line. It carries no tour anchor.

**The page** looks and works like Zerodha Kite's watchlist. At the top a fixed two-index line (NIFTY 50, SENSEX: name muted, level, then
"+128.40 (+0.53%)" in green or red; stacked at 320 dp and large text); a tap opens that index's sheet, there is no accordion. Under it the
"Search & add" bar with how many names are on the list out of the real limit (`12/30`, `MarketSymbols.MAX_WATCH`); it opens the editor.
Then the status line with Refresh, and dense 56 dp rows with a hairline between each: name left with its exchange beneath in muted grey;
price right in green or red with "+2.70 (+1.04%)" in muted grey beneath, tabular figures. A tap opens the stock sheet; a long press (or
the custom action "Edit the watch list" for a screen reader) opens the editor. Loading is static placeholder rows; offline with no prices
is a message with "Try again"; with prices it is "Offline · updated ...", plus the delayed-quote notice (up to 15 minutes). Green and red
are only ever text on prices and changes, never a fill. Guarded by `MarketWatchPolicyTest`.

**Group by market.** When the list spans more than one market (India, United States and the others in `Market`: UK, Germany, Japan,
Hong Kong, Canada, Australia, Singapore; the dollar rate, gold and crypto go last under "Currencies and commodities"), a quiet
"Group by market" chip sits above the rows, on by default and remembered (`WatchSettings.groupByMarket`, null until chosen; hidden
for a single market). The market comes from the ticker (`.NS`/`.BO` India, `.L` UK, `^GSPC` US, a bare ticker with no exchange US,
`MarketOf`). Each group has a tonal header, one heading for a screen reader: flag emoji (two regional-indicator letters in plain
`Text`; the repo has no emoji ban, custom categories already use emoji; a phone without a flag font shows the two letters), the
country name, a regular-hours hint ("Open · closes 4:00 PM ET", "Closed · opens Mon 9:15 AM IST") and the market's index with
today's move when that index is on the list (beside the name, beneath it at 320 dp and large text). The hint comes from
`MarketCalendar`: regular hours Monday to Friday in the market's own zone (summer time followed); holidays, half days, lunch
breaks and extended hours are not modelled, so on a holiday it can say Open. Rows, tap, long press, loading and offline are unchanged.

**Market close summary** (Settings, Market watch). One switch per market (India after 15:30 IST, United States after 16:00 ET)
and one offset (5, 15, 30 or 60 minutes after the close; 15 by default, so India stays at 15:45). Each posts one silent,
low-importance notification (channel "Market summary", `zyke_icon`, its own id per market, `BigTextStyle`): the market's indices
(Nifty 50 and Sensex; S&P 500, NASDAQ and Dow) and "Your stocks", the day's move of each watched stock listed on that market,
biggest mover first (up or down), at most 8 then "+N more", then in the stock's own currency. Tapping opens Market watch. Weekdays
only; a quote counts only if its last trade is today and at or after five minutes before the close, so a holiday (not modelled)
sends nothing after one retry 20 minutes later. With no stocks listed for a market it is the indices only. The old single "Market
close" switch is India's. Inexact alarms, no exact-alarm permission. Guarded by `MarketWatchPolicyTest` and `MarketGroupingTest`.

## Home deck (stacks of cards)

**Home shows one real stack, in one card (rework).** The whole module is ONE `HomeCard` with three segments told apart by hairline dividers (edge to edge,
20 dp gutters inside each segment, opaque cards, no chips outside it):

- **Header.** "YOUR DECK", "N to go" (or "N to review" when the review is the body), the position ("2 / 7") and the 48 dp settings gear.
- **Body.** The stack itself, clipped inside the card (`clipToBounds`): the front card with the next two peeking behind (the triage page's card in a compact
  form), swipe left archives, right keeps, up or down moves around; same hints, tick and Undo as the triage page; every gesture is a screen-reader action.
  The front card reads as a physical card on a tray: a tonal step up the container (`surfaceContainerHigh`), the `control` corner (16 dp, smaller than the container's 24), no outline (a hairline only on true black), and the two slabs behind are thin tonal edges with no outline. Never a bordered box inside a bordered box. Page dots and the quiet line "Swipe left to archive, right to keep" sit under it. With no new cards the review is the body (one flat row that opens it: a small seal, the eyebrow "Needs a look · From your payments", the title in the text colour, "Checked on this phone" and a chevron; the header already carries "N to review", so the body does not repeat the count);
  with nothing at all it is a compact "All caught up".
- **Footer.** Equal tappable segments split by hairlines (`DeckFooter`): "N to review" (accent, only while the stack is the body), "Quiet N", "Auto-cleared N"
  and "All stacks" (always there). Two to a row at most; one per row at 320 dp or large text (`homeStacked`); 48 dp targets; never wrapping chips.

The container rises in once as one piece (`enterOnce`, one slot). The review queue (ledger questions) and the deck (unread messages) are different sources.
`HOME_DECK` is on the container, `HOME_REVIEW` on whichever of the review card, the footer item or the review row is showing.

Home's "Your deck" turns what is new into a few small stacks you can clear with a swipe. The rules are in the domain and tested
(`domain/triage`: `TriageStacks`, `TriageSettings`, `TriageGestures`); the screens are `home/HomeDeck.kt` (the section and the Auto cleared page),
`home/TriageScreen.kt` (the card stack) and `home/TriageSettingsPage.kt` ("Make it yours"). `HomeDeckPolicyTest` keeps them true.

- **Where.** One keyed item on Home, under the Today card and the budget line (see "Home (the calm layout)"). A header ("YOUR DECK", a 48 dp gear that
  opens "Make it yours"), "N to go" (`TypeRole.title`, `rememberCountUpInt`: counts up from zero on Home's first showing only, static when reduced) with a
  text "Swipe all ->" button (stacked at large text or 320 dp), at most four tiles (two to a row), "All stacks" when there are more, then one quiet line
  for Quiet and Auto cleared. Nothing left to go: one caption, "All caught up". The old Read row is gone from Home (Messages has it).
- **Tiles.** Equal size within a row (`IntrinsicSize.Max`), at least 112 dp and growing with text; a stack's own tonal pairing from
  `theme/StackPalette.kt` (container + ink, light and dark, every pair 8:1 or better; the only literal colours for stacks, and only in the theme);
  the mode tag chip, "N new", the name in
  `titleMedium` (two lines at most; the paper edge is gone) and the newest headline on one line with an ellipsis. A tile dips while held (`tappable`) and is one button
  with one spoken sentence (amounts masked through `privateSpeech`). The header and each row of tiles rise in once, in turn (`enterOnce`, the first
  eight slots of Home's gate).
- **Triage page** (`AppPage`, not scrollable): Fresh . N / Quiet . N (`SegmentedTabs`), chips All . N and one per stack, the title with "3 of 8", the
  deck, and a control bar pinned in the page footer: `<- Sweep it`, `Got it ->`, up, down (48 dp+, `ChromeScale`). The two decisions are equal in width and the two arrows are equal 52 dp keys. Under the deck, the same page dots as the Accounts deck (`DeckDots`) show the position; the slabs behind carry a hairline so they read on white and on true black; the summary is full-strength ink, the headline `headlineSmall`, with a hairline above the footer. Both decks tilt 8 degrees and peek 10 dp. Cards are `TriageCard`s: logo
  (`MerchantChip`), name with the verified check (`SenderTitle`), time, a category chip that opens the existing `CategorySheet` (instant, through
  `MessageActions`), the bold headline, a summary of one or two lines, up to three tags, and the footer "Summary by Zyke . on this phone" with
  "Open message" (the detail sheet, which does not read the message, so the card stays).
- **Gestures.** The front card follows the finger on one axis (locked after 12 px) and tilts up to 8 degrees; a quiet tint, an icon and a word firm up as it nears the
  line (left "Sweep it" with the archive glyph in a neutral tint, never an alarm red; right "Got it" with a check in the accent tint). Past 30 % of the width or a 1,200 px/s flick it flies off (240 ms, `Motion.Emphasized`) and the card
  behind grows forward; a light tick fires on commit, never on a spring back (`gentle()`). Down moves to the next card and up to the previous
  (a vertical drag past 20 % of the height or a flick; the cursor wraps; the card slides out of the way). Behind the card two opaque slabs peek.
  The data moves on at once: nothing waits for an animation, and the next card can be acted on immediately. Reduced motion or Lite: no tilt, no
  flying, instant swaps. Decisions: `TriageGestures.decide`, `next`, `previous`, `afterRemoval` (tested).
- **Alternatives to gestures.** The control bar buttons, and four TalkBack custom actions on the card (Sweep it, Got it, Previous card, Next card).
- **Actions** are the existing stores: Got it = mark read (the repeats a payment card holds too), Sweep it = archive (the anchor only, as in Messages).
  Both are optimistic (`triageHidden` in Home's state, the same idea as `MessageActions.pending`), so "to go" has dropped before anything is saved,
  and both offer Undo in the snackbar (amounts masked while hidden). Restore (Auto cleared) brings a card back under Quiet.
- **Empty.** "Stack cleared" with "Next stack" (when there is one) and "Back to Home"; with nothing left on either side, "All caught up" and a note that cleared cards stay in Messages.
- **Auto clear safety rule.** Only Codes (expired codes; a fresh one stays on Home's code card and the code itself is never drawn) and Offers
  (plain promotional or muted messages) can be cleared without a look. Payments, bills, refunds, balance and FASTag alerts, orders and trips can
  only be Keep or Quiet: the settings page offers nothing else for them (`TriageStackSpec.allowed`), `TriageSettings.modeOf` turns any other
  stored value back into the default, and `TriageStacks.autoClearable` checks the message itself. Offers start Quiet and are never auto-cleared
  unless the person chooses it; Codes start Auto clear. Cleared cards are not deleted: they stay in Messages.
- **Make it yours.** The stacks in order with a rank, up and down buttons (48 dp, equal), a segmented Keep / Quiet (+ Auto clear for Codes and Offers),
  a draft until Save, Reset to defaults. Also in Settings, Messages and pictures, "Home deck". Stored as `triage_prefs` (in backups, in memory in Demo).
- **Not animated:** anything on scroll, amounts while hidden, the settings page.

## Settings (wave B5)

Settings is a short list of **groups**, not a stack of cards. The first screen (`settings/SettingsHub.kt`) is a search field, an
optional "Finish setting up" card, and one `FintechCard` per group under a `CardHeader`. A row is an icon tile (40 dp, accent at
10 %), a one-line title, a one-line summary of **how it is set now**, and a chevron; the whole row opens the existing page and dips
while held. The first groups rise in once (`enterOnce`). The page keeps its `AppPage` header and back arrow, so the host's
transition does the rest.

| Group | Pages (rows) |
|---|---|
| Appearance and feel | Appearance (theme, colour, app icon, haptics, performance) |
| Notifications and alerts | Reminders and alerts (morning digest, refresh balances before it, code buttons, weekly summary, heads-up, low balance floor, share codes) |
| Money and budgets | Money plan, Budgets, Market watch |
| Accounts and cards | Cards and accounts |
| Messages and senders | Messages and pictures (logos, tags, RCS, parsing health), Your rules |
| Travel | Flights and trains |
| Backup and data | Your data and about (automatic backup, backup and restore, exports, reset, delete all, version, share logs) |
| Privacy and security | Privacy and security |
| Help and about | Help and tour, Demo mode, Privacy and terms, Open-source licences |
| Developer | Developer options (only while developer mode is on) |

**Nothing was dropped.** `domain/settings/SettingsIndex` lists every setting as data: `(id, title, summary, keywords, group, page,
developerOnly, hiddenInDemo)`. It drives the search field, the Settings results of the global search, and
`SettingsIndexTest.noSettingHasDisappeared`, which holds the list of ids that existed before the regrouping. Add a line to the index
whenever a setting is added (and its id to that test). `SettingsLayout` says which pages each group lists and
`SettingsSearchOnboardingPolicyTest` checks that the domain's `SettingsPageId` and the app's `SettingsGroup` are the same pages.

- **Search in settings.** Matches title, summary and keywords (every typed word must match); best first (title starts with the word,
  a word of the title, a keyword, the summary). Results sit under their group, the typed words in bold on a 14 % accent wash, with
  "N settings found" announced politely. No match: the shared empty state, "No matches for "x"", with "Clear search".
- **Rows.** A switch is `ToggleRow`: title, a summary of at most two lines, the switch, the whole row toggles with a light tick.
  Anything longer is `learnMore = "..."`, a "Learn more" line (48 dp) that opens the text under the row. A caption of three lines or
  more in Settings is a test failure.
- **Destructive confirms** are `ConfirmDialog`: the title asks, the body names what will be lost, Cancel and the confirming button are
  the same width (stacked at 320 dp or large text), the confirming button is the theme's error colour, and the tap gives the firmer
  buzz. Used for Reset marks and budgets, Remove a card or account, Replace my setup (restore), and clearing the code lists. "Delete
  all my data" keeps its type-DELETE sheet and buzzes as it wipes. A sheet's "Remove"/"Delete" button buzzes (`FormSheet`); deletions
  with an Undo (rules, categories, taught senders) buzz and show the Undo.
- **Deep link.** A search result for a setting sets `SettingsDeepLink.pending` to its page and opens Settings; the page opens on it.
- **Demo and developer.** Developer options are listed and searchable only while developer mode is on; settings that would act on the
  real phone in demo (app icon, backup, delete all, share logs) are neither listed nor found there.

## Search (wave B5)

`search/SearchPage.kt` moves between five states with one `CrossfadeContent` (the motion funnel): **idle** (recent searches,
suggestions, jump-to tiles), **loading** (the shared `LoadingState` skeleton, only if a search takes longer than 250 ms, so a quick
one never flashes it), **results**, **empty** and **error** (`ErrorState` with "Try again"). Results are grouped under section headers
with counts (Payments and messages, Accounts and cards, Bills and reminders, Goals, Calendar, Tags, Stocks, **Settings** from
`SettingsIndex`, Jump to); payments carry the merchant, so merchants are found inside Payments. Rows follow the Messages anatomy (48 dp
tile, one-line title, caption, amount) and dip on press (`tappable`); rows that come and go use `itemMotion()`.

- **Recent searches** are equal 48 dp chips in a `FlowRow` (a tap searches again, the small cross removes one) with "Clear all" in
  the header. **Typed words** are bold on the accent wash; the ranges come from `TextHighlight.ranges` in the domain (tested).
- **Empty:** "No matches for "x"" with "Try fewer words, or another spelling", then search tips. The keyboard's action is Search.
- Settings results are searched only for two letters or more, so one letter is not a list of settings.

## Onboarding (wave B5)

One layout for every welcome and permission page (`common/OnboardingHero`): an icon tile (`StateIcon`), the headline
(`TypeRole.headline`), one line of promise, a full-width primary button, a quiet secondary text button and a small footnote. The four
parts rise in once, 40 ms apart, the first time the page is shown. Progress is a row of **step dots** (`StepDotsRow`: done and
current in the accent, upcoming muted, the current one wider, eased in `QUICK_MS`, spoken as "Step 2 of 3"; nothing loops; hidden for
a single step). The steps are `PermissionStep` in the domain: SMS read (the only required one, the page that `PermissionGate` shows),
notifications, notification access (optional) and SMS send (only when code sharing is switched on); each page asks for **one**
thing and an optional one has "Not now". `StepDots` is a function of the step, tested.

- The SMS page's secondary button is "How it stays private", which opens the three promises under the buttons.
- "Welcome back?" (restore on first run) uses the same tile and headline and keeps "By continuing you agree to the Terms and
  Privacy." with the link to read them.
- **Tour.** The coach card is on tokens (`AppShapes.card`, `Spacing`), its spotlight finds its place with `gentle()` and its scrim fades
  in `TAB_MS`, both instant when reduced. Anchors register through the modifier; two anchors with one id can coexist for a moment (the
  Accounts header crossfading from one sub-tab to the next), so an anchor only clears or reports itself if it is still the registered
  one. The deck anchor is created once and shared by both sub-tabs.

## Empty, error and loading voice

One voice for the three states of "there is nothing to show yet". `EmptyState`, `ErrorState`, `PermissionGate`, `LoadingState` and
`ConfirmDialog` are on `Spacing`, `Radius`, `TypeRole` and `IconSize` (no raw dp for spacing or corners; `SettingsSearchOnboardingPolicyTest`
checks). Empty and error pages share one 64 dp tile (`StateIcon`) from the existing icon set.

| | Do | Don't |
|---|---|---|
| Empty title | "No cards yet", "All clear", "Nothing taught yet": what is missing, or the good news | "Error", "Nothing found!", a full stop, more than 40 characters |
| Empty body | One line of why or what will appear: "They appear when your bank texts you about a card." | Two sentences, instructions in three steps, jargon ("no records") |
| Empty action | One verb phrase, only if the person can do something: "Add a card", "Clear search" | Two buttons, "OK", an action that only explains |
| Error | Say what happened and what to do: "Couldn't read your messages" / "Check that SMS access is allowed, then try again." with "Try again" | Blame ("You did not..."), codes, stack traces, a dead end |
| Loading | The still skeleton (`LoadingState`, or the screen's own), one spoken "Loading ..." | A spinner that spins, a shimmer, "Please wait..." |
| Search miss | "No matches for "x"" / "Try fewer words, or another spelling" | "0 results" |

A body longer than 100 characters in an `EmptyState` is a test failure (`home/SubscriptionsPage.kt`, `home/PaidBillsPage.kt` and
`home/BillsPage.kt` belong to the Bills wave and are exempt until it lands).

## Spending map (Where you spend)

A full page (`presentation/places/SpendMapPage.kt`, `AppPage(scrollable = false)`), opened from the Insights card "Where you spend" (`dashboard/WhereYouSpendEntry.kt`), from a payment's "Where" section and from Settings, Spending places, through `SpendMapRequests` (AppRoot draws it with `SpendMapHost`). The map takes every touch inside it (pan, pinch, double-tap zoom), so there is no vertical scroll to fight; the page's own back arrow and system Back leave it.

- **The map is drawn by us** (`SlippyMap.kt`, one Canvas), no map library. Web-Mercator maths, tile choice, fitting, clustering and heat cells are pure `:domain` code (`TileMath`, `PlaceClusters`, `SpendHeat`, `PriceTag`) with tests. Zoom is continuous (3 to 19); squares come from the integer zoom below it and are scaled, and a missing square shows a blurrier one from a lower zoom, else a blank with a faint outline.
- **Two layers.** Heat (soft radial blobs, cool to warm from the theme's info, success, warning and danger colours, intensity by the square root of what was spent in a 28 px cell of a grid fixed to the world, so panning never moves a blob) fades out between zoom 13 and 15; price tags (the booking.com idea) appear from zoom 11.5. The layer button cycles Auto, Heat, Prices. A tag says the total of the payments that merge at that zoom ("₹1,240" close up, "₹1.2k" when zoomed out; dots while amounts are hidden), grows and darkens with spend, carries a count badge when it holds several payments, and points at the spot. Tap: a sheet with the payments (each opens its message), the total, "Name this place" (kept only on the phone) and "Zoom in"; double-tap a tag with several payments to zoom to where they split.
- **Controls.** + and minus, "My area" (fit all), period chips (This month, 3 months, Year, All) and category chips (from `CategoryOptions.forSpending`, only those present), and a total line for the screen ("₹18,400 across 23 payments in view", recomputed 100 ms after the view settles) with a "Places" button that lists the same places as text, the way in for anyone who cannot use the map by touch.
- **Attribution and privacy.** "© OpenStreetMap contributors" is always on screen with map pictures. Map pictures are off until the person agrees ("Map pictures come from OpenStreetMap; only the tile coordinates being viewed are requested"); without them the pins and heat sit on a blank grid, so the map works offline and with nothing requested. In Demo mode the grid is always used and the page says "Map pictures are off in demo"; its places are made up (`DemoPlaces`).
- **Lite and reduced motion.** Lite mode draws flat translucent discs instead of gradients on a grid twice as coarse; zoom buttons and double-tap ease over 220 to 260 ms through `rememberMotion()` and are instant when motion is reduced or lite is on.
- **Empty.** An `EmptyState` says how places are collected and offers Settings when the switch is off.
- **The "Where" section of a payment** (`PlaceWhere.kt`): a rounded still map with a pin, the place's name (the person's nickname, else the phone's geocoder if allowed, else coordinates), "Saved on this phone when the text arrived", and equal-width Open map and Remove location (Undo in a snackbar). A payment skipped as an online shop says so and offers "Save places for this payee from now on".
- **Spending places prompt** (`PlacesPrompt.kt`, domain `PlacesPrompt`): the last step after the SMS and notification steps. A full-screen page in the one onboarding layout (`OnboardingHero`: icon, headline, promise, three plain points, equal-width filled "Turn on" and outlined "Not now"), then a consent page ("Before you allow", the four promises, Continue / Not now), then the system location dialog and, where Android has the step, an "Allow all the time" page (Android 10 dialog, 11+ app settings, "Do this later"). Fresh installs see "Remember where you spend"; an update or restore sees "New: remember where you spend". Shown once: any answer (Turn on, Not now, back) sets `prompted` in `spend_places_settings` (never backed up); anyone who already enabled or explained it is not asked. Never in Demo mode, behind the lock screen or during Welcome. If the phone refuses location, the switch goes back off. Settings keeps the entry; no reminder row, so a "Not now" is respected.
- **Settings, Spending places** (`PlacesSettings.kt`): one explanation sheet, then the switch; a health card in the CodeSharing pattern whose states are Off, Allow location, Allow all the time (needed because texts arrive when the app is closed), On, with equal-width action and Turn off buttons; Android 10 asks for background location in a dialog, Android 11 and later send the person to the app's settings page. Then map pictures, place names (with the remote-geocoder warning), keep places for (3, 6, 12, 24 months or until deleted), include places in my backup (off), forget shop choices, and Delete all places (`ConfirmDialog`, the firm buzz).

Not verified on a device: the location fix, the permission flow, the geocoder, the gestures and the feel of the animation. The tile request was never run against the live OpenStreetMap server.

## On-device AI

Rendered as light/dark previews at 360 dp and 320 dp before the code (Settings page, suggestion sheet, Ask answer, Insights sentence). Principles:

- **Honest and quiet.** Every AI surface says where it came from ("By the AI on this phone"), and sits beside the app's own answer, never in place of a figure. The app's sentence is on screen first; the AI's replaces it only when it passes its checks. Wrong or slow means the plain version stays, with no error shouting.
- **Confirm, never auto-apply.** The suggestion sheet highlights the words the AI used (amount, merchant, last four) in the message, shows "The app would record ...", and has two equal-width buttons, *Use this* and *Not right*, with "Teach it myself instead" below. A bill or non-payment reading offers only Close.
- **Settings, On-device AI** (under Messages and senders): master switch with a one-time explanation sheet; a status card (Not supported on this phone, Model not downloaded, Downloading, Ready, Busy, Problem) with *Download model* and *Check again* side by side; one `ToggleRow` per feature; the review-pile card; a Try it box; *Clear AI suggestions cache* and a quiet diagnostics line. Demo mode shows labelled made-up examples and nothing else. No indeterminate or looping progress: downloading shows megabytes in words. The card never leaves the person without an action or the truth: when Google's service says *downloading* but this app is not following a download (`ModelWatch.waitingOnGoogle`), the pill reads "Waiting for Google's service", *Download model* becomes *Ask again* and re-attaches, *Check again* is always enabled, and only our own running stream disables the download button. Under the status are "Google's AI service reports: ..." with "Last checked h:mm:ss", and, until bytes arrive, "No progress is reported by the phone yet. It usually waits for Wi-Fi, charging and free space (about 3 GB)." The open page asks the phone again every 5 s while downloading and on every return to the app (lifecycle STARTED only). A quiet "Where to look on the phone" tip names Android AICore in the phone's app settings, Wi-Fi and charger, and Pixel 8 or later. *Try it* runs whatever the status says (the real test is whether an answer comes); a successful answer flips the state to Ready. The diagnostics line adds the last status word from the phone and the last download event kind, never any text. The rules are in `ModelWatch` (domain, `ModelWatchTest`). **Evidence of use beats the status word.** A phone whose `checkStatus()` said "not downloaded" still answered "Try it" in 7 s, so every real answer is recorded (`workedAtMs` and its latency, two numbers in the `ai_settings` prefs, `ModelEvidenceStore`; no backup carries them). For 14 days after an answer `ModelWatch.effective` shows **Ready** whatever the status said (not downloaded, not supported, an error; only a download the phone really reports keeps its own state), the card says "Working on this phone · last answer 7.0 s · h:mm" with the quiet note "Android reports the model as not downloaded, but it answered at h:mm.", *Download model* is hidden (*Check again* stays), and the five feature switches and `AiGateway` run on either Ready or that evidence (`AiContext.modelWorked`), relying on the failure messages if the model has gone: an UNAVAILABLE failure withdraws the proof. A stream that shows no event at all for 30 s (`ModelWatch.STREAMLESS_CAP_MS`) is dropped and the phone is asked again; if it still says not downloaded the card reads "Model not downloaded: Asked, but the phone has not started. Try 'Try it' to see if it works anyway." with *Ask again*. A real DOWNLOADING from the phone still reads "Waiting for Google's service". The first request after the app starts takes seconds while the model wakes up: the card then shows a quiet text line "Warming up the model..." (text only, no spinner), the Try it caption says so, and the open page may send one tiny warm-up request (`AiHub.warmUp`: 2 tokens, not counted in the page's numbers, only with the switch on and a usable model, never in Demo or lite mode or from the SMS receiver, once per app start and at most every 10 minutes, cancelled when the switch goes off).

Not verified on a device: what AI Core reports in each state, whether cancelling the download flow lets the phone's own download carry on, and the warm-up's effect on the first answer time.
- **Ask your money** is a row under the Search chips when the words look like a question, titled "Ask Zyke"; it opens the Ask Zyke page (below) with the typed question already asked, and the answer is the chat's: the big figure (`PrivateAmount`, so dots while hidden), the sentence, "Read as: Spent · Food · last week" and the payments behind it (each opens the payment).
- **Ask Zyke** is a page of its own (`ai/AskZykePage.kt`, an `AppPage` with `calm = true`: the title, Back and the quiet menu are in the ordinary text colours, not the accent), opened from Home's round button and from Search's "Ask Zyke" row (which opens it with the typed question already asked). It is laid out like a chat, as Gemini's home is: the title row has Back, "Ask Zyke" and a quiet menu (Clear chat; "Chats are not saved"); a message list fills the page; the text box sits directly on the keyboard and the navigation bar. **Insets are applied once**: the page's own `imePadding` (and `navigationBarsPadding` on its content), with `android:windowSoftInputMode="adjustResize"` on `MainActivity` so the system never pans the window as well (an unspecified mode let some phones pan by the keyboard's height on top of `imePadding`, which left the bar floating with a blank gap). Nothing in the chat adds a second inset.
  - **Input bar**: a 52 dp field with 26 dp corners, 16 dp side padding, `surfaceContainerHigh` fill, "Ask Zyke" as placeholder, growing to four lines, and a 44 dp round send button (a 48 dp target) in `primary` when it can send, a quiet 10 % disc with a 38 % icon when it cannot; it becomes Stop while Zyke is thinking. A hairline shows over the bar while the list runs on beneath it. The bar is 320 dp safe (16 + field + 8 + 48 + 16).
  - **Empty state**: in the upper third, the Zyke mark (the "Z" of the wordmark, `zyke_mark`, on a `secondaryContainer` disc, 64 dp), "Hi, what would you like to know?" in `headlineMedium`, one quiet line, "Answers are worked out on this phone. Chats aren't saved.", and, centred just above the text box, four tappable 40 dp suggestions with a hairline outline in a wrapping `FlowRow` (This month's spending, Biggest payment this month, Food last week, Money received this month; `AskSuggestions`). A tap asks at once. While the keyboard is up the greeting shrinks to a 32 dp mark and "Ask Zyke" and the suggestions become one scrolling row, so nothing is cramped or scrolled away. "Last month against this month" is not in the query vocabulary, so it is a follow-up instead: ask this month's spending, then "and last month?". When the model is not ready the empty state says so with "Set up on-device AI" and "Use normal search".
  - **Bubbles** (`ai/AskBubbles.kt`): the person's on the right in `primaryContainer`, Zyke's on the left in `surfaceContainer` with the Zyke mark (28 dp) beside it; the corner on the speaking side is 6 dp, the others 20 dp; at most 85% of the width; text is selectable; the time sits quietly under each answer. Spoken as "You said" / "Zyke said" (a label on the bubble, then its text); the newest answer is a polite live region. An answer holds the big figure (`PrivateAmount`, dots while hidden), the sentence, "Read as: Spent · Food · last week", up to three payments (each opens the payment, over the page) and "See all N" / "Show fewer". A question that cannot be read gets a plain bubble ("I couldn't read that as a question about your spending. Try one of these:", the reason, three suggestions and "Search messages for “…”", which opens Search with it typed); a model that is not ready gets "Set up on-device AI" (Settings, On-device AI) and "Use normal search"; a failure gets its plain sentence and "Try again".
  - **Thinking**: a Zyke bubble that says "Thinking…" in words, with three dots that fade in once through `rememberMotion()` (all there at once under reduced motion or Lite; nothing loops) and "Tap stop to cancel". The list scrolls to the newest question with its answer under it (a jump, not a glide, when motion is reduced).
  - **Follow-ups**: the request Zyke last answered (metric, category, payee, period, grouping) goes into the prompt, and the model replies with only what the new question changes ("and last month?", "what about Swiggy?", "and the biggest one?"). The app merges it and checks every changed field against the same lists as a fresh question (`AskFollowUp`); if that does not make sense it reads the question fresh. The model never writes a figure.
  - **State**: the chat is held in memory (`AskChatSession`), survives rotation, is never written to disk and ends with "Clear chat" or the app closing. In Demo it holds canned, labelled examples ("Example only: made up, not from your messages."), the field is off and no model is asked. Haptics: the light tick on send (`rememberHaptics()`). The page adds no tour anchor.
- **Month in a sentence** is an Insights card with "Show the plain version" toggle.
- **Five more features** (own switches under *What it can do*; each hidden unless on, never in Demo mode). All use one block, `AiThinkBlock`: an outline button, then the words "Thinking... this takes a few seconds on this phone." with a Cancel button (no spinner or looping bar), then the checked text with a one-line source ("By the AI on this phone ... uses no other number"), or the reason there is none and *Try again*. *Clean payee names*: under the Name box in the nickname sheet, *Suggest a clean name*, then a filled *Use "Swiggy"* with *Not this one*; only the box changes and Save stays the person's. *Explain this message*: under the original text in a message's details (not for one-time codes). *Why did this go up?*: an Insights card under the month sentence when spending is above last month at this point; the app's plain sentence first, rows of the biggest rises (payee or category, `+₹1,800` or `new ₹900`, `PrivateAmount` so dots while hidden), then *Say it in a sentence*. *Morning digest line*: no screen of its own; the notification's collapsed line and first expanded line are the AI's line when one was prepared, else it is exactly the old digest; Settings says it is prepared while the app is open. *Explain unusual payments*: in the review pile under an "Unusually large payments" card, the app's own comparison ("About 42 times your usual payment of ₹600 ...") and *Say why in a sentence*. Demo mode shows five labelled made-up examples. Lite mode: the on-demand ones work (the person asked); the digest preparation is background work and does not run.
- Wide text, 320 dp and large fonts: nothing uses fixed heights; buttons stack through `ResponsiveRow`; colours are the theme's (light, dark, true black); every control is 48 dp with a label.
- Hidden when there is nothing to do: not enabled, Demo mode, or an unsupported phone.

## Status and what comes next

Phase A (this page): tokens, the motion package, tab/page/sheet transitions, shared components on tokens, Home as the
reference. Wave B1: Messages (above). Wave B5 (Settings, Search, onboarding and the empty/error/loading voice) is described above; it is the last wave. Not verified on a device: no animation here has been seen running; the numbers are from the specification.
Accounts, Cards and Investments (B2) follow the Accounts section above. See the wave plan in the pull-request notes: B1 Messages, B3 Insights, B4 Bills and
Calendar, B5 Search, Settings, onboarding and empty states (done).

## Logos from the logo list

A payee with no picture of its own shows, in this order: the person's chosen picture, the logo the bundled list names for the brand, the brand's website icon, the generic icon.
The bundled logo is drawn exactly as any other brand logo (same `MerchantChip` tile, same "This logo is wrong" and "Show the logo again" rows, same Fetch brand logos switch), and while it loads the tile shows
the generic icon, never the website icon first, so the tile does not change twice. Settings > Messages > Logo list is a card that appears only when the person has chosen a web picture for a payee: a count, one
sentence on what is shared, Export logo list (outline button) and a text button "Look through the list" that opens a page with one line per shop (name, host) and a Remove button each.
