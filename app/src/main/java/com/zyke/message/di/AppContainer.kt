package com.zyke.message.di

import android.content.Context
import com.zyke.message.data.cache.EncryptedClassificationCache
import com.zyke.message.data.images.ImageStore
import com.zyke.message.data.repository.BudgetRepositoryImpl
import com.zyke.message.data.repository.CardRepositoryImpl
import com.zyke.message.data.repository.CategoryOverrideRepositoryImpl
import com.zyke.message.data.repository.MutedSenderRepositoryImpl
import com.zyke.message.data.repository.SettingsRepositoryImpl
import com.zyke.message.data.repository.SmsRepositoryImpl
import com.zyke.message.data.repository.UserMessageStateRepositoryImpl
import com.zyke.message.data.sms.RcsMessageStore
import com.zyke.message.data.sms.SmsDataSource
import com.zyke.message.domain.classification.MessageClassifier
import com.zyke.message.domain.repository.BudgetRepository
import com.zyke.message.domain.repository.CardRepository
import com.zyke.message.domain.repository.ClassificationCache
import com.zyke.message.domain.repository.CategoryOverrideRepository
import com.zyke.message.domain.repository.MutedSenderRepository
import com.zyke.message.domain.repository.SettingsRepository
import com.zyke.message.domain.repository.SmsRepository
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
    val settings: SettingsRepository = SettingsRepositoryImpl(context)
    val imageStore = ImageStore(context) { settings.settings.value.fetchLogos }
    val messageClassifier = MessageClassifier()
    val mutedSenders: MutedSenderRepository = MutedSenderRepositoryImpl(context)

    /**
     * How each message was read, kept encrypted on the phone. Tagged with the app's version and install time, so a
     * new or reinstalled build, which may read messages differently, starts again from the messages themselves.
     */
    val classificationCache: ClassificationCache = EncryptedClassificationCache(context, cacheTag(context))

    val observeMessages = ObserveMessagesUseCase(
        smsRepository, messageClassifier,
        categoryOverrides = categoryOverrides, cardRepository = cards, senderImages = imageStore, mutedSenders = mutedSenders,
        classificationCache = classificationCache, userState = userMessageState
    )

    private fun cacheTag(context: Context): String {
        val info = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
        return "${info?.versionName}-${info?.longVersionCode}-${info?.lastUpdateTime}"
    }
}
