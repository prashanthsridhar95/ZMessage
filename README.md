# Zyke Message

An Android SMS inbox that turns raw messages into cards: OTPs with one-tap copy, bank / card / UPI
transactions, card bills, FASTag events, courier updates, data-usage alerts and loyalty points.
Everything is processed on-device; the only permission is `READ_SMS`.

## Architecture

Two Gradle modules: **`:domain`** (pure Kotlin) and **`:app`** (Android). Clean Architecture with an MVI
presentation layer, 100% Jetpack Compose UI, and no DI framework (a small manual `AppContainer`).
See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for diagrams.

```
domain/  (kotlin-jvm, no Android)
  model, classification (parsers + classifier), feed / dashboard / home / conversation builders,
  diagnostics, repository contracts, ObserveMessagesUseCase
app/
  data/          SMS provider + SharedPreferences implementations of the contracts
  presentation/  MVI view models, Compose screens and components, formatters, theme
  di/            AppContainer
```

Dependencies point inwards: `presentation -> domain <- data`, and `:domain` cannot see Android.

## Quality, security and privacy

- **Tests:** `./gradlew :domain:test :app:testDebugUnitTest`. Parsers, builders, view models, formatters, a
  fuzz / hostile-input suite for the classifier, and a manifest policy test.
- **Static analysis:** `./gradlew detekt` must report zero issues (`config/detekt/detekt.yml`).
- **Lint and CI:** GitHub Actions runs tests, detekt, Android lint and builds the debug APK on every PR;
  `release.yml` builds a signed release (R8 minified) from repository secrets. Dependabot keeps
  dependencies and actions current.
- **Privacy by construction:** `INTERNET` only for the brand-logo fetch (can be turned off), the market strip's prices and stock-name suggestions and live flight and train status (the last three off until switched on; with it on, a foreground service refreshes an in-flight notification every 30 s while a flight is in the air or a train is under way); a few pinned files, tested, backups off, one exported activity, enforced by
  `ManifestPolicyTest`. See [SECURITY.md](SECURITY.md) and [docs/PRIVACY.md](docs/PRIVACY.md).
- **Privacy and terms in the app:** Settings, Privacy and terms (what stays on the phone, what is sent and to which host, permissions, terms of use, minimum age 18, contact) and Open-source licences. The words live in `:domain` (`LegalText`, `OpenSourceNotices`) and tests keep them in step with the manifest, the network hosts and the dependency list. See [docs/LEGAL_NOTES.md](docs/LEGAL_NOTES.md) (not legal advice; have a lawyer review before a public release), [docs/PRIVACY.md](docs/PRIVACY.md) and [NOTICE](NOTICE). The repository has no LICENSE file yet; that choice is the owner's.
- **Accessibility:** WCAG AA text contrast, one-sentence TalkBack descriptions for every message, 48dp targets, labelled switches and checkboxes, headings, a state for progress rings, Done / Next on keyboards and Escape to close a sheet or page; `AccessibilityPolicyTest` guards the basics.
- **Contributing:** [CONTRIBUTING.md](CONTRIBUTING.md).
- **Budget phones:** layouts bend for 320 to 411 dp screens and large text, and a lite mode (automatic on low-memory phones) drops looping animations and effects. See [docs/LOWEND.md](docs/LOWEND.md).
- **App tour:** a first-launch walkthrough (also in Settings, Help and tour) that opens each screen and explains it; local only. See [docs/TOUR.md](docs/TOUR.md).

### Three tabs

The app opens on the **Inbox**; Back from Home or Money returns to it before leaving.

- **Home**: what needs attention (fresh OTP, bills due, low FASTag), this month versus last month,
  upcoming bills and auto-debits (`UpcomingBuilder`), budget status, recent payments
- **Money**: the dashboard (spending by category / vendor / account, card bills, budgets)
- **Inbox**: timeline and senders, conversations, search

Filtering has two layers. **Quick filters** sit along the top as plain text tabs with counts
(Inbox: All / Money / OTP / Bills / Travel / ...; Money: This month / Last month / 3 months / All time).
**More filters** open from the filter button in a bottom sheet of selectable tiles (Inbox: category,
read status, time window; Money: category). Choices apply immediately, the button at the bottom says
what you will see ("Show 12 messages"), and active sheet filters stay visible as a summary bar with
Change / Clear.

### Senders and conversations

The Messages tab has a **Timeline** (grouped by day) and a **Senders** view (one row per sender,
latest first, with unread counts). Operator prefixes and traffic-type suffixes are ignored, so
`AD-HDFCBK` and `VM-HDFCBK-S` are the same sender. Tapping a sender opens that
sender's messages in a bottom sheet: every message from them, newest first, in day sections. Tapping
any message opens its detail in a second sheet on top; closing it returns to the list. A message opened
from "More from this sender" is scrolled into view.

### Dashboard tab

The second tab answers "where does my money go?". `DashboardBuilder` (domain, pure) aggregates the
sent / received transactions for a period (this month, last month, 3 months, all time) into:

- a headline total with a comparison against the previous period (same number of days for a
  partial month), plus spent and received totals
- **credit card bills by card**: latest statement (amount, minimum, due date, pay link) with earlier
  statements on tap; independent of the selected period
- a daily (or monthly) trend, drawn as bars stacked by category with a colour key
- totals **by category**: tap one to filter everything below it; set a **monthly budget** per category (Settings > Budgets, with optional carry-over
  and move-money)
  and see what is left (amber from 80%, red once over) for this month's spending
- **top vendors** (UPI ids and merchants grouped by name): tap one for its latest payments
- **paid from**: which account or card the money moved through

State and intents live in `DashboardContract` / `DashboardViewModel`, separate from the messages tab.

### Feed model

`FeedBuilder` (domain, pure) turns the classified inbox + what the user did (read / pinned /
archived / dismissed) + the active filter or search query into a `Feed`:

- **attention**: up to three things that need action now (a fresh OTP, a card bill that is due,
  a low FASTag balance) via `AttentionBuilder`
- **summary**: money spent this month, split by category (`SpendSummaryBuilder`)
- **sections**: a "Pinned" section, then one section per day; repeated OTPs / data alerts from one
  sender on one day collapse into a single expandable item
- **availableFilters**: the smart chips worth showing (empty buckets are left out)

Promotional messages (DLT `-P` sender headers and marketing phrases) and archived messages stay out
of the default feed. User marks live in `UserMessageStateRepository` (SharedPreferences); the SMS
provider itself is never written to.

### Adding support for a new message type

1. Add a branch to `MessageClassifier` (and a parser under `domain/classification` if needed).
2. Add a `MessageContent` subtype and a card composable in `presentation/messages/components`.
3. Handle it in `MessageItem`. Add unit tests next to the existing ones.

## If the app crashes on launch

Long-press the Zyke Message icon on the home screen and choose **Share logs**. It opens the share sheet with one text file
(start-up steps, crash class and function names, line numbers, how earlier runs ended; never message text, amounts or
names) that you can send to whoever is helping. It works even when the app itself cannot start, and also from
Settings, About, "Share logs". Nothing is sent unless you pick an app to send it with.

## Font

The UI uses **Plus Jakarta Sans** (SIL OFL, `third_party/fonts/PlusJakartaSans-OFL.txt`), with Regular,
Medium and Bold in `res/font`. It has a native rupee sign and full Latin / punctuation coverage. The
app previously bundled *Nexa Demo*, a trial font that replaces `/ % < > | … · → ←`, accented letters and
more with a "DEMO CASE" placeholder and has no `₹`; it is not licensed for distribution. To use a
licensed font instead, drop its files into `res/font` and change `AppFontFamily` in `theme/Theme.kt`.
`AppTypography` keeps Material's size scale and only swaps the family.

## Tests

```
./gradlew testDebugUnitTest
```

Parsers, classifier, feed / attention / spend builders, use case, formatters and the ViewModel are
covered by JVM unit tests. `Previews.kt` has Compose previews of the whole feed (light, dark, loading).

CI (`.github/workflows/ci.yml`) runs the unit tests and builds the debug APK.
