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
import com.zyke.message.data.cache.EncryptedClassificationCache
import com.zyke.message.data.images.ImageStore
import com.zyke.message.data.repository.BudgetRepositoryImpl
import com.zyke.message.data.repository.CardRepositoryImpl
import com.zyke.message.data.repository.CategoryOverrideRepositoryImpl
import com.zyke.message.data.repository.MoneyPlanRepositoryImpl
import com.zyke.message.data.repository.NicknameRepositoryImpl
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

/** Manual dependency container; one instance lives in the Application. */
class AppContainer(context: Context) {
    val rcsStore = RcsMessageStore(context.applicationContext)
    private val smsDataSource = SmsDataSource(context.applicationContext.contentResolver, rcsStore)

    val smsRepository: SmsRepository = SmsRepositoryImpl(smsDataSource)
    val userMessageState: UserMessageStateRepository = UserMessageStateRepositoryImpl(context)
    val budgets: BudgetRepository = BudgetRepositoryImpl(context)
    val categoryOverrides: CategoryOverrideRepository = CategoryOverrideRepositoryImpl(context)
    val cards: CardRepository = CardRepositoryImpl(context)
    val moneyPlan: MoneyPlanRepository = MoneyPlanRepositoryImpl(context)
    val settings: SettingsRepository = SettingsRepositoryImpl(context)
    val wishlist: WishlistRepository = WishlistRepositoryImpl(context)
    val splits: SplitRepository = SplitRepositoryImpl(context)
    val goals: GoalRepository = GoalRepositoryImpl(context)
    val reminders: ReminderRepository = ReminderRepositoryImpl(context)
    val imageStore = ImageStore(context) { settings.settings.value.fetchLogos }
    val messageClassifier = MessageClassifier()
    val mutedSenders: MutedSenderRepository = MutedSenderRepositoryImpl(context)
    val nicknames: NicknameRepository = NicknameRepositoryImpl(context)
    val merges: MergeRepository = MergeRepositoryImpl(context)
    val calendarEvents: com.zyke.message.domain.repository.CalendarEventRepository = com.zyke.message.data.repository.CalendarEventRepositoryImpl(context)
    val insightsFilter: com.zyke.message.domain.repository.InsightsFilterRepository = com.zyke.message.data.repository.InsightsFilterRepositoryImpl(context)
    val providentFundAccounts: com.zyke.message.domain.repository.ProvidentFundAccountRepository = com.zyke.message.data.repository.ProvidentFundAccountRepositoryImpl(context)
    val watchlist: com.zyke.message.domain.markets.WatchlistRepository = com.zyke.message.data.repository.WatchlistRepositoryImpl(context)
    val markets = com.zyke.message.data.markets.MarketTicker(watchlist, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default))
    val hiddenAccounts: com.zyke.message.domain.repository.HiddenAccountRepository = com.zyke.message.data.repository.HiddenAccountRepositoryImpl(context)

    /**
     * How each message was read, kept encrypted on the phone. Tagged with the app's version and install time, so a
     * new or reinstalled build, which may read messages differently, starts again from the messages themselves.
     */
    val classificationCache: ClassificationCache = EncryptedClassificationCache(context, cacheTag(context))

    /** Outlives every screen, so the inbox is read once and shared, and keeps being read when a screen goes. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val observeMessages = ObserveMessagesUseCase(
        smsRepository, messageClassifier,
        categoryOverrides = categoryOverrides, cardRepository = cards, senderImages = imageStore, mutedSenders = mutedSenders, nicknameRepository = nicknames, mergeRepository = merges,
        classificationCache = classificationCache, userState = userMessageState, scope = appScope
    )

    private fun cacheTag(context: Context): String {
        val info = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
        // Not the install time: updating the app must not throw away what was read, or every update would read the whole
        // inbox again. When the rules change what a message is read as, MessageClassifier.RULES_VERSION is raised instead.
        return "${info?.versionName}-${info?.longVersionCode}-r${com.zyke.message.domain.classification.MessageClassifier.RULES_VERSION}"
    }
}
