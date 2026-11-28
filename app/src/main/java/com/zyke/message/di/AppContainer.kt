package com.zyke.message.di

import com.zyke.message.domain.repository.MergeRepository
import com.zyke.message.data.repository.MergeRepositoryImpl
import com.zyke.message.domain.repository.ReminderRepository
import com.zyke.message.domain.repository.GoalRepository
import com.zyke.message.data.repository.ReminderRepositoryImpl
import com.zyke.message.data.repository.GoalRepositoryImpl
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.zyke.message.data.cache.EncryptedClassificationCache
import com.zyke.message.data.images.ImageStore
import com.zyke.message.data.repository.BudgetRepositoryImpl
import com.zyke.message.data.repository.CardRepositoryImpl
import com.zyke.message.data.repository.CategoryOverrideRepositoryImpl
import com.zyke.message.data.repository.MoneyPlanRepositoryImpl
import com.zyke.message.data.repository.NicknameRepositoryImpl
import com.zyke.message.data.repository.PaymentNotesRepositoryImpl
import com.zyke.message.domain.repository.PaymentNotesRepository
import com.zyke.message.data.repository.MutedSenderRepositoryImpl
import com.zyke.message.data.repository.SettingsRepositoryImpl
import com.zyke.message.data.repository.SplitRepositoryImpl
import com.zyke.message.data.repository.WishlistRepositoryImpl
import com.zyke.message.data.repository.SmsRepositoryImpl
import com.zyke.message.data.repository.UserMessageStateRepositoryImpl
import com.zyke.message.data.sms.RcsMessageStore
import com.zyke.message.data.sms.SmsDataSource
import com.zyke.message.domain.classification.MessageClassifier
import com.zyke.message.domain.repository.BudgetRepository
import com.zyke.message.domain.repository.CardRepository
import com.zyke.message.domain.repository.ClassificationCache
import com.zyke.message.domain.repository.NoClassificationCache
import com.zyke.message.data.demo.DemoSmsRepository
import com.zyke.message.domain.repository.CategoryOverrideRepository
import com.zyke.message.domain.repository.MoneyPlanRepository
import com.zyke.message.domain.repository.MutedSenderRepository
import com.zyke.message.domain.repository.NicknameRepository
import com.zyke.message.domain.repository.SettingsRepository
import com.zyke.message.domain.repository.SmsRepository
import com.zyke.message.domain.repository.SplitRepository
import com.zyke.message.domain.repository.WishlistRepository
import com.zyke.message.domain.repository.UserMessageStateRepository
import com.zyke.message.domain.usecase.ObserveMessagesUseCase

/**
 * Manual dependency container; one instance lives in the Application.
 *
 * With [demo] set (Demo mode) the caller passes a [com.zyke.message.data.demo.DemoContext]: every store below is the app's
 * ordinary one but reads and writes in-memory preferences, the inbox is [DemoSmsRepository] (a fixed made-up list, never the
 * phone's), nothing is cached on disk, the market strip has fixed prices, and no backup place can run. A demo container
 * holds no reference to the real context's files or preferences.
 */
class AppContainer(context: Context, val demo: Boolean = false) {
    private val appContext: Context = context.applicationContext ?: context
    /** Decided first thing, before any store can write: whether this is a fresh install that should offer "Welcome back". */
    val needsWelcome: Boolean = !demo && com.zyke.message.data.backup.BackupPrefs.needsWelcome(context)
    /** [needsWelcome] and not answered yet. Never throws: if anything about the first launch fails, the app just opens. */
    /** Whether this is a fresh install, decided in the same breath (before any setup is written); it only picks how the tour is offered. */
    init { if (!demo) com.zyke.message.data.backup.FirstRun.step("tour kind", Unit) { com.zyke.message.data.repository.TourSeenRepositoryImpl.decideKindNow(context) } }
    val tourSeen: com.zyke.message.domain.tour.TourSeenRepository by lazy { com.zyke.message.data.repository.TourSeenRepositoryImpl(context) }
    fun welcomeStillPending(): Boolean =
        !demo && com.zyke.message.data.backup.FirstRun.step("welcome state", false) { needsWelcome && !com.zyke.message.data.backup.FirstRun.isDone(appContext) }

    /** The made-up inbox and setup of Demo mode; built only when asked for. */
    val demoData: com.zyke.message.domain.demo.DemoDataset by lazy { com.zyke.message.domain.demo.DemoData.build(System.currentTimeMillis()) }

    val backup by lazy {
        com.zyke.message.data.backup.BackupHub(context, imageStore, if (demo) null else com.zyke.message.data.backup.FirstRun.step("drive auth", null) { com.zyke.message.data.backup.GmsDriveAuth(context) })
    }
    /** Sends the paired watch (if any) its small snapshot; does nothing unless built with -PwithWear=true. */
    val wearSync by lazy { com.zyke.message.wear.WearSync(com.zyke.message.wear.WatchLinks.create(context)) }
    val rcsStore by lazy { RcsMessageStore(context.applicationContext) }
    val smsDataSource by lazy { SmsDataSource(context.applicationContext.contentResolver, rcsStore, com.zyke.message.data.sms.MmsDataSource(context.applicationContext.contentResolver)) }

    val smsRepository: SmsRepository by lazy { if (demo) DemoSmsRepository(demoData.messages) else SmsRepositoryImpl(smsDataSource) }
    val userMessageState: UserMessageStateRepository by lazy { UserMessageStateRepositoryImpl(context) }
    val budgets: BudgetRepository by lazy { BudgetRepositoryImpl(context) }
    val categoryOverrides: CategoryOverrideRepository by lazy { CategoryOverrideRepositoryImpl(context) }
    val tokenRules: com.zyke.message.domain.repository.TokenRuleRepository by lazy { com.zyke.message.data.repository.TokenRuleRepositoryImpl(context) }
    val cards: CardRepository by lazy { CardRepositoryImpl(context) }
    val moneyPlan: MoneyPlanRepository by lazy { MoneyPlanRepositoryImpl(context) }
    val settings: SettingsRepository by lazy { SettingsRepositoryImpl(context) }
    val wishlist: WishlistRepository by lazy { WishlistRepositoryImpl(context) }
    val splits: SplitRepository by lazy { SplitRepositoryImpl(context) }
    val goals: GoalRepository by lazy { GoalRepositoryImpl(context) }
    val goalContributions: com.zyke.message.domain.goals.GoalContributionRepository by lazy { com.zyke.message.data.repository.GoalContributionRepositoryImpl(context) }
    val goalSavings by lazy { com.zyke.message.domain.goals.GoalSavings(goals, goalContributions) }
    val reminders: ReminderRepository by lazy { ReminderRepositoryImpl(context) }
    val planActions: com.zyke.message.domain.repository.PlanActionRepository by lazy { com.zyke.message.data.repository.PlanActionRepositoryImpl(context) }
    val imageStore by lazy {
        ImageStore(context, offline = demo) { settings.settings.value.fetchLogos }.also { store ->
            // The logo list that ships with the app: read once off the main thread, then the screens showing a logo ask again.
            store.webLogos = { bundledLogos.table }
            // A removed picture takes the web address recorded for it along (only a payee's own key, never a message's or an account's).
            store.onChosenRemoved = { key -> if (com.zyke.message.domain.logo.LogoList.keyOf(key) == key) logoList.forget(key) }
            if (!demo) bundledLogos.loadInBackground(store::refresh)
        }
    }
    /** The logo list that ships with the app (`assets/known_logos.json`): brand to the web address its logo comes from. */
    val bundledLogos by lazy { com.zyke.message.data.logo.BundledLogos(context) }
    /** The web addresses of the pictures the person chose from the web, kept for "Export logo list" in Settings. Nothing in the demo. */
    val logoList: com.zyke.message.domain.repository.LogoListRepository by lazy { if (demo) com.zyke.message.domain.repository.NoLogoList else com.zyke.message.data.repository.LogoListRepositoryImpl(context) }
    val messageClassifier by lazy { MessageClassifier() }
    val mutedSenders: MutedSenderRepository by lazy { MutedSenderRepositoryImpl(context) }
    /** Who login codes are passed on to. Nothing in Demo mode: nothing is stored, nothing can be sent. */
    val codeSharing: com.zyke.message.domain.sharing.CodeSharingRepository by lazy { if (demo) com.zyke.message.domain.sharing.NoCodeSharing else com.zyke.message.data.repository.CodeSharingRepositoryImpl(context) }
    /** "Check balance by SMS": the person's changes to the bank presets, the saved UAN and the last request per account. In memory only in Demo mode. */
    /** Where purchases were made, and the choices about it (prefs `spend_places` and `spend_places_settings`; only in memory in the demo, seeded with made-up places). */
    val spendPlaces: com.zyke.message.domain.places.SpendPlaceRepository by lazy { if (demo) com.zyke.message.domain.places.SpendPlaceStore() else com.zyke.message.data.repository.SpendPlaceRepositoryImpl(context) }
    val balanceCheck: com.zyke.message.domain.balance.BalanceCheckRepository by lazy { if (demo) com.zyke.message.domain.balance.InMemoryBalanceCheck() else com.zyke.message.data.repository.BalanceCheckRepositoryImpl(context) }
    /** The monthly spending limit per card, set from the Cards tab. In memory only in Demo mode. */
    val cardLimits: com.zyke.message.domain.cards.CardLimitsRepository by lazy { if (demo) com.zyke.message.domain.cards.InMemoryCardLimits() else com.zyke.message.data.repository.CardLimitsRepositoryImpl(context) }
    val nicknames: NicknameRepository by lazy { NicknameRepositoryImpl(context) }
    val paymentNotes: PaymentNotesRepository by lazy { PaymentNotesRepositoryImpl(context) }
    val learnedTemplates: com.zyke.message.domain.repository.LearnedTemplateRepository by lazy { com.zyke.message.data.repository.LearnedTemplateRepositoryImpl(context) }
    val merges: MergeRepository by lazy { MergeRepositoryImpl(context) }
    val calendarEvents: com.zyke.message.domain.repository.CalendarEventRepository by lazy { com.zyke.message.data.repository.CalendarEventRepositoryImpl(context) }
    val insightsFilter: com.zyke.message.domain.repository.InsightsFilterRepository by lazy { com.zyke.message.data.repository.InsightsFilterRepositoryImpl(context) }
    val recentSearches: com.zyke.message.domain.search.RecentSearchStore by lazy { com.zyke.message.data.repository.RecentSearchStoreImpl(context) }
    val providentFundAccounts: com.zyke.message.domain.repository.ProvidentFundAccountRepository by lazy { com.zyke.message.data.repository.ProvidentFundAccountRepositoryImpl(context) }
    val manualEntries: com.zyke.message.domain.ledger.ManualEntryRepository by lazy { com.zyke.message.data.repository.ManualEntryRepositoryImpl(context) }
    val setAsides: com.zyke.message.domain.ledger.SetAsideRepository by lazy { com.zyke.message.data.repository.SetAsideRepositoryImpl(context) }
    val cardBills: com.zyke.message.domain.ledger.CardBillRepository by lazy { com.zyke.message.data.repository.CardBillRepositoryImpl(context) }
    val samePayments: com.zyke.message.domain.ledger.SamePaymentRepository by lazy { com.zyke.message.data.repository.SamePaymentRepositoryImpl(context) }
    val customCategories: com.zyke.message.domain.repository.CustomCategoryRepository by lazy { com.zyke.message.data.repository.CustomCategoryRepositoryImpl(context) }
    /**
     * The on-device AI (Gemini Nano through the phone's AI Core service): off until switched on in Settings, never in the demo
     * (no engine is built then), never from the SMS receiver. Built on first use by a screen.
     */
    val ai: com.zyke.message.data.ai.AiHub by lazy { com.zyke.message.data.ai.AiHub(context, demo, appScope) { customCategories.state.value.categories } }
    /**
     * Bank alert emails (opt-in, off until switched on in Settings, Email alerts): the choices and the parsed alerts (private preferences, never in a
     * backup), the app password (sealed with a Keystore key, never in a backup) and the one thing that talks to a mailbox, [ImapClient], built here and
     * nowhere else. In Demo mode there is nothing to store and nothing to connect to.
     */
    val emailAlerts: com.zyke.message.domain.email.EmailAlertRepository by lazy { if (demo) com.zyke.message.domain.email.NoEmailAlerts else com.zyke.message.data.repository.EmailAlertRepositoryImpl(context) }
    val emailCredentials: com.zyke.message.domain.email.EmailCredentialStore by lazy { if (demo) com.zyke.message.domain.email.NoEmailCredentials else com.zyke.message.data.email.EmailPasswordVault(context) }
    val emailSync by lazy {
        com.zyke.message.domain.email.EmailSync(emailAlerts, emailCredentials, if (demo) com.zyke.message.domain.email.NoMailSource else com.zyke.message.data.email.ImapClient())
    }
    /** The one way a payment or payee is given a name and / or a category, so every screen does it alike. */
    val namingEdits by lazy { com.zyke.message.domain.nickname.NamingEdits(nicknames, categoryOverrides, customCategories) }
    val paidBills: com.zyke.message.domain.home.PaidBillRepository by lazy { com.zyke.message.data.repository.PaidBillRepositoryImpl(context) }
    val watchlist: com.zyke.message.domain.markets.WatchlistRepository by lazy { com.zyke.message.data.repository.WatchlistRepositoryImpl(context) }
    val markets by lazy {
        com.zyke.message.data.markets.MarketTicker(
            watchlist, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default),
            fixedQuotes = if (demo) demoData.quotes else null
        )
    }
    /** Live flight status: off until switched on in Settings; in the demo it is made up from the clock and never asks the network. */
    val flights by lazy { com.zyke.message.data.flights.FlightService(com.zyke.message.data.flights.FlightSettings(context), demo) }
    /** Flights the person added by hand (in memory only in the demo, like every store here). */
    val manualFlights: com.zyke.message.domain.flight.ManualFlightRepository by lazy { com.zyke.message.data.repository.ManualFlightRepositoryImpl(context) }
    /** The flights found in texts, written down for the alarm and the tracking service; bookkeeping, not in backups. */
    val flightPlan by lazy { com.zyke.message.data.flights.FlightPlanStore(context) }
    /** Live train status: off until switched on in Settings; in the demo it is made up from the clock and never asks the network. */
    val trains by lazy { com.zyke.message.data.trains.TrainService(com.zyke.message.data.trains.TrainSettings(context), demo) }
    /** Train journeys the person added by hand (in memory only in the demo, like every store here). */
    val manualTrains: com.zyke.message.domain.train.ManualTrainRepository by lazy { com.zyke.message.data.repository.ManualTrainRepositoryImpl(context) }
    /** The journeys found in texts, written down for the alarm and the tracking service; bookkeeping, not in backups. */
    val trainPlan by lazy { com.zyke.message.data.trains.TrainPlanStore(context) }
    val dailyBudgetOverride: com.zyke.message.domain.budget.DailyBudgetOverrideRepository by lazy { com.zyke.message.data.repository.DailyBudgetOverrideRepositoryImpl(context) }
    val hiddenAccounts: com.zyke.message.domain.repository.HiddenAccountRepository by lazy { com.zyke.message.data.repository.HiddenAccountRepositoryImpl(context) }
    val accountOverrides: com.zyke.message.domain.accounts.AccountOverrideRepository by lazy { com.zyke.message.data.repository.AccountOverrideRepositoryImpl(context) }
    val netWorth: com.zyke.message.domain.repository.NetWorthRepository by lazy { com.zyke.message.data.repository.NetWorthRepositoryImpl(context) }
    val investments: com.zyke.message.domain.investments.HoldingRepository by lazy { com.zyke.message.data.repository.HoldingRepositoryImpl(context) }
    val loans: com.zyke.message.domain.repository.LoanRepository by lazy { com.zyke.message.data.repository.LoanRepositoryImpl(context) }
    val recurringPayments: com.zyke.message.domain.repository.RecurringConfirmationRepository by lazy { com.zyke.message.data.repository.RecurringConfirmationRepositoryImpl(context) }
    val recurringMerges: com.zyke.message.domain.repository.RecurringMergeRepository by lazy { com.zyke.message.data.repository.RecurringMergeRepositoryImpl(context) }
    val recurringGroups: com.zyke.message.domain.repository.RecurringGroupRepository by lazy { com.zyke.message.data.repository.RecurringGroupRepositoryImpl(context) }
    val cancelledSubscriptions: com.zyke.message.domain.repository.CancelledSubscriptionRepository by lazy { com.zyke.message.data.repository.CancelledSubscriptionRepositoryImpl(context) }
    val receivedRefunds: com.zyke.message.domain.repository.ReceivedRefundRepository by lazy { com.zyke.message.data.repository.ReceivedRefundRepositoryImpl(context) }
    /** The order and modes of Home's stacks of cards, and the cards restored from Auto cleared (prefs `triage_prefs`; in memory in the demo). */
    val triage: com.zyke.message.domain.triage.TriageRepository by lazy { com.zyke.message.data.repository.TriageRepositoryImpl(context) }
    /** Which optional parts of Home are shown (prefs `home_layout`, in backups; in memory in the demo because `context` is the demo context). */
    val homeLayout: com.zyke.message.domain.home.HomeLayoutRepository by lazy { com.zyke.message.data.repository.HomeLayoutRepositoryImpl(context) }

    /**
     * How each message was read, kept encrypted on the phone. Tagged with the app's version and install time, so a
     * new or reinstalled build, which may read messages differently, starts again from the messages themselves.
     */
    val classificationCache: ClassificationCache by lazy { if (demo) NoClassificationCache else EncryptedClassificationCache(context, cacheTag(context)) }

    /** Outlives every screen, so the inbox is read once and shared, and keeps being read when a screen goes. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val observeMessages by lazy { ObserveMessagesUseCase(
        smsRepository, messageClassifier,
        categoryOverrides = categoryOverrides, tokenRules = tokenRules, cardRepository = cards, senderImages = imageStore, mutedSenders = mutedSenders, nicknameRepository = nicknames, mergeRepository = merges,
        classificationCache = classificationCache, userState = userMessageState, scope = appScope,
        isReadingPaused = { settings.settings.value.readingPaused },
        manualEntries = manualEntries, emailAlerts = emailAlerts, setAsides = setAsides, learnedTemplates = learnedTemplates, paymentNotes = paymentNotes, customCategories = customCategories, cardBills = cardBills, samePayments = samePayments,
        legacyKeys = if (demo) null else com.zyke.message.domain.categories.LegacyKeyMigration(categoryOverrides, nicknames, customCategories, imageStore)
    ) }

    /**
     * Builds, off the main thread and in parallel, what the first screen is certain to ask for. Every part above is
     * created on first use, so asking early here only moves the cost (reading each store's saved JSON) away from the
     * launch frame; anything the screen asks for before this has built it simply waits for that one part.
     */
    fun warmUp() {
        val parts = listOf<() -> Any?>(
            { settings }, { userMessageState }, { cards }, { budgets }, { categoryOverrides }, { mutedSenders }, { nicknames },
            { merges }, { moneyPlan }, { goals }, { reminders }, { planActions }, { wishlist }, { splits }, { calendarEvents }, { hiddenAccounts }, { accountOverrides },
            { providentFundAccounts }, { setAsides }, { cardBills }, { samePayments }, { manualEntries }, { emailAlerts }, { recurringPayments }, { cancelledSubscriptions }, { loans }, { imageStore },
            { classificationCache }, { observeMessages }
        )
        for (part in parts) appScope.launch { runCatching { part() } }
    }

    init {
        // Last, so every store above exists: fill the in-memory ones with the made-up setup.
        if (demo) DemoSeeder.seed(this, demoData)
    }

    private fun cacheTag(context: Context): String {
        val info = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
        // Not the install time: updating the app must not throw away what was read, or every update would read the whole
        // inbox again. When the rules change what a message is read as, MessageClassifier.RULES_VERSION is raised instead.
        return "${info?.versionName}-${info?.longVersionCode}-r${com.zyke.message.domain.classification.MessageClassifier.RULES_VERSION}"
    }
}
