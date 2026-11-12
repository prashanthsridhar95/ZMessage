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

## Status and what comes next

Phase A (this page): tokens, the motion package, tab/page/sheet transitions, shared components on tokens, Home as the
reference. Not verified on a device: no animation here has been seen running; the numbers are from the specification.
See the wave plan in the pull-request notes: B1 Messages, B2 Accounts, Cards and Investments, B3 Insights, B4 Bills and
Calendar, B5 Search, Settings, onboarding and empty states.
