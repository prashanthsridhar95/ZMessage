# Budget phones (Redmi, POCO, Galaxy A0x) and lite mode

Written without a device to test on: everything here was found by reading the code and is guarded by pure-function tests
and `LowEndPolicyTest`. The developer simulator (below) is how to check it on a Pixel. Items marked *unverified* have not
been seen on a real budget phone.

## What differs on those phones

- 720x1600 panels at 280 to 320 dpi: 360 to 411 dp wide but only about 700 to 760 dp tall; a 320 dp width is still around.
- MIUI "Display size" and "Font size": text at 1.15 to 1.3, "Largest" 1.4 and up.
- MIUI/ColorOS/One UI "force dark" for third-party apps, display cut-outs, gesture or 3-button navigation.
- 2 to 4 GB of memory, slow cores and a Mali GPU: looping animations, off-screen layers and big bitmaps drop frames.

## Audit: fixed

| Where | Problem | Fix |
|---|---|---|
| Bottom bar (5 tabs) | "Accounts"/"Messages" wrap or cut at 64 to 72 dp per tab, worse at 1.3 text | labels use `FitText` (one line, shrinks to 70 %); the bar is in `ChromeScale` (text size capped at 1.3) |
| Toolbar | logo + search + filter + eye + chip + settings wider than 360 dp; the right-hand buttons were pushed off | buttons are measured first, the logo takes what is left; chip is just the countdown on a narrow phone; `ChromeScale` |
| Hero amounts (Accounts, Cards, Investments, today's budget, spent, year, net worth, account sheet, PF tiles) | `displayMedium` rupee amounts wrapped or were cut at 320 to 360 dp | `PrivateAmount(fit = true)` / `FitText`: one line, shrinks to about 55 %, ellipsis only below that. Still masks amounts while hidden |
| Cards tab hero | BILLED and UNBILLED side by side, 130 dp each | `ResponsiveRow`: stacked on a narrow phone or at large text |
| Bank account tile | amount squeezed between the name column and an 88 dp sparkline | `fit = true` |
| Message and bill rows | the amount (titleLarge) could take the whole row and squeeze the name to nothing | amount limited to half the window, steps down a size when narrow, second line at large text |
| Card face | fixed 1.586 ratio: the four rows overlap above text 1.3; the number wrapped and hid the last four digits | text capped at 1.15 inside the face (`CapFontScale`); number uses `FitText`; less peek (20 dp) on a narrow phone |
| `AppButton` | label `softWrap=false` was clipped mid-word in a half-width button | ellipsis; 16 dp side padding when narrow |
| `SegmentedTabs`, `UnderlineTabs`, `PillSwitch` | labels overflow at large text | `ChromeScale`; pill switch tightens and scrolls sideways when still too wide |
| Web sheet (`LinkSheet`) | fixed 640 dp height, taller than a 700 dp screen | at most 80 % of the window |
| Display cut-out | landscape notch on the side covered content; toolbar only knew the status bar | whole app padded by the horizontal cut-out; toolbar by status bar and cut-out |
| Snackbar | could sit behind the keyboard | `imePadding` |
| MIUI force dark | colours inverted on top of the app's own Night theme | `android:forceDarkAllowed=false` (`values-v29/themes.xml`) |
| Looping animations that kept running when "reduced" | skeleton pulse and journey scene still started a clock | no clock is started when reduced/lite |

Already fine (checked, unchanged): icon buttons are 48 dp; `FintechCard` is flat (no shadow or blur); the tour card
scrolls and is limited to 62 % of the height; sheets and pages already handle the keyboard (`imePadding`) and the
navigation bar; fonts are bundled (Plus Jakarta Sans), so MiSans metrics never apply and Compose 1.7 already uses
`includeFontPadding = false`; no dynamic colour is assumed (the Android 12 wallpaper scheme is used only when
`SDK_INT >= 31`; below that the Material baseline purple scheme, which is AA); edge-to-edge is on and the cut-out mode
comes from `enableEdgeToEdge()` (no manifest attribute added, so `ManifestPolicyTest` is unchanged).

`AdaptiveSheet` turns a form into a page when it would be taller than 85 % of the window. On a 700 dp phone that is
about 595 dp, so more forms become pages sooner. That is intended: a page scrolls and keeps its footer, a nearly full
sheet does not.

## Audit: deferred

- `AppButton` COMPACT keeps its 40 dp minimum height (changing it moves every dialog and toolbar).
- Two-column layouts other than the Cards hero (for example the Year page stat pairs, forecast cards) are not stacked yet;
  their text ellipsizes. Use `ResponsiveRow` when touching them.
- Charts: sizes are fixed in dp (120 to 160 dp tall), widths fill; fine at 320 dp, not drawn differently in lite mode
  beyond the grow-in animation being skipped.
- Dialogs use Material's default width (280 to 560 dp); fine on 320 dp.
- Nothing was seen on a real device (*unverified*).

## Lite mode

`LiteMode.resolve(PerformanceMode, detectedLowEnd)` (domain, tested): Lite wins, Full wins, Automatic follows the
detection. Detection (`LowEnd`, run once on a background thread, cached in the `perf` preferences, which no backup carries)
is `LowEndRules.isLowEnd`: the system's low-RAM flag, or 3 GB of memory or less, or a per-app heap of 192 MB or less.
Settings, Appearance, Performance shows the choice and what it resolves to.

Lite does this:
- `rememberReduceMotion()` also reads `LocalLite`, so every screen that honours "remove animations" skips decorative and
  looping animation: the priority card's moving border (a faint static outline), the loading skeleton pulse, the journey
  scene, tab slides, count-up numbers, growing bars, press scale.
- The market strip does not scroll by itself; it is swiped by hand.
- The tour spotlight is four flat rectangles plus the ring instead of an off-screen layer with a cleared hole.
- Pictures: cache 4 MB, decoded at 128 px at most, JPEGs at 16 bits per pixel.
- Derive pipelines (Home, Dashboard, Messages): at most 2 parts at a time (`LiteRuntime.bounded`); screens rebuild at most
  every 1.2 s (700 ms normally) while the inbox is still being read; the developer log notes memory every 15 s (5 s).
- No blur, render effect or shadow exists in the app (`LowEndPolicyTest` keeps it so).

## Developer simulator

Settings, About: tap the version seven times to unlock Developer options, then **Simulate a small phone**. Choose a preset
(Redmi 9A-like 360x745 dp at font 1.15; Redmi Note-like 393x830 dp; Small 320 dp) and/or density x1.0/1.15/1.3 and font
scale x1.0/1.15/1.3/1.5 (they multiply the phone's own). The whole app is drawn in that window, centred, with a border;
the chip "Simulating ..." with **Reset** stays on top. It is only applied while developer mode is on, is stored in the
developer preferences (not backed up) and turning developer options off ends it. Real system bars, the keyboard and
dialogs/permission prompts stay real, so the simulated window shows the real status-bar height. Pair it with
Performance, Lite to see the lite look.

## Guards

`LowEndPolicyTest` (looping animations and marquee gated, no blur/render effect, Offscreen only in the tour with a lite
version, no fixed width over 280 dp, nav labels fit, force-dark off, simulator gated and unbacked) and the pure tests
`LowEndTest`, `DeviceProfileTest`, `ScreenClassTest` in `:domain`.
