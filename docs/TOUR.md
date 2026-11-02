# The guided tour

A first-launch walkthrough that moves around the real app, highlights what is where and explains it. It is also in
Settings, Help and tour (the whole tour, auto-play, or any chapter on its own).

## How it works

* **Content** lives in one place, `domain/.../tour/Tours.kt` (`Tours.full()`): about 45 steps in 10 chapters
  (`TourGroup`). A step is `TourStep(id, group, title, body, tab, anchor, page, ...)`. Plain English, present tense: what it is,
  where it is, what to do with it. Body at most 220 characters, optional `tip`. Nothing else holds tour words.
* **Progress** is `TourProgress` (pure: `next`, `back`, `skip`, `jumpToGroup`, `resume`). **Offers** are `TourPolicy.offer(state)`
  (pure): fresh install gets an invitation after Welcome and the permission steps; restored and updated installs, and anyone who
  chose "Maybe later", get a quiet dismissible banner on Home; a tour interrupted by process death resumes (at most twice at the
  same step, so a failing step cannot crash-loop).
* **State** (`TourSeenRepository`, preferences file `tour_state`) is local only and excluded from backups; a restore marks
  the install as restored so the tour is not replayed. `TOUR_VERSION` re-offers a future major tour (as a banner).
* **Engine** (`presentation/tour/`): `TourController` (navigation, waiting for targets, persistence, failure handling),
  `TourHost` (scrim with a spotlight hole, coach card, a11y), `TourRegistry` (anchor bounds), `TourPreview` (sample cards),
  `TourLaunch` (invitation and banner), `TourHelp` (Settings entry).
* **Auto-navigation**: before a step is shown, the controller asks the `TourNavigator` (implemented in `AppRoot`) to
  switch tab and open/close the step's page (Settings page via `TourSettingsRequest`, Search, and Home's Calendar, Goals and
  Reminders via `TourHomeNav` in `MainActivity`). It then waits up to about 1.2 s for the anchor to register; if it is not on
  screen it scrolls the tab's list down (`TourScroll`), then tries the step's `fallbackAnchor`, and finally shows a centred card.
  It never waits forever and never throws into the app (any failure closes the tour and logs under the tag `tour`).
* **Empty data**: on a fresh install most cards do not exist. A step with `needsData = true` has a `preview`
  (`TourPreviewKind`); when its real `anchor` is not found, the always-present `fallbackAnchor` is spotlighted and a small
  labelled "Example" sample is drawn in the card. Nothing is injected into any repository.

## Adding a step

1. Add a `TourStep` to `Tours.full()` in the right chapter. Give it `covers` feature keys.
2. If the app gained a feature, add its key to `featureCoverageTest` (`TourTest.kt`) and cover it in a step. The test fails
   until every key is explained by some step.
3. If the step points at a new element: add a value to `TourAnchor`, then put **one** `Modifier.tourAnchor(TourAnchor.X)` on
   that element. `TourPolicyTest` requires every anchor to be referenced exactly once in `presentation/`. Keep the edit to the
   screen to that one modifier (wrap in a `Box` when the composable has no modifier). Use a `fallbackAnchor` (something always
   on screen) for anything that exists only once there is data, and a `preview` kind for it.
4. If it needs a page: add a `TourPage` (Settings pages are named `SETTINGS_<SettingsGroup>`; the test checks the group exists) and
   open it in `AppRoot`'s navigator or `TourHomeNav`.
5. A new lazy list that holds targets gets `TourScroll(TourTab.X, listState)`.

## Rules the tests enforce

Unique ids; title and body present and short; chapters contiguous and in order; feature coverage; progress arithmetic; offer
rules; anchors used exactly once and present for every step; no colour literals or Material `Text` in the overlay (scrim from
`colorScheme.scrim`); `tour_state` excluded from backups; the host mounted above sheets and pages but below the snackbar.

## Accessibility

The card is a polite live region with a pane title and takes focus on each step; the spotlight is decorative; every control has
a spoken label ("Next, step 3 of 45"); targets are 48dp; colours are theme tokens only; reduced motion removes the spotlight
animation; large fonts scroll the card; right-to-left mirrors swipe; auto-play is off by default, never offered to a screen
reader user, and never moves through a step that asks for an action.
