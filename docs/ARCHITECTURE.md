# Architecture

Two Gradle modules with a strict dependency direction.

```mermaid
flowchart LR
    subgraph app[":app (Android)"]
        P[presentation<br/>Compose + MVI]
        D[data<br/>SMS provider, SharedPreferences]
        DI[di<br/>AppContainer]
    end
    subgraph domain[":domain (pure Kotlin)"]
        M[model]
        C[classification<br/>parsers + classifier]
        B[builders<br/>feed, dashboard, home, conversation]
        R[repository contracts]
        U[usecase]
    end
    P --> domain
    D --> domain
    DI --> P
    DI --> D
```

`:domain` has no Android dependency, so the compiler enforces the rule and its tests run on any JVM.

## Data flow

```mermaid
flowchart LR
    SMS[(SMS provider)] -->|ContentObserver, debounced| Repo[SmsRepositoryImpl]
    Repo -->|List&lt;SmsMessage&gt;| UC[ObserveMessagesUseCase<br/>sort, dedupe, classify]
    UC -->|List&lt;ClassifiedMessage&gt;| VM[ViewModels]
    Marks[(UserMessageState<br/>Budgets, Settings)] --> VM
    VM -->|State| UI[Composables]
    UI -->|Intent| VM
    VM -->|Effect| UI
```

## Presentation (MVI)

Each screen has a `Contract` (immutable `State`, `Intent`, one-shot `Effect`) and a `ViewModel` that reduces
intents into state. Composables are stateless (`State` in, `(Intent) -> Unit` out); the `*Route` composable
connects the ViewModel and handles effects (clipboard, links, permission).

Derived data (the feed, the dashboard, the home overview, conversations) is built by pure functions in
`:domain` (`FeedBuilder`, `DashboardBuilder`, ...) and stored in `State`, so recomposition does no heavy work.

## Classification

`MessageClassifier` runs rules in order (OTP, FASTag, money, data usage, courier, loyalty, generic). Money
messages go to the hand-written bank parsers first and then to `GenericTransactionParser`, a best-effort reader
for other banks' wording. It never throws and parses at most 4096 characters.

## Quality gates

`:domain:test`, `:app:testDebugUnitTest`, `detekt` (zero issues), Android lint, and `ManifestPolicyTest`
(privacy promise). CI runs them on every pull request.
