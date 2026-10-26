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

### Adding support for a new message type

1. Add a branch to `MessageClassifier` (and a parser under `domain/classification` if needed).
2. Add a `MessageContent` subtype and a card composable in `presentation/messages/components`.
3. Handle it in `MessageItem`. Add unit tests next to the existing ones.

## Tests

```
./gradlew testDebugUnitTest
```

Parsers, classifier, use case, date formatting and the ViewModel are covered by JVM unit tests.
