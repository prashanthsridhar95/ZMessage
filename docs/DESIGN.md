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

1. **One-shot only.** No `rememberInfiniteTransition`, `infiniteRepeatable`, shimmer or pulse. (The one inherited loop is the
   priority card's light, `movingBorder`; it is a faint static outline when reduced and is allow-listed in
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
| An error | `ErrorState` |
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

## Accounts (Accounts, Cards, Investments)

The Accounts tab has three sub-tabs, each with one hero: the **bank total** with its deck of accounts, the **deck of cards**,
and the **investments total**. The heroes are header regions (`HeaderRegion`, a dark region in both themes with `Radius.hero`
bottom corners); everything under them is `FintechCard` on `Tonal.Quiet` with `CardHeader` labels. `AccountsPolicyTest` keeps
this page true.

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
| `rememberChartTicker()` | one light haptic each time a finger lands on a different bar, at most one every 60 ms |
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
  lambdas, so nothing recomposes per frame. Touch a bar or drag across the chart to read it (a caption with the date and amount,
  a tick for each new bar).
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

## Status and what comes next

Phase A (this page): tokens, the motion package, tab/page/sheet transitions, shared components on tokens, Home as the
reference. Wave B1: Messages (above). Not verified on a device: no animation here has been seen running; the numbers are from the specification.
Accounts, Cards and Investments (B2) follow the Accounts section above. See the wave plan in the pull-request notes: B1 Messages, B3 Insights, B4 Bills and
Calendar, B5 Search, Settings, onboarding and empty states.
