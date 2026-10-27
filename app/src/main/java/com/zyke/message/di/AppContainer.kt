package com.zyke.message.di

import android.content.Context
import com.zyke.message.data.repository.BudgetRepositoryImpl
import com.zyke.message.data.repository.CategoryOverrideRepositoryImpl
import com.zyke.message.data.repository.SettingsRepositoryImpl
import com.zyke.message.data.repository.SmsRepositoryImpl
import com.zyke.message.data.repository.UserMessageStateRepositoryImpl
import com.zyke.message.data.sms.RcsMessageStore
import com.zyke.message.data.sms.SmsDataSource
import com.zyke.message.domain.classification.MessageClassifier
import com.zyke.message.domain.repository.BudgetRepository
import com.zyke.message.domain.repository.CategoryOverrideRepository
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
    val settings: SettingsRepository = SettingsRepositoryImpl(context)
    val messageClassifier = MessageClassifier()
    val observeMessages = ObserveMessagesUseCase(smsRepository, messageClassifier, categoryOverrides = categoryOverrides)
}
