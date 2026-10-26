# Zyke Message

An Android SMS inbox that turns raw messages into cards: OTPs with one-tap copy, bank / card / UPI
transactions, card bills, FASTag events, courier updates, data-usage alerts and loyalty points.
Everything is processed on-device; the only permission is `READ_SMS`.

## Architecture

Clean Architecture with an MVI presentation layer, 100% Jetpack Compose UI, no DI framework
(a small manual `AppContainer`).

```
com.zyke.message
├── domain            pure Kotlin, no Android imports
│   ├── dashboard       DashboardBuilder: totals, categories, vendors, sources, trend
│   ├── feed            FeedBuilder, AttentionBuilder, SpendSummaryBuilder
│   ├── model           SmsMessage, ClassifiedMessage, MessageContent, MessageCategory, ...
│   ├── classification  MessageClassifier + parsers (bank, FASTag, OTP) + MerchantResolver
│   ├── repository      SmsRepository (interface)
│   └── usecase         ObserveMessagesUseCase: sort, de-duplicate, classify
├── data              Android implementation of the domain contracts
│   ├── sms             SmsDataSource (ContentResolver + ContentObserver)
│   └── repository      SmsRepositoryImpl
├── presentation
│   ├── mvi             MviViewModel<State, Intent, Effect>
│   ├── messages        MessagesContract, MessagesViewModel, MessagesScreen / MessagesRoute
│   │   └── components  one composable per card type, bars, list, detail
│   ├── format          date and text formatting
│   └── theme
└── di                AppContainer (wired in ZykeMessagesApplication)
```

Dependencies point inwards: `presentation -> domain <- data`.

### MVI flow

`MessagesRoute` sends `Intent`s to `MessagesViewModel`, which reduces them into an immutable
`State` (exposed as a `StateFlow`) and emits one-shot `Effect`s (permission request, clipboard).
`MessagesScreen` is stateless: `(State, (Intent) -> Unit) -> Unit`.

### Dashboard tab

The second tab answers "where does my money go?". `DashboardBuilder` (domain, pure) aggregates the
sent / received transactions for a period (this month, last month, 3 months, all time) into:

- a headline total with a comparison against the previous period (same number of days for a
  partial month), plus spent and received totals
- a daily (or monthly) trend
- totals **by category**: tap one to filter everything below it
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

## Tests

```
./gradlew testDebugUnitTest
```

Parsers, classifier, feed / attention / spend builders, use case, formatters and the ViewModel are
covered by JVM unit tests. `Previews.kt` has Compose previews of the whole feed (light, dark, loading).

CI (`.github/workflows/ci.yml`) runs the unit tests and builds the debug APK.
